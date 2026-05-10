package com.xiaomei.assistant.xposed;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import com.xiaomei.assistant.runtime.RuntimeBootstrap;
import io.github.libxposed.api.XposedModule;

@Keep
public class ModuleMain extends XposedModule {

    private static final String TAG = "XiaoMeiHook";
    private volatile boolean mBootstrapped = false;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log(Log.INFO, TAG,
                "onModuleLoaded process=" + param.getProcessName()
                        + " framework=" + getFrameworkName()
                        + " api=" + getApiVersion()
                        + " versionCode=" + getFrameworkVersionCode());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String packageName = param.getPackageName();
        ApplicationInfo applicationInfo = param.getApplicationInfo();
        String processName = Application.getProcessName();
        if (processName == null || processName.isEmpty()) {
            processName = packageName;
        }
        log(Log.INFO, TAG,
                "onPackageReady package=" + packageName
                        + " process=" + processName
                        + " first=" + param.isFirstPackage());
        if (!TargetPackages.MI_HEALTH.equals(packageName)) {
            return;
        }
        if (mBootstrapped) {
            log(Log.DEBUG, TAG, "skip bootstrap because runtime already started for process=" + processName);
            return;
        }
        synchronized (this) {
            if (mBootstrapped) {
                log(Log.DEBUG, TAG, "skip bootstrap because runtime already started for process=" + processName);
                return;
            }
            mBootstrapped = true;
        }
        RuntimeBootstrap.start(
                this,
                packageName,
                processName,
                param.getClassLoader(),
                applicationInfo,
                getModuleApplicationInfo().sourceDir
        );
    }
}
