plugins {
    id("com.android.application")
}

android {
    namespace = "com.openclawchip.jarvis"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.openclawchip.jarvis"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val copyOrb = tasks.register<Sync>("copyOrb") {
    from(rootProject.layout.projectDirectory.dir("../ui/orb"))
    into(layout.projectDirectory.dir("src/main/assets/orb"))
}
tasks.named("preBuild") { dependsOn(copyOrb) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("ai.picovoice:porcupine-android:4.0.2")
    implementation("com.github.gkonovalov.android-vad:silero:2.0.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
