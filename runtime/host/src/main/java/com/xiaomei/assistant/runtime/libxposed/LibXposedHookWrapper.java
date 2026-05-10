package com.xiaomei.assistant.runtime.libxposed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class LibXposedHookWrapper {

    private LibXposedHookWrapper() {
        throw new AssertionError("No instance for you!");
    }

    public static XposedModule self = null;

    private static final CallbackWrapper[] EMPTY_CALLBACKS = new CallbackWrapper[0];
    private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
    private static final AtomicLong sNextHookId = new AtomicLong(1);
    private static final Set<Member> sHookedMethods = ConcurrentHashMap.newKeySet();
    private static final Object sRegistryWriteLock = new Object();
    private static final ConcurrentHashMap<Integer, ConcurrentHashMap<Class<?>, ConcurrentHashMap<Member, CallbackListHolder>>> sCallbackRegistry =
            new ConcurrentHashMap<>();

    public interface Hooker extends XposedInterface.Hooker {

        Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable;
    }

    public static final class CallbackWrapper {

        public final CompatHookBridge.MemberHookCallback callback;
        public final long hookId = sNextHookId.getAndIncrement();
        public final int priority;

        public CallbackWrapper(@NonNull CompatHookBridge.MemberHookCallback callback, int priority) {
            this.callback = callback;
            this.priority = priority;
        }
    }

    public static final class CallbackListHolder {

        public final Object lock = new Object();
        public CallbackWrapper[] callbacks = EMPTY_CALLBACKS;
    }

    @NonNull
    public static UnhookHandle hookAndRegisterMethodCallback(
            @NonNull Member method,
            @NonNull CompatHookBridge.MemberHookCallback callback,
            int priority
    ) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(callback, "callback");
        CallbackWrapper wrapper = new CallbackWrapper(callback, priority);
        UnhookHandle handle = new UnhookHandle(wrapper, method);
        Class<?> declaringClass = method.getDeclaringClass();
        CallbackListHolder holder;
        synchronized (sRegistryWriteLock) {
            ConcurrentHashMap<Class<?>, ConcurrentHashMap<Member, CallbackListHolder>> taggedCallbackRegistry =
                    sCallbackRegistry.get(priority);
            if (taggedCallbackRegistry == null) {
                taggedCallbackRegistry = new ConcurrentHashMap<>();
                sCallbackRegistry.put(priority, taggedCallbackRegistry);
            }
            ConcurrentHashMap<Member, CallbackListHolder> callbackList = taggedCallbackRegistry.get(declaringClass);
            if (callbackList == null) {
                callbackList = new ConcurrentHashMap<>();
                taggedCallbackRegistry.put(declaringClass, callbackList);
            }
            holder = callbackList.get(method);
            if (holder == null) {
                if (!(method instanceof Executable)) {
                    throw new IllegalArgumentException("only method and constructor can be hooked, but got " + method);
                }
                Lsp101HookDispatchAgent agent = new Lsp101HookDispatchAgent(priority);
                XposedInterface.HookHandle hookHandle = self.hook((Executable) method)
                        .setPriority(priority)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(agent);
                agent.setFrameworkHookHandle(hookHandle);
                CallbackListHolder newHolder = new CallbackListHolder();
                callbackList.put(method, newHolder);
                holder = newHolder;
                sHookedMethods.add(method);
            }
        }
        synchronized (holder.lock) {
            int newSize = holder.callbacks == null ? 1 : holder.callbacks.length + 1;
            CallbackWrapper[] newCallbacks = new CallbackWrapper[newSize];
            if (holder.callbacks != null) {
                int i = 0;
                for (; i < holder.callbacks.length; i++) {
                    if (holder.callbacks[i].priority > priority) {
                        newCallbacks[i] = holder.callbacks[i];
                    } else {
                        break;
                    }
                }
                newCallbacks[i] = wrapper;
                for (; i < holder.callbacks.length; i++) {
                    newCallbacks[i + 1] = holder.callbacks[i];
                }
            } else {
                newCallbacks[0] = wrapper;
            }
            holder.callbacks = newCallbacks;
        }
        return handle;
    }

    public static void removeMethodCallback(@NonNull Member method, @NonNull CallbackWrapper callback) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(callback, "callback");
        ConcurrentHashMap<Class<?>, ConcurrentHashMap<Member, CallbackListHolder>> taggedCallbackRegistry =
                sCallbackRegistry.get(callback.priority);
        if (taggedCallbackRegistry == null) {
            return;
        }
        ConcurrentHashMap<Member, CallbackListHolder> callbackList = taggedCallbackRegistry.get(method.getDeclaringClass());
        if (callbackList == null) {
            return;
        }
        CallbackListHolder holder = callbackList.get(method);
        if (holder == null) {
            return;
        }
        synchronized (holder.lock) {
            ArrayList<CallbackWrapper> newCallbacks = new ArrayList<>();
            for (CallbackWrapper cb : holder.callbacks) {
                if (cb != callback) {
                    newCallbacks.add(cb);
                }
            }
            holder.callbacks = newCallbacks.toArray(new CallbackWrapper[0]);
        }
    }

    public static boolean isMethodCallbackRegistered(@NonNull Member method, @NonNull CallbackWrapper callback) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(callback, "callback");
        ConcurrentHashMap<Class<?>, ConcurrentHashMap<Member, CallbackListHolder>> taggedCallbackRegistry =
                sCallbackRegistry.get(callback.priority);
        if (taggedCallbackRegistry == null) {
            return false;
        }
        ConcurrentHashMap<Member, CallbackListHolder> callbackList = taggedCallbackRegistry.get(method.getDeclaringClass());
        if (callbackList == null) {
            return false;
        }
        CallbackListHolder holder = callbackList.get(method);
        if (holder == null) {
            return false;
        }
        CallbackWrapper[] callbacks = holder.callbacks;
        for (CallbackWrapper cb : callbacks) {
            if (cb == callback) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static CallbackWrapper[] copyCallbacks(@Nullable CallbackListHolder holder) {
        if (holder == null) {
            return EMPTY_CALLBACKS;
        }
        synchronized (holder.lock) {
            if (holder.callbacks != null) {
                return holder.callbacks.clone();
            }
        }
        return EMPTY_CALLBACKS;
    }

    public static final class InvocationParamWrapper implements CompatHookBridge.MemberHookParam {

        public int index = -1;
        public CallbackWrapper[] callbacks;
        public Object[] extras;
        public boolean skipOriginal;
        public Object result;
        public Throwable throwable;
        public Object thisObjectCompat;
        public Object[] argsCompat;
        public Member member;
        public XposedInterface.Chain chain;

        @NonNull
        @Override
        public Member getMember() {
            checkLifecycle();
            return member;
        }

        @Nullable
        @Override
        public Object getThisObject() {
            checkLifecycle();
            return thisObjectCompat;
        }

        @NonNull
        @Override
        public Object[] getArgs() {
            checkLifecycle();
            return argsCompat;
        }

        @Nullable
        @Override
        public Object getResult() {
            checkLifecycle();
            return result;
        }

        @Override
        public void setResult(@Nullable Object result) {
            checkLifecycle();
            this.result = result;
            this.skipOriginal = true;
        }

        @Nullable
        @Override
        public Throwable getThrowable() {
            checkLifecycle();
            return throwable;
        }

        @Override
        public void setThrowable(@NonNull Throwable throwable) {
            checkLifecycle();
            this.throwable = throwable;
            this.skipOriginal = true;
        }

        @Nullable
        @Override
        public Object getExtra() {
            checkLifecycle();
            if (extras == null || index < 0 || index >= extras.length) {
                return null;
            }
            return extras[index];
        }

        @Override
        public void setExtra(@Nullable Object extra) {
            checkLifecycle();
            if (callbacks == null || index < 0) {
                return;
            }
            if (extras == null) {
                extras = new Object[callbacks.length];
            }
            extras[index] = extra;
        }

        private void checkLifecycle() {
            if (chain == null) {
                throw new IllegalStateException("attempt to access hook param after destroyed");
            }
        }
    }

    static final class Lsp101HookDispatchAgent implements Hooker {

        private final int priority;
        private XposedInterface.HookHandle handle;

        Lsp101HookDispatchAgent(int priority) {
            this.priority = priority;
        }

        void setFrameworkHookHandle(@NonNull XposedInterface.HookHandle hookHandle) {
            if (handle != null && handle != hookHandle) {
                throw new IllegalStateException("Hook handle already set");
            }
            handle = hookHandle;
        }

        @Override
        public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
            Executable executable = chain.getExecutable();
            ConcurrentHashMap<Class<?>, ConcurrentHashMap<Member, CallbackListHolder>> taggedCallbackRegistry =
                    sCallbackRegistry.get(priority);
            if (taggedCallbackRegistry == null) {
                return chain.proceed();
            }
            ConcurrentHashMap<Member, CallbackListHolder> callbackList = taggedCallbackRegistry.get(executable.getDeclaringClass());
            if (callbackList == null) {
                return chain.proceed();
            }
            CallbackListHolder holder = callbackList.get(executable);
            if (holder == null) {
                return chain.proceed();
            }
            CallbackWrapper[] callbacks = copyCallbacks(holder);
            if (callbacks.length == 0) {
                return chain.proceed();
            }

            InvocationParamWrapper param = new InvocationParamWrapper();
            Object[] argsCompat = chain.getArgs().toArray(EMPTY_OBJECT_ARRAY);
            param.member = executable;
            param.thisObjectCompat = chain.getThisObject();
            param.argsCompat = argsCompat;
            param.callbacks = callbacks;
            param.chain = chain;

            Object result = null;
            Throwable throwable = null;

            for (int i = 0; i < callbacks.length; i++) {
                param.index = i;
                try {
                    callbacks[i].callback.beforeHookedMember(param);
                } catch (Throwable t) {
                    LibXposedHookBridge.INSTANCE.log(t);
                }
            }
            param.index = -1;

            if (!param.skipOriginal) {
                try {
                    result = chain.proceed(argsCompat);
                } catch (Throwable t) {
                    throwable = t;
                }
            } else {
                result = param.result;
                throwable = param.throwable;
            }

            param.result = result;
            param.throwable = throwable;
            for (int i = callbacks.length - 1; i >= 0; i--) {
                param.index = i;
                try {
                    callbacks[i].callback.afterHookedMember(param);
                } catch (Throwable t) {
                    LibXposedHookBridge.INSTANCE.log(t);
                }
            }

            result = param.result;
            throwable = param.throwable;
            param.callbacks = null;
            param.extras = null;
            param.member = null;
            param.thisObjectCompat = null;
            param.argsCompat = null;
            param.result = null;
            param.throwable = null;
            param.chain = null;

            if (throwable != null) {
                throw throwable;
            }
            return result;
        }
    }

    public static final class UnhookHandle implements CompatHookBridge.MemberUnhookHandle {

        private final CallbackWrapper callback;
        private final Member method;

        public UnhookHandle(@NonNull CallbackWrapper callback, @NonNull Member method) {
            this.callback = callback;
            this.method = method;
        }

        @NonNull
        @Override
        public Member getMember() {
            return method;
        }

        @NonNull
        @Override
        public CompatHookBridge.MemberHookCallback getCallback() {
            return callback.callback;
        }

        @Override
        public boolean isHookActive() {
            return isMethodCallbackRegistered(method, callback);
        }

        @Override
        public void unhook() {
            removeMethodCallback(method, callback);
        }
    }
}
