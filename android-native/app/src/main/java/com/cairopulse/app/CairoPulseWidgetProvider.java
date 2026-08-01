package com.cairopulse.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CairoPulseWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_REFRESH_WIDGET = "com.cairopulse.app.ACTION_REFRESH_WIDGET";
    private static final String PREFS = "cairo_pulse_widget";
    private static final ZoneId CAIRO_ZONE = ZoneId.of("Africa/Cairo");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}:\\d{2})");

    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast?latitude=30.0444&longitude=31.2357&current=temperature_2m,weather_code,is_day,precipitation&timezone=Africa%2FCairo";
    private static final String PRAYER_URL = "https://api.aladhan.com/v1/timingsByCity?city=Cairo&country=Egypt&method=5";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            renderWidget(context, appWidgetManager, appWidgetId, false);
        }
        refreshData(context.getApplicationContext());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH_WIDGET.equals(intent.getAction())) {
            updateAllWidgetsFromCache(context);
            refreshData(context.getApplicationContext());
        }
    }

    public static void saveBridgeData(Context context, JSONObject data) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (data.has("temperature") && !data.isNull("temperature")) {
            editor.putFloat("temperature", (float) data.optDouble("temperature", Float.NaN));
        }
        editor.putString("weather_description", data.optString("weatherDescription", "—"));
        editor.putString("weather_icon", data.optString("weatherIcon", "☀️"));
        editor.putString("next_prayer", data.optString("nextPrayer", "—"));
        editor.putString("next_prayer_time", data.optString("nextPrayerTime", "--:--"));
        editor.putString("remaining", compactRemaining(data.optString("remaining", "--:--")));
        editor.putLong("updated_at", data.optLong("updatedAt", System.currentTimeMillis()));
        editor.apply();
    }

    public static void updateAllWidgetsFromCache(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, CairoPulseWidgetProvider.class));
        for (int id : ids) {
            renderWidget(context, manager, id, false);
        }
    }

    private static void refreshData(Context context) {
        EXECUTOR.execute(() -> {
            if (!isOnline(context)) {
                updateAllWidgetsFromCache(context);
                return;
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            boolean changed = false;

            try {
                JSONObject weather = new JSONObject(fetch(WEATHER_URL)).getJSONObject("current");
                double temp = weather.optDouble("temperature_2m", Double.NaN);
                int code = weather.optInt("weather_code", -1);
                int isDay = weather.optInt("is_day", 1);
                if (!Double.isNaN(temp)) {
                    editor.putFloat("temperature", (float) temp);
                    editor.putString("weather_description", weatherDescription(code));
                    editor.putString("weather_icon", weatherIcon(code, isDay == 1));
                    changed = true;
                }
            } catch (Exception ignored) {
                // Keep the last successful weather values.
            }

            try {
                JSONObject timings = new JSONObject(fetch(PRAYER_URL))
                        .getJSONObject("data")
                        .getJSONObject("timings");
                PrayerResult next = nextPrayer(timings);
                editor.putString("next_prayer", next.name);
                editor.putString("next_prayer_time", next.time);
                editor.putString("remaining", next.remaining);
                changed = true;
            } catch (Exception ignored) {
                // Keep the last successful prayer values.
            }

            if (changed) {
                editor.putLong("updated_at", System.currentTimeMillis()).apply();
            }
            updateAllWidgetsFromCache(context);
        });
    }

    private static void renderWidget(Context context, AppWidgetManager manager, int widgetId, boolean loading) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_cairo_pulse);

        float temp = prefs.getFloat("temperature", Float.NaN);
        views.setTextViewText(R.id.widget_temp, Float.isNaN(temp) ? "--°" : Math.round(temp) + "°");
        views.setTextViewText(R.id.widget_weather_desc, loading ? "جاري التحديث…" : prefs.getString("weather_description", "جاري التحديث…"));
        views.setTextViewText(R.id.widget_weather_icon, prefs.getString("weather_icon", "☀️"));
        views.setTextViewText(R.id.widget_prayer_name, prefs.getString("next_prayer", "—"));
        views.setTextViewText(R.id.widget_prayer_time, prefs.getString("next_prayer_time", "--:--"));
        views.setTextViewText(R.id.widget_remaining, "متبقي " + prefs.getString("remaining", "--:--"));

        Intent openIntent = new Intent(context, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                context,
                1000 + widgetId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_root, openPending);

        Intent refreshIntent = new Intent(context, CairoPulseWidgetProvider.class);
        refreshIntent.setAction(ACTION_REFRESH_WIDGET);
        PendingIntent refreshPending = PendingIntent.getBroadcast(
                context,
                2000 + widgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPending);
        manager.updateAppWidget(widgetId, views);
    }

    private static PrayerResult nextPrayer(JSONObject timings) {
        String[][] prayers = {
                {"Fajr", "الفجر"},
                {"Dhuhr", "الظهر"},
                {"Asr", "العصر"},
                {"Maghrib", "المغرب"},
                {"Isha", "العشاء"}
        };
        LocalDateTime now = LocalDateTime.now(CAIRO_ZONE);
        LocalDate today = now.toLocalDate();

        for (String[] prayer : prayers) {
            String time = cleanTime(timings.optString(prayer[0], ""));
            if (time == null) continue;
            LocalDateTime dateTime = LocalDateTime.of(today, LocalTime.parse(time, TIME_FORMAT));
            if (dateTime.isAfter(now)) {
                return new PrayerResult(prayer[1], time, remaining(now, dateTime));
            }
        }

        String fajr = cleanTime(timings.optString("Fajr", ""));
        if (fajr == null) fajr = "--:--";
        LocalDateTime tomorrowFajr = "--:--".equals(fajr)
                ? now.plusHours(8)
                : LocalDateTime.of(today.plusDays(1), LocalTime.parse(fajr, TIME_FORMAT));
        return new PrayerResult("الفجر", fajr, remaining(now, tomorrowFajr));
    }

    private static String remaining(LocalDateTime now, LocalDateTime target) {
        long minutes = Math.max(0, Duration.between(now, target).toMinutes());
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private static String cleanTime(String value) {
        Matcher matcher = TIME_PATTERN.matcher(value == null ? "" : value);
        if (!matcher.find()) return null;
        String[] parts = matcher.group(1).split(":");
        return String.format(Locale.US, "%02d:%02d", Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    private static String compactRemaining(String value) {
        if (value == null || value.isEmpty()) return "--:--";
        String[] parts = value.split(":");
        if (parts.length >= 2) return parts[0] + ":" + parts[1];
        return value;
    }

    private static String weatherDescription(int code) {
        if (code == 0) return "سماء صافية";
        if (code == 1) return "صحو غالبًا";
        if (code == 2) return "غيوم متفرقة";
        if (code == 3) return "غائم";
        if (code == 45 || code == 48) return "ضباب";
        if (code >= 51 && code <= 55) return "رذاذ";
        if (code >= 61 && code <= 65) return "مطر";
        if (code >= 71 && code <= 75) return "ثلج";
        if (code >= 80 && code <= 82) return "زخات مطر";
        if (code >= 95) return "عاصفة رعدية";
        return "حالة الطقس";
    }

    private static String weatherIcon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code == 1) return day ? "🌤️" : "🌙";
        if (code == 2) return "⛅";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if ((code >= 51 && code <= 65) || (code >= 80 && code <= 82)) return "🌧️";
        if (code >= 71 && code <= 75) return "❄️";
        if (code >= 95) return "⛈️";
        return "🌡️";
    }

    private static String fetch(String urlText) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "CairoPulse/6.0 AndroidWidget");
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) throw new IllegalStateException("HTTP " + code);
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        } finally {
            connection.disconnect();
        }
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        return result.toString();
    }

    private static boolean isOnline(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.net.Network network = manager.getActiveNetwork();
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        return true;
    }

    private static final class PrayerResult {
        final String name;
        final String time;
        final String remaining;

        PrayerResult(String name, String time, String remaining) {
            this.name = name;
            this.time = time;
            this.remaining = remaining;
        }
    }
}
