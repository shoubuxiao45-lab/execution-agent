plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}
val isolatedDeviceTests = providers.gradleProperty("deviceTestBuildType").orNull == "validation"
android {
    namespace = "com.executionagent.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.executionagent.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.9.0-dynamic-reminders"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    if (isolatedDeviceTests) {
        buildTypes.create("validation") {
            initWith(buildTypes.getByName("debug"))
            applicationIdSuffix = ".validation"
            versionNameSuffix = "-validation"
            matchingFallbacks += listOf("debug")
            resValue("string", "app_name", "Execution Agent 验证")
        }
        testBuildType = "validation"
    }
    // Keep vendor native libraries as supplied; this phase does not need an NDK.
    packaging { jniLibs.keepDebugSymbols += setOf("**/libandroidx.graphics.path.so", "**/libdatastore_shared_counter.so") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        // Versions are pinned to the verified SDK 35 / Kotlin 2.1 toolchain.
        // Availability of a newer release is reviewed separately from code correctness.
        disable += "GradleDependency"
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17); allWarningsAsErrors.set(true) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("com.google.dagger:hilt-android:2.56.2")
    ksp("com.google.dagger:hilt-compiler:2.56.2")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    if (isolatedDeviceTests) {
        add("validationImplementation", "androidx.compose.ui:ui-test-manifest")
    }
}






