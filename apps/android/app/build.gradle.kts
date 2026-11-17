import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

abstract class SyncCookAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dataDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val data = dataDir.get().asFile
        val shared = sharedDir.get().asFile
        val outRoot = outputDir.get().asFile
        val cookOut = File(outRoot, "cook")
        if (outRoot.exists()) {
            outRoot.deleteRecursively()
        }
        cookOut.mkdirs()

        val metaFile = File(data, "recipe-meta.json")
        if (!metaFile.isFile) {
            throw IllegalStateException(
                "Missing packages/data/recipe-meta.json. Run `pnpm convert` in the repo root first.",
            )
        }

        @Suppress("UNCHECKED_CAST")
        val meta = JsonSlurper().parse(metaFile) as Map<String, Any?>
        val schemaVersion = (meta["schemaVersion"] as? Number)?.toInt()
        if (schemaVersion != 1) {
            throw IllegalStateException("Unsupported recipe-meta.json schemaVersion=$schemaVersion (expected 1)")
        }

        val requiredFiles = mutableListOf(
            "recipe-meta.json",
            "incompatible-foods.json",
            "aliases.json",
            "food.json",
            "pantry.json",
            "query.json",
            "shelf-life.json",
            "recipe-text.json",
            "recipe-amounts.json",
        )

        val chunked = meta["chunked"] as? Boolean ?: false
        if (chunked) {
            @Suppress("UNCHECKED_CAST")
            val chunks = meta["chunks"] as? List<Map<String, Any?>> ?: emptyList()
            if (chunks.isEmpty()) {
                throw IllegalStateException("recipe-meta.json declares chunked=true but has no chunks")
            }
            chunks.forEach { chunk ->
                val idx = (chunk["index"] as? Number)?.toInt()
                    ?: throw IllegalStateException("Chunk entry missing index")
                val name = chunk["file"] as? String ?: "recipe-chunk-$idx.json"
                requiredFiles.add(name)
            }
        } else {
            requiredFiles.add("recipe.json")
        }

        // Raw byte-for-byte copy so SHA-256 digests match iOS and Web exactly.
        requiredFiles.forEach { name ->
            val src = File(data, name)
            if (!src.isFile) {
                throw IllegalStateException("Missing required CookData asset: ${src.absolutePath}")
            }
            src.copyTo(File(cookOut, name), overwrite = true)
        }

        listOf("recipe-dietary.json", "recipe-institutional-low-gi.json").forEach { optionalName ->
            val src = File(data, optionalName)
            if (src.isFile) {
                src.copyTo(File(cookOut, optionalName), overwrite = true)
            }
        }

        val linksFile = File(shared, "links.json")
        if (!linksFile.isFile) {
            throw IllegalStateException("Missing required shared asset: ${linksFile.absolutePath}")
        }
        linksFile.copyTo(File(cookOut, "links.json"), overwrite = true)
    }
}

android {
    namespace = "cn.yunyoujun.cook"
    compileSdk = 36

    defaultConfig {
        applicationId = "fun.yunle.cook"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "2.0.0-alpha.1"

        val feedbackUrl = (project.findProperty("cook.feedback.url") as? String
            ?: System.getenv("COOK_FEEDBACK_URL")
            ?: "")
            .trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "COOK_FEEDBACK_URL", "\"$feedbackUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty(
                "cook.room.schemas.dir",
                project.projectDir.resolve("schemas").canonicalPath,
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

val syncCookAssets = tasks.register<SyncCookAssetsTask>("syncCookAssets") {
    dataDir.set(rootProject.layout.projectDirectory.dir("../../packages/data"))
    sharedDir.set(rootProject.layout.projectDirectory.dir("../../packages/shared"))
    outputDir.set(layout.buildDirectory.dir("generated/cookAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            syncCookAssets,
            SyncCookAssetsTask::outputDir,
        )
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tink.android)
    implementation(libs.mlkit.text.recognition.chinese)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
}
