// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Process
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku UserService: runs in a separate process as the shell uid (2000), which holds
 * TETHER_PRIVILEGED. Talks to TetheringManager (@SystemApi, so via reflection).
 * TetheringService checks that the caller package matches the calling uid, so the
 * context is rebased onto com.android.shell.
 */
class TetherControlService : ITetherControl.Stub {

    private val shell: Context
    private var setupError = ""

    @Suppress("unused") // Shizuku < 13 path
    constructor() : this(systemContext())

    constructor(context: Context) {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            .onFailure { setupError += "hiddenapi: ${describe(it)}; " }
        shell = runCatching { asShell(context) }
            .getOrElse { setupError += "asShell: ${describe(it)}; "; context }
    }

    private val manager: Any get() = shell.getSystemService("tethering") ?: error("tethering service unavailable")
    private val managerClass = Class.forName("android.net.TetheringManager")

    override fun whoami(): String =
        "uid=${Process.myUid()} pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT} " +
            "build=${Build.DISPLAY} pkg=${shell.opPackageName}" +
            (if (setupError.isEmpty()) "" else " SETUP ERRORS: $setupError")

    override fun startHotspot(): String {
        val exempt = startOnce(WIFI, exemptFromEntitlement = true)
        if (exempt.ok) return "OK ${exempt.line}"
        val plain = startOnce(WIFI, exemptFromEntitlement = false)
        return if (plain.ok) "OK ${plain.line}" else "FAIL exempt[${exempt.line}] plain[${plain.line}]"
    }

    override fun stopHotspot(): String = runCatching {
        val t0 = System.nanoTime()
        managerClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(manager, WIFI)
        "OK stopTethering(wifi) in ${ms(t0)} ms"
    }.getOrElse { "FAIL stopTethering: ${describe(it)}" }

    override fun shell(command: String): String = runCatching {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        "exit ${p.waitFor()}${if (out.isEmpty()) "" else ": $out"}"
    }.getOrElse { "FAIL ${describe(it)}" }

    override fun destroy() = exitProcess(0)

    private class Result(val ok: Boolean, val line: String)

    private fun startOnce(type: Int, exemptFromEntitlement: Boolean): Result = runCatching {
        val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(type)
        if (exemptFromEntitlement) {
            builderClass.getMethod("setExemptFromEntitlementCheck", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
        }
        val request = builderClass.getMethod("build").invoke(builder)

        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val latch = CountDownLatch(1)
        var outcome = "timeout after ${START_TIMEOUT_MS} ms"
        var ok = false
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, m, args ->
            when (m.name) {
                "onTetheringStarted" -> { ok = true; outcome = "started"; latch.countDown() }
                "onTetheringFailed" -> {
                    val code = args?.firstOrNull() as? Int
                    outcome = "failed error=$code ${ERRORS[code] ?: "?"}"
                    latch.countDown()
                }
            }
            null
        }

        val t0 = System.nanoTime()
        managerClass.getMethod(
            "startTethering",
            Class.forName("android.net.TetheringManager\$TetheringRequest"),
            Executor::class.java,
            callbackClass,
        ).invoke(manager, request, Executor { it.run() }, callback)
        latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val tag = if (exemptFromEntitlement) "exempt" else "plain"
        Result(ok, "wifi $tag: $outcome in ${ms(t0)} ms")
    }.getOrElse { Result(false, describe(it)) }

    private companion object {
        const val SHELL = "com.android.shell"
        const val WIFI = 0 // TetheringManager.TETHERING_WIFI
        const val START_TIMEOUT_MS = 20_000L

        val ERRORS = mapOf(
            0 to "NO_ERROR", 1 to "UNKNOWN_IFACE", 2 to "SERVICE_UNAVAIL", 3 to "UNSUPPORTED",
            4 to "UNAVAIL_IFACE", 5 to "INTERNAL_ERROR", 6 to "TETHER_IFACE_ERROR",
            7 to "UNTETHER_IFACE_ERROR", 8 to "ENABLE_FORWARDING_ERROR", 9 to "DISABLE_FORWARDING_ERROR",
            10 to "IFACE_CFG_ERROR", 11 to "PROVISIONING_FAILED", 12 to "DHCPSERVER_ERROR",
            13 to "ENTITLEMENT_UNKNOWN", 14 to "NO_CHANGE_TETHERING_PERMISSION",
            15 to "NO_ACCESS_TETHERING_PERMISSION", 16 to "UNKNOWN_TYPE", 17 to "UNKNOWN_REQUEST",
            18 to "DUPLICATE_REQUEST", 19 to "BLUETOOTH_SERVICE_PENDING", 20 to "SOFT_AP_CALLBACK_PENDING",
        )

        fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
        fun describe(t: Throwable): String {
            val root = generateSequence(t) { it.cause }.last()
            return "${root.javaClass.simpleName}: ${root.message}"
        }

        @SuppressLint("PrivateApi")
        fun systemContext(): Context {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            return at.getMethod("getSystemContext").invoke(thread) as Context
        }

        @SuppressLint("DiscouragedPrivateApi")
        fun asShell(context: Context): Context {
            val shell = context.createPackageContext(SHELL, 0)
            val field = shell.javaClass.getDeclaredField("mAttributionSource").apply { isAccessible = true }
            val source = field.get(shell)
            field.set(shell, source.javaClass.getMethod("withPackageName", String::class.java).invoke(source, SHELL))
            return shell
        }
    }
}
