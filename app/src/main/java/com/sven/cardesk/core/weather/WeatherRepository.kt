package com.sven.cardesk.core.weather

import android.content.Context
import com.sven.cardesk.R
import kotlin.math.roundToInt

data class WeatherInfo(
    val temperatureC: Int,
    val description: String,
    val iconRes: Int,
    val humidityPercent: Int?,
    val windSpeedKmh: Double?
)

object WeatherRepository {

    private const val LATITUDE = 24.73
    private const val LONGITUDE = 118.17

    private val api: WeatherApi by lazy { WeatherApi.create() }

    suspend fun fetchCurrent(context: Context): WeatherInfo? = runCatching {
        val current = api.getCurrentWeather(LATITUDE, LONGITUDE).current ?: return@runCatching null
        val temperature = current.temperature2m ?: return@runCatching null
        val visual = weatherVisual(context, current.weatherCode)
        WeatherInfo(
            temperatureC = temperature.roundToInt(),
            description = visual.first,
            iconRes = visual.second,
            humidityPercent = current.relativeHumidity2m,
            windSpeedKmh = current.windSpeed10m
        )
    }.getOrNull()

    private fun weatherVisual(context: Context, code: Int?): Pair<String, Int> {
        return when (code) {
            0 -> context.getString(R.string.weather_clear) to R.drawable.ic_sun
            1, 2 -> context.getString(R.string.weather_partly_cloudy) to R.drawable.ic_weather_cloud
            3 -> context.getString(R.string.weather_cloudy) to R.drawable.ic_weather_cloud
            45, 48 -> context.getString(R.string.weather_fog) to R.drawable.ic_weather_fog
            51, 53, 55, 56, 57 ->
                context.getString(R.string.weather_drizzle) to R.drawable.ic_weather_rain
            61, 63, 65, 66, 67 ->
                context.getString(R.string.weather_rain) to R.drawable.ic_weather_rain
            71, 73, 75, 77, 85, 86 ->
                context.getString(R.string.weather_snow) to R.drawable.ic_weather_snow
            80, 81, 82 ->
                context.getString(R.string.weather_showers) to R.drawable.ic_weather_rain
            95, 96, 99 ->
                context.getString(R.string.weather_thunder) to R.drawable.ic_weather_thunder
            else -> context.getString(R.string.weather_unknown) to R.drawable.ic_weather_cloud
        }
    }
}
