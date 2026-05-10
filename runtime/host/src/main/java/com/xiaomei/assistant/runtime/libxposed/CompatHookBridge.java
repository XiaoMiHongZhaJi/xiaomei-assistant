package com.xiaomei.assistant.runtime.libxposed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

public interface CompatHookBridge {

    int PRIORITY_DEFAULT = 50;
    int PRIORITY_LOWEST = -10000;
    int PRIORITY_HIGHEST = 10000;

    interface MemberHookCallback {

        void beforeHookedMember(@NonNull MemberHookParam param) throws Throwable;

        void afterHookedMember(@NonNull MemberHookParam param) throws Throwable;
    }

    interface MemberHookParam {

        @NonNull
        Member getMember();

        @Nullable
        Object getThisObject();

        @NonNull
        Object[] getArgs();

        @Nullable
        Object getResult();

        void setResult(@Nullable Object result);

        @Nullable
        Throwable getThrowable();

        void setThrowable(@NonNull Throwable throwable);

        @Nullable
        Object getExtra();

        void setExtra(@Nullable Object extra);
    }

    interface MemberUnhookHandle {

        @NonNull
        Member getMember();

        @NonNull
        MemberHookCallback getCallback();

        boolean isHookActive();

        void unhook();
    }

    @NonNull
    MemberUnhookHandle hookMethod(@NonNull Member member, @NonNull MemberHookCallback callback, int priority);

    boolean deoptimize(@NonNull Member member);

    @Nullable
    Object invokeOriginalMethod(@NonNull Method method, @Nullable Object thisObject, @NonNull Object[] args)
            throws InvocationTargetException, IllegalAccessException;

    <T> void invokeOriginalConstructor(@NonNull Constructor<T> ctor, @NonNull T thisObject, @NonNull Object[] args)
            throws InvocationTargetException, IllegalAccessException;

    void log(@NonNull String msg);

    void log(@NonNull Throwable tr);
}
