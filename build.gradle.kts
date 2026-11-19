// Veröffentlichung nach Maven Central (Central Portal, Namespace io.github.bitshiftdev).
//
// core und maven-extension wenden `maven-publish` an; hier bekommen beide dieselben POM-Angaben,
// dasselbe Ziel (ein Verzeichnis im Maven-Layout unter build/central-staging) und die Signatur.
// `./gradlew centralBundle` packt das Verzeichnis zu build/central-bundle-<v>.zip – genau das Format,
// das das Central Portal annimmt (Upload: .github/scripts/central-upload.sh, Ablauf: RELEASING.md).
//
// Signatur: -PsigningKey=<ASCII-armored privater Schlüssel> -PsigningPassword=… (in CI über
// ORG_GRADLE_PROJECT_signingKey/…Password) oder lokal -PsigningGpg=true (gpg-Agent, Schlüssel aus
// signing.gnupg.keyName). Ohne Schlüssel bleibt alles unsigniert; nur centralBundle verlangt eine Signatur.

import java.util.concurrent.Callable

/** Module, die nach Central gehen; das IntelliJ-Plugin geht auf den Marketplace. */
val centralModules = listOf(":core", ":maven-extension")

val stagingRepo = layout.buildDirectory.dir("central-staging")

val cleanCentralStaging by tasks.registering(Delete::class) {
    description = "Leert das Staging-Verzeichnis, damit kein alter Stand ins Bundle rutscht."
    delete(stagingRepo)
}

subprojects {
    plugins.withId("maven-publish") {
        apply(plugin = "signing")

        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "centralStaging"
                    url = uri(stagingRepo)
                }
            }
            publications.withType<MavenPublication>().configureEach {
                pom {
                    url.set("https://github.com/BitShiftDev/maven-detective")
                    inceptionYear.set("2026")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("BitShiftDev")
                            name.set("BitShiftDev")
                            url.set("https://github.com/BitShiftDev")
                        }
                    }
                    scm {
                        url.set("https://github.com/BitShiftDev/maven-detective")
                        connection.set("scm:git:https://github.com/BitShiftDev/maven-detective.git")
                        developerConnection.set("scm:git:ssh://git@github.com/BitShiftDev/maven-detective.git")
                    }
                    issueManagement {
                        system.set("GitHub")
                        url.set("https://github.com/BitShiftDev/maven-detective/issues")
                    }
                }
            }
        }

        configure<SigningExtension> {
            val key = providers.gradleProperty("signingKey").orNull
            if (!key.isNullOrBlank()) {
                useInMemoryPgpKeys(key, providers.gradleProperty("signingPassword").orNull ?: "")
            } else if (providers.gradleProperty("signingGpg").orNull == "true") {
                useGpgCmd()
            }
            val publishing = the<PublishingExtension>()
            sign(publishing.publications)
            // Unsigniert veröffentlichen darf nur, wer nicht nach Central will (Fixture-Test, Probe-Bundle)
            setRequired(Callable { gradle.taskGraph.hasTask(":centralBundle") })
        }

        tasks.withType<PublishToMavenRepository>().configureEach {
            mustRunAfter(cleanCentralStaging)
        }
    }
}

// Testabdeckung (JaCoCo) nur auf Wunsch: ./gradlew -Pcoverage :core:test :core:jacocoTestReport
// Fest angewendet bräuchte jeder Testlauf die JaCoCo-Artefakte von Maven Central – offline (Sandbox)
// liegen sie nicht im Cache. CI erzeugt den Bericht als Artefakt, noch ohne Schwelle.
if (providers.gradleProperty("coverage").isPresent) {
    centralModules.forEach { path ->
        project(path) {
            apply(plugin = "jacoco")
            configure<JacocoPluginExtension> { toolVersion = "0.8.14" }
            // jacocoTestReport entsteht erst mit dem java-Plugin des Moduls, das nach diesem Skript kommt
            tasks.withType<JacocoReport>().configureEach {
                reports {
                    xml.required.set(true)
                    csv.required.set(true)
                    html.required.set(true)
                }
            }
        }
    }
}

tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Baut das signierte Bundle für den Upload ins Maven Central Portal."
    dependsOn(cleanCentralStaging)
    dependsOn(centralModules.map { "$it:publishAllPublicationsToCentralStagingRepository" })
    from(stagingRepo) {
        // Metadaten pflegt Central selbst; im Bundle haben sie nichts zu suchen
        exclude("**/maven-metadata*")
    }
    archiveFileName.set("central-bundle-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory)
    doFirst {
        val version = project.version.toString()
        if (version.endsWith("-SNAPSHOT")) {
            throw GradleException("SNAPSHOT-Versionen gehen nicht nach Maven Central (version=$version)")
        }
    }
}
