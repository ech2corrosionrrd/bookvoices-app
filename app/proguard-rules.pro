# R8 увімкнено для release (isMinifyEnabled + isShrinkResources).
# Класи з AndroidManifest (Application, Activity, Service, FileProvider) R8 зберігає сам.

# --- Room ---
# Room генерує *_Impl і знаходить його через Class.forName(<DB>_Impl).
# Consumer-правила Room це покривають, але залишаємо явно: помилка тут виявляється
# лише в release-збірці на пристрої, а не під час компіляції.
-keep class ua.nichnyk.listen.data.ListenDatabase_Impl { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- WorkManager ---
# WebDavSyncWorker створюється рефлексією за іменем класу з бази WorkManager.
-keep class ua.nichnyk.listen.data.WebDavSyncWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- Media3 ---
# media3 постачає власні consumer-правила; додаткові -dontwarn для необов'язкових
# інтеграцій (Cast, IMA, RTMP), яких у проєкті немає.
-dontwarn androidx.media3.exoplayer.ext.**
-dontwarn com.google.android.gms.cast.**

# --- Kotlin ---
-keepclassmembers class kotlin.Metadata { public <methods>; }
-dontwarn kotlin.Unit

# --- Помилки в стектрейсах ---
# Без цього рядки в звітах про збої нечитабельні; mapping.txt лежить у
# app/build/outputs/mapping/release/.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
