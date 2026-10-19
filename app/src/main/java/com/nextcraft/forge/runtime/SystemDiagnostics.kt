package com.nextcraft.forge.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ChrootProcess(
    val pid: Int,
    val rssKb: Long,
    val name: String,
    val cmdline: String,
)

data class DiagnosticsSnapshot(
    val cpuPercent: Float,
    val memUsedMb: Long,
    val memTotalMb: Long,
    val batteryPercent: Int,
    val charging: Boolean,
    val batteryTempC: Float,
    val storageFreeMb: Long,
    val storageTotalMb: Long,
    val chrootProcesses: List<ChrootProcess>,
)

class SystemDiagnostics(private val context: Context) {
    private var previousCpuTotal = 0L
    private var previousCpuIdle = 0L
    private val rootfsPath: String = RuntimeInstaller.rootfsDir(context).absolutePath

    suspend fun sample(): DiagnosticsSnapshot = withContext(Dispatchers.IO) {
        val (memUsed, memTotal) = readMemory()
        val (batteryPercent, charging, temp) = readBattery()
        val (storageFree, storageTotal) = readStorage()
        DiagnosticsSnapshot(
            cpuPercent = readCpuPercent(),
            memUsedMb = memUsed,
            memTotalMb = memTotal,
            batteryPercent = batteryPercent,
            charging = charging,
            batteryTempC = temp,
            storageFreeMb = storageFree,
            storageTotalMb = storageTotal,
            chrootProcesses = runCatching { listChrootProcesses() }.getOrDefault(emptyList()),
        )
    }

    suspend fun kill(pid: Int, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (pid <= 1) return@withContext false
        val signal = if (force) "KILL" else "TERM"
        runSu("kill -$signal $pid", timeoutMs = 3_000L)?.exitCode == 0
    }

    private fun readCpuPercent(): Float {
        val line = runCatching { File("/proc/stat").bufferedReader().use { it.readLine() } }.getOrNull() ?: return 0f
        val parts = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return 0f
        val idle = parts[3] + parts.getOrElse(4) { 0L }
        val total = parts.sum()
        val deltaTotal = total - previousCpuTotal
        val deltaIdle = idle - previousCpuIdle
        previousCpuTotal = total
        previousCpuIdle = idle
        if (deltaTotal <= 0L) return 0f
        return ((deltaTotal - deltaIdle).toFloat() / deltaTotal * 100f).coerceIn(0f, 100f)
    }

    private fun readMemory(): Pair<Long, Long> {
        val values = runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.mapNotNull { line ->
                    val key = line.substringBefore(':')
                    val kb = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
                    if (kb != null) key to kb else null
                }.toMap()
            }
        }.getOrDefault(emptyMap())
        val total = values["MemTotal"] ?: 0L
        val available = values["MemAvailable"] ?: values["MemFree"] ?: 0L
        return ((total - available) / 1024L).coerceAtLeast(0L) to total / 1024L
    }

    private fun readBattery(): Triple<Int, Boolean, Float> {
        val intent: Intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return Triple(-1, false, 0f)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        return Triple(percent, charging, temp)
    }

    private fun readStorage(): Pair<Long, Long> = runCatching {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBytes / MB to stat.totalBytes / MB
    }.getOrDefault(0L to 0L)

    private fun listChrootProcesses(): List<ChrootProcess> {
        val ps = runSu("ps -A -o PID,RSS,NAME,ARGS", timeoutMs = 3_000L) ?: return emptyList()
        if (ps.exitCode != 0) return emptyList()
        val rows = ps.output.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 4)
                val pid = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                ChrootProcess(
                    pid = pid,
                    rssKb = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
                    name = parts.getOrNull(2).orEmpty(),
                    cmdline = parts.getOrNull(3).orEmpty(),
                )
            }
            .filter { it.pid > 1 }
            .toList()
        if (rows.isEmpty()) return emptyList()

        val byCmdline = rows.filter { it.cmdline.contains(rootfsPath) }
        val candidates = rows.filterNot { it.cmdline.contains(rootfsPath) }
        if (candidates.isEmpty()) return byCmdline

        // One su call for every pid: "<pid> <root>" per line; unreadable roots print "<pid> ?".
        val script = buildString {
            append("for p in ")
            candidates.joinTo(this, " ") { it.pid.toString() }
            append("; do r=\$(readlink /proc/\$p/root 2>/dev/null || echo '?'); echo \"\$p \$r\"; done")
        }
        val roots = runSu(script, timeoutMs = 3_000L)?.takeIf { it.exitCode == 0 }?.output.orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val pid = line.substringBefore(' ').toIntOrNull() ?: return@mapNotNull null
                pid to line.substringAfter(' ', "")
            }
            .toMap()
        val byRoot = candidates.filter { proc ->
            val root = roots[proc.pid] ?: return@filter false
            root == rootfsPath || root.startsWith("$rootfsPath/")
        }
        return (byCmdline + byRoot).sortedBy { it.pid }
    }

    private data class SuResult(val exitCode: Int, val output: String)

    private fun runSu(command: String, timeoutMs: Long): SuResult? = runCatching {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val collector = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(4096)
                    while (output.length < MAX_SU_OUTPUT_CHARS) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.append(buffer, 0, count)
                    }
                }
            }
        }, "forge-diag-su").apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        collector.join(500L)
        SuResult(process.exitValue(), output.toString())
    }.getOrNull()

    private companion object {
        const val MB = 1024L * 1024L
        const val MAX_SU_OUTPUT_CHARS = 512 * 1024
    }
}
