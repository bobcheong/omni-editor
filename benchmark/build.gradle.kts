import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlin.random.Random

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.omnieditor.benchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Target the "direct" flavour — the one that ships first.
    flavorDimensions += "distribution"
    productFlavors {
        create("direct") { dimension = "distribution" }
        create("store") { dimension = "distribution" }
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
}

dependencies {
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.uiautomator)
    implementation(libs.junit)
}

androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "benchmark"
    }
}

tasks.register("generateFixtures") {
    group = "benchmark"
    description = "Generate deterministic fixture files for benchmark runs"
    val fixtureDir = layout.buildDirectory.dir("fixtures")
    outputs.dir(fixtureDir)

    doLast {
        val dir = fixtureDir.get().asFile
        dir.mkdirs()
        val seed = 20260816L
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,-_"

        fun buildSegment(rng: Random, length: Int): String =
            buildString(length) { repeat(length) { append(chars[rng.nextInt(chars.length)]) } }

        // 250 MB pair (~2.5M lines, ~100 chars each, 20% of lines differ)
        println("Generating 250 MB pair...")
        val rng1 = Random(seed)
        BufferedWriter(FileWriter(File(dir, "large-left.txt"))).use { left ->
            BufferedWriter(FileWriter(File(dir, "large-right.txt"))).use { right ->
                for (i in 0 until 2_500_000) {
                    val prefix = "%06d ".format(i)
                    val body = buildSegment(rng1, 93)
                    val line = prefix + body
                    if (rng1.nextDouble() < 0.20) {
                        val modified = line.replaceRange(10, 30, buildSegment(rng1, 20))
                        left.write(line); left.newLine()
                        right.write(modified); right.newLine()
                    } else {
                        left.write(line); left.newLine()
                        right.write(line); right.newLine()
                    }
                }
            }
        }

        // 500k-line scroll file (~87 chars/line)
        println("Generating 500k-line scroll file...")
        val rng2 = Random(seed + 1)
        BufferedWriter(FileWriter(File(dir, "scroll-500k.txt"))).use { w ->
            for (i in 0 until 500_000) {
                w.write("%06d ".format(i) + buildSegment(rng2, 80))
                w.newLine()
            }
        }

        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            println("  ${f.name}: ${f.length() / (1024 * 1024)} MB")
        }
    }
}
