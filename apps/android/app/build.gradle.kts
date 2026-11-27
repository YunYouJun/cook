import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
            require(path.matches(Regex("(?:[a-z0-9-]+/)+[a-z0-9-]+\\.glb"))) { "Invalid model path: $path" }
            require(sha256.matches(Regex("[a-f0-9]{64}")) && bytes in 1L..64L * 1024 * 1024) {
                "Invalid model checksum or size: $path"
            }
            require(name.matches(Regex("[a-z0-9-]+-$sha256\\.glb"))) { "Invalid model object: $name" }

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

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val achievementBadges: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val showcaseManifest: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val modelsDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val androidAssetsDir: DirectoryProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundledProduceModel: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val kitchenManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val kitchenModel: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val privacyPolicy: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val terms: RegularFileProperty

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

        // Images install from the CDN. Android models can be installed from verified APK assets.
        showcaseManifest.get().asFile.copyTo(File(cookOut, "showcase-packs.json"), overwrite = true)
        val showcase = JsonSlurper().parse(showcaseManifest.get().asFile) as List<*>
        val badgesByName = achievementBadges.files.associateBy { it.name }
        showcase.forEach { entry ->
            val pack = entry as Map<*, *>
            val recipeId = pack["recipeID"] as Int
            val badgeName = pack["badgeName"] as? String
            if (badgeName != null) {
                require(Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(badgeName))
                badgesByName.getValue("cooking-badge-$badgeName.webp")
                    .copyTo(File(cookOut, "showcase/$recipeId.webp"), overwrite = true)
            }
            val model = pack["androidModel"] as? Map<*, *> ?: return@forEach
            val pairId = pack["pairID"] as String
            require(recipeId > 0 && Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(pairId))
            val version = model["version"] as? Int
            val size = model["bytes"] as? Int
            val sha256 = model["sha256"] as? String
            require(model["kind"] == "model" && version != null && version > 0 && size != null && size in 20..8 * 1024 * 1024) {
                "Invalid Android showcase model descriptor for $pairId"
            }
            val modelFile = modelsDir.file("showcase/$pairId/model.glb").get().asFile
            require(modelFile.length() == size.toLong()) { "Android showcase model size mismatch for $pairId" }
            val modelBytes = modelFile.readBytes()
            val header = ByteBuffer.wrap(modelBytes).order(ByteOrder.LITTLE_ENDIAN)
            val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(modelBytes))
            val filename = "v$version-$digest.glb"
            require(modelBytes.size == size && header.int == 0x46546c67 && header.int == 2 && header.int == size &&
                sha256 == digest && model["url"] == "https://assets.yunle.fun/cook/showcase/$recipeId/$pairId/model/$filename"
            ) { "Android showcase model does not match its manifest for $pairId" }
            File(cookOut, "showcase/models/$recipeId-$pairId/$filename").apply {
                parentFile.mkdirs()
                writeBytes(modelBytes)
            }
        }

        kitchenManifest.get().asFile.copyTo(File(cookOut, "scenes/kitchen.json"), overwrite = true)
        privacyPolicy.get().asFile.copyTo(File(cookOut, "privacy-android.md"), overwrite = true)
        terms.get().asFile.copyTo(File(cookOut, "terms.md"), overwrite = true)
        kitchenModel.get().asFile.copyTo(File(cookOut, "scenes/cloud-kitchen.glb"), overwrite = true)
        modelsDir.file("android/showcase-plate.glb").get().asFile
            .copyTo(File(cookOut, "scenes/showcase-plate.glb"), overwrite = true)
        for (asset in listOf("model.json", "labels.txt", "TinyCLIP-LICENSE.txt")) {
            androidAssetsDir.file("produce/$asset").get().asFile.copyTo(File(cookOut, "produce/$asset"), overwrite = true)
        }
        bundledProduceModel.orNull?.asFile?.let { produceModel ->
            val produceManifest = JsonSlurper().parse(File(cookOut, "produce/model.json")) as Map<*, *>
            val produceModelBytes = (produceManifest["bytes"] as Number).toLong()
            require(produceModelBytes in 1L..64L * 1024 * 1024 && produceModel.length() == produceModelBytes) {
                "Bundled Android produce model size does not match model.json"
            }
            val produceDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(produceModel.readBytes()))
            require(produceManifest["sha256"] == produceDigest && produceManifest["file"] == "classifier-$produceDigest.onnx") {
                "Bundled Android produce model does not match model.json"
            }
            produceModel.copyTo(File(cookOut, "produce/classifier.onnx"), overwrite = true)
        }
    }
}

