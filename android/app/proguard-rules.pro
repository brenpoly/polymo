# --------------------------------------------------------------------------
# JNI / Native methods
# --------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

# --------------------------------------------------------------------------
# Hilt / Dagger
# --------------------------------------------------------------------------
-dontwarn dagger.hilt.internal.aggregatedroot.codegen.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclassmembers class * {
    @dagger.hilt.android.qualifiers.ApplicationContext *;
    @dagger.hilt.android.qualifiers.ActivityContext *;
}

# --------------------------------------------------------------------------
# Room
# --------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# --------------------------------------------------------------------------
# Kotlin Coroutines
# --------------------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# --------------------------------------------------------------------------
# Compose
# --------------------------------------------------------------------------
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# --------------------------------------------------------------------------
# DataStore
# --------------------------------------------------------------------------
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}
