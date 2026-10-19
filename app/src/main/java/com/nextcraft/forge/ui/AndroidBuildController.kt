package com.nextcraft.forge.ui

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.nextcraft.forge.R
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.projectSlug
import com.nextcraft.forge.network.LocalNetwork
import com.nextcraft.forge.runtime.AndroidAction
import com.nextcraft.forge.runtime.AndroidAppInstaller
import com.nextcraft.forge.runtime.AndroidBuildPhase
import com.nextcraft.forge.runtime.AndroidBuildRecord
import com.nextcraft.forge.runtime.AndroidBuildStage
import com.nextcraft.forge.runtime.AndroidLogLevel
import com.nextcraft.forge.runtime.AndroidLogLine
import com.nextcraft.forge.runtime.AndroidProjectTemplateGenerator
import com.nextcraft.forge.runtime.RuntimeExecutionService
import com.nextcraft.forge.runtime.RuntimeTaskController
import com.nextcraft.forge.runtime.androidGradleCommand
import com.nextcraft.forge.runtime.findAndroidProjectRoot
import com.nextcraft.forge.runtime.findDebugApk
import com.nextcraft.forge.runtime.hasAndroidLogcatAccess
import com.nextcraft.forge.runtime.inferAndroidBuildStage
import com.nextcraft.forge.runtime.parseAndroidBuildIssues
import com.nextcraft.forge.runtime.readBoundedText
import com.nextcraft.forge.runtime.startPackageLogcatProcess
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AndroidBuildController(private val vm: MainViewModel) {
    private val _state: MutableStateFlow<ForgeUiState> get() = vm._state
    private val viewModelScope: CoroutineScope get() = vm.viewModelScope
    private fun <T : Application> getApplication(): T = vm.getApplication()
    private val preferences get() = vm.preferences
    private val checkpoints get() = vm.checkpoints
    private fun startChrootProcess(projectSlug: String, workspaceDir: File, command: String): Process =
        vm.terminal.startChrootProcess(projectSlug, workspaceDir, command)

    internal var activeBuildJob: Job? = null
    internal var activeBuildProcess: Process? = null
    internal var activeLogcatJob: Job? = null
    internal var activeLogcatProcess: Process? = null
    private val logLineIdCounter = AtomicLong(1L)

    fun runAndroidBuild(task: String = "assembleDebug", installAfterBuild: Boolean = false) {
        val project = _state.value.activeProject ?: return
        if (_state.value.androidBuild.phase == AndroidBuildPhase.BUILDING) return
        if (batteryBlocksBuild()) {
            _state.update {
                it.copy(bannerMessage = getApplication<Application>().getString(R.string.build_blocked_low_battery, LOW_BATTERY_PERCENT))
            }
            return
        }
        val startedAt = System.currentTimeMillis()
        _state.update {
            it.copy(
                androidBuild = AndroidBuildRecord(
                    phase = AndroidBuildPhase.BUILDING,
                    stage = AndroidBuildStage.PREPARING,
                    action = if (installAfterBuild) AndroidAction.BUILD_AND_RUN else AndroidAction.BUILD,
                    startedAtMillis = startedAt,
                    message = "Running Gradle $task…",
                    log = "",
                ),
            )
        }
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                .putExtra(RuntimeExecutionService.EXTRA_TITLE, "Building Android APK")
                .putExtra(RuntimeExecutionService.EXTRA_DETAIL, "Running $task in ${project.name}")
                .putExtra(RuntimeExecutionService.EXTRA_CAN_STOP, true),
        )
        val buildStopHook: () -> Unit = {
            activeBuildProcess?.destroyForcibly()
            activeBuildJob?.cancel()
        }
        RuntimeTaskController.stopAction = buildStopHook

        activeBuildJob = viewModelScope.launch {
            var succeeded = false
            var cancelled = false
            var builtApk: File? = null
            var fullLog = ""
            var exitCode = -1
            val ws = withContext(Dispatchers.IO) { checkpoints.ensureWorkspace(project.id) }
            val androidRoot = withContext(Dispatchers.IO) { findAndroidProjectRoot(ws) ?: ws }

            try {
                val cmd = androidGradleCommand(androidRoot, task)
                withContext(Dispatchers.IO) {
                    val relativeRoot = androidRoot.relativeTo(ws).invariantSeparatorsPath
                    val guestCommand = if (relativeRoot.isEmpty()) cmd else "cd ${shellSingleQuote(relativeRoot)} && $cmd"
                    val process = startChrootProcess(project.slug, ws, guestCommand)
                    activeBuildProcess = process
                    val logBuilder = StringBuilder()
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            logBuilder.append(line).append('\n')
                            val stage = inferAndroidBuildStage(line, _state.value.androidBuild.stage)
                            _state.update { current ->
                                current.copy(
                                    androidBuild = current.androidBuild.copy(
                                        stage = stage,
                                        log = logBuilder.takeLast(64 * 1024).toString(),
                                    ),
                                )
                            }
                        }
                    }
                    exitCode = process.waitFor()
                    fullLog = logBuilder.toString()
                    builtApk = if (exitCode == 0) findDebugApk(androidRoot) else null
                    succeeded = exitCode == 0 && (task == "clean" || builtApk != null)
                }
            } catch (e: CancellationException) {
                cancelled = true
            } catch (e: Throwable) {
                if (!isActive) {
                    cancelled = true
                } else {
                    fullLog += "\nBuild exception: ${e.message}"
                }
            } finally {
                if (!isActive) cancelled = true
                activeBuildProcess = null
                RuntimeTaskController.release(buildStopHook)
            }

            val issues = if (!succeeded && !cancelled && task != "clean") {
                parseAndroidBuildIssues(fullLog, androidRoot, exitCode)
            } else {
                emptyList()
            }
            val finalApk = builtApk ?: if (!cancelled && task != "clean") {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { findDebugApk(androidRoot) }
            } else {
                null
            }
            _state.update {
                it.copy(
                    androidBuild = it.androidBuild.copy(
                        phase = when {
                            cancelled -> AndroidBuildPhase.CANCELLED
                            succeeded -> AndroidBuildPhase.SUCCEEDED
                            else -> AndroidBuildPhase.FAILED
                        },
                        stage = when {
                            cancelled -> AndroidBuildStage.CANCELLED
                            succeeded -> AndroidBuildStage.COMPLETE
                            else -> AndroidBuildStage.FAILED
                        },
                        finishedAtMillis = System.currentTimeMillis(),
                        apkPath = finalApk?.absolutePath,
                        apkSizeBytes = finalApk?.length(),
                        message = when {
                            cancelled -> "Build cancelled by user."
                            succeeded && finalApk != null -> "APK ready: ${finalApk.name} (${finalApk.length() / 1024} KB)"
                            succeeded -> "Gradle $task completed."
                            else -> issues.firstOrNull()?.detail ?: "Build failed (exit $exitCode)"
                        },
                        issues = issues,
                    ),
                )
            }
            getApplication<Application>().startService(
                Intent(getApplication(), RuntimeExecutionService::class.java)
                    .setAction(
                        when {
                            cancelled -> RuntimeExecutionService.ACTION_CANCELLED
                            succeeded -> RuntimeExecutionService.ACTION_COMPLETE
                            else -> RuntimeExecutionService.ACTION_FAILED
                        },
                    )
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                    .putExtra(
                        RuntimeExecutionService.EXTRA_DETAIL,
                        when {
                            cancelled -> "Android build cancelled"
                            succeeded -> "Android build succeeded"
                            else -> "Android build failed"
                        },
                    ),
            )
            if (succeeded && !cancelled && installAfterBuild && finalApk != null) {
                installBuiltApk(finalApk.absolutePath)
            }
        }
    }

    fun installBuiltApk(apkPath: String? = _state.value.androidBuild.apkPath) {
        val path = apkPath ?: return
        val apkFile = File(path)
        if (!apkFile.isFile) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    androidBuild = it.androidBuild.copy(
                        stage = AndroidBuildStage.INSTALLING,
                        message = "Installing ${apkFile.name}…",
                    ),
                )
            }
            val errorMsg = withContext(Dispatchers.IO) {
                runCatching {
                    AndroidAppInstaller.install(
                        context = getApplication(),
                        apk = apkFile,
                        launchAfterInstall = true,
                    )
                    null
                }.getOrElse { err -> err.message ?: "APK installation failed" }
            }
            if (errorMsg != null) {
                _state.update {
                    it.copy(
                        androidBuild = it.androidBuild.copy(
                            stage = AndroidBuildStage.FAILED,
                            message = errorMsg,
                        ),
                        bannerMessage = errorMsg,
                    )
                }
            }
        }
    }

    private fun resolveProjectAndroidPackageName(project: Project): String {
        val builtApkPath = _state.value.androidBuild.apkPath
        if (!builtApkPath.isNullOrBlank()) {
            val apkFile = File(builtApkPath)
            if (apkFile.isFile) {
                val apkPkg = AndroidAppInstaller.packageName(getApplication(), apkFile)
                if (!apkPkg.isNullOrBlank()) return apkPkg
            }
        }
        val ws = runCatching { checkpoints.ensureWorkspace(project.id) }.getOrNull()
        val androidRoot = ws?.let { findAndroidProjectRoot(it) ?: it }
        if (androidRoot != null) {
            val existingApk = findDebugApk(androidRoot)
            if (existingApk != null) {
                val apkPkg = AndroidAppInstaller.packageName(getApplication(), existingApk)
                if (!apkPkg.isNullOrBlank()) return apkPkg
            }
            val candidates = listOf(
                File(androidRoot, "app/build.gradle.kts"),
                File(androidRoot, "app/build.gradle"),
                File(androidRoot, "build.gradle.kts"),
                File(androidRoot, "build.gradle"),
                File(androidRoot, "app/src/main/AndroidManifest.xml"),
            )
            val appIdRegex = Regex("""(?:applicationId|namespace|package)\s*=?\s*["']([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+)["']""")
            for (file in candidates) {
                if (file.isFile) {
                    val text = runCatching { file.readBoundedText(64 * 1024) }.getOrNull().orEmpty()
                    val match = appIdRegex.find(text)?.groupValues?.getOrNull(1)
                    if (!match.isNullOrBlank()) return match
                }
            }
        }
        return AndroidProjectTemplateGenerator.packageName(project.name)
    }

    fun startProjectLogcat() {
        val project = _state.value.activeProject ?: return
        activeLogcatProcess?.destroyForcibly()
        activeLogcatJob?.cancel()
        activeLogcatJob = viewModelScope.launch {
            val pkg = withContext(Dispatchers.IO) { resolveProjectAndroidPackageName(project) }
            val canRead = withContext(Dispatchers.IO) { hasAndroidLogcatAccess(getApplication()) }
            if (!canRead) {
                _state.update {
                    it.copy(
                        androidLogcat = it.androidLogcat.copy(
                            available = false,
                            running = false,
                            packageName = pkg,
                            message = "Root (su) or READ_LOGS permission is required for live logcat.",
                        ),
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    androidLogcat = it.androidLogcat.copy(
                        available = true,
                        running = true,
                        packageName = pkg,
                        message = "Streaming logcat for $pkg…",
                    ),
                )
            }
            withContext(Dispatchers.IO) {
                runCatching {
                    val proc = startPackageLogcatProcess(getApplication(), pkg)
                    activeLogcatProcess = proc
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { raw ->
                            if (!isActive) return@forEach
                            val level = when {
                                " F " in raw -> AndroidLogLevel.FATAL
                                " E " in raw -> AndroidLogLevel.ERROR
                                " W " in raw -> AndroidLogLevel.WARNING
                                " I " in raw -> AndroidLogLevel.INFO
                                " D " in raw -> AndroidLogLevel.DEBUG
                                else -> AndroidLogLevel.VERBOSE
                            }
                            val entry = AndroidLogLine(
                                id = logLineIdCounter.getAndIncrement(),
                                text = raw,
                                level = level,
                            )
                            _state.update { current ->
                                current.copy(
                                    androidLogcat = current.androidLogcat.copy(
                                        lines = (current.androidLogcat.lines + entry).takeLast(300),
                                    ),
                                )
                            }
                        }
                    }
                    if (isActive) {
                        _state.update {
                            it.copy(
                                androidLogcat = it.androidLogcat.copy(
                                    running = false,
                                    message = "Logcat stream ended for $pkg.",
                                ),
                            )
                        }
                    }
                }.onFailure { err ->
                    _state.update {
                        it.copy(
                            androidLogcat = it.androidLogcat.copy(
                                running = false,
                                message = err.message ?: "Could not attach logcat to $pkg (is the app installed?)",
                            ),
                        )
                    }
                }
            }
        }
    }

    fun stopProjectLogcat() {
        activeLogcatProcess?.destroyForcibly()
        activeLogcatProcess = null
        activeLogcatJob?.cancel()
        _state.update {
            it.copy(androidLogcat = it.androidLogcat.copy(running = false, message = "Logcat paused"))
        }
    }

    fun clearProjectLogcat() {
        _state.update {
            it.copy(androidLogcat = it.androidLogcat.copy(lines = emptyList()))
        }
    }

    fun setLogcatMinimumLevel(level: AndroidLogLevel) {
        _state.update {
            it.copy(androidLogcat = it.androidLogcat.copy(minimumLevel = level))
        }
    }

    fun setLogcatQuery(query: String) {
        _state.update {
            it.copy(androidLogcat = it.androidLogcat.copy(query = query))
        }
    }

    fun updatePreviewUrl(url: String) {
        _state.update { it.copy(previewUrl = url) }
    }

    fun scanLocalPreviewPorts() {
        viewModelScope.launch {
            val openPorts = withContext(Dispatchers.IO) { LocalNetwork.scanOpenLocalPorts() }
            _state.update { current ->
                val newUrl = if (openPorts.isNotEmpty() && current.previewUrl == "http://127.0.0.1:3000") {
                    "http://127.0.0.1:${openPorts.first()}"
                } else {
                    current.previewUrl
                }
                current.copy(activeLocalPorts = openPorts, previewUrl = newUrl)
            }
        }
    }

    private fun batteryBlocksBuild(): Boolean {
        if (!preferences.batterySaver) return false
        val status = runCatching {
            getApplication<Application>().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return false
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return false
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val percent = level * 100 / scale
        return percent < LOW_BATTERY_PERCENT && !plugged
    }

    internal fun onCleared() {
        activeBuildProcess?.destroyForcibly()
        activeLogcatProcess?.destroyForcibly()
    }

    private companion object {
        const val LOW_BATTERY_PERCENT = 15
    }
}
