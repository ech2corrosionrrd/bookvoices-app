plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

/**
 * Модуль існує лише щоб згенерувати `baseline-prof.txt` для :app.
 *
 * У готовий APK він не потрапляє: це `com.android.test`, який запускається на
 * пристрої, ганяє застосунок і записує, які саме класи й методи знадобилися
 * під час запуску. ART потім компілює їх наперед, замість інтерпретувати
 * при першому старті в користувача.
 */
android {
    namespace = "ua.nichnyk.listen.baseline"
    compileSdk = 36

    defaultConfig {
        // Генерація профілю потребує root на пристрої, а це можливо з API 28.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Глушимо рівно одну перевірку — «це емулятор». Вона потрібна, щоб профіль
        // узагалі можна було зміряти без телефона, і на реальному пристрої мовчить.
        //
        // Решту (розряджена батарея, debuggable-збірка, eng-прошивка) навмисно НЕ
        // глушимо: на телефоні це чесні сигнали, що вимір вийде сміттям, і краще
        // впасти з поясненням, ніж отримати гарну неправдиву цифру.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    targetProjectPath = ":app"

    // Керований пристрій, а не той, що підключений зараз: профіль треба збирати
    // на передбачуваній конфігурації, і образ має бути rootable. google_apis_playstore
    // не підходить — `adb root` на ньому заборонений, а без нього профіль не витягти.
    testOptions.managedDevices.allDevices {
        create<com.android.build.api.dsl.ManagedVirtualDevice>("profileGenerator") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp"
            // Явно, а не за замовчуванням: в AGP 9 типовим стане "arm64-v8a",
            // і мовчазна зміна архітектури зламала б генерацію на x86-машині.
            testedAbi = "x86_64"
        }
    }
}

baselineProfile {
    managedDevices += "profileGenerator"
    // На CI підключеного пристрою немає, тож покладаємося лише на керований.
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
