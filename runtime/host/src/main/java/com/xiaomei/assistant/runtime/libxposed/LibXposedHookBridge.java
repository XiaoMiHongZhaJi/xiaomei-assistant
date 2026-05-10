package com.xiaomei.assistant.runtime.libxposed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

public final class LibXposedHookBridge implements CompatHookBridge {

    public static final LibXposedHookBridge INSTANCE = new LibXposedHookBridge();
    private static final String DEFAULT_LOG_TAG = "XiaoMeiHook";
    private static XposedModule self;

    private LibXposedHookBridge() {
    }

    public static void init(@NonNull XposedModule module) {
        self = module;
        LibXposedHookWrapper.self = module;
    }

    @NonNull
    @Override
    public MemberUnhookHandle hookMethod(@NonNull Member member, @NonNull MemberHookCallback callback, int priority) {
        return LibXposedHookWrapper.hookAndRegisterMethodCallback(member, callback, priority);
    }

    @Override
    public boolean deoptimize(@NonNull Member member) {
        return requireSelf().deoptimize((Executable) member);
    }

    @Nullable
    @Override
    public Object invokeOriginalMethod(@NonNull Method method, @Nullable Object thisObject, @NonNull Object[] args)
            throws InvocationTargetException, IllegalAccessException {
        XposedInterface.Invoker<?, Method> invoker = requireSelf().getInvoker(method);
        invoker.setType(XposedInterface.Invoker.Type.ORIGIN);
        return invoker.invoke(thisObject, args);
    }

    @Override
    public <T> void invokeOriginalConstructor(@NonNull Constructor<T> ctor, @NonNull T thisObject, @NonNull Object[] args)
            throws InvocationTargetException, IllegalAccessException {
        XposedInterface.CtorInvoker<T> invoker = requireSelf().getInvoker(ctor);
        invoker.setType(XposedInterface.Invoker.Type.ORIGIN);
        invoker.invoke(thisObject, args);
    }

    @Override
    public void log(@NonNull String msg) {
        requireSelf().log(android.util.Log.INFO, DEFAULT_LOG_TAG, msg, null);
    }

    @Override
    public void log(@NonNull Throwable tr) {
        String msg = tr.getMessage();
        if (msg == null) {
            msg = tr.getClass().getSimpleName();
        }
        requireSelf().log(android.util.Log.ERROR, DEFAULT_LOG_TAG, msg, tr);
    }

    @NonNull
    private static XposedModule requireSelf() {
        if (self == null) {
            throw new IllegalStateException("LibXposedHookBridge is not initialized");
        }
        return self;
    }
}
