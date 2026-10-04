plugins { alias(libs.plugins.kotlinMultiplatform); id("com.vanniktech.maven.publish.base") }
kotlin {
    jvm()
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":library"))
            implementation(libs.kotlinx.coroutines.core)
            implementation("org.xerial:sqlite-jdbc:3.45.3.0")
        }
        jvmTest.dependencies { implementation(kotlin("test-junit")); implementation(project(":migration-example-spec")) }
    }
}

mavenPublishing {
    coordinates("com.latenighthack.ktstore", "ktstore-migration-tooling", version.toString())
}
