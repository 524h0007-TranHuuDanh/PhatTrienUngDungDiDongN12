import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}


fun readLocalProperty(name: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return null
    return try {
        Properties().apply {
            file.inputStream().use { load(it) }
        }.getProperty(name)
    } catch (_: Exception) {
        null
    }
}

android {
    namespace = "com.example.coffeestock"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.coffeestock"

        minSdk = 24
        targetSdk = 37

        val apiBaseUrl = readLocalProperty("COFFEESTOCK_API_BASE_URL")
            ?: providers.gradleProperty("COFFEESTOCK_API_BASE_URL")
                .orElse("http://127.0.0.1:8080/")
                .get()

        val apiKey = readLocalProperty("COFFEESTOCK_API_KEY")
            ?: providers.gradleProperty("COFFEESTOCK_API_KEY")
                .orElse("")
                .get()

        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"${apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )

        buildConfigField(
            "String",
            "API_KEY",
            "\"${apiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.register<Exec>("adbReverse") {
    group = "coffeestock"
    description = "Forward emulator tcp:8080 → host tcp:8080"
    commandLine("adb", "reverse", "tcp:8080", "tcp:8080")
    isIgnoreExitValue = true
    doFirst { println("→ adb reverse tcp:8080 tcp:8080") }
    doLast  { println("adb reverse OK (bỏ qua nếu log không lỗi)") }
}

tasks.matching {
    it.name == "installDebug" ||
            it.name == "installDebugAndroidTest" ||
            it.name == "connectedDebugAndroidTest"
}.configureEach { dependsOn("adbReverse") }