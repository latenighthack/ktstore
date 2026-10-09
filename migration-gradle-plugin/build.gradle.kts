import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish.base") version "0.29.0"
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
gradlePlugin {
    plugins {
        create("storeMigrations") {
            id = "com.latenighthack.ktstore.migrations"
            implementationClass = "com.latenighthack.ktstore.gradle.StoreMigrationsPlugin"
        }
    }
}
dependencies { testImplementation(gradleTestKit()); testImplementation("junit:junit:4.13.2") }
repositories { mavenCentral() }
// Keep Gradle plugin resolution separate from application library version overrides.
group = "com.latenighthack.ktstore.gradle"
version = "0.2.2"

mavenPublishing {
    configureBasedOnAppliedPlugins(true, true)
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    pom {
        name.set("Ktstore migration Gradle plugin")
        description.set("Generate and verify typed Kotlin Multiplatform store migrations.")
        url.set("https://github.com/latenighthack/ktstore")
        licenses {
            license {
                name.set("The Apache Software License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/latenighthack/ktstore.git")
            developerConnection.set("scm:git:ssh://git@github.com/latenighthack/ktstore.git")
            url.set("https://github.com/latenighthack/ktstore")
        }
        developers {
            developer { name.set("Late Night Hack") }
        }
    }
}
