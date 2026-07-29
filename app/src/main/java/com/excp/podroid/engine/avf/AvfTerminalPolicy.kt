package com.excp.podroid.engine.avf

import com.excp.podroid.engine.VmState

/** Pure callback decision used to keep rejected-stop Error handles retry-safe. */
internal object AvfTerminalPolicy {
    enum class Decision { IGNORE, CLEANUP_RETAIN_ERROR, TRANSITION_AND_CLEANUP }

    fun decide(
        callbackGeneration: Long,
        currentGeneration: Long,
        currentState: VmState,
        cleanupComplete: Boolean,
    ): Decision = when {
        callbackGeneration != currentGeneration -> Decision.IGNORE
        currentState is VmState.Stopped || currentState is VmState.Idle -> Decision.IGNORE
        currentState is VmState.Error && !cleanupComplete -> Decision.CLEANUP_RETAIN_ERROR
        currentState is VmState.Error -> Decision.IGNORE
        else -> Decision.TRANSITION_AND_CLEANUP
    }
}
