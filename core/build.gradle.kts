plugins {
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
}

val mavenVersion: String by project
val mavenResolverVersion: String by project

dependencies {
    // Bewusst keine Laufzeit-Abhängigkeiten: der Kern läuft in der IDE, in der Maven-Extension
    // (Maven-Classloader!) und als CLI. JSON und YAML-Subset sind deshalb selbst implementiert.
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // nur im Test: MavenVersion wird gegen Mavens eigenes ComparableVersion geprüft
    testImplementation("org.apache.maven:maven-artifact:$mavenVersion")
    // nur im Test: Differenztests – EffectiveModel gegen Mavens Model-Builder, DependencyResolver gegen den
    // Collector von Maven Resolver (offline, nur aus einem lokalen Repository im @TempDir)
    testImplementation("org.apache.maven:maven-model-builder:$mavenVersion")
    testImplementation("org.apache.maven:maven-resolver-provider:$mavenVersion")
    testImplementation("org.apache.maven.resolver:maven-resolver-impl:$mavenResolverVersion")
    testImplementation("org.apache.maven.resolver:maven-resolver-util:$mavenResolverVersion")
}

tasks.compileJava {
    // Java-8-Bytecode, weil Maven 3.9 noch auf Java 8 läuft
    options.release.set(8)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options"))
}

tasks.compileTestJava {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    // Die meisten Tests vergleichen deutsche Texte; Englisch prüfen TextsTest und CatalogLanguagesTest gezielt
    systemProperty("mavenDetective.language", "de")
}

tasks.jar {
    archiveBaseName.set("maven-detective-core")
    manifest {
        attributes(
            "Main-Class" to "io.github.bitshiftdev.mavendetective.core.cli.Main",
            "Implementation-Title" to "Maven Detective Core",
            "Implementation-Version" to project.version,
        )
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.javadoc {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        charSet("UTF-8")
        docEncoding("UTF-8")
        // Javadoc ist intern (deutsch, nicht vollständig); fehlende Tags sollen den Release nicht stoppen
        addStringOption("Xdoclint:none", "-quiet")
    }
}

// Maven Central: io.github.bitshiftdev.mavendetective:maven-detective-core – Bibliothek und
// Kommandozeile (java -jar), keine Abhängigkeiten. POM-Angaben und Signatur: build.gradle.kts im Wurzelverzeichnis.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "maven-detective-core"
            from(components["java"])
            pom {
                name.set("Maven Detective Core")
                description.set(
                    "Explains why a Maven build fails: log parser, rule catalog, checks and dependency analysis " +
                        "shared by the Maven Detective extension, CLI and IntelliJ plugin. Runnable as CLI (java -jar)."
                )
            }
        }
    }
}
