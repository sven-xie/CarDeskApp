package com.sven.cardesk.core.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.CpuUsageInfo
import android.os.HardwarePropertiesManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import kotlin.math.roundToInt

/**
 * Best-effort system performance sampler for the bottom bar.
 *
 * CPU usage and device temperatures use HardwarePropertiesManager (the same
 * data the framework exposes to privileged system apps); memory uses
 * ActivityManager. /proc and thermal sysfs are kept as fallbacks but are
 * normally blocked by SELinux for platform_app.
 */
class PerformanceMonitor(context: Context) {

    data class Snapshot(
        val cpuPercent: Int?,
        val memoryPercent: Int?,
        val temperatureC: Float?
    )

    private val appContext = context.applicationContext
    private val hardwareProperties: HardwarePropertiesManager? by lazy {
        appContext.getSystemService(HardwarePropertiesManager::class.java)
    }
    private val activityManager: ActivityManager? by lazy {
        appContext.getSystemService(ActivityManager::class.java)
    }

    private var lastCpuActive = 0L
    private var lastCpuTotal = 0L
    private var lastProcTotal = 0L
    private var lastProcIdle = 0L
    private var lastAppCpu = 0L
    private var lastAppWall = 0L

    fun sample(): Snapshot {
        return Snapshot(
            cpuPercent = readCpu(),
            memoryPercent = readMemory(),
            temperatureC = readTemperature()
        )
    }

    private fun readCpu(): Int? {
        val usages: Array<CpuUsageInfo>? = runCatching {
            hardwareProperties?.getCpuUsages()
        }.getOrNull()
        if (usages != null && usages.isNotEmpty()) {
            val active = usages.sumOf { it.active }
            val total = usages.sumOf { it.total }
            if (total > 0) {
                var result: Int? = null
                if (lastCpuTotal > 0 && total > lastCpuTotal) {
                    result = (((active - lastCpuActive) * 100.0) / (total - lastCpuTotal))
                        .roundToInt()
                        .coerceIn(0, 100)
                }
                lastCpuActive = active
                lastCpuTotal = total
                return result
            }
        }
        return readProcCpu() ?: readAppCpu()
    }

    /**
     * Fallback for emulator images where HardwarePropertiesManager has no
     * CPU usage HAL: report this process's CPU usage. On a real car device
     * the system-wide value from HardwarePropertiesManager is used first.
     */
    private fun readAppCpu(): Int? {
        val cpu = Process.getElapsedCpuTime()
        val wall = SystemClock.elapsedRealtime()
        var result: Int? = null
        if (lastAppWall > 0 && wall > lastAppWall) {
            result = (((cpu - lastAppCpu) * 100.0) / (wall - lastAppWall))
                .roundToInt()
                .coerceIn(0, 100)
        }
        lastAppCpu = cpu
        lastAppWall = wall
        return result
    }

    private fun readProcCpu(): Int? {
        val line = runCatching {
            File("/proc/stat").useLines { lines ->
                lines.firstOrNull { it.startsWith("cpu ") }
            }
        }.getOrNull() ?: return null
        val parts = line.trim().split(Regex("\\s+")).drop(1)
            .mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return null
        val idle = parts[3] + parts.getOrElse(4) { 0L }
        val total = parts.sum()
        var result: Int? = null
        if (lastProcTotal > 0 && total > lastProcTotal) {
            result = (100.0 - (idle - lastProcIdle) * 100.0 / (total - lastProcTotal))
                .roundToInt()
                .coerceIn(0, 100)
        }
        lastProcTotal = total
        lastProcIdle = idle
        return result
    }

    private fun readMemory(): Int? {
        val manager = activityManager ?: return readProcMemory()
        val info = ActivityManager.MemoryInfo()
        val ok = runCatching {
            manager.getMemoryInfo(info)
            true
        }.getOrDefault(false)
        if (ok && info.totalMem > 0) {
            return (((info.totalMem - info.availMem) * 100.0) / info.totalMem)
                .roundToInt()
                .coerceIn(0, 100)
        }
        return readProcMemory()
    }

    private fun readProcMemory(): Int? {
        val values = runCatching {
            File("/proc/meminfo").readLines().mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1).trim()
                    .split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
                if (key.isNotBlank() && value != null) key to value else null
            }.toMap()
        }.getOrNull() ?: return null

        val total = values["MemTotal"] ?: return null
        val available = values["MemAvailable"]
            ?: (values["MemFree"] ?: 0L) + (values["Buffers"] ?: 0L) + (values["Cached"] ?: 0L)
        if (total <= 0) return null
        return (((total - available) * 100.0) / total)
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun readTemperature(): Float? {
        val manager = hardwareProperties
        if (manager != null) {
            val types = intArrayOf(
                HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU
            )
            var max: Float? = null
            for (type in types) {
                val temperatures = runCatching {
                    manager.getDeviceTemperatures(
                        type,
                        HardwarePropertiesManager.TEMPERATURE_CURRENT
                    )
                }.getOrNull() ?: FloatArray(0)
                for (value in temperatures) {
                    if (value in 1f..120f) {
                        max = if (max == null) value else maxOf(max, value)
                    }
                }
            }
            if (max != null) return max
        }
        return readSysfsTemperature() ?: readBatteryTemperature()
    }

    /** Sticky battery broadcast, readable by a privileged app without location. */
    private fun readBatteryTemperature(): Float? {
        val intent = runCatching {
            appContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        }.getOrNull() ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        return if (tenths > 0) tenths / 10f else null
    }

    private fun readSysfsTemperature(): Float? {
        val zones = runCatching {
            File("/sys/class/thermal").listFiles()
                ?.filter { it.name.startsWith("thermal_zone") }
                .orEmpty()
        }.getOrDefault(emptyList())
        var max: Float? = null
        for (zone in zones) {
            val raw = runCatching { File(zone, "temp").readText().trim().toFloat() }
                .getOrNull() ?: continue
            val celsius = if (raw > 1000f) raw / 1000f else raw
            if (celsius in 1f..120f) {
                max = if (max == null) celsius else maxOf(max, celsius)
            }
        }
        return max
    }
}
