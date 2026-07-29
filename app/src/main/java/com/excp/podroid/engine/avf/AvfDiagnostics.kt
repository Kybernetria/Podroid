/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AVF (Android Virtualization Framework) diagnostic + smoke-test entry point.
 *
 * The android.system.virtualmachine.* APIs are @SystemApi (only present in
 * the system-stub JAR, not the public SDK). We reach them via reflection so
 * a normal Gradle build still compiles on every device. On phones without
 * pKVM the reflective lookups simply fail and the probe reports "not
 * available" — no crash, no missing-class linker error.
 *
 * Purpose: validate the manifest+`adb pm grant` path on pKVM hardware
 * (Pixel 8/9/10) before investing in a real dual-backend rewrite. Reports
 * what's present, what's granted, whether the service is reachable, and
 * (optionally) attempts to create + start a minimal VM using our existing
 * Alpine kernel/initrd in the injected default-instance paths.
 */
package com.excp.podroid.engine.avf

import android.content.Context
import android.content.pm.PackageManager
import com.excp.podroid.PodroidApplication
import com.excp.podroid.vm.MonotonicDeadline
import com.excp.podroid.vm.VmAtomicFile
import com.excp.podroid.vm.VmPathSecurity
import com.excp.podroid.vm.VmPaths
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.nanoseconds

/** One-line entries in the diagnostic report; UI just joins them. */
data class AvfReport(
    val featureSupported: Boolean,
    val managePermissionGranted: Boolean,
    val customPermissionGranted: Boolean,
    val virtApexPresent: Boolean,
    val managerClassPresent: Boolean,
    val serviceReachable: Boolean,
    val customVmConfigSupported: Boolean = false,
    val smokeTestResult: String?,
    val capabilitiesRaw: Int = 0,
    val capabilitiesDecoded: String = "n/a",
    val activeBackend: String = "?",
) {
    fun pretty(): String = buildString {
        appendLine("Active backend")
        appendLine("  $activeBackend")
        appendLine()
        appendLine("Feature: virtualization_framework")
        appendLine("  supported = $featureSupported")
        appendLine()
        appendLine("Permission: MANAGE_VIRTUAL_MACHINE")
        appendLine("  granted = $managePermissionGranted")
        appendLine()
        appendLine("Permission: USE_CUSTOM_VIRTUAL_MACHINE")
        appendLine("  granted = $customPermissionGranted")
        appendLine()
        appendLine("APEX /apex/com.android.virt")
        appendLine("  present = $virtApexPresent")
        appendLine()
        appendLine("API VirtualMachineManager")
        appendLine("  class loadable = $managerClassPresent")
        appendLine()
        appendLine("Service")
        appendLine("  reachable via system service = $serviceReachable")
        appendLine()
        appendLine("Custom-VM API")
        appendLine("  builder present = $customVmConfigSupported")
        appendLine()
        appendLine("Hypervisor capabilities")
        appendLine("  raw = $capabilitiesRaw ($capabilitiesDecoded)")
        if (smokeTestResult != null) {
            appendLine()
            appendLine("Smoke test")
            appendLine(smokeTestResult.prependIndent("  "))
        }
    }
}

object AvfDiagnostics {

    private const val FEATURE = "android.software.virtualization_framework"
    private const val PERM_MANAGE = "android.permission.MANAGE_VIRTUAL_MACHINE"
    private const val PERM_CUSTOM = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
    private const val CLS_MANAGER = "android.system.virtualmachine.VirtualMachineManager"
    private const val CLS_CONFIG = "android.system.virtualmachine.VirtualMachineConfig"
    private const val CLS_CUSTOM_CFG = "android.system.virtualmachine.VirtualMachineCustomImageConfig"

    /**
     * True only if this device's AVF build exposes the custom-VM builder API
     * Podroid drives (raw kernel + initrd). A vendor build that ships AVF for
     * system use but omits the custom-image config will return false, so
     * EngineHolder.pick() can fall back to QEMU at selection time instead of
     * erroring at VM start. Never throws: a missing class is a normal "no".
     */
    fun customVmConfigSupported(): Boolean = runCatching {
        val builder = Class.forName("$CLS_CUSTOM_CFG\$Builder")
        builder.getDeclaredMethod("setKernelPath", String::class.java)
        builder.getDeclaredMethod("setInitrdPath", String::class.java)
        Class.forName("$CLS_CONFIG\$Builder")
            .getDeclaredMethod("setCustomImageConfig", Class.forName(CLS_CUSTOM_CFG))
        true
    }.getOrDefault(false)

