plugins {
    java
    `maven-publish`
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

    // Unit-Tests: Maven-Objekte direkt erzeugen, ohne Maven-Lauf (der läuft in fixtureTest)
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.apache.maven:maven-core:$mavenVersion")
    testImplementation("org.apache.maven.resolver:maven-resolver-api:$mavenResolverVersion")
    testImplementation("javax.inject:javax.inject:1")
    testImplementation("org.slf4j:slf4j-api:1.7.36")
}

tasks.compileJava {
    options.release.set(8)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-options")
}

tasks.compileTestJava {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    systemProperty("mavenDetective.language", "de")
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

java {
    withJavadocJar()
}

tasks.javadoc {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        charSet("UTF-8")
        docEncoding("UTF-8")
        addStringOption("Xdoclint:none", "-quiet")
    }
}

// Quellen passend zum JAR: Extension samt eingebettetem Kern
val sourcesJar by tasks.registering(Jar::class) {
    archiveBaseName.set("maven-detective-extension")
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
    from(project(":core").layout.projectDirectory.dir("src/main/java"))
    from(project(":core").layout.projectDirectory.dir("src/main/resources"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<Jar>("javadocJar") {
    archiveBaseName.set("maven-detective-extension")
}

// Maven Central: io.github.bitshiftdev.mavendetective:maven-detective-extension. Bewusst ohne
// components["java"]: Der Kern steckt im JAR, die POM darf ihn nicht noch einmal als Abhängigkeit
// nennen (Maven lüde ihn sonst doppelt in den Extension-Realm). So bleibt die POM ohne Abhängigkeiten
// und dasselbe JAR taugt für .mvn/extensions.xml und -Dmaven.ext.class.path.
publishing {
    repositories {
        // Für fixtureTest: Extension so ablegen, wie Maven sie aus Central lädt
        maven {
            name = "fixture"
            url = uri(layout.buildDirectory.dir("fixture-repo"))
        }
    }
    publications {
        create<MavenPublication>("maven") {
            artifactId = "maven-detective-extension"
            artifact(tasks.jar)
            artifact(sourcesJar)
            artifact(tasks.named("javadocJar"))
            pom {
                name.set("Maven Detective Extension")
                description.set(
                    "Maven core extension that explains why a build failed: prints a diagnosis card " +
                        "(cause, evidence, quick fix) at the end of the Maven log. Never changes the build."
                )
            }
        }
    }
}

// Alte Versionen raus, sonst prüft fixtureTest womöglich nicht den aktuellen Stand
tasks.named("publishMavenPublicationToFixtureRepository") {
    val fixtureRepo = layout.buildDirectory.dir("fixture-repo")
    doFirst { delete(fixtureRepo) }
}

// Führt alle Beispielprojekte unter fixtures/ mit einem echten Maven und der Extension aus
// und prüft, ob Build Doctor jeweils die erwartete Regel liefert. Braucht `mvn` im PATH.
tasks.register<Exec>("fixtureTest") {
    group = "verification"
    description = "Löst jeden Fehler aus fixtures/ absichtlich aus und prüft die Diagnose."
    val fixtureRepo = layout.buildDirectory.dir("fixture-repo")
    dependsOn(tasks.jar, "publishMavenPublicationToFixtureRepository")
    workingDir = rootProject.projectDir
    // Windows: "bash" fände zuerst System32\bash.exe (WSL-Starter) – daher Git Bash ausdrücklich
    val bash = providers.environmentVariable("ProgramFiles").map { "$it\\Git\\bin\\bash.exe" }
        .filter { System.getProperty("os.name").startsWith("Windows") && File(it).isFile }
        .getOrElse("bash")
    // Pfade mit / – unter Git Bash zerlegen dirname/basename D:\…-Pfade nicht
    commandLine(
        bash, "fixtures/run-fixtures.sh",
        tasks.jar.get().archiveFile.get().asFile.invariantSeparatorsPath,
        fixtureRepo.get().asFile.invariantSeparatorsPath,
    )
}
