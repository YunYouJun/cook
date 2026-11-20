import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

abstract class DownloadCookModelsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val offline: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun downloadModels() {
        val models = JsonSlurper().parse(manifest.get().asFile) as Map<*, *>
        for ((relativePath, metadata) in models) {
            val path = relativePath as String
            val model = metadata as Map<*, *>
            val name = model["file"] as String
            val bytes = (model["bytes"] as Number).toLong()
            val sha256 = model["sha256"] as String
            require(path.matches(Regex("(?:[a-z0-9-]+/)+[a-z0-9-]+\\.(?:glb|onnx)"))) { "Invalid model path: $path" }
            require(sha256.matches(Regex("[a-f0-9]{64}")) && bytes in 1L..64L * 1024 * 1024) {
                "Invalid model checksum or size: $path"
            }
            require(name.matches(Regex("[a-z0-9-]+-$sha256\\.(?:glb|onnx)"))) { "Invalid model object: $name" }

            val destination = outputDir.file(path).get().asFile
            if (matches(destination, bytes, sha256)) continue
            check(!offline.get()) { "Missing verified Android model $path. Run Gradle without --offline once." }

            Files.createDirectories(destination.toPath().parent)
            val temporary = Files.createTempFile(destination.toPath().parent, "cook-model-", ".tmp").toFile()
            try {
                val connection = URI("https://assets.yunle.fun/cook/android/$name").toURL().openConnection()
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.getInputStream().use { input ->
                    temporary.outputStream().use { output ->
                        val copied = input.transferTo(output)
                        check(copied == bytes) { "Unexpected Android model size: $path ($copied bytes)" }
                    }
                }
                check(matches(temporary, bytes, sha256)) { "Android model SHA-256 does not match: $path" }
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        }
    }

    private fun matches(file: File, bytes: Long, sha256: String): Boolean {
        if (!file.isFile || file.length() != bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = input.read(buffer)
            while (count >= 0) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return HexFormat.of().formatHex(digest.digest()) == sha256
    }
}

abstract class SyncCookAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dataDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val showcaseDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val showcaseManifest: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val androidAssetsDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val modelsDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val kitchenManifest: RegularFileProperty

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

        // Both clients use the same recipe ID to pair a learned dish with its artwork.
        val showcase = JsonSlurper().parse(showcaseManifest.get().asFile) as List<*>
        showcase.forEach { entry ->
            val pack = entry as Map<*, *>
            val recipeId = pack["recipeID"] as Number
            val pairId = pack["pairID"] as String
            showcaseDir.file("$pairId/image.webp").get().asFile
                .copyTo(File(cookOut, "showcase/$recipeId.webp"), overwrite = true)
            modelsDir.file("showcase/$pairId/model.glb").get().asFile
                .copyTo(File(cookOut, "showcase/$recipeId.glb"), overwrite = true)
        }

        kitchenManifest.get().asFile.copyTo(File(cookOut, "scenes/kitchen.json"), overwrite = true)
        for (model in listOf("cloud-kitchen.glb", "showcase-plate.glb")) {
            modelsDir.file("android/$model").get().asFile.copyTo(File(cookOut, "scenes/$model"), overwrite = true)
        }
        modelsDir.file("android/produce/classifier.onnx").get().asFile
            .copyTo(File(cookOut, "produce/classifier.onnx"), overwrite = true)
        for (asset in listOf("labels.txt", "TinyCLIP-LICENSE.txt")) {
            androidAssetsDir.file("produce/$asset").get().asFile.copyTo(File(cookOut, "produce/$asset"), overwrite = true)
        }
    }
}

android {
    namespace = "cn.yunyoujun.cook"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"
    androidResources.noCompress += "onnx"

    defaultConfig {
        applicationId = "fun.yunle.cook"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0-alpha.2"

        val feedbackUrl = (project.findProperty("cook.feedback.url") as? String
            ?: System.getenv("COOK_FEEDBACK_URL")
            ?: "")
            .trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "COOK_FEEDBACK_URL", "\"$feedbackUrl\"")

        val directMembershipCheckout = providers.gradleProperty("cook.distribution").orNull == "direct"
        buildConfigField("boolean", "COOK_WEB_MEMBERSHIP_CHECKOUT", directMembershipCheckout.toString())

        val weChatLogin = providers.gradleProperty("cook.wechat.login").orNull == "true"
        val weChatSignup = weChatLogin && providers.gradleProperty("cook.wechat.signup").orNull == "true"
        val weChatAppId = providers.gradleProperty("cook.wechat.app-id").orElse("wxce6c7405dd756b9d").get()
        require(weChatAppId.matches(Regex("wx[0-9a-f]{16}"))) { "Invalid WeChat mobile App ID" }
        buildConfigField("boolean", "COOK_WECHAT_LOGIN_ENABLED", weChatLogin.toString())
        buildConfigField("boolean", "COOK_WECHAT_SIGNUP_ENABLED", weChatSignup.toString())
        buildConfigField("String", "COOK_WECHAT_APP_ID", "\"$weChatAppId\"")
        manifestPlaceholders["cookWeChatEnabled"] = weChatLogin.toString()

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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

val downloadCookModels = tasks.register<DownloadCookModelsTask>("downloadCookModels") {
    manifest.set(rootProject.layout.projectDirectory.file("../../assets/android/models.json"))
    offline.set(gradle.startParameter.isOffline)
    outputDir.set(layout.buildDirectory.dir("downloaded/cookModels"))
}

val syncCookAssets = tasks.register<SyncCookAssetsTask>("syncCookAssets") {
    dataDir.set(rootProject.layout.projectDirectory.dir("../../packages/data"))
    sharedDir.set(rootProject.layout.projectDirectory.dir("../../packages/shared"))
    showcaseDir.set(rootProject.layout.projectDirectory.dir("../../assets/showcase"))
    showcaseManifest.set(rootProject.layout.projectDirectory.file("../ios/Cook/Resources/showcase-packs.json"))
    androidAssetsDir.set(rootProject.layout.projectDirectory.dir("../../assets/android"))
    modelsDir.set(downloadCookModels.flatMap { it.outputDir })
    kitchenManifest.set(rootProject.layout.projectDirectory.file("../ios/Cook/Resources/CloudKitchenManifest.json"))
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
    implementation(libs.onnxruntime.android)
    implementation(libs.wechat.opensdk)
    implementation(libs.filament.gltfio)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
}
