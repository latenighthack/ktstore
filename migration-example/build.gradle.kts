import com.latenighthack.ktstore.gradle.StoreMigrationsExtension
plugins { alias(libs.plugins.kotlinMultiplatform); id("com.latenighthack.ktstore.migrations") }
evaluationDependsOn(":migration-tooling")
evaluationDependsOn(":migration-example-spec")
kotlin {
    jvm()
    sourceSets {
        commonMain {
            kotlin.srcDir("src/migrations/main")
            dependencies { implementation(project(":migration-example-spec")) }
        }
        jvmMain {
            kotlin.srcDir("src/migrations/test")
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation("org.xerial:sqlite-jdbc:3.45.3.0")
            }
        }
    }
}
val tooling = project(":migration-tooling")
val spec = project(":migration-example-spec")
configure<StoreMigrationsExtension> {
    providerClass.set("example.UsersHistory")
    specificationReference.set("example.UsersHistory")
    packageName.set("example.generated")
    toolClasspath.from(tooling.configurations.named("jvmRuntimeClasspath"), tooling.layout.buildDirectory.dir("classes/kotlin/jvm/main"))
    toolClasspath.from(spec.configurations.named("jvmRuntimeClasspath"), spec.layout.buildDirectory.dir("classes/kotlin/jvm/main"))
    toolClasspath.builtBy(tooling.tasks.named("jvmMainClasses"), spec.tasks.named("jvmMainClasses"))
    historicalInputs.from(listOf("UsersV1.kt", "UsersV2.kt", "UsersMapping.kt", "UsersFixtures.kt").map { spec.file("src/commonMain/kotlin/example/$it") })
    historicalRoot.set(rootProject.layout.projectDirectory)
    verificationClasspath.from(configurations.named("jvmRuntimeClasspath"), layout.buildDirectory.dir("classes/kotlin/jvm/main"))
    verificationClasspath.builtBy(tasks.named("jvmMainClasses"))
    verificationMainClass.set("example.VerifyKt")
}
