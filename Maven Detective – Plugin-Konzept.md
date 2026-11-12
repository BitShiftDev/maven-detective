# Maven Detective – Plugin-Konzept

Nov 12, 2026 · @Timo

Maven Detective ist ein IntelliJ-Plugin mit zwei Aufgaben. Es erklärt, warum der Dependency-Graph so aussieht, wie er aussieht. Und wenn der Build scheitert, sagt es in Klartext, was kaputt ist und wie man es behebt.

## Ziel und Abgrenzung

- **Zielgruppe:** Java-Entwickler in Maven-Projekten, vor allem Multi-Modul, die mit IntelliJ IDEA und Maven Helper arbeiten. Das Plugin ist Open Source (Apache 2.0), in Java geschrieben und nur für IntelliJ IDEA gebaut.
- **Kein Ersatz** für Maven Helper oder IntelliJs Dependency Analyzer. Die zeigen Fakten an, Maven Detective ergänzt Ursache und Lösung.
- **Zwei Module:** A = Dependency-Analyse, B = Build Doctor (Fehlerdiagnose bei kaputten Builds).
- **Nebenprodukt:** eine Maven-Core-Extension, die dieselbe Diagnose auch auf der Kommandozeile und in CI liefert.

## Lücken heute

Maven Helper und IntelliJ zeigen, *was* im Graph steht und *dass* ein Build scheitert. Das *Warum* und die Reparatur bleiben Handarbeit.

| Bedarf | Maven Helper | IntelliJ (Bordmittel) | Maven Detective |
| --- | --- | --- | --- |
| Warum gewinnt Version X? | Konflikt sichtbar, Herkunft aus BOM/Parent fehlt | Konflikt sichtbar, Herkunft fehlt | Komplette Kette mit Sprung zur POM-Zeile |
| Versionsunterschiede zwischen Modulen | nur je Modul | nur je Modul | Matrix über den ganzen Reactor |
| Gleiche Klassen unter anderen Koordinaten | nein | nein | Classpath-Scan auf doppelte Klassen |
| Veraltete Exclusions | nein | nein | Erkennung + Quick-Fix |
| Build-Fehler verstehen | nein | Log und Fehlerbaum, keine Ursachenanalyse | Diagnose-Karte mit Ursache und Quick-Fix |
| „Gestern lief es noch“ | nein | nein | Vergleich mit dem letzten grünen Build |
| IDE baut anders als Kommandozeile | nein | nein | Umgebungs-Check (JDK, Maven-Version, settings.xml, Profile) |

## Modul A: Dependency-Analyse

Modul A beantwortet die Frage „Warum steht genau diese Version im Classpath?“ und repariert, was dabei auffällt.

### A1 – Why this version?

- Rechtsklick auf eine Dependency (Baum, `pom.xml` oder External Libraries) öffnet die Entscheidungskette.
- Die Kette zeigt: Pfad im Baum (nearest wins), Eintrag in `dependencyManagement`, importiertes BOM oder Parent, setzende Property.
- Jede Station ist klickbar und springt zur Zeile in der richtigen POM, auch im Parent außerhalb des Projekts.
- **Fertig, wenn:** für jede Dependency eines Spring-Boot-Projekts die Herkunft der Version korrekt angezeigt wird.

### A2 – Konflikt-Matrix über den Reactor

- Tabelle: Zeile = Artifact, Spalte = Modul, Zelle = aufgelöste Version. Abweichungen sind farbig markiert.
- Quick-Fix: „Version im Parent unter `dependencyManagement` festlegen“.

### A3 – Doppelte Klassen

- Prüft, ob dieselbe Klasse in mehreren JARs liegt, auch unter verschiedenen Koordinaten.
- Typische Treffer: `javax.*` und `jakarta.*` nebeneinander, `commons-logging` und `jcl-over-slf4j`, alte und neue Varianten derselben Bibliothek.
- Quick-Fix: passende Exclusion einfügen.

### A4 – Exclusion-Hygiene

- Findet Exclusions, die nichts mehr ausschließen, etwa nach einem Upgrade.
- Inspection direkt in der `pom.xml` mit Quick-Fix „Exclusion entfernen“.

### A5 – Unused und undeclared Dependencies

- Nutzt IntelliJs Code-Index statt Bytecode-Analyse und läuft live im Editor.
- Meldet genutzte, aber nur transitiv vorhandene Dependencies und deklarierte, aber ungenutzte.

### A6 – Upgrade-Simulator

- „Was passiert, wenn ich Version X auf Y hebe?“ zeigt den Unterschied der Bäume vorher und nachher, bevor die POM geändert wird.

## Modul B: Build Doctor

