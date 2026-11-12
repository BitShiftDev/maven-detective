# Maven Detective

IntelliJ-Plugin, das erklärt, warum ein Maven-Build scheitert – in Klartext, mit Belegen, Sicherheit und Quick-Fix. Dazu eine Maven-Core-Extension, die dieselbe Diagnose auf der Kommandozeile und in CI liefert.

```
[ERROR] +-- Maven Detective · Build Doctor ----------------------------
[ERROR] | #4 Kaputtes JAR im lokalen Repo · sicher
[ERROR] | Ursache: Die Datei …/broken-maven-plugin-1.0.jar im lokalen Repository ist beschädigt (abgebrochener Download oder volle Platte).
[ERROR] |   - Failed to parse plugin descriptor for com.example.fixture:broken-maven-plugin:1.0 (…): zip END header not found
[ERROR] |   - Lässt sich nicht als ZIP öffnen: zip END header not found -> …/broken-maven-plugin-1.0.jar
[ERROR] | Behebung: Datei löschen und neu laden  (1 Datei: …/broken-maven-plugin-1.0.jar)
[ERROR] +--------------------------------------------------------------
```

## Stand: Phase 2 – Doctor v2

| Baustein | Status |
| --- | --- |
| Analyse-Kern (`core`, Java 8, ohne Abhängigkeiten) | fertig, 45 Unit-Tests |
| Fehlerkatalog v1 (YAML) | 12 Kategorien aus dem Konzept + „Tests fehlgeschlagen“ |
| Log-Parser (Fallback) | fertig: Maven 3.9-Format, CI-Zeitstempel, ANSI-Farben, `[FATAL]` |
| Maven-Extension (`EventSpy`) | fertig, mit Maven 3.9.11 an 10 Fixture-Projekten getestet |
| „Was hat sich geändert?“ (Schnappschuss-Diff) | fertig in Kern, Extension, CLI (`changes`) und IDE |
| IDE gegen Kommandozeile | fertig: Vergleich im Tool-Window, Hinweis auf der Karte |
| IntelliJ-Plugin | baut gegen IntelliJ IDEA 2025.3; Quick-Fixes: Neubau mit `-U`/`-am`, Runner-JDK, Plugin-Version, `.lastUpdated` löschen, JVM-Option. **In `runIde` noch nicht durchgeklickt** |
| Gate 1 (30 eigene Logs) | **offen** – braucht deine Logs, siehe unten |
| Modul A (Dependency-Analyse) | noch nicht begonnen |

## Schnellstart

Voraussetzung: JDK 21 für den Build (erzeugt wird Java-8-Bytecode für Kern und Extension).

```bash
gradle wrapper --gradle-version 9.8.2   # einmalig, danach ./gradlew
./gradlew :core:test                    # Unit-Tests
./gradlew :maven-extension:jar          # build/libs/maven-detective-extension-<version>.jar
./gradlew :maven-extension:fixtureTest  # löst jeden Fehler mit echtem Maven aus (mvn im PATH nötig),
                                        # inkl. „grün → Version geändert → rot“ für den Schnappschuss-Diff
./gradlew :intellij-plugin:runIde       # IntelliJ mit Plugin starten
```

### Kommandozeile

```bash
# Log aus Datei, CI oder Pipe
java -jar core/build/libs/maven-detective-core-*.jar diagnose build.log --project .
mvn verify 2>&1 | java -jar core/build/libs/maven-detective-core-*.jar diagnose -
# Markdown für Ticket oder Chat
java -jar core/build/libs/maven-detective-core-*.jar diagnose build.log --markdown
# Was hat sich seit dem letzten grünen Build geändert?
java -jar core/build/libs/maven-detective-core-*.jar changes --project .
```

### Extension in Maven-Läufen

```bash
mvn -Dmaven.ext.class.path=/pfad/maven-detective-extension.jar verify
# optional: strukturierte Events mitschreiben (macht später die IDE)
mvn -Dmaven.ext.class.path=… -DmavenDetective.eventsFile=target/md-events.jsonl verify
```

