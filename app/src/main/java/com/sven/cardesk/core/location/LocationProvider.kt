package com.sven.cardesk.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

object LocationProvider {

    data class Coordinates(val latitude: Double, val longitude: Double)

    private val defaultCoordinates = Coordinates(24.73, 118.17)

    fun defaultLocation(): Coordinates = defaultCoordinates

    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun getLastKnown(context: Context): Coordinates? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val best: Location? = providers
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
        return best?.toCoordinates()
    }

    /**
     * Requests a fresh single location update and also reports the last known location immediately.
     * The callback may be invoked more than once.
     */
    @SuppressLint("MissingPermission")
    fun requestCurrent(context: Context, callback: (Coordinates?) -> Unit) {
        if (!hasPermission(context)) {
            callback(null)
            return
        }
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null) {
            callback(null)
            return
        }

        getLastKnown(context)?.let(callback)

        val provider = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).firstOrNull { candidate ->
            runCatching { manager.isProviderEnabled(candidate) }.getOrDefault(false)
        } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancellationSignal = CancellationSignal()
            runCatching {
                manager.getCurrentLocation(
                    provider,
                    cancellationSignal,
                    ContextCompat.getMainExecutor(context)
                ) { location ->
                    location?.toCoordinates()?.let(callback)
                }
            }
            Handler(Looper.getMainLooper()).postDelayed(
                { cancellationSignal.cancel() },
                10_000L
            )
        } else {
            @Suppress("DEPRECATION")
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    location.toCoordinates().let(callback)
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }
            runCatching {
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }
    }

    private fun Location.toCoordinates(): Coordinates =
        Coordinates(latitude, longitude)
}
