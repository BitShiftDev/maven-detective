import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    java
    // Aktuelle Version prüfen: https://plugins.gradle.org/plugin/org.jetbrains.intellij.platform
    id("org.jetbrains.intellij.platform") version "2.12.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val platformVersion: String by project
val pluginSinceBuild: String by project

dependencies {
    implementation(project(":core"))

    intellijPlatform {
        intellijIdea(platformVersion)
        bundledPlugin("org.jetbrains.idea.maven")
        pluginVerifier()
    }

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Der Test-Launcher der IntelliJ-Plattform lädt JUnit-4-Klassen (TestRule), auch für JUnit-5-Tests
    testRuntimeOnly("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.bitshiftdev.mavendetective"
        name = "Maven Detective"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = provider { null }
        }
    }
    // Gate 2: Plugin Verifier gegen die letzten drei IntelliJ-IDEA-Versionen
    pluginVerification {
        ides {
            select {
                types = listOf(IntelliJPlatformType.IntellijIdea)
                sinceBuild = pluginSinceBuild
            }
        }
    }
}
