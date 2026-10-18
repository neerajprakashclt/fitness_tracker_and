plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.garmin.fit)
    implementation(libs.kotlinx.serialization.json)
    // XmlPullParser is part of Android, so the app must not bundle a second copy; the JVM tests need one.
    compileOnly(libs.xmlpull)
    testImplementation(libs.xmlpull)
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The PC app buckets days in the machine's zone; the golden fixtures were made in US Eastern.
    systemProperty("user.timezone", "America/New_York")
    systemProperty("golden.dir", rootProject.file("seed/golden").absolutePath)
    systemProperty("seed.dir", rootProject.file("seed").absolutePath)
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