val releaseSigningInputs = listOf(
    "COOK_ANDROID_KEYSTORE_PATH",
    "COOK_ANDROID_KEYSTORE_PASSWORD",
    "COOK_ANDROID_KEY_ALIAS",
    "COOK_ANDROID_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val requireReleaseSigning = providers.gradleProperty("cook.signing.required").orNull == "true"
val configureReleaseSigning = requireReleaseSigning || releaseSigningInputs.values.any { it != null }
if (configureReleaseSigning) {
    val missing = releaseSigningInputs.filterValues { it.isNullOrBlank() }.keys
    require(missing.isEmpty()) { "Android release signing requires: ${missing.joinToString()}" }
}

val weChatLogin = providers.gradleProperty("cook.wechat.login").orNull == "true"
val weChatSourceDir = if (weChatLogin) "src/wechatEnabled" else "src/wechatDisabled"
val bundleProduceModel = providers.gradleProperty("cook.fridge.produce-model").orNull == "bundled"

android {
    namespace = "cn.yunyoujun.cook"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "fun.yunle.cook"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0-alpha.2"

        val appFilingNumber = providers.environmentVariable("COOK_ANDROID_APP_FILING_NUMBER").orElse("").get().trim()
        require(appFilingNumber.isEmpty() || appFilingNumber.matches(Regex("\\p{IsHan}ICP备[0-9]+号-[0-9]+A"))) {
            "COOK_ANDROID_APP_FILING_NUMBER must be the APP service filing number ending in A."
        }
        buildConfigField("String", "COOK_ANDROID_APP_FILING_NUMBER", "\"$appFilingNumber\"")

        val feedbackUrl = (project.findProperty("cook.feedback.url") as? String
            ?: System.getenv("COOK_FEEDBACK_URL")
            ?: "")
            .trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "COOK_FEEDBACK_URL", "\"$feedbackUrl\"")

        val fridgeVisionEnabled = when (providers.gradleProperty("cook.fridge.vision").orNull) {
            null, "on" -> true
            "off" -> false
            else -> error("cook.fridge.vision must be on or off")
        }
        buildConfigField("boolean", "COOK_FRIDGE_VISION_ENABLED", fridgeVisionEnabled.toString())
        buildConfigField("boolean", "COOK_BUNDLED_PRODUCE_MODEL", bundleProduceModel.toString())

        val directMembershipCheckout = when (providers.gradleProperty("cook.distribution").orNull) {
            null, "play" -> false
            "direct" -> true
            else -> error("cook.distribution must be direct or play")
        }
        buildConfigField("boolean", "COOK_WEB_MEMBERSHIP_CHECKOUT", directMembershipCheckout.toString())
        val playLedger = when (providers.gradleProperty("cook.play.billing").orNull) {
            null, "off" -> ""
            "production" -> "Production"
            "sandbox" -> "Sandbox"
            else -> error("cook.play.billing must be off, sandbox or production")
        }
        require(!directMembershipCheckout || playLedger.isEmpty()) {
            "Play Billing requires a Google Play distribution build."
        }
        val playLicenseTestSideload = providers.gradleProperty("cook.play.licenseTestSideload").orNull == "true"
        require(!playLicenseTestSideload || playLedger == "Sandbox") {
            "cook.play.licenseTestSideload requires cook.play.billing=sandbox."
        }
        buildConfigField("String", "COOK_PLAY_MEMBERSHIP_LEDGER", "\"$playLedger\"")
        buildConfigField("boolean", "COOK_PLAY_LICENSE_TEST_SIDELOAD", playLicenseTestSideload.toString())

        val weChatSignup = weChatLogin && providers.gradleProperty("cook.wechat.signup").orNull == "true"
        if (weChatLogin) {
            val weChatAppId = providers.gradleProperty("cook.wechat.app-id").orElse("wxce6c7405dd756b9d").get()
            require(weChatAppId.matches(Regex("wx[0-9a-f]{16}"))) { "Invalid WeChat mobile App ID" }
            buildConfigField("String", "COOK_WECHAT_APP_ID", "\"$weChatAppId\"")
        }
        buildConfigField("boolean", "COOK_WECHAT_SIGNUP_ENABLED", weChatSignup.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.named("main") {
        kotlin.directories += "$weChatSourceDir/kotlin"
    }

    if (configureReleaseSigning) {
        signingConfigs.create("release") {
            val keystore = File(checkNotNull(releaseSigningInputs["COOK_ANDROID_KEYSTORE_PATH"]))
            require(keystore.isAbsolute && keystore.isFile && keystore.canRead()) {
                "COOK_ANDROID_KEYSTORE_PATH must point to a readable keystore at an absolute path."
            }
            storeFile = keystore
            storePassword = releaseSigningInputs["COOK_ANDROID_KEYSTORE_PASSWORD"]
            keyAlias = releaseSigningInputs["COOK_ANDROID_KEY_ALIAS"]
            keyPassword = releaseSigningInputs["COOK_ANDROID_KEY_PASSWORD"]
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (configureReleaseSigning) signingConfig = signingConfigs.getByName("release")
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
            it.systemProperty(
                "cook.fridge.vision.nameFixtures",
                rootProject.projectDir.resolve("../../packages/data/fixtures/fridge-vision-names.json").canonicalPath,
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

val sharedKitchen = rootProject.layout.projectDirectory.dir("../ios/Cook/Resources")
val kitchenExportScript = rootProject.layout.projectDirectory.file("../../scripts/export-android-scenes.py")
val kitchenOutput = layout.buildDirectory.file("generated/cookKitchen/cloud-kitchen.glb")
val macBlender = File("/Applications/Blender.app/Contents/MacOS/Blender")
val blenderExecutable = providers.environmentVariable("COOK_BLENDER")
    .orElse(if (macBlender.canExecute()) macBlender.path else "blender")
val exportCookKitchen = tasks.register<Exec>("exportCookKitchen") {
    inputs.files(sharedKitchen.file("CloudKitchen.usdz"), sharedKitchen.file("CloudKitchenManifest.json"), kitchenExportScript)
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("blenderExecutable", blenderExecutable)
    outputs.file(kitchenOutput)
    commandLine(
        blenderExecutable.get(), "-b", "--factory-startup", "--python-exit-code", "1",
        "--python", kitchenExportScript.asFile, "--", "--kitchen-only", kitchenOutput.get().asFile,
    )
}

val syncCookAssets = tasks.register<SyncCookAssetsTask>("syncCookAssets") {
    dependsOn(exportCookKitchen)
    dataDir.set(rootProject.layout.projectDirectory.dir("../../packages/data"))
    sharedDir.set(rootProject.layout.projectDirectory.dir("../../packages/shared"))
    achievementBadges.from(rootProject.fileTree("../ios/Cook/Resources") { include("cooking-badge-*.webp") })
    showcaseManifest.set(rootProject.layout.projectDirectory.file("../ios/Cook/Resources/showcase-packs.json"))
    modelsDir.set(downloadCookModels.flatMap { it.outputDir })
    androidAssetsDir.set(rootProject.layout.projectDirectory.dir("../../assets/android"))
    if (bundleProduceModel) {
        bundledProduceModel.set(rootProject.layout.projectDirectory.file("../../assets/android/produce/classifier.onnx"))
    }
    kitchenManifest.set(sharedKitchen.file("CloudKitchenManifest.json"))
    kitchenModel.set(kitchenOutput)
    privacyPolicy.set(rootProject.layout.projectDirectory.file("../../docs/privacy-android.md"))
    terms.set(rootProject.layout.projectDirectory.file("../../docs/terms.md"))
    outputDir.set(layout.buildDirectory.dir("generated/cookAssets"))
}

androidComponents {
    onVariants { variant ->
        if (weChatLogin) {
            variant.sources.manifests.addStaticManifestFile("$weChatSourceDir/AndroidManifest.xml")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            syncCookAssets,
            SyncCookAssetsTask::outputDir,
        )
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.customview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.view)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.window)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tink.android)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.onnxruntime.android)
    if (weChatLogin) implementation(libs.wechat.opensdk)
    implementation(libs.play.billing)
    implementation(libs.filament.gltfio)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
}
