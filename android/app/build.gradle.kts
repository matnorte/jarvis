import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
    packaging.jniLibs.excludes += setOf("**/x86/**", "**/armeabi-v7a/**")
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

val wakeAssets = layout.buildDirectory.dir("generated/wakeAssets")
val sherpaRuntime = layout.buildDirectory.file("wake-download/sherpa-onnx-2.0.0.aar")
val prepareWakeAssets = tasks.register("prepareWakeAssets") {
    outputs.file(sherpaRuntime)
    outputs.dir(wakeAssets)
    doLast {
        fun sha256(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var count = input.read(buffer)
                while (count >= 0) { if (count > 0) hash.update(buffer, 0, count); count = input.read(buffer) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        fun fetch(url: String, file: File, hash: String) {
            if (file.isFile && sha256(file) == hash) return
            file.parentFile.mkdirs()
            val temp = File(file.path + ".part")
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = 30000; readTimeout = 60000
            }
            connection.getInputStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            check(sha256(temp) == hash) { "Wake asset checksum mismatch" }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.0/sherpa-onnx-static-link-onnxruntime-2.0.0.aar",
            sherpaRuntime.get().asFile, "59366e37cf2760b419e895d2872aa1664027fd721f6e4c827cdb0e805088fcbe")
        val archive = layout.buildDirectory.file("wake-download/kws-model.tar.bz2").get().asFile
        fetch("https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2",
            archive, "68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6")
        copy {
            from(tarTree(resources.bzip2(archive)))
            include("*/encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx", "*/decoder-epoch-13-avg-2-chunk-8-left-64.onnx",
                "*/joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx", "*/tokens.txt")
            eachFile { path = name }
            includeEmptyDirs = false
            into(wakeAssets.get().dir("wake"))
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(wakeAssets.get().asFile)
tasks.named("preBuild") { dependsOn(prepareWakeAssets) }

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation(files(sherpaRuntime).builtBy(prepareWakeAssets))
    implementation("com.github.gkonovalov.android-vad:silero:2.0.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
