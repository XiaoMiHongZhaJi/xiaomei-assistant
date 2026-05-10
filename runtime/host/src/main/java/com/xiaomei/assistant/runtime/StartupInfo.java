package com.xiaomei.assistant.runtime;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.xiaomei.assistant.runtime.libxposed.CompatHookBridge;
import io.github.libxposed.api.XposedModule;
import java.util.Objects;

public final class StartupInfo {

    private static final String DEFAULT_LOG_TAG = "XiaoMeiHook";

    private static String modulePath;
    private static String hostDataDir;
    private static CompatHookBridge hookBridge;
    private static ClassLoader hostClassLoader;
    private static Boolean inHostProcess;
    private static XposedModule module;

    private StartupInfo() {
        throw new AssertionError("No instance for you!");
    }

    @NonNull
    public static String getModulePath() {
        return modulePath;
    }

    public static void setModulePath(@NonNull String modulePath) {
        StartupInfo.modulePath = Objects.requireNonNull(modulePath);
    }

    @NonNull
    public static String getHostDataDir() {
        return hostDataDir;
    }

    public static void setHostDataDir(@NonNull String hostDataDir) {
        StartupInfo.hostDataDir = Objects.requireNonNull(hostDataDir);
    }

    @Nullable
    public static CompatHookBridge getHookBridge() {
        return hookBridge;
    }

    public static void setHookBridge(@Nullable CompatHookBridge hookBridge) {
        StartupInfo.hookBridge = hookBridge;
    }

    @NonNull
    public static ClassLoader getHostClassLoader() {
        return hostClassLoader;
    }

    public static void setHostClassLoader(@NonNull ClassLoader hostClassLoader) {
        StartupInfo.hostClassLoader = Objects.requireNonNull(hostClassLoader);
    }

    public static boolean isInHostProcess() {
        if (inHostProcess == null) {
            throw new IllegalStateException("Host process status is not initialized");
        }
        return inHostProcess;
    }

    public static void setInHostProcess(boolean inHostProcess) {
        if (StartupInfo.inHostProcess != null) {
            throw new IllegalStateException("Host process status is already initialized");
        }
        StartupInfo.inHostProcess = inHostProcess;
    }

    @Nullable
    public static XposedModule getModule() {
        return module;
    }

    public static void setModule(@NonNull XposedModule module) {
        StartupInfo.module = Objects.requireNonNull(module);
    }

    public static void log(@NonNull String message) {
        CompatHookBridge bridge = hookBridge;
        if (bridge != null) {
            bridge.log(message);
            return;
        }
        XposedModule currentModule = module;
        if (currentModule != null) {
            currentModule.log(android.util.Log.INFO, DEFAULT_LOG_TAG, message, null);
            return;
        }
        android.util.Log.i(DEFAULT_LOG_TAG, message);
    }

    public static void log(@NonNull Throwable tr) {
        CompatHookBridge bridge = hookBridge;
        if (bridge != null) {
            bridge.log(tr);
            return;
        }
        String message = tr.getMessage();
        if (message == null) {
            message = tr.getClass().getSimpleName();
        }
        XposedModule currentModule = module;
        if (currentModule != null) {
            currentModule.log(android.util.Log.ERROR, DEFAULT_LOG_TAG, message, tr);
            return;
        }
        android.util.Log.e(DEFAULT_LOG_TAG, message, tr);
    }
}
