plugins {
    java
}

repositories {
    mavenCentral()
}

val mavenVersion: String by project
val mavenResolverVersion: String by project

dependencies {
    implementation(project(":core"))

    // Stellt Maven zur Laufzeit bereit (Core-Extension-Realm)
    compileOnly("org.apache.maven:maven-core:$mavenVersion")
    compileOnly("org.apache.maven.resolver:maven-resolver-api:$mavenResolverVersion")
    compileOnly("javax.inject:javax.inject:1")
    compileOnly("org.slf4j:slf4j-api:1.7.36")
}

tasks.compileJava {
    options.release.set(8)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-options")
}

tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    // Ein einziges JAR: der Kern wird eingebettet, damit -Dmaven.ext.class.path=<jar> reicht.
    archiveBaseName.set("maven-detective-extension")
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Führt alle Beispielprojekte unter fixtures/ mit einem echten Maven und der Extension aus
// und prüft, ob Build Doctor jeweils die erwartete Regel liefert. Braucht `mvn` im PATH.
tasks.register<Exec>("fixtureTest") {
    group = "verification"
    description = "Löst jeden Fehler aus fixtures/ absichtlich aus und prüft die Diagnose."
    dependsOn(tasks.jar)
    workingDir = rootProject.projectDir
    commandLine("bash", "fixtures/run-fixtures.sh", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}
