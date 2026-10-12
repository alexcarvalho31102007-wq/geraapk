package com.example.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class BuildBackgroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTasks = ConcurrentHashMap<Long, Boolean>()

    private val database by lazy { AppDatabase.getInstance(applicationContext) }
    private val dao by lazy { database.buildRecordDao() }
    private val supabaseRepo by lazy { SupabaseRepository(applicationContext) }

    private val gitHubApi: GitHubApiService by lazy {
        val moshi = Moshi.Builder().build()
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()
                chain.proceed(req)
            }
            .build()
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(okHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GitHubApiService::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Processando compilação Release..."))

        val recordId = intent?.getLongExtra(EXTRA_RECORD_ID, -1L) ?: -1L
        val isRecompile = intent?.getBooleanExtra(EXTRA_RECOMPILE, false) ?: false
        val supabaseAccessToken = intent?.getStringExtra(EXTRA_SUPABASE_ACCESS_TOKEN).orEmpty()

        serviceScope.launch {
            if (recordId > 0L) {
                launchBuildTaskIfNeeded(
                    recordId = recordId,
                    isRecompile = isRecompile,
                    supabaseAccessToken = supabaseAccessToken
                )
            } else {
                val activeRecords = dao.getActiveBuilds()
                for (rec in activeRecords) {
                    launchBuildTaskIfNeeded(
                        recordId = rec.id,
                        isRecompile = false,
                        supabaseAccessToken = supabaseAccessToken
                    )
                }
                if (activeTasks.isEmpty()) {
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun launchBuildTaskIfNeeded(
        recordId: Long,
        isRecompile: Boolean,
        supabaseAccessToken: String
    ) {
        if (activeTasks.putIfAbsent(recordId, true) != null) return
        serviceScope.launch {
            try {
                executeAutomatedReleaseBuild(
                    recordId = recordId,
                    isRecompile = isRecompile,
                    supabaseAccessToken = supabaseAccessToken
                )
            } finally {
                activeTasks.remove(recordId)
                if (activeTasks.isEmpty()) {
                    stopSelf()
                }
            }
        }
    }

    private suspend fun executeAutomatedReleaseBuild(
        recordId: Long,
        isRecompile: Boolean,
        supabaseAccessToken: String
    ) {
        var record = dao.getRecordById(recordId) ?: return

        try {
            // 1. Se houver Edge Function implantada no Supabase, utiliza-a
            if (supabaseRepo.isCloudConfigured()) {
                val edgeHandled = tryExecuteViaSupabaseEdgeFunction(
                    record = record,
                    isRecompile = isRecompile,
                    supabaseAccessToken = supabaseAccessToken
                )
                if (edgeHandled) return
            }

            // 2. Recupera automaticamente o token padrão do Supabase (ou BuildConfig)
            val backendToken = supabaseRepo.fetchDynamicBackendTokenIfNeeded(supabaseAccessToken)
            if (backendToken.isEmpty()) {
                failBuild(
                    record = record,
                    errorSummary = "Serviço de compilação indisponível.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            val authHeader = "Bearer $backendToken"

            // Se já havia um workflowRunId em andamento e não for recompilação forçada, retoma o monitoramento
            if (!isRecompile && record.workflowRunId != null && record.repoFullName.contains("/")) {
                val parts = record.repoFullName.split("/")
                monitorWorkflowAndFetchApk(
                    record = record,
                    authHeader = authHeader,
                    owner = parts[0],
                    repo = parts[1],
                    runId = record.workflowRunId!!,
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            val zipFile = File(record.localZipPath)
            // Recompilação direta de um repositório já existente no GitHub quando o ZIP local não está mais presente
            if (isRecompile && !zipFile.exists() && record.repoFullName.contains("/")) {
                val parts = record.repoFullName.split("/")
                val owner = parts[0]
                val repoName = parts[1]
                val branch = record.branch.ifBlank { "main" }

                updateNotification("Enviando arquivos...")
                record = updateRecordState(
                    record = record,
                    status = BuildStatus.IN_PROGRESS,
                    progress = 0.25f,
                    stepMessage = "Enviando arquivos",
                    supabaseAccessToken = supabaseAccessToken
                )

                val dispatchResp = gitHubApi.dispatchWorkflow(
                    authHeader = authHeader,
                    owner = owner,
                    repo = repoName,
                    workflowId = ZipSanitizer.SINGLE_WORKFLOW_FILENAME,
                    request = GitHubWorkflowDispatchRequest(ref = branch)
                )

                if (!dispatchResp.isSuccessful) {
                    val refResp = gitHubApi.getBranchRef(authHeader, owner, repoName, branch)
                    if (refResp.isSuccessful && refResp.body() != null) {
                        val headSha = refResp.body()!!.gitObject.sha
                        val commitInfo = gitHubApi.getGitCommit(authHeader, owner, repoName, headSha).body()
                        if (commitInfo != null) {
                            val newCommit = gitHubApi.createGitCommit(
                                authHeader = authHeader,
                                owner = owner,
                                repo = repoName,
                                request = GitCreateCommitRequest(
                                    message = "release: recompile ${record.appName} via GERA APK",
                                    treeSha = commitInfo.tree.sha,
                                    parents = listOf(headSha)
                                )
                            ).body()
                            if (newCommit != null) {
                                gitHubApi.updateBranchRef(
                                    authHeader = authHeader,
                                    owner = owner,
                                    repo = repoName,
                                    branch = branch,
                                    request = GitUpdateRefRequest(sha = newCommit.sha, force = true)
                                )
                            }
                        }
                    }
                }

                updateNotification("Preparando ambiente...")
                record = updateRecordState(
                    record = record,
                    status = BuildStatus.IN_PROGRESS,
                    progress = 0.45f,
                    stepMessage = "Preparando ambiente",
                    supabaseAccessToken = supabaseAccessToken
                )

                var matchedRun: GitHubWorkflowRun? = null
                for (attempt in 1..15) {
                    delay(3000)
                    val runsResp = gitHubApi.getWorkflowRuns(authHeader, owner, repoName, branch, perPage = 10)
                    if (runsResp.isSuccessful) {
                        val runs = runsResp.body()?.workflowRuns.orEmpty()
                        matchedRun = runs.firstOrNull { it.status != "completed" } ?: runs.firstOrNull()
                        if (matchedRun != null) break
                    }
                }

                if (matchedRun == null) {
                    failBuild(
                        record = record,
                        errorSummary = "Não foi possível iniciar a recompilação Release.",
                        supabaseAccessToken = supabaseAccessToken
                    )
                    return
                }

                record = record.copy(
                    workflowRunId = matchedRun.id,
                    workflowHtmlUrl = matchedRun.htmlUrl,
                    updatedAtMillis = System.currentTimeMillis()
                )
                dao.updateRecord(record)
                supabaseRepo.pushBuildRecordToSupabase(record, supabaseAccessToken)

                monitorWorkflowAndFetchApk(
                    record = record,
                    authHeader = authHeader,
                    owner = owner,
                    repo = repoName,
                    runId = matchedRun.id,
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            if (!zipFile.exists()) {
                failBuild(
                    record = record,
                    errorSummary = "Arquivo ZIP não encontrado no armazenamento local.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            updateNotification("Enviando arquivos...")
            record = updateRecordState(
                record = record,
                status = BuildStatus.IN_PROGRESS,
                progress = 0.14f,
                stepMessage = "Enviando arquivos",
                supabaseAccessToken = supabaseAccessToken
            )

            val projectInfo = try {
                ZipProjectHelper.inspectZipBytes(record.zipFileName, zipFile.readBytes())
            } catch (e: Exception) {
                failBuild(
                    record = record,
                    errorSummary = e.localizedMessage ?: "Arquivo ZIP inválido.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            val userResp = gitHubApi.getAuthenticatedUser(authHeader)
            if (!userResp.isSuccessful || userResp.body() == null) {
                failBuild(
                    record = record,
                    errorSummary = "Não foi possível autenticar no servidor de compilação.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            val username = userResp.body()!!.login
            val autoRepoName = if (record.repoFullName.contains("/")) {
                record.repoFullName.substringAfter("/")
            } else {
                sanitizeZipNameToRepoName(record.zipFileName)
            }
            val fullRepoName = "$username/$autoRepoName"

            var targetBranch = "main"
            val existingRepoResp = gitHubApi.getRepository(authHeader, username, autoRepoName)
            if (existingRepoResp.isSuccessful && existingRepoResp.body() != null) {
                targetBranch = existingRepoResp.body()!!.defaultBranch ?: "main"
            } else {
                val createResp = gitHubApi.createRepository(
                    authHeader = authHeader,
                    request = GitHubCreateRepoRequest(
                        name = autoRepoName,
                        isPrivate = false,
                        autoInit = true
                    )
                )
                if (!createResp.isSuccessful || createResp.body() == null) {
                    failBuild(
                        record = record,
                        errorSummary = "Não foi possível preparar o repositório de compilação.",
                        supabaseAccessToken = supabaseAccessToken
                    )
                    return
                }
                targetBranch = createResp.body()!!.defaultBranch ?: "main"
            }

            record = record.copy(
                appName = projectInfo.detectedAppName,
                packageName = projectInfo.detectedPackageName,
                repoFullName = fullRepoName,
                branch = targetBranch,
                updatedAtMillis = System.currentTimeMillis()
            )
            dao.updateRecord(record)
            supabaseRepo.pushBuildRecordToSupabase(record, supabaseAccessToken)

            updateNotification("Enviando arquivos...")
            record = updateRecordState(
                record = record,
                status = BuildStatus.IN_PROGRESS,
                progress = 0.22f,
                stepMessage = "Enviando arquivos",
                supabaseAccessToken = supabaseAccessToken
            )

            // Garante que a branch inicial do repositório já esteja disponível (com retry caso recém-criado)
            var latestCommitSha: String? = null
            for (attempt in 1..6) {
                val refResp = gitHubApi.getBranchRef(authHeader, username, autoRepoName, targetBranch)
                if (refResp.isSuccessful && refResp.body() != null) {
                    latestCommitSha = refResp.body()!!.gitObject.sha
                    break
                }
                delay(1200)
            }

            // Se o repositório estava completamente vazio (sem commit inicial), cria o README inicial via Contents API
            if (latestCommitSha == null) {
                val initB64 = Base64.encodeToString(
                    "# ${projectInfo.detectedAppName}\n".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                val putInit = gitHubApi.putFileContent(
                    authHeader = authHeader,
                    owner = username,
                    repo = autoRepoName,
                    path = "README.md",
                    request = GitHubPutFileRequest(
                        message = "init: initialize repository",
                        contentBase64 = initB64,
                        branch = targetBranch
                    )
                )
                if (putInit.isSuccessful) {
                    latestCommitSha = putInit.body()?.commit?.sha
                }
                if (latestCommitSha == null) {
                    val refRetry = gitHubApi.getBranchRef(authHeader, username, autoRepoName, targetBranch)
                    latestCommitSha = refRetry.body()?.gitObject?.sha
                }
            }

            if (latestCommitSha == null) {
                failBuild(
                    record = record,
                    errorSummary = "Não foi possível inicializar a branch do repositório.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            // Envio ultra-estável em apenas 2 blobs atômicos:
            // 1) `uploads/project.zip` (já higienizado sem workflows duplicados e com Gradle 9.3.1)
            // 2) `.github/workflows/gera-apk-build.yml` (o único workflow Release que descompacta e compila)
            // Isso evita centenas de chamadas paralelas e elimina 100% de erros de rate-limit (HTTP 403/429) e timeout na Etapa 1!
            val zipBlobB64 = Base64.encodeToString(projectInfo.rawZipBytes, Base64.NO_WRAP)
            val zipBlobResp = createBlobWithRetry(authHeader, username, autoRepoName, zipBlobB64)
            if (zipBlobResp == null) {
                failBuild(
                    record = record,
                    errorSummary = "Falha ao enviar pacote do projeto para o servidor.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            dao.updateRecord(
                record.copy(
                    progress = 0.32f,
                    statusStepMessage = "Enviando arquivos",
                    updatedAtMillis = System.currentTimeMillis()
                )
            )

            val workflowBytes = projectInfo.generatedWorkflowYaml.toByteArray(Charsets.UTF_8)
            val workflowBlobB64 = Base64.encodeToString(workflowBytes, Base64.NO_WRAP)
            val workflowBlobResp = createBlobWithRetry(authHeader, username, autoRepoName, workflowBlobB64)
            if (workflowBlobResp == null) {
                failBuild(
                    record = record,
                    errorSummary = "Falha ao configurar workflow Release no servidor.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            val treeEntries = listOf(
                GitTreeEntry(
                    path = "uploads/project.zip",
                    mode = "100644",
                    type = "blob",
                    sha = zipBlobResp.sha
                ),
                GitTreeEntry(
                    path = ZipSanitizer.SINGLE_WORKFLOW_PATH,
                    mode = "100644",
                    type = "blob",
                    sha = workflowBlobResp.sha
                )
            )

            // baseTree = null substitui integralmente a árvore do repositório por apenas esses 2 arquivos,
            // garantindo que NENHUM outro workflow exista no repositório!
            val treeResp = gitHubApi.createGitTree(
                authHeader = authHeader,
                owner = username,
                repo = autoRepoName,
                request = GitCreateTreeRequest(baseTree = null, tree = treeEntries)
            )
            if (!treeResp.isSuccessful || treeResp.body() == null) {
                failBuild(
                    record = record,
                    errorSummary = "Erro ao preparar árvore limpa no repositório.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            val newCommitResp = gitHubApi.createGitCommit(
                authHeader = authHeader,
                owner = username,
                repo = autoRepoName,
                request = GitCreateCommitRequest(
                    message = "release: compile ${projectInfo.detectedAppName} via GERA APK",
                    treeSha = treeResp.body()!!.sha,
                    parents = listOf(latestCommitSha)
                )
            )
            if (!newCommitResp.isSuccessful || newCommitResp.body() == null) {
                failBuild(
                    record = record,
                    errorSummary = "Erro ao registrar versão Release no servidor.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            val pushedCommitSha = newCommitResp.body()!!.sha
            val updateRefResp = gitHubApi.updateBranchRef(
                authHeader = authHeader,
                owner = username,
                repo = autoRepoName,
                branch = targetBranch,
                request = GitUpdateRefRequest(sha = pushedCommitSha, force = true)
            )
            if (!updateRefResp.isSuccessful) {
                failBuild(
                    record = record,
                    errorSummary = "Erro ao iniciar disparo de compilação Release.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            updateNotification("Preparando ambiente...")
            record = updateRecordState(
                record = record,
                status = BuildStatus.IN_PROGRESS,
                progress = 0.45f,
                stepMessage = "Preparando ambiente",
                supabaseAccessToken = supabaseAccessToken
            )

            var matchedRun: GitHubWorkflowRun? = null
            for (attempt in 1..18) {
                delay(3000)
                val runsResp = gitHubApi.getWorkflowRuns(authHeader, username, autoRepoName, targetBranch, perPage = 10)
                if (runsResp.isSuccessful) {
                    val allRuns = runsResp.body()?.workflowRuns.orEmpty()
                    val runsForCommit = allRuns.filter { it.headSha == pushedCommitSha }
                    val candidateList = runsForCommit.ifEmpty { allRuns.take(1) }

                    if (candidateList.isNotEmpty()) {
                        val primaryRun = candidateList.firstOrNull {
                            it.name?.contains("GERA APK", ignoreCase = true) == true
                        } ?: candidateList.first()

                        runsForCommit.filter { it.id != primaryRun.id }.forEach { duplicateRun ->
                            try {
                                gitHubApi.cancelWorkflowRun(authHeader, username, autoRepoName, duplicateRun.id)
                            } catch (_: Exception) {
                            }
                        }

                        matchedRun = primaryRun
                        break
                    }
                }
            }

            if (matchedRun == null) {
                failBuild(
                    record = record,
                    errorSummary = "Não foi possível iniciar o servidor de compilação.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return
            }

            record = record.copy(
                workflowRunId = matchedRun.id,
                workflowHtmlUrl = matchedRun.htmlUrl,
                updatedAtMillis = System.currentTimeMillis()
            )
            dao.updateRecord(record)
            supabaseRepo.pushBuildRecordToSupabase(record, supabaseAccessToken)

            monitorWorkflowAndFetchApk(
                record = record,
                authHeader = authHeader,
                owner = username,
                repo = autoRepoName,
                runId = matchedRun.id,
                supabaseAccessToken = supabaseAccessToken
            )
        } catch (_: Exception) {
            failBuild(
                record = record,
                errorSummary = "Falha durante o processamento da compilação.",
                supabaseAccessToken = supabaseAccessToken
            )
        }
    }

    private suspend fun createBlobWithRetry(
        authHeader: String,
        owner: String,
        repo: String,
        contentBase64: String
    ): GitBlobResponse? {
        for (attempt in 1..3) {
            try {
                val resp = gitHubApi.createGitBlob(
                    authHeader = authHeader,
                    owner = owner,
                    repo = repo,
                    request = GitCreateBlobRequest(content = contentBase64)
                )
                if (resp.isSuccessful && resp.body() != null) {
                    return resp.body()
                }
            } catch (_: Exception) {
            }
            delay(1000L * attempt)
        }
        return null
    }

    private suspend fun tryExecuteViaSupabaseEdgeFunction(
        record: BuildRecordEntity,
        isRecompile: Boolean,
        supabaseAccessToken: String
    ): Boolean {
        var currentRecord = record
        val zipFile = File(currentRecord.localZipPath)

        val initialResponse = if (isRecompile && !zipFile.exists()) {
            supabaseRepo.invokeEdgeFunctionCompiler(
                accessToken = supabaseAccessToken,
                request = EdgeCompilerRequest(
                    action = "recompile_release",
                    buildId = currentRecord.id,
                    appName = currentRecord.appName,
                    repoFullName = currentRecord.repoFullName,
                    branch = currentRecord.branch
                )
            )
        } else {
            if (!zipFile.exists()) return false
            val projectInfo = try {
                ZipProjectHelper.inspectZipBytes(currentRecord.zipFileName, zipFile.readBytes())
            } catch (_: Exception) {
                return false
            }

            val fileDtos = listOf(
                EdgeCompileFileDto(
                    path = "uploads/project.zip",
                    contentBase64 = Base64.encodeToString(projectInfo.rawZipBytes, Base64.NO_WRAP),
                    mode = "100644"
                ),
                EdgeCompileFileDto(
                    path = ZipSanitizer.SINGLE_WORKFLOW_PATH,
                    contentBase64 = Base64.encodeToString(
                        projectInfo.generatedWorkflowYaml.toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP
                    ),
                    mode = "100644"
                )
            )

            supabaseRepo.invokeEdgeFunctionCompiler(
                accessToken = supabaseAccessToken,
                request = EdgeCompilerRequest(
                    action = "start_release_build",
                    buildId = currentRecord.id,
                    appName = projectInfo.detectedAppName,
                    repoSlug = sanitizeZipNameToRepoName(currentRecord.zipFileName),
                    repoFullName = currentRecord.repoFullName,
                    files = fileDtos
                )
            )
        } ?: return false

        if (!initialResponse.ok || initialResponse.runId == null) return false

        val runId = initialResponse.runId
        val repoFullName = initialResponse.repoFullName ?: currentRecord.repoFullName

        currentRecord = currentRecord.copy(
            status = BuildStatus.IN_PROGRESS,
            progress = 0.45f,
            statusStepMessage = "Preparando ambiente",
            repoFullName = repoFullName,
            workflowRunId = runId,
            workflowHtmlUrl = initialResponse.htmlUrl,
            updatedAtMillis = System.currentTimeMillis()
        )
        dao.updateRecord(currentRecord)
        supabaseRepo.pushBuildRecordToSupabase(currentRecord, supabaseAccessToken)

        for (poll in 1..300) {
            delay(4500)
            val statusResp = supabaseRepo.invokeEdgeFunctionCompiler(
                accessToken = supabaseAccessToken,
                request = EdgeCompilerRequest(
                    action = "check_status",
                    buildId = currentRecord.id,
                    repoFullName = repoFullName,
                    runId = runId
                )
            ) ?: continue

            val stepTitle = statusResp.stepTitle ?: "Compilando aplicativo"
            val progress = statusResp.progress ?: 0.70f
            updateNotification("${currentRecord.appName}: $stepTitle")

            currentRecord = currentRecord.copy(
                status = BuildStatus.IN_PROGRESS,
                progress = progress,
                statusStepMessage = stepTitle,
                updatedAtMillis = System.currentTimeMillis()
            )
            dao.updateRecord(currentRecord)

            if (statusResp.status == "completed") {
                if (statusResp.conclusion == "success") {
                    val apkB64 = statusResp.apkBase64
                        ?: supabaseRepo.invokeEdgeFunctionCompiler(
                            accessToken = supabaseAccessToken,
                            request = EdgeCompilerRequest(
                                action = "download_apk",
                                buildId = currentRecord.id,
                                repoFullName = repoFullName,
                                runId = runId
                            )
                        )?.apkBase64

                    if (!apkB64.isNullOrBlank()) {
                        val rawBytes = Base64.decode(apkB64, Base64.DEFAULT)
                        val apkBytes = ZipProjectHelper.extractApkFromArtifactZip(rawBytes) ?: rawBytes
                        val savedFile = ZipProjectHelper.saveExtractedArtifactApk(
                            context = applicationContext,
                            buildId = currentRecord.id,
                            appName = currentRecord.appName,
                            extractedApkBytes = apkBytes
                        )
                        currentRecord = currentRecord.copy(
                            status = BuildStatus.SUCCESS,
                            progress = 1.0f,
                            statusStepMessage = "Compilação concluída",
                            apkDownloadUrl = statusResp.apkDownloadUrl,
                            apkLocalPath = savedFile.absolutePath,
                            apkFileName = savedFile.name,
                            apkSizeBytes = savedFile.length(),
                            errorSummary = null,
                            updatedAtMillis = System.currentTimeMillis()
                        )
                        dao.updateRecord(currentRecord)
                        supabaseRepo.pushBuildRecordToSupabase(currentRecord, supabaseAccessToken)
                        updateNotification("Compilação concluída: ${currentRecord.appName}")
                        return true
                    }
                }
                failBuild(
                    record = currentRecord,
                    errorSummary = statusResp.errorMessage ?: "A compilação falhou.",
                    supabaseAccessToken = supabaseAccessToken
                )
                return true
            }
        }
        return true
    }

    private suspend fun monitorWorkflowAndFetchApk(
        record: BuildRecordEntity,
        authHeader: String,
        owner: String,
        repo: String,
        runId: Long,
        supabaseAccessToken: String
    ) {
        var currentRecord = record
        var pollCount = 0

        while (pollCount < 300) {
            pollCount++
            val runResp = gitHubApi.getWorkflowRun(authHeader, owner, repo, runId)
            if (!runResp.isSuccessful || runResp.body() == null) {
                delay(4000)
                continue
            }

            val run = runResp.body()!!
            val runStatus = run.status ?: "in_progress"
            val runConclusion = run.conclusion

            val jobsResp = gitHubApi.getWorkflowRunJobs(authHeader, owner, repo, runId)
            val jobs = jobsResp.body()?.jobs.orEmpty()
            var cleanStageTitle = "Preparando ambiente"
            var mappedProgress = 0.50f

            for (job in jobs) {
                for (step in job.steps.orEmpty()) {
                    if (step.status == "in_progress") {
                        when {
                            step.name.contains("assembleRelease", ignoreCase = true) ||
                                step.name.contains("Compilar Aplicativo", ignoreCase = true) ||
                                step.name.contains("Build", ignoreCase = true) -> {
                                cleanStageTitle = "Compilando aplicativo"
                                mappedProgress = 0.72f
                            }
                            step.name.contains("Assinar", ignoreCase = true) ||
                                step.name.contains("Publicar", ignoreCase = true) ||
                                step.name.contains("Upload", ignoreCase = true) ||
                                step.name.contains("Artifact", ignoreCase = true) -> {
                                cleanStageTitle = "Gerando APK"
                                mappedProgress = 0.88f
                            }
                            else -> {
                                cleanStageTitle = "Preparando ambiente"
                                mappedProgress = 0.55f
                            }
                        }
                    }
                }
            }

            updateNotification("${currentRecord.appName}: $cleanStageTitle")
            currentRecord = currentRecord.copy(
                status = BuildStatus.IN_PROGRESS,
                progress = mappedProgress,
                statusStepMessage = cleanStageTitle,
                updatedAtMillis = System.currentTimeMillis()
            )
            dao.updateRecord(currentRecord)
            if (pollCount % 3 == 0) {
                supabaseRepo.pushBuildRecordToSupabase(currentRecord, supabaseAccessToken)
            }

            if (runStatus == "completed") {
                if (runConclusion == "success") {
                    currentRecord = updateRecordState(
                        record = currentRecord,
                        status = BuildStatus.IN_PROGRESS,
                        progress = 0.90f,
                        stepMessage = "Gerando APK",
                        supabaseAccessToken = supabaseAccessToken
                    )

                    val artifactsResp = gitHubApi.getRunArtifacts(authHeader, owner, repo, runId)
                    val artifacts = artifactsResp.body()?.artifacts.orEmpty()
                    val targetArtifact = artifacts.firstOrNull {
                        it.name.contains("release", ignoreCase = true)
                    } ?: artifacts.firstOrNull()

                    if (targetArtifact == null) {
                        failBuild(
                            record = currentRecord,
                            errorSummary = "Nenhum artefato APK Release encontrado.",
                            supabaseAccessToken = supabaseAccessToken
                        )
                        return
                    }

                    val downloadResp = gitHubApi.downloadArtifactZip(authHeader, targetArtifact.archiveDownloadUrl)
                    if (!downloadResp.isSuccessful || downloadResp.body() == null) {
                        failBuild(
                            record = currentRecord,
                            errorSummary = "Falha ao baixar o APK Release.",
                            supabaseAccessToken = supabaseAccessToken
                        )
                        return
                    }

                    val artifactZipBytes = downloadResp.body()!!.bytes()
                    val extractedApkBytes = ZipProjectHelper.extractApkFromArtifactZip(artifactZipBytes)

                    if (extractedApkBytes == null || extractedApkBytes.isEmpty()) {
                        failBuild(
                            record = currentRecord,
                            errorSummary = "Artefato APK Release inválido.",
                            supabaseAccessToken = supabaseAccessToken
                        )
                        return
                    }

                    val savedApkFile = ZipProjectHelper.saveExtractedArtifactApk(
                        context = applicationContext,
                        buildId = currentRecord.id,
                        appName = currentRecord.appName,
                        extractedApkBytes = extractedApkBytes
                    )

                    currentRecord = currentRecord.copy(
                        status = BuildStatus.SUCCESS,
                        progress = 1.0f,
                        statusStepMessage = "Compilação concluída",
                        apkDownloadUrl = targetArtifact.archiveDownloadUrl,
                        apkLocalPath = savedApkFile.absolutePath,
                        apkFileName = savedApkFile.name,
                        apkSizeBytes = savedApkFile.length(),
                        errorSummary = null,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                    dao.updateRecord(currentRecord)
                    supabaseRepo.pushBuildRecordToSupabase(currentRecord, supabaseAccessToken)
                    updateNotification("Compilação concluída: ${currentRecord.appName}")
                    return
                } else {
                    failBuild(
                        record = currentRecord,
                        errorSummary = "Falha na compilação do aplicativo.",
                        supabaseAccessToken = supabaseAccessToken
                    )
                    return
                }
            }

            delay(4500)
        }

        failBuild(
            record = currentRecord,
            errorSummary = "Tempo limite excedido na compilação.",
            supabaseAccessToken = supabaseAccessToken
        )
    }

    private suspend fun updateRecordState(
        record: BuildRecordEntity,
        status: String,
        progress: Float,
        stepMessage: String,
        supabaseAccessToken: String
    ): BuildRecordEntity {
        val updated = record.copy(
            status = status,
            progress = progress,
            statusStepMessage = stepMessage,
            updatedAtMillis = System.currentTimeMillis()
        )
        dao.updateRecord(updated)
        supabaseRepo.pushBuildRecordToSupabase(updated, supabaseAccessToken)
        return updated
    }

    private suspend fun failBuild(
        record: BuildRecordEntity,
        errorSummary: String,
        supabaseAccessToken: String
    ) {
        val failed = record.copy(
            status = BuildStatus.FAILED,
            statusStepMessage = "Falha na compilação",
            errorSummary = errorSummary,
            updatedAtMillis = System.currentTimeMillis()
        )
        dao.updateRecord(failed)
        supabaseRepo.pushBuildRecordToSupabase(failed, supabaseAccessToken)
        updateNotification("Falha na compilação: ${record.appName}")
    }

    private fun sanitizeZipNameToRepoName(zipFileName: String): String {
        val base = zipFileName
            .substringBeforeLast(".")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
        return base.ifEmpty { "gera-apk-project" }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Compilações Release (GERA APK)",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("GERA APK • Release")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "gera_apk_build_channel"
        private const val NOTIFICATION_ID = 4102
        const val EXTRA_RECORD_ID = "extra_record_id"
        const val EXTRA_RECOMPILE = "extra_recompile"
        const val EXTRA_SUPABASE_ACCESS_TOKEN = "extra_supabase_access_token"

        fun enqueueBuild(
            context: Context,
            recordId: Long,
            isRecompile: Boolean = false,
            supabaseAccessToken: String = ""
        ) {
            val intent = Intent(context, BuildBackgroundService::class.java).apply {
                putExtra(EXTRA_RECORD_ID, recordId)
                putExtra(EXTRA_RECOMPILE, isRecompile)
                putExtra(EXTRA_SUPABASE_ACCESS_TOKEN, supabaseAccessToken)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                context.startService(intent)
            }
        }

        fun resumeActiveBuildsIfAny(
            context: Context,
            supabaseAccessToken: String = ""
        ) {
            val intent = Intent(context, BuildBackgroundService::class.java).apply {
                putExtra(EXTRA_RECORD_ID, -1L)
                putExtra(EXTRA_SUPABASE_ACCESS_TOKEN, supabaseAccessToken)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
