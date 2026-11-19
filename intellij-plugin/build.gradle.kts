import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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
val pluginVersion = project.version.toString()
val changelogFile = rootProject.layout.projectDirectory.file("CHANGELOG.md")

/**
 * Liest den Abschnitt einer Version aus CHANGELOG.md (Keep-a-Changelog-Format) und macht daraus
 * die Change-Notes für den Marketplace. Bewusst ohne Changelog-Plugin: Das Format ist schmal
 * (Überschriften "### ", Listenpunkte "- ", **fett**, `Code`, *kursiv*, [Text](URL)).
 */
object Changelog {
    private val linkDefinition = Regex("""^\[[^\]]+]: """)

    /** Text unter "## [version]" bis zur nächsten Versionsüberschrift, leer wenn es die Version nicht gibt. */
    fun section(markdown: String, version: String): String {
        val lines = markdown.lines()
        val start = lines.indexOfFirst { it.startsWith("## [$version]") }
        if (start < 0) return ""
        val end = (start + 1 until lines.size)
            .firstOrNull { lines[it].startsWith("## ") || linkDefinition.containsMatchIn(lines[it]) } ?: lines.size
        return lines.subList(start + 1, end).joinToString("\n").trim()
    }

    fun toHtml(markdown: String): String {
        val html = StringBuilder()
        var inList = false
        for (line in markdown.lines()) {
            if (line.startsWith("- ")) {
                if (!inList) html.append("<ul>\n").also { inList = true }
                html.append("<li>").append(inline(line.substring(2))).append("</li>\n")
                continue
            }
            if (inList) html.append("</ul>\n").also { inList = false }
            when {
                line.startsWith("### ") -> html.append("<h3>").append(inline(line.substring(4))).append("</h3>\n")
                line.isNotBlank() -> html.append("<p>").append(inline(line)).append("</p>\n")
            }
        }
        if (inList) html.append("</ul>\n")
        return html.toString()
    }

    private fun inline(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("""`([^`]+)`"""), "<code>$1</code>")
        .replace(Regex("""\*\*(.+?)\*\*"""), "<b>$1</b>")
        .replace(Regex("""\*([^*]+)\*"""), "<i>$1</i>")
        .replace(Regex("""\[([^\]]+)]\(([^)]+)\)"""), "<a href=\"$2\">$1</a>")
}

dependencies {
    implementation(project(":core"))

    intellijPlatform {
        intellijIdea(platformVersion)
        bundledPlugin("org.jetbrains.idea.maven")
        // Modul A5: Java-PSI (Referenzen auflösen, Inspection auf Imports)
        bundledPlugin("com.intellij.java")
        pluginVerifier()
        // Das Maven-Plugin braucht in der Test-IDE das Modul intellij.properties.backend – ohne lädt es nicht
        // (und mit ihm Maven Detective nicht)
        testBundledPlugin("com.intellij.properties")
        // Plattform-Tests (BasePlatformTestCase, CodeInsightTestFixture) und Java-Fixtures (JavaCodeInsightFixtureTestCase)
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Die Plattform-Testklassen erben von junit.framework.TestCase (JUnit 3/4); die Vintage-Engine führt sie
    // neben den JUnit-5-Tests aus. Der Test-Launcher der Plattform lädt JUnit-4-Klassen (TestRule) auch für JUnit 5.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
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

// maven-detective-<version>.zip statt intellij-plugin-<version>.zip (Anhang im GitHub-Release)
tasks.buildPlugin {
    archiveBaseName.set("maven-detective")
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.bitshiftdev.mavendetective"
        name = "Maven Detective"
        version = pluginVersion
        // Abschnitt der aktuellen Version aus CHANGELOG.md; fehlt er (Entwicklungsstand), bleiben die Change-Notes leer
        changeNotes = providers.fileContents(changelogFile).asText
            .map { Changelog.toHtml(Changelog.section(it, pluginVersion)) }
        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = provider { null }
        }
    }
    // Marketplace: Schlüssel und Token nur aus Umgebungsvariablen (GitHub-Secrets im Workflow publish.yml).
    // Zertifikat erzeugen: https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Standard-Kanal; -PpublishChannel=eap o. Ä. für Vorabversionen
        channels = providers.gradleProperty("publishChannel").map { listOf(it) }.orElse(listOf("default"))
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

// Text für das GitHub-Release (release.yml): Abschnitt der aktuellen Version aus CHANGELOG.md als Markdown.
// Schlägt fehl, wenn es den Abschnitt nicht gibt – so geht kein Release ohne Changelog-Eintrag raus.
// Eine SNAPSHOT-Version (Entwicklung, CI auf main) liest "## [Unreleased]"; release.yml lässt sie nie durch.
// Direkt nach einem Release ist "## [Unreleased]" leer – das ist für eine SNAPSHOT-Version kein Fehler.
val releaseNotes by tasks.registering {
    group = "publishing"
    description = "Schreibt den CHANGELOG-Abschnitt der aktuellen Version nach build/release-notes.md."
    val changelog = changelogFile.asFile
    val output = layout.buildDirectory.file("release-notes.md")
    val snapshot = pluginVersion.endsWith("-SNAPSHOT")
    val version = if (snapshot) "Unreleased" else pluginVersion
    inputs.file(changelog)
    inputs.property("version", version)
    outputs.file(output)
    doLast {
        val section = Changelog.section(changelog.readText(), version)
        check(snapshot || section.isNotEmpty()) { "CHANGELOG.md: Abschnitt \"## [$version]\" fehlt oder ist leer" }
        output.get().asFile.writeText(if (section.isEmpty()) "" else section + "\n")
    }
}