Scheitert ein Build oder der Maven-Sync, zeigt Build Doctor statt 500 Zeilen Log eine Karte: was gescheitert ist, warum, welche Belege es gibt und welcher Klick es behebt.

### So arbeitet Build Doctor

1. **Erfassen:** Eine mitgelieferte Maven-Core-Extension schreibt strukturierte Events (Modul, Mojo, Exception-Kette, angefragtes Repository). Fehlen sie, liest ein Log-Parser die Textausgabe als Fallback.
2. **Einordnen:** Ein Regelkatalog ordnet den Fehler einer Kategorie zu und führt gezielte Prüfungen aus (Dateien in `~/.m2`, `settings.xml`, JDK, Effective POM).
3. **Erklären:** Die Karte nennt die Ursache in einem Satz, zeigt die Belege (Logzeile, geprüfte Datei) und eine Sicherheit (sicher / wahrscheinlich).
4. **Beheben:** Quick-Fix mit einem Klick, danach Build automatisch neu starten.

### Fehlerkatalog für Version 1

| # | Kategorie | Typisches Symptom im Log | Was Build Doctor prüft | Quick-Fix |
| --- | --- | --- | --- | --- |
| 1 | Artifact nicht auflösbar | `Could not resolve dependencies`, `was cached in the local repository` | Welches Repository gefragt wurde, `.lastUpdated`-Dateien, Offline-Modus, greifender Mirror | `.lastUpdated` löschen und mit `-U` neu bauen |
| 2 | Zugriff verweigert | `status code: 401` / `403` | Passt die `server`-ID in `settings.xml` zur Repository-ID? | `settings.xml` an der Stelle öffnen |
| 3 | HTTP-Repository blockiert | `maven-default-http-blocker` | Repository-URL beginnt mit `http://` | URL auf `https` umstellen oder Mirror eintragen |
| 4 | Kaputtes JAR im lokalen Repo | `zip END header not found`, `invalid LOC header` | Prüfsumme lokal gegen Remote | Datei löschen und neu laden |
| 5 | JDK passt nicht | `invalid target release`, `UnsupportedClassVersionError` | JDK des Maven-Runners gegen `maven.compiler.release` und Toolchains | Runner-JDK umstellen |
| 6 | Plugin fehlt oder ohne Version | `No plugin found for prefix`, `'version' ... is missing` | `pluginManagement`, `pluginGroups` | Version einfügen |
| 7 | Ungültige POM | `Non-resolvable parent POM`, `Malformed POM` | `relativePath`, Parent-Version, XML | Zur fehlerhaften Zeile springen |
| 8 | Reactor-Problem | `Child module ... does not exist`, Zyklus, fehlendes Modul bei `-pl` | Modulpfade, Abhängigkeiten zwischen Modulen | Neu bauen mit `-am` |
| 9 | Compiler-Fehler durch Dependency | `package ... does not exist`, `cannot find symbol` | In welchem JAR liegt die Klasse? Falscher Scope? Durch Exclusion entfernt? | Dependency hinzufügen oder Scope ändern |
| 10 | Laufzeitkonflikt in Tests | `NoSuchMethodError`, `NoClassDefFoundError` | Welche JARs enthalten die Klasse, welche Version gewann (Modul A) | Version pinnen |
| 11 | Enforcer-Regel verletzt | `Rule ... failed` | Welche Regel, welche Dependency | Dependency im Baum zeigen |
| 12 | Speicher | `OutOfMemoryError`, `Metaspace` | `MAVEN_OPTS`, `.mvn/jvm.config`, Surefire-`argLine` | Wert vorschlagen und eintragen |

Der Katalog liegt als Datendatei (YAML) im Plugin. Neue Regeln brauchen so keinen Code und können von der Community beigesteuert werden.

### Zusatzfunktionen

- **„Was hat sich geändert?“** Nach jedem grünen Build speichert das Plugin einen Schnappschuss: aufgelöste Dependencies, Hash der Effective POM, JDK, Maven-Version, aktive Profile, Mirrors. Bei einem Fehler zeigt es den Unterschied, etwa „Spring Boot 3.4 → 3.5 über den Parent, JDK 21 → 17“.
- **IDE gegen Kommandozeile:** Vergleicht, womit IntelliJ baut und womit `mvnw` baut: Maven-Version (gebündelt oder Wrapper), JDK, `settings.xml`, Profile, `.mvn/maven.config`. Das klärt „im Terminal geht es, in der IDE nicht“.
- **Preflight-Check:** Auf Knopfdruck vor dem Build: `settings.xml` gültig, Repositories erreichbar, JDK passend. Netzwerkprüfungen laufen nur auf Klick.
- **Log-Navigator:** Blendet Download-Rauschen aus, springt zum ersten echten Fehler und zum gescheiterten Modul.
- **Bericht kopieren:** Fasst Fehler, Ursache und Umgebung als Markdown zusammen, fertig für Ticket oder Chat.
- **Sync-Fehler:** Derselbe Katalog läuft auch, wenn der Maven-Import in IntelliJ scheitert.

