# Gson matches JSON keys to Kotlin field names by reflection, and only three of the API
# model's fields carry @SerializedName - the rest rely on the name itself. Renaming them
# does not fail loudly: Gson finds no match, hands back null, and toEntity drops the
# transaction. The app would go on syncing and quietly stop seeing any money.
-keep class com.spendroid.data.remote.** { *; }

# Entities are written and read by Room, which generates code against their field names,
# and are also serialised into backups by hand. A backup is the only copy of anything
# older than ninety days, so a rename here is unrecoverable rather than merely wrong.
-keep class com.spendroid.data.db.** { *; }

# Gson's own reflective machinery.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Retrofit reads generic return types off the interface at runtime.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# WorkManager builds workers by class name from a string in its own database, so a worker
# that is only ever referenced that way looks unreachable to the shrinker.
-keep class com.spendroid.work.** { *; }

# Glance instantiates the receiver from the manifest, which the shrinker cannot see.
-keep class com.spendroid.widget.** { *; }

# Keep enough of a stack trace to read a crash report against the mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
