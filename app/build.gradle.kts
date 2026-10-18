plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.fitness.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.fitness.android"
        minSdk = 37          // Android 17: the user's Pixel 7 and nothing older
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.vico.compose.m3)
    implementation(libs.maplibre)
    implementation(libs.okhttp)         // MapLibre already runs on it; compiled against to send our own User-Agent for OSM tiles
    implementation(libs.kotlinx.serialization.json)
}
