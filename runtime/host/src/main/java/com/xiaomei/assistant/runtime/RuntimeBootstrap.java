package com.xiaomei.assistant.runtime;

import android.content.pm.ApplicationInfo;
import androidx.annotation.NonNull;
import com.xiaomei.assistant.runtime.libxposed.LibXposedHookBridge;
import com.xiaomei.assistant.xposed.HookEntry;
import com.xiaomei.assistant.xposed.HookLoadParams;
import com.xiaomei.assistant.xposed.HookResult;
import com.xiaomei.assistant.xposed.TargetPackages;
import io.github.libxposed.api.XposedModule;

public final class RuntimeBootstrap {

    private RuntimeBootstrap() {
        throw new AssertionError("No instance for you!");
    }

    @NonNull
    public static HookResult start(
            @NonNull XposedModule module,
            @NonNull String packageName,
            @NonNull String processName,
            @NonNull ClassLoader hostClassLoader,
            @NonNull ApplicationInfo applicationInfo,
            @NonNull String modulePath
    ) {
        String normalizedProcess = TargetPackages.INSTANCE.normalizeProcessName(packageName, processName);
        StartupInfo.setModule(module);
        LibXposedHookBridge.init(module);
        StartupInfo.setHookBridge(LibXposedHookBridge.INSTANCE);
        StartupInfo.setModulePath(modulePath);
        StartupInfo.setHostDataDir(applicationInfo.dataDir != null ? applicationInfo.dataDir : "/data/user/0/" + packageName);
        StartupInfo.setHostClassLoader(hostClassLoader);
        StartupInfo.setInHostProcess(true);
        StartupInfo.log("RuntimeBootstrap.start package=" + packageName + " process=" + normalizedProcess);
        HookResult result = new HookEntry().handleLoad(
                new HookLoadParams(
                        packageName,
                        normalizedProcess,
                        hostClassLoader,
                        modulePath,
                        applicationInfo.dataDir
                )
        );
        StartupInfo.log(
                "RuntimeBootstrap.result package=" + packageName
                        + " process=" + normalizedProcess
                        + " hooks=" + result.getInstalledHooks()
                        + " matched=" + result.getMatchedTarget()
                        + " message=" + result.getMessage()
        );
        return result;
    }
}
