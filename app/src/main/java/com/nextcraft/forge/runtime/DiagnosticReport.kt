package com.nextcraft.forge.runtime

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Assembles a redacted, shareable text report about the device, runtime and recent setup log. */
object DiagnosticReport {
    private val secretPatterns = listOf(
        Regex("sk-[A-Za-z0-9_-]{8,}"),
        Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("(?i)(api[_-]?key|token|secret|password|authorization)(\\s*[=:]\\s*)([^\\s'\",}]+)"),
        Regex("(?i)(bearer\\s+)[^\\s'\",}]+"),
        Regex("https://[^\\s/]+:[^\\s@/]+@"),
    )

    fun redact(text: String): String {
        var out = text
        out = out.replace(secretPatterns[0], "sk-••••")
        out = out.replace(secretPatterns[1], "gh•_••••")
        out = out.replace(secretPatterns[2], "$1$2••••")
        out = out.replace(secretPatterns[3], "$1••••")
        out = out.replace(secretPatterns[4], "https://••••@")
        return out
    }

    fun build(
        context: Context,
        installer: RuntimeInstaller,
        rootAvailable: Boolean,
        lastLogLines: Int = 200,
    ): String {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val stacks = runCatching { installer.installedStacks().joinToString { it.name } }.getOrDefault("unavailable")
        val agents = runCatching {
            installer.installedAgentVersions().entries.joinToString { "${it.key.name}=${it.value}" }
        }.getOrDefault("unavailable")
        val toolchains = runCatching {
            installer.toolchainVersions().entries.joinToString("\n") { "  ${it.key}: ${it.value ?: "-"}" }
        }.getOrDefault("  unavailable")
        val setupLog = runCatching { RuntimeSetupController.fullLog(context) }.getOrDefault("")
            .lineSequence().filter { it.isNotBlank() }.toList().takeLast(lastLogLines).joinToString("\n")
        val runtimeInstalled = runCatching { installer.isInstalled() }.getOrDefault(false)
        val report = buildString {
            appendLine("NextCraft Forge diagnostic report")
            appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine()
            appendLine("[App]")
            appendLine("  version: $versionName")
            appendLine("  package: ${context.packageName}")
            appendLine()
            appendLine("[Device]")
            appendLine("  android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("  model: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("  abis: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("  root available: $rootAvailable")
            appendLine()
            appendLine("[Runtime]")
            appendLine("  installed: $runtimeInstalled")
            appendLine("  stacks: $stacks")
            appendLine("  agents: $agents")
            appendLine("  toolchains:")
            appendLine(toolchains)
            appendLine()
            appendLine("[Setup log, last $lastLogLines lines]")
            appendLine(setupLog.ifBlank { "  (empty)" })
        }
        return redact(report)
    }

    fun writeToCache(context: Context, report: String): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("diagnostic-") }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "diagnostic-$stamp.txt").apply { writeText(report) }
    }
}