    /**
     * Read-only probe — never blocks, never touches the system service for
     * real (just checks reachability). Safe to call from anywhere.
     */
    fun probe(context: Context): AvfReport {
        val pm = context.packageManager
        val featureSupported = pm.hasSystemFeature(FEATURE)
        val managePermissionGranted = pm.checkPermission(PERM_MANAGE, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
        val customPermissionGranted = pm.checkPermission(PERM_CUSTOM, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
        val virtApexPresent = File("/apex/com.android.virt/bin").exists()
        val managerClassPresent = runCatching { Class.forName(CLS_MANAGER) }.isSuccess
        val serviceReachable = managerClassPresent && managePermissionGranted &&
            runCatching { getVirtualizationManager(context) != null }.getOrDefault(false)

        val capabilitiesRaw = if (serviceReachable) {
            runCatching { AvfReflect.getCapabilities(AvfReflect.manager(context)) }.getOrDefault(0)
        } else 0

        return AvfReport(
            featureSupported = featureSupported,
            managePermissionGranted = managePermissionGranted,
            customPermissionGranted = customPermissionGranted,
            virtApexPresent = virtApexPresent,
            managerClassPresent = managerClassPresent,
            serviceReachable = serviceReachable,
            customVmConfigSupported = customVmConfigSupported(),
            smokeTestResult = null,
            capabilitiesRaw = capabilitiesRaw,
            capabilitiesDecoded = AvfCapabilities.decode(capabilitiesRaw),
        )
    }

    /**
     * Attempts a real minimal-VM creation using our existing Alpine kernel and
     * initrd. All uncontrolled vendor setup/create/run/stop work is isolated in
     * a daemon Future with a hard caller-side deadline.
     */
    suspend fun runSmokeTest(
        context: Context,
        vmPaths: VmPaths,
        callerDeadlineNanos: Long,
        nanoTime: () -> Long = System::nanoTime,
    ): String {
        val deadlineNanos = MonotonicDeadline.clamp(
            callerDeadlineNanos,
            TOTAL_SMOKE_TIMEOUT_MS,
            nanoTime,
        ) ?: return boundSmokeTestResult(smokeDeadlineResult())
        return withContext(Dispatchers.IO) {
            val result = try {
                runSmokeTestOnIo(context, vmPaths, deadlineNanos, nanoTime)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failureResult(failure)
            }
            boundSmokeTestResult(result)
        }
    }

    internal fun boundSmokeTestResult(result: String): String = result.take(MAX_SMOKE_RESULT_CHARS)

    /** Waits for readiness using only the time left on the propagated absolute deadline. */
    internal suspend fun awaitSmokeReadiness(
        deadlineNanos: Long,
        nanoTime: () -> Long = System::nanoTime,
        awaitReady: suspend () -> Unit,
    ): Boolean {
        val remainingNanos = MonotonicDeadline.remainingNanos(deadlineNanos, nanoTime)
        if (remainingNanos == 0L) return false
        return withTimeoutOrNull(remainingNanos.nanoseconds) {
            awaitReady()
            MonotonicDeadline.remainingNanos(deadlineNanos, nanoTime) > 0L
        } == true
    }

    private data class SmokeSetup(val manager: Any, val config: Any)
    private class SmokeOutcome(val result: String) : IOException(result)

    private suspend fun runSmokeTestOnIo(
        context: Context,
        vmPaths: VmPaths,
        deadlineNanos: Long,
        nanoTime: () -> Long,
    ): String {
        val application = context.applicationContext as? PodroidApplication
            ?: return "FAILED: Podroid application readiness gate unavailable"
        val ready = try {
            awaitSmokeReadiness(deadlineNanos, nanoTime) {
                application.awaitAssetsReady()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return "FAILED: default VM migration/assets unavailable: ${failureSummary(failure)}"
        }
        if (!ready) return smokeDeadlineResult()

        // Readiness, operation, and cleanup reporting all consume the same
        // absolute caller deadline; no phase receives a restarted duration.
        val pathSecurity = VmPathSecurity(vmPaths)
        val kernelSrc = vmPaths.kernel
        val initrd = vmPaths.initrd
        val name = SMOKE_VM_NAME
        val execution = AvfSmokeTestExecutor(nanoTime = nanoTime).execute(
            deadlineNanos = deadlineNanos,
            setup = {
                try {
                    pathSecurity.validateForLaunch()
                } catch (failure: Throwable) {
                    throw SmokeOutcome("FAILED: unsafe default VM paths: ${failureSummary(failure)}")
                }
                val pre = probe(context)
                if (!pre.featureSupported) {
                    throw SmokeOutcome("skipped: feature flag not present (device does not ship AVF)")
                }
                if (!pre.managePermissionGranted) {
                    throw SmokeOutcome("skipped: MANAGE_VIRTUAL_MACHINE not granted (run: adb shell pm grant ${context.packageName} $PERM_MANAGE)")
                }
                if (!pre.customPermissionGranted) {
                    throw SmokeOutcome("skipped: USE_CUSTOM_VIRTUAL_MACHINE not granted (run: adb shell pm grant ${context.packageName} $PERM_CUSTOM)")
                }
                if (!pre.managerClassPresent) {
                    throw SmokeOutcome("FAILED: $CLS_MANAGER not on the boot classpath — system stub missing")
                }
                if (!kernelSrc.exists()) {
                    throw SmokeOutcome("FAILED: kernel not extracted yet at ${kernelSrc.absolutePath}")
                }
                if (!initrd.exists()) {
                    throw SmokeOutcome("FAILED: initrd not extracted yet at ${initrd.absolutePath}")
                }
                if (AvfCapabilities.choose(pre.capabilitiesRaw) is
                    AvfCapabilities.ProtectedVmChoice.Unsupported
                ) {
                    throw SmokeOutcome(protectedVmNotApplicable("caps=${pre.capabilitiesDecoded}"))
                }

                val manager = getVirtualizationManager(context)
                    ?: throw SmokeOutcome("FAILED: VirtualMachineManager system service returned null")
                // crosvm requires the raw ARM64 Image. Preparation and all
                // reflective builder calls remain inside the bounded Future.
                val kernel = ensureRawKernel(kernelSrc, vmPaths.rawKernel, pathSecurity)
                val customConfig = buildCustomImageConfig(kernel.absolutePath, initrd.absolutePath)
                SmokeSetup(manager, buildVirtualMachineConfig(manager, context, customConfig))
            },
            create = { prepared -> invokeOrCreate(prepared.manager, name, prepared.config) },
            run = { vm ->
                pathSecurity.validateForLaunch()
                vm.javaClass.getMethod("run").invoke(vm)
            },
            stop = { vm -> vm.javaClass.getMethod("stop").invoke(vm) },
            // Resolve the manager independently. Delete must not depend on setup
            // publishing a manager or create publishing a VM object.
            deleteByFixedName = {
                val manager = getVirtualizationManager(context)
                    ?: throw IOException("VirtualMachineManager system service returned null")
                manager.javaClass.getMethod("delete", String::class.java).invoke(manager, name)
            },
        )

        if (execution.busy) {
            return "FAILED: another AVF smoke operation/final cleanup is pending; " +
                "admission remains closed and no concurrent attempt was started"
        }
        val deleteStatus = when {
            execution.cleanupPending -> "named delete=pending; admission remains closed"
            !execution.deleteAttempted -> "named delete=not needed"
            execution.deleteFailure == null -> "named delete=completed"
            else -> "named delete=${failureSummary(execution.deleteFailure)}"
        }
        if (execution.timedOut) {
            return "FAILED: caller deadline reached at ${execution.timeoutStage}; " +
                "cancellation was requested best-effort and vendor work may still be running; " +
                deleteStatus
        }
        execution.failure?.let { failure ->
            if (failure is SmokeOutcome) return failure.result
            val cause = failure.cause ?: failure
            if (cause is UnsupportedOperationException &&
                cause.message?.contains("protected", ignoreCase = true) == true
            ) {
                return protectedVmNotApplicable(failureSummary(cause))
            }
            return "FAILED during AVF smoke setup/create/run: ${failureSummary(failure)}; $deleteStatus" +
                (execution.stopFailure?.let { "; stop=${failureSummary(it)}" } ?: "")
        }
        execution.stopFailure?.let {
            return "FAILED during AVF smoke cleanup: stop=${failureSummary(it)}; $deleteStatus"
        }
        execution.deleteFailure?.let {
            return "FAILED during AVF smoke cleanup: $deleteStatus"
        }
        return "SUCCESS: AVF accepted our config, VM started + stopped cleanly; $deleteStatus. " +
            "The dev-grant path works on this device."
    }

    private fun smokeDeadlineResult(): String =
        "FAILED: AVF smoke exceeded total ${TOTAL_SMOKE_TIMEOUT_MS}ms absolute deadline"

    private fun protectedVmNotApplicable(detail: String): String =
        "not applicable on this device: AVF rejected a non-protected VM ($detail). " +
            "Podroid's custom Linux kernel can run only as a non-protected VM. This is expected, " +
            "not a failure: Podroid automatically uses the QEMU backend here."

    private fun failureResult(failure: Throwable): String {
        val cause = failure.cause ?: failure
        return if (cause is UnsupportedOperationException &&
            cause.message?.contains("protected", ignoreCase = true) == true
        ) {
            protectedVmNotApplicable(failureSummary(cause))
        } else {
            "FAILED: ${failureSummary(failure)}"
        }
    }

    private fun failureSummary(failure: Throwable): String {
        val cause = failure.cause ?: failure
        return "${cause.javaClass.simpleName}: ${cause.message ?: "no detail"}"
    }

    private fun getVirtualizationManager(context: Context): Any? {
        // Context.getSystemService(Class) — but the class is loaded reflectively
        // so we can't call the typed overload at compile time.
        val mgrCls = Class.forName(CLS_MANAGER)
        val m = Context::class.java.getMethod("getSystemService", Class::class.java)
        return m.invoke(context, mgrCls)
    }

    /**
     * Mirrors AvfEngine.ensureRawKernel: crosvm requires the raw ARM64 Image
     * (magic `ARM\x64` at 0x38), not the gzip-compressed vmlinuz. Decompress to
     * the same sibling `.raw` file so the cache is shared with the real VM path.
     * Returns the source untouched if it isn't gzip.
     */
    private fun ensureRawKernel(
        source: File,
        raw: File,
        pathSecurity: VmPathSecurity,
    ): File {
        val magic = ByteArray(4)
        source.inputStream().use { it.read(magic) }
        if (magic[0] != 0x1f.toByte() || magic[1] != 0x8b.toByte()) return source
        if (raw.exists() && raw.lastModified() >= source.lastModified()) return raw
        VmAtomicFile.write(raw, pathSecurity) { output ->
            java.util.zip.GZIPInputStream(source.inputStream().buffered()).use { gz ->
                gz.copyTo(output)
            }
        }
        return raw
    }

    private fun buildCustomImageConfig(kernelPath: String, initrdPath: String): Any {
        val builderCls = Class.forName("$CLS_CUSTOM_CFG\$Builder")
        val ctor = builderCls.getDeclaredConstructor().apply { isAccessible = true }
        val builder = ctor.newInstance()
        invokeSetter(builderCls, builder, "setName", String::class.java, "podroid-avf-smoke")
        invokeSetter(builderCls, builder, "setKernelPath", String::class.java, kernelPath)
        invokeSetter(builderCls, builder, "setInitrdPath", String::class.java, initrdPath)
        runCatching {
            invokeSetter(builderCls, builder, "setParams", String::class.java, "console=hvc0 panic=1")
        }
        val buildM = builderCls.getDeclaredMethod("build").apply { isAccessible = true }
        return buildM.invoke(builder)
    }

    private fun buildVirtualMachineConfig(vmm: Any, context: Context, customCfg: Any): Any {
        val builderCls = Class.forName("$CLS_CONFIG\$Builder")
        val ctor = builderCls.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val builder = ctor.newInstance(context)
        val customCfgCls = Class.forName(CLS_CUSTOM_CFG)
        invokeSetter(builderCls, builder, "setCustomImageConfig", customCfgCls, customCfg)
        when (val choice = AvfReflect.applyProtectedVm(vmm, builder)) {
            is AvfCapabilities.ProtectedVmChoice.Unsupported ->
                throw UnsupportedOperationException(choice.reason)
            else -> Unit  // NonProtected/Unknown: setter already applied (or threw, which surfaces)
        }
        runCatching {
            invokeSetter(builderCls, builder, "setMemoryBytes",
                Long::class.javaPrimitiveType!!, 256L * 1024 * 1024)
        }
        val buildM = builderCls.getDeclaredMethod("build").apply { isAccessible = true }
        return buildM.invoke(builder)
    }

    private fun invokeSetter(cls: Class<*>, target: Any, name: String, argType: Class<*>, arg: Any?) {
        val m = cls.getDeclaredMethod(name, argType).apply { isAccessible = true }
        m.invoke(target, arg)
    }

    private fun invokeOrCreate(vmm: Any, name: String, config: Any): Any {
        val cfgCls = Class.forName(CLS_CONFIG)
        return runCatching {
            val m = vmm.javaClass.getDeclaredMethod("getOrCreate", String::class.java, cfgCls)
                .apply { isAccessible = true }
            m.invoke(vmm, name, config)
        }.getOrElse {
            val m = vmm.javaClass.getDeclaredMethod("create", String::class.java, cfgCls)
                .apply { isAccessible = true }
            m.invoke(vmm, name, config)
        } ?: error("getOrCreate returned null")
    }

    private const val SMOKE_VM_NAME = "podroid-avf-smoke"
    private const val MAX_SMOKE_RESULT_CHARS = 4 * 1024
    private const val TOTAL_SMOKE_TIMEOUT_MS = 15_000L
}
