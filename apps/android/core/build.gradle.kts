import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
}

tasks.withType<Test>().configureEach {
    systemProperty(
        "cook.data.dir",
        rootProject.projectDir.resolve("../../packages/data").canonicalPath,
    )
    systemProperty(
        "cook.fixtures.dir",
        rootProject.projectDir.resolve("../../packages/data/fixtures").canonicalPath,
    )
}
