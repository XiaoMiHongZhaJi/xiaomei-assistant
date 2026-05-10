# Keep modern libxposed entry and resource mapping stable.
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep class com.xiaomei.assistant.xposed.ModuleMain { *; }
-keep class com.xiaomei.assistant.runtime.** { *; }

# Keep app entry points referenced by manifest and host navigation.
-keep class com.xiaomei.assistant.MainActivity { *; }
-keep class com.xiaomei.assistant.HostSettingsActivity { *; }
-keep class com.xiaomei.assistant.XiaoMeiApplication { *; }
-keep class com.xiaomei.assistant.activity.MainActivityAlias { *; }
-keep class com.xiaomei.assistant.settings.** { *; }
-keep class com.xiaomei.assistant.uihost.** { *; }
-keep class com.xiaomei.assistant.host.** { *; }

# Keep view model and repositories used by host settings UI.
-keep class com.xiaomei.assistant.ui.MainViewModel { *; }
-keep class com.xiaomei.assistant.data.** { *; }
-keep class com.xiaomei.assistant.llm.** { *; }
-keep class com.xiaomei.assistant.model.** { *; }
-keep class com.xiaomei.assistant.status.** { *; }
-keep class com.xiaomei.assistant.host.** { *; }
-keep class com.xiaomei.assistant.xposed.** { *; }

# Keep Kotlin serialization/data classes used by persistence and protocol payloads.
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable *;
}

# Keep annotation-based entry classes.
-keep @androidx.annotation.Keep class * { *; }

# Avoid stripping AndroidX Startup provider wiring.
-keep class androidx.startup.** { *; }
