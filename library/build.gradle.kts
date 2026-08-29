import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("com.vanniktech.maven.publish.base")
}

// Unique archive base name so the published jvm/js artifacts are `ktstore-library-*` rather than the
// generic `library-*` (the project name). Otherwise two `:library` modules at the same version (e.g.
// ktstore + ktcrypto both 0.0.8) collide as `library-jvm-<v>.jar` in a consumer's application
// distribution, which Gradle 9 rejects as a duplicate.
base { archivesName.set("ktstore-library") }

kotlin {
    js {
        browser()
    }
    androidTarget {
        publishLibraryVariants("release")
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
    jvm {
    }
    iosX64 {
        compilations["main"].cinterops.create("sqlite3")
    }
    iosArm64 {
        compilations["main"].cinterops.create("sqlite3")
    }
    iosSimulatorArm64 {
        compilations["main"].cinterops.create("sqlite3")
    }
    macosX64 {
        compilations["main"].cinterops.create("sqlite3")
    }
    macosArm64 {
        compilations["main"].cinterops.create("sqlite3")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                //put your multiplatform dependencies here
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.postgresql)
                implementation(libs.hikari)
            }
        }
    }
}

android {
    namespace = "com.latenighthack.ktstore"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

mavenPublishing {
    coordinates("com.latenighthack.ktstore", "ktstore-library", version.toString())

    pom {
        name.set("ktstore")
        description.set("A native Kotlin implementation for indexed storage")
        inceptionYear.set("2024")
        url.set("https://github.com/latenighthack/ktstore/")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("mproberts")
                name.set("Mike Roberts")
                url.set("https://github.com/mproberts/")
            }
        }
        scm {
            url.set("https://github.com/latenighthack/ktstore/")
            connection.set("scm:git:git://github.com/latenighthack/ktstore.git")
            developerConnection.set("scm:git:ssh://git@github.com/latenighthack/ktstore.git")
        }
    }
}
