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
        // Modul A5: Java-PSI (Referenzen auflösen, Inspection auf Imports)
        bundledPlugin("com.intellij.java")
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
    // Gate 2: Plugin Verifier gegen alle IntelliJ-IDEA-Releases ab sinceBuild (je Linie die neueste).
    // Alle auf einmal sprengen die Platte eines CI-Runners (IDEs plus nachgeladene Plugins wie
    // Android/Rust). Die CI prüft deshalb je Release-Linie in einem eigenen Job:
    //   -PverifySince=261 -PverifyUntil=261.*   → nur 2026.1
    //   -PverifySince=263                       → 2026.3 und alles Neuere
    val verifySince = providers.gradleProperty("verifySince").orElse(pluginSinceBuild)
    val verifyUntil = providers.gradleProperty("verifyUntil")
    pluginVerification {
        ides {
            select {
                types = listOf(IntelliJPlatformType.IntellijIdea)
                sinceBuild = verifySince
                untilBuild = verifyUntil
            }
        }
    }
}
