plugins { alias(libs.plugins.kotlinMultiplatform) }
kotlin {
    js {
        browser { commonWebpackConfig { outputFileName = "storage-fixture.js" } }
        binaries.executable()
    }
    sourceSets {
        val jsMain by getting {
            kotlin.srcDir("../library/src/commonTest/kotlin")
            kotlin.srcDir("../migration-example-spec/src/commonMain/kotlin")
            kotlin.srcDir("../migration-example/src/migrations/main")
            kotlin.srcDir("../migration-example/src/migrations/test")
            dependencies {
                implementation(project(":library"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
    }
}
