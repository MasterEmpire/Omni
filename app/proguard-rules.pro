# Main Activity & Activities
-keep class com.omni.hub.MainActivity { *; }
-keep class com.omni.hub.container.PluginContainerActivity { *; }

# Shared API Contracts (Crucial for Dynamic Code Execution)
-keep class com.omni.hub.api.** { *; }
-keep interface com.omni.hub.api.** { *; }
-keep class com.omni.hub.loader.** { *; }

# Kotlin Standard Library & Coroutines
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# Jetpack Compose Internals
-keep class androidx.compose.** { *; }
-keep class androidx.activity.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.savedstate.** { *; }
-dontwarn com.google.errorprone.annotations.**

# Networking
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**