## Architektur

&#91;embedded content: Architektur · 4 Quellen, 1 Kern, 2 Ausgaben\]

Der Analyse-Kern ist eine reine Java-Bibliothek. So laufen dieselben Regeln in der IDE und im CI-Build.

- **Build:** Plugin in Java mit dem IntelliJ Platform Gradle Plugin 2.x und Abhängigkeit auf das gebündelte Maven-Plugin (`bundledPlugin("org.jetbrains.idea.maven")`).
- **Maven-Modell:** `MavenProjectsManager.getInstance(project).projects` liefert die Projekte, `dependencyTree` den Baum. Die Knoten tragen Status wie `CONFLICT`, `DUPLICATE` und `EXCLUDED`.
- **Build-Events:** Die Extension implementiert Mavens `EventSpy` und schreibt JSON-Zeilen in eine Datei. IntelliJ hängt sie per `-Dmaven.ext.class.path=…` an jeden Maven-Lauf, CI lädt sie über `.mvn/extensions.xml`.
- **Kompatibilität:** Kern und Extension als Java-8-Bytecode, weil Maven 3.9 noch auf Java 8 läuft.
- **UI:** Tool-Window mit zwei Tabs, `localInspection` für die `pom.xml`, Quick-Fixes als Intentions, Listener auf Maven-Läufe über `ExecutionManager.EXECUTION_TOPIC`.
- **Tests:** Je Fehlerkategorie ein kleines Beispielprojekt, das den Fehler absichtlich auslöst. Eine Regel ist fertig, wenn sie ihr Beispiel korrekt erkennt.

## Roadmap

&#91;embedded content: Roadmap · 5 Phasen, 2 Gates\]

Build Doctor kommt vor der Dependency-Analyse: Er bringt sofort Nutzen, auch ohne das komplexe Dependency-Modell. Die Wochen sind eine grobe Schätzung für ein Nebenprojekt.

- **Gate 1** prüfst du mit echten Daten: Sammle die Logs deiner letzten 30 gescheiterten Builds und miss, wie viele Build Doctor richtig einordnet.
- **Gate 2** sichert ab, dass das Plugin auf den letzten drei IntelliJ-Versionen läuft, bevor es in den Marketplace geht.

## Risiken und offene Fragen

| Risiko | Gegenmaßnahme |
| --- | --- |
| IntelliJs Maven-API ist teils intern und ändert sich zwischen IDE-Versionen | Zugriff in einer Adapter-Schicht kapseln, Plugin Verifier gegen die letzten drei IDE-Versionen in CI |
| Maven 4 ist noch Release Candidate (aktuell rc-6) und braucht Java 17 zur Laufzeit | Extension gegen Maven 3.9 und 4 RC testen, Maven-4-spezifisches erst nach GA |
| Log-Formate ändern sich mit Plugin-Versionen | Strukturierte Events zuerst, Regex-Parser nur als Fallback, Regeln mit Testfällen |
| Falsche Diagnose kostet Vertrauen | Jede Karte zeigt Belege und Sicherheit, „Falsch erkannt“-Knopf erzeugt einen Testfall |
| JetBrains baut Teile selbst ein | Fokus auf Ursache und Reparatur statt Anzeige, Release Notes jeder IDE-Version prüfen |

**Entscheidungen**

- **Sprache:** Java für Plugin, Analyse-Kern und Extension. Das Gradle-Build-Skript darf trotzdem `build.gradle.kts` bleiben.
- **Lizenz:** Open Source unter Apache 2.0, wie Maven Helper. Code auf GitHub, Beiträge zum Fehlerkatalog per Pull Request.
- **Zielplattform:** nur IntelliJ IDEA (Community und Ultimate), keine anderen JetBrains-IDEs.

**Offene Frage**

- [ ] Welche Fehler treten in deinen eigenen Projekten am häufigsten auf? Diese sollten den Katalog für Version 1 bestimmen.

## Quellen

- [Maven Helper auf GitHub](https://github.com/krasa/MavenHelper)
- [Maven 4.0.0-rc-6 Release Notes](https://maven.apache.org/docs/4.0.0-rc-6/release-notes.html)
- [Maven 4.0.0-rc-1 Release Notes (Java 17 als Laufzeit)](https://maven.apache.org/docs/4.0.0-rc-1/release-notes.html)
- [IntelliJ Platform Gradle Plugin 2.x](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