Für CI über `.mvn/extensions.xml` muss die Extension erst in ein Repository veröffentlicht werden (noch offen). `-DmavenDetective.report=false` schaltet die Karte ab. Die Extension ändert nie etwas am Build; jeder interne Fehler wird geschluckt.

## Was hat sich geändert?

Nach jedem Lauf legt Maven Detective einen Schnappschuss ab: Maven- und JDK-Version, Profile, Mirrors und je Modul Parent, Plugins, Versions-Properties (`*.version`, `maven.compiler.release` …), Dependencies und ein Hash der Effective POM. Ein grüner Lauf wird die neue Referenz. Scheitert ein späterer Lauf, steht auf der Karte, was sich seitdem geändert hat:

```
[ERROR] | Seit dem letzten grünen Build (2026-11-12 07:45):
[ERROR] |   - JDK 21.0.4 → 17.0.12
[ERROR] |   - Parent org.springframework.boot:spring-boot-starter-parent 3.4.1 → 3.5.0 (2 Module)
[ERROR] |   - Dependency com.fasterxml.jackson.core:jackson-databind 2.18.2 → 2.19.0 (2 Module)
```

- **Wo:** `~/.maven-detective/snapshots/<projekt>-<hash>/` (`green-*.json`, `latest-*.json`), also nie im Git-Status. Verlegen mit `-DmavenDetective.home=…` oder `MAVEN_DETECTIVE_HOME`, abschalten mit `-DmavenDetective.snapshots=false`.
- **Wer schreibt:** die Extension bei jedem Lauf auf Kommandozeile und in CI; die IDE nach jedem Maven-Lauf aus ihrem eigenen Maven-Modell. Kurze grüne Läufe (`validate`, `-pl`) dünnen die Referenz nicht aus.
- **Rauschen vermeiden:** Gleiche Änderungen in mehreren Modulen werden zusammengefasst. Zwischen IDE- und Extension-Schnappschuss zählt nur, was beide gleich messen (JDK-Hauptversion, Maven, Profile, Parent, Versionen).

## IDE gegen Kommandozeile

„Im Terminal geht es, in der IDE nicht“: Der Knopf **IDE gegen Kommandozeile** im Tool-Window stellt gegenüber, womit IntelliJ baut (Maven-Installation, Runner-JDK, settings.xml, explizite Profile, VM-Optionen) und was `./mvnw` bzw. `mvn` im Terminal nehmen würde (`.mvn/wrapper`, `JAVA_HOME`/`PATH`, `.mvn/maven.config`, `.mvn/jvm.config`, `MAVEN_OPTS`). Jede Abweichung bekommt einen Satz, warum sie zählt. Gibt es Abweichungen, nennt die Diagnose-Karte sie direkt.

Die Terminal-Seite wird aus den Umgebungsvariablen der IDE abgeleitet. Eine Shell mit anderem `JAVA_HOME` kann abweichen; das steht auch so im Fenster.

**Warum die IDE die Extension nicht selbst anhängt:** IntelliJ 2025.x startet Maven über das `mvn`-Skript (Registry `maven.use.scripts`) und setzt dabei `-Dmaven.ext.class.path` für den eigenen Event-Listener. Ein zweiter Wert würde ihn überschreiben und den Build-View der IDE leeren. Die IDE nimmt deshalb den Text-Log und ihr Maven-Modell; die Extension bleibt für Terminal und CI.

## Gate 1: mit echten Logs messen

1. Logs der letzten 30 gescheiterten Builds in einen Ordner legen (`*.log` oder `*.txt`).
2. Optional `labels.properties` anlegen: `dateiname.log=regel-id` mit der Ursache, die es *wirklich* war.
3. Auswerten:

```bash
java -jar core/build/libs/maven-detective-core-*.jar evaluate gate1-logs/
```

Die Ausgabe zeigt pro Datei die erkannte Regel und am Ende die Quote „eingeordnet“ sowie – mit Labels – „richtig“. Was als `unknown` endet oder falsch erkannt wird, ist der Kandidat für die nächste Regel.

## Fehlerkatalog erweitern

