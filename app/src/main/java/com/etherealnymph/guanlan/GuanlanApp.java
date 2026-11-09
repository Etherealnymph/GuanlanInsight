package com.etherealnymph.guanlan;

import android.app.Application;
import android.os.Process;

/** Installs a last-resort crash logger before any activity is created. */
public final class GuanlanApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            CrashLog.write(GuanlanApp.this, error, "uncaught");
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }
}
