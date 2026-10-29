plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
    jacoco
}

android {
    namespace = "com.utbildning.tracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.utbildning.tracker"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (!providers.gradleProperty("includeAuxiliaryTests").map(String::toBoolean).getOrElse(false)) {
            testInstrumentationRunnerArguments["notAnnotation"] = listOf(
                "com.utbildning.tracker.SystemScenario",
                "com.utbildning.tracker.EmulatorFixture",
                "com.utbildning.tracker.ScreenshotCapture",
            ).joinToString(",")
        }
    }

    buildFeatures {
        compose = true
    }
    androidResources {
        localeFilters += listOf("en", "ru")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

jacoco {
    toolVersion = "0.8.13"
}

// JVM coverage only: Android UI, Room and system services are verified on-device.
tasks.register<JacocoReport>("jvmCoverageReport") {
    dependsOn("testDebugUnitTest")
    executionData(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
    classDirectories.setFrom(fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        include("com/utbildning/tracker/domain/**")
        include("com/utbildning/tracker/backup/BackupArchive*.class")
        include("com/utbildning/tracker/maintenance/RetentionPolicy*.class")
    })
    sourceDirectories.setFrom(files("src/main/java"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

kotlin {
    jvmToolchain(17)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.gson)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
