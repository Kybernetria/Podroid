/* UI-safe domain aliases/policies that do not expose backend implementations. */
package com.excp.podroid.vm

/** Persisted backend preference is configuration data, not an engine capability. */
typealias EngineSelection = com.excp.podroid.engine.EngineSelection

/** Console export privacy policy is backend-neutral. */
internal typealias SensitiveConsolePolicy = com.excp.podroid.engine.SensitiveConsolePolicy

enum class VmFailureAdvice { TRY_ONE_CORE, SWITCH_TO_QEMU }

fun vmFailureAdvice(cpus: Int): VmFailureAdvice =
    if (cpus > 1) VmFailureAdvice.TRY_ONE_CORE else VmFailureAdvice.SWITCH_TO_QEMU
