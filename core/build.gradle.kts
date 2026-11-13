plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    // Bewusst keine Laufzeit-Abhängigkeiten: der Kern läuft in der IDE, in der Maven-Extension
    // (Maven-Classloader!) und als CLI. JSON und YAML-Subset sind deshalb selbst implementiert.
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
