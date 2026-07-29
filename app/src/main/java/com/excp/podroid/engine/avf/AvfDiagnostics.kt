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
import com.excp.podroid.vm.VmAtomicFile
import com.excp.podroid.vm.VmPathSecurity
import com.excp.podroid.vm.VmPaths
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
     * initrd. All file/reflection work is forced onto IO, the attempt has a
     * deadline, and every path after creation attempts bounded stop and delete.
     */
    suspend fun runSmokeTest(context: Context, vmPaths: VmPaths): String =
        withContext(Dispatchers.IO) {
            val result = try {
                withTimeoutOrNull(TOTAL_SMOKE_TIMEOUT_MS) {
                    runSmokeTestOnIo(context, vmPaths)
                } ?: "FAILED: AVF smoke test exceeded total ${TOTAL_SMOKE_TIMEOUT_MS}ms deadline"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failureResult(failure)
            }
            boundSmokeTestResult(result)
        }

    internal fun boundSmokeTestResult(result: String): String = result.take(MAX_SMOKE_RESULT_CHARS)

    private suspend fun runSmokeTestOnIo(context: Context, vmPaths: VmPaths): String {
        val application = context.applicationContext as? PodroidApplication
            ?: return "FAILED: Podroid application readiness gate unavailable"
        try {
            application.awaitAssetsReady()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return "FAILED: default VM migration/assets unavailable: ${failureSummary(failure)}"
        }
        val pathSecurity = VmPathSecurity(vmPaths)
        try {
            pathSecurity.validateForLaunch()
        } catch (failure: Throwable) {
            return "FAILED: unsafe default VM paths: ${failureSummary(failure)}"
        }

        val pre = runInterruptible(Dispatchers.IO) { probe(context) }
        if (!pre.featureSupported) return "skipped: feature flag not present (device does not ship AVF)"
        if (!pre.managePermissionGranted) return "skipped: MANAGE_VIRTUAL_MACHINE not granted (run: adb shell pm grant ${context.packageName} $PERM_MANAGE)"
        if (!pre.customPermissionGranted) return "skipped: USE_CUSTOM_VIRTUAL_MACHINE not granted (run: adb shell pm grant ${context.packageName} $PERM_CUSTOM)"
        if (!pre.managerClassPresent) return "FAILED: $CLS_MANAGER not on the boot classpath — system stub missing"

        val kernelSrc = vmPaths.kernel
        val initrd = vmPaths.initrd
        if (!kernelSrc.exists()) return "FAILED: kernel not extracted yet at ${kernelSrc.absolutePath}"
        if (!initrd.exists()) return "FAILED: initrd not extracted yet at ${initrd.absolutePath}"

        if (AvfCapabilities.choose(pre.capabilitiesRaw) is AvfCapabilities.ProtectedVmChoice.Unsupported) {
            return protectedVmNotApplicable("caps=${pre.capabilitiesDecoded}")
        }

        val vmm = runInterruptible(Dispatchers.IO) { getVirtualizationManager(context) }
            ?: return "FAILED: VirtualMachineManager system service returned null"

        // crosvm needs the raw ARM64 Image, not gzip vmlinuz. Reuse the same
        // confined cache as AvfEngine; preparation happens before VM creation.
        val config = runInterruptible(Dispatchers.IO) {
            val kernel = ensureRawKernel(kernelSrc, vmPaths.rawKernel, pathSecurity)
            val customCfg = buildCustomImageConfig(kernel.absolutePath, initrd.absolutePath)
            buildVirtualMachineConfig(vmm, context, customCfg)
        }
        val name = "podroid-avf-smoke"

        val execution = AvfSmokeTestExecutor().execute(
            create = { invokeOrCreate(vmm, name, config) },
            run = { vm ->
                pathSecurity.validateForLaunch()
                vm.javaClass.getMethod("run").invoke(vm)
            },
            stop = { vm -> vm.javaClass.getMethod("stop").invoke(vm) },
            delete = { vmm.javaClass.getMethod("delete", String::class.java).invoke(vmm, name) },
        )

        val cleanupFailures = buildList {
            execution.stopFailure?.let { add("stop=${failureSummary(it)}") }
            execution.deleteFailure?.let { add("delete=${failureSummary(it)}") }
        }
        if (execution.timedOut) {
            return "FAILED: AVF smoke test exceeded ${AvfSmokeTestExecutor.DEFAULT_OPERATION_TIMEOUT_MS}ms" +
                cleanupFailures.joinToString(prefix = if (cleanupFailures.isEmpty()) "" else "; cleanup: ")
        }
        execution.failure?.let { failure ->
            val cause = failure.cause ?: failure
            if (cause is UnsupportedOperationException &&
                cause.message?.contains("protected", ignoreCase = true) == true
            ) {
                return protectedVmNotApplicable(failureSummary(cause))
            }
            return "FAILED at VM create/run: ${failureSummary(failure)}" +
                cleanupFailures.joinToString(prefix = if (cleanupFailures.isEmpty()) "" else "; cleanup: ")
        }
        if (cleanupFailures.isNotEmpty()) {
            return cleanupFailures.joinToString(prefix = "FAILED during AVF smoke cleanup: ")
        }
        return "SUCCESS: AVF accepted our config, VM started + stopped cleanly. The dev-grant path works on this device."
    }

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

    private const val MAX_SMOKE_RESULT_CHARS = 4 * 1024
    private const val TOTAL_SMOKE_TIMEOUT_MS = 15_000L
}
