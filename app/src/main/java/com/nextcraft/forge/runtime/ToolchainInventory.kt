package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack

data class ToolchainEntry(
    val id: String,
    val label: String,
    val installedVersion: String?,
    val stack: DevStack? = null,
    val agent: AgentKind? = null,
    val removable: Boolean = false,
) {
    val installed: Boolean get() = installedVersion != null
}

class ToolchainInventory(
    @Suppress("UNUSED_PARAMETER") private val context: Context,
    private val installer: RuntimeInstaller,
) {
    fun list(): List<ToolchainEntry> {
        if (!installer.isInstalled()) return emptyList()
        val versions = installer.toolchainVersions()
        val stacks = installer.installedStacks()
        val agents = installer.installedAgentVersions()
        fun stackVersion(stack: DevStack, key: String): String? =
            versions[key]?.takeIf { stack in stacks || stack == DevStack.WEB }

        return listOf(
            ToolchainEntry("node", "Node.js / npm", versions["node"], stack = DevStack.WEB, removable = false),
            ToolchainEntry("git", "Git", versions["git"]),
            ToolchainEntry("python", "Python 3", stackVersion(DevStack.PYTHON, "python"), stack = DevStack.PYTHON, removable = true),
            ToolchainEntry("java", "JDK (Temurin)", stackVersion(DevStack.ANDROID, "java"), stack = DevStack.ANDROID, removable = true),
            ToolchainEntry("gradle", "Gradle", stackVersion(DevStack.ANDROID, "gradle"), stack = DevStack.ANDROID, removable = true),
            ToolchainEntry("android-sdk", "Android SDK", stackVersion(DevStack.ANDROID, "android-sdk"), stack = DevStack.ANDROID, removable = true),
            ToolchainEntry("gcc", "C / C++ (gcc, cmake)", stackVersion(DevStack.CPP, "gcc"), stack = DevStack.CPP, removable = true),
            ToolchainEntry("php", "PHP / Composer", stackVersion(DevStack.PHP, "php"), stack = DevStack.PHP, removable = true),
            ToolchainEntry("ssh", "OpenSSH client", versions["ssh"]),
            ToolchainEntry("gh", "GitHub CLI", versions["gh"]),
            ToolchainEntry("claude-code", AgentKind.CLAUDE_CODE.title, agents[AgentKind.CLAUDE_CODE], agent = AgentKind.CLAUDE_CODE),
            ToolchainEntry("dsh", AgentKind.DEEPSEEK_HARNESS.title, agents[AgentKind.DEEPSEEK_HARNESS], agent = AgentKind.DEEPSEEK_HARNESS),
            ToolchainEntry("agy", AgentKind.ANTIGRAVITY.title, agents[AgentKind.ANTIGRAVITY], agent = AgentKind.ANTIGRAVITY),
        )
    }

    /** Stacks the user can add from the inventory (not yet installed, excluding the always-present core). */
    fun installableStacks(): List<DevStack> =
        DevStack.entries.filter { it != DevStack.WEB && it !in installer.installedStacks() }
}
