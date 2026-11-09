package com.etherealnymph.guanlan;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes the most recent fatal error to app-private storage for diagnostics. */
final class CrashLog {

    private static final String TAG = "Guanlan";
    private static final String FILE_NAME = "last-crash.txt";
    private static final int MAX_CHARS = 48000;

    private CrashLog() {
    }

    static synchronized void write(Context context, Throwable error, String phase) {
        String body = buildReport(context, error, phase);
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            File temp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not replace old crash log");
            }
            if (!temp.renameTo(file)) {
                Log.w(TAG, "Could not publish crash log");
            }
        } catch (Throwable writeError) {
            Log.e(TAG, "Could not write crash log", writeError);
        }
        Log.e(TAG, body, error);
    }

    static String read(Context context) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (!file.exists() || file.length() == 0) {
                return null;
            }
            try (FileInputStream input = new FileInputStream(file)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                String text = output.toString(StandardCharsets.UTF_8.name());
                return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
            }
        } catch (Throwable readError) {
            Log.w(TAG, "Could not read crash log", readError);
            return null;
        }
    }

    static void clear(Context context) {
        try {
            File file = new File(context.getFilesDir(), FILE_NAME);
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Throwable ignored) {
            // Nothing useful to do here.
        }
    }

    /**
     * Returns the log only when it belongs to the currently installed build.
     * This prevents a fixed release from showing the previous version's crash
     * dialog forever after an update.
     */
    static String readCurrent(Context context) {
        String text = read(context);
        if (text == null) {
            return null;
        }
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            String marker = "version  : " + info.versionName + " (" + info.versionCode + ")";
            if (!text.contains(marker)) {
                clear(context);
                return null;
            }
        } catch (Throwable ignored) {
            // If the package info cannot be read, show the log rather than hide it.
        }
        return text;
    }

    private static String buildReport(Context context, Throwable error, String phase) {
        StringBuilder builder = new StringBuilder(4096);
        builder.append("time     : ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()))
                .append('\n');
        builder.append("phase    : ").append(phase == null ? "unknown" : phase).append('\n');
        builder.append("package  : ").append(context.getPackageName()).append('\n');
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            builder.append("version  : ")
                    .append(info.versionName)
                    .append(" (").append(info.versionCode).append(")\n");
        } catch (Throwable ignored) {
            builder.append("version  : unknown\n");
        }
        builder.append("device   : ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        builder.append("android  : ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        builder.append("build    : ").append(Build.DISPLAY).append('\n');
        builder.append("thread   : ").append(Thread.currentThread().getName()).append('\n');
        builder.append('\n');
        builder.append(Log.getStackTraceString(error));
        return builder.toString();
    }
}
