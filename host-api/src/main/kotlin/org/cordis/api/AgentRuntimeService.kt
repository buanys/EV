package org.cordis.api

import org.cordis.ServiceKey

/**
 * Where the agent runtime binary lives.
 *
 * The daemon cannot ship inside a plugin container: Android's SELinux policy
 * denies executing anything under an app's data directory, so unpacking a jar
 * entry to `files/` and starting it fails with EACCES no matter what the file
 * mode says. Native libraries are the one place an app may execute from, so the
 * host packages the binary as `libev-agentd.so` and hands out that path.
 *
 * Null means this build was assembled without the runtime (the file was not
 * present when the APK was built); callers must report that instead of guessing
 * a path.
 */
interface AgentRuntimeService {
    fun binaryPath(): String?

    companion object {
        val Key = ServiceKey<AgentRuntimeService>("host/agent-runtime")
    }
}
