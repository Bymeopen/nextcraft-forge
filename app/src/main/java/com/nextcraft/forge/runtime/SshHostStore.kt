package com.nextcraft.forge.runtime

import android.content.Context
import android.system.Os
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class PortForward(
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
)

data class SshHost(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val keyId: String? = null,
    val portForwards: List<PortForward> = emptyList(),
)

data class SshKeyInfo(val id: String, val label: String)

/** Saved SSH hosts and private keys. Keys live in the rootfs so `ssh` inside the chroot can read them. */
class SshHostStore(private val context: Context) {
    private val dir = File(context.filesDir, "ssh")
    private val hostsFile = File(dir, "hosts.json")
    private val keysFile = File(dir, "keys.json")
    private val rootfs = RuntimeInstaller.rootfsDir(context)
    private val guestKeyDir = File(rootfs, "root/.ssh")

    @Synchronized
    fun hosts(): List<SshHost> = readArray(hostsFile).let { array ->
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                SshHost(
                    id = item.getString("id"),
                    label = item.optString("label"),
                    host = item.getString("host"),
                    port = item.optInt("port", 22),
                    user = item.optString("user", "root"),
                    keyId = item.optString("keyId").takeIf(String::isNotBlank),
                    portForwards = item.optJSONArray("portForwards")?.let { forwards ->
                        (0 until forwards.length()).mapNotNull { forwardIndex ->
                            forwards.optJSONObject(forwardIndex)?.let { forward ->
                                PortForward(
                                    localPort = forward.optInt("localPort"),
                                    remoteHost = forward.optString("remoteHost", "localhost"),
                                    remotePort = forward.optInt("remotePort"),
                                )
                            }
                        }
                    }.orEmpty(),
                )
            }.getOrNull()
        }
    }

    @Synchronized
    fun save(host: SshHost): SshHost {
        validate(host)
        val updated = hosts().filterNot { it.id == host.id } + host
        writeHosts(updated)
        return host
    }

    @Synchronized
    fun delete(hostId: String) {
        writeHosts(hosts().filterNot { it.id == hostId })
    }

    @Synchronized
    fun keys(): List<SshKeyInfo> = readArray(keysFile).let { array ->
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                val id = item.optString("id")
                if (id.isBlank()) null else SshKeyInfo(id, item.optString("label"))
            }
        }
    }

    /** Stores [pem] as root/.ssh/<id> (0600) inside the rootfs and returns the id. */
    @Synchronized
    fun importPrivateKey(label: String, pem: String): String {
        val body = pem.trim()
        require(body.contains("PRIVATE KEY")) { "Not a PEM private key" }
        val id = "key-" + UUID.randomUUID().toString().replace("-", "")
        guestKeyDir.mkdirs()
        runCatching { Os.chmod(guestKeyDir.absolutePath, 0b111000000) }
        val target = File(guestKeyDir, id)
        target.writeText(body + "\n")
        runCatching { Os.chmod(target.absolutePath, 0b110000000) }
        val entries = keys() + SshKeyInfo(id, label.trim().ifBlank { "Key ${keys().size + 1}" }.take(60))
        writeKeys(entries)
        return id
    }

    @Synchronized
    fun deleteKey(keyId: String) {
        require(SAFE_ID.matches(keyId)) { "Invalid key id" }
        File(guestKeyDir, keyId).delete()
        writeKeys(keys().filterNot { it.id == keyId })
        writeHosts(hosts().map { if (it.keyId == keyId) it.copy(keyId = null) else it })
    }

    fun isSshClientInstalled(): Boolean = File(rootfs, "usr/bin/ssh").let { it.exists() || java.nio.file.Files.isSymbolicLink(it.toPath()) }

    /** Argument vector for an interactive session, run inside the chroot. */
    fun buildSshCommand(host: SshHost): List<String> {
        validate(host)
        return buildList {
            add("ssh")
            add("-o"); add("StrictHostKeyChecking=accept-new")
            add("-o"); add("ServerAliveInterval=30")
            add("-p"); add(host.port.toString())
            host.keyId?.let { add("-i"); add("$GUEST_KEY_DIR/$it") }
            host.portForwards.forEach { forward ->
                add("-L"); add("${forward.localPort}:${forward.remoteHost}:${forward.remotePort}")
            }
            add("${host.user}@${host.host}")
        }
    }

    /** Same as [buildSshCommand] but only keeps the tunnels open (`-N`). */
    fun buildForwardOnlyCommand(host: SshHost): List<String> {
        val command = buildSshCommand(host).toMutableList()
        command.add(1, "-N")
        return command
    }

    private fun validate(host: SshHost) {
        require(SAFE_ID.matches(host.id)) { "Invalid host id" }
        require(host.host.isNotBlank() && SAFE_HOST.matches(host.host)) { "Invalid host name" }
        require(host.user.isNotBlank() && SAFE_USER.matches(host.user)) { "Invalid user name" }
        require(host.port in 1..65535) { "Invalid port" }
        host.keyId?.let { require(SAFE_ID.matches(it)) { "Invalid key id" } }
        host.portForwards.forEach { forward ->
            require(forward.localPort in 1..65535 && forward.remotePort in 1..65535) { "Invalid port forward" }
            require(SAFE_HOST.matches(forward.remoteHost)) { "Invalid forward host" }
        }
    }

    private fun writeHosts(hosts: List<SshHost>) {
        val array = JSONArray()
        hosts.forEach { host ->
            array.put(
                JSONObject()
                    .put("id", host.id)
                    .put("label", host.label)
                    .put("host", host.host)
                    .put("port", host.port)
                    .put("user", host.user)
                    .put("keyId", host.keyId ?: "")
                    .put(
                        "portForwards",
                        JSONArray().apply {
                            host.portForwards.forEach { forward ->
                                put(
                                    JSONObject()
                                        .put("localPort", forward.localPort)
                                        .put("remoteHost", forward.remoteHost)
                                        .put("remotePort", forward.remotePort),
                                )
                            }
                        },
                    ),
            )
        }
        writeAtomically(hostsFile, array.toString())
    }

    private fun writeKeys(keys: List<SshKeyInfo>) {
        val array = JSONArray()
        keys.forEach { array.put(JSONObject().put("id", it.id).put("label", it.label)) }
        writeAtomically(keysFile, array.toString())
    }

    private fun readArray(file: File): JSONArray =
        runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())

    private fun writeAtomically(target: File, content: String) {
        dir.mkdirs()
        val temporary = File(dir, ".${target.name}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    private companion object {
        const val GUEST_KEY_DIR = "/root/.ssh"
        val SAFE_ID = Regex("^[A-Za-z0-9-]{1,80}$")
        val SAFE_HOST = Regex("^[A-Za-z0-9.:\\[\\]-]{1,253}$")
        val SAFE_USER = Regex("^[A-Za-z0-9._-]{1,64}$")
    }
}
