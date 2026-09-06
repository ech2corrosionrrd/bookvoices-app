import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
}

// Дані для підпису релізу читаємо з keystore.properties (файл у .gitignore).
// Шаблон — keystore.properties.example.
val releaseKeystore: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "ua.nichnyk.listen"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.bookvoices"
        minSdk = 26
        targetSdk = 36
        // Версія приходить із тега релізу — див. кореневий build.gradle.kts.
        // Тут її не тримаємо: інакше APK з іменем 1.2.0 знову міг би виявитися
        // версією 1.1.0 усередині.
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
                // v1 (JAR signing) потрібен лише до API 24; minSdk тут 26,
                // тому вимикаємо — це зайві файли в APK і повільніша верифікація.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Підписуємо релізним ключем лише якщо є keystore.properties (не в VCS).
            // Інакше збірка лишається непідписаною — краще, ніж падати або підписувати чужим ключем.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // debug свідомо використовує стандартний debug-keystore Android SDK,
        // щоб релізний ключ ніколи не потрапляв у тестові збірки.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // local.properties Android Studio генерує без екранування; AGP читає його коректно,
        // а сам файл у .gitignore. Решта перевірок лишається блокувальною.
        disable += "PropertyEscape"
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        sarifReport = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric читає маніфест і ресурси зі справжньої збірки.
        unitTests.all { it.systemProperty("robolectric.logging.enabled", "false") }
    }
}

// Room експортує схему БД у app/schemas — потрібно для тестів міграцій.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Схеми Room — вхідні дані для androidTest міграцій.
android.sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

dependencies {
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.play.billing.ktx)

    // Ставить згенерований baseline-профіль на пристрій. Поза Google Play це єдиний
    // спосіб, яким профіль узагалі потрапляє в ART.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric дає Room, DataStore і ViewModel у звичайному JVM-тесті: раніше
    // все, що торкалося Android, можна було перевірити лише на емуляторі — тобто
    // в найповільнішій і найкрихкішій задачі CI.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.room.testing)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
}
