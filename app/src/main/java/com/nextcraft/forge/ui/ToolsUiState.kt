package com.nextcraft.forge.ui

import com.nextcraft.forge.network.Workflow
import com.nextcraft.forge.network.WorkflowJob
import com.nextcraft.forge.network.WorkflowRun
import com.nextcraft.forge.runtime.SshHost
import com.nextcraft.forge.runtime.SshKeyInfo

data class CicdUiState(
    val resolved: Boolean = false,
    val owner: String? = null,
    val repo: String? = null,
    val branch: String = "",
    val runs: List<WorkflowRun> = emptyList(),
    val workflows: List<Workflow> = emptyList(),
    val selectedRunId: Long? = null,
    val jobs: List<WorkflowJob> = emptyList(),
    val selectedJobId: Long? = null,
    val jobLog: String? = null,
    val busy: Boolean = false,
    val statusMessage: String? = null,
) {
    val repositoryName: String? get() = owner?.let { o -> repo?.let { r -> "$o/$r" } }
}

data class SshUiState(
    val hosts: List<SshHost> = emptyList(),
    val keys: List<SshKeyInfo> = emptyList(),
    val clientInstalled: Boolean = false,
    val forwardingHostId: String? = null,
    val statusMessage: String? = null,
)