Der Katalog liegt in [`core/src/main/resources/…/catalog/build-doctor-v1.yaml`](core/src/main/resources/io/github/bitshiftdev/mavendetective/core/catalog/build-doctor-v1.yaml). Eine neue Regel braucht keinen Java-Code, solange sie vorhandene Prüfungen und Quick-Fix-Typen nutzt:

```yaml
  - id: my-new-rule
    number: 14
    category: Kurzer Titel der Karte
    priority: 60            # höher gewinnt, wenn mehrere Regeln passen
    patterns:
      - 'Regex mit (?<artifact>benannter Gruppe)'
    cause: 'Ursache in einem Satz mit {artifact|Ersatztext}.'
    checks: [last-updated-files]
    fix:
      type: rebuild
      label: 'Mit -U neu bauen'
      params:
        args: '-U'
```

Dazu gehört immer ein Testlog unter `core/src/test/resources/logs/<regel-id>__<beschreibung>.log`. `LogCatalogTest` prüft automatisch, dass jede Datei als die Regel im Dateinamen erkannt wird und dass jede Regel mindestens einen Testlog hat. Eigene Kataloge lassen sich mit `--catalog extra.yaml` dazuladen und mit `validate-catalog extra.yaml` prüfen.

Vorhandene Prüfungen: `offline-mode`, `network-failure`, `last-updated-files`, `reactor-sibling`, `requested-repository`, `server-id`, `http-repository`, `corrupt-jar`, `pom-location`, `parent-relative-path`, `child-module`, `plugin-prefix`, `plugin-version`, `jdk-version`, `source-location`, `enforcer-rule`, `memory-settings`. Prüfungen lesen nur Dateien und Einstellungen und gehen nie ins Netz.

## Aufbau

```
core/              Modell, Log-Parser, Event-Format, YAML-Subset, Regel-Engine, Prüfungen, Fix-Ausführung, CLI,
                   Schnappschüsse + Diff (snapshot/), IDE-gegen-Kommandozeile (env/BuildSetup, SetupComparison)
maven-extension/   EventSpy: Events als JSON-Zeilen + Diagnose-Karte im Maven-Log (bettet den Kern ein)
intellij-plugin/   Tool-Window „Maven Detective“, Listener auf Maven-Läufe, Quick-Fixes;
                   IdeMaven kapselt alle Zugriffe auf IntelliJs Maven-API (Adapter-Schicht)
fixtures/          Je Fehlerkategorie ein Projekt, das den Fehler absichtlich auslöst (fixtures.txt),
                   dazu 09-changed-since-green für den Schnappschuss-Diff
```

So entsteht eine Diagnose: Fehlerzeilen sammeln (Events oder Log) → jede Regel prüft ihre Muster und sammelt Variablen → die Prüfungen der Regel laufen → die erste bestätigende Prüfung mit eigener Ursache bestimmt Ursache und ersten Quick-Fix, sonst gilt der Text der Regel. Sicherheit „sicher“ heißt: das Muster ist eindeutig oder eine Prüfung hat die Ursache an einer Datei, Einstellung oder am JDK bestätigt.

## Nächste Schritte

- **Gate 1:** die eigenen 30 Logs mit `evaluate` messen; daraus fehlende Regeln ableiten. Laut Roadmap hätte Gate 1 vor Phase 2 liegen sollen – die Messung steht noch aus.
- Plugin in `runIde` durchklicken: grüner Lauf → POM ändern → roter Lauf, Quick-Fixes `-U`, Runner-JDK, Plugin-Version.
- **Gate 2:** `./gradlew :intellij-plugin:verifyPlugin` gegen die letzten drei IDE-Versionen (lädt die IDEs herunter; läuft in CI).
- Preflight-Check und Log-Navigator aus dem Konzept.
- Extension veröffentlichen, damit `.mvn/extensions.xml` in CI funktioniert.
- Danach Phase 3: Modul A (Why this version, Exclusion-Hygiene, Unused/undeclared).

## Lizenz

Apache License 2.0, siehe [LICENSE](LICENSE).
