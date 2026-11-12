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

## Stand: Phase 3 – Modul A (A1, A2 fertig)

| Baustein | Status |
| --- | --- |
| Analyse-Kern (`core`, Java 8, ohne Abhängigkeiten) | fertig, 73 Unit-Tests |
| Fehlerkatalog v1 (YAML) | 12 Kategorien aus dem Konzept + „Tests fehlgeschlagen“ |
| Log-Parser (Fallback) | fertig: Maven 3.9-Format, CI-Zeitstempel, ANSI-Farben, `[FATAL]` |
| Maven-Extension (`EventSpy`) | fertig, mit Maven 3.9.11 an 10 Fixture-Projekten getestet |
| „Was hat sich geändert?“ (Schnappschuss-Diff) | fertig in Kern, Extension, CLI (`changes`) und IDE |
| IDE gegen Kommandozeile | fertig: Vergleich im Tool-Window, Hinweis auf der Karte |
| IntelliJ-Plugin | baut gegen IntelliJ IDEA 2025.3; Quick-Fixes: Neubau mit `-U`/`-am`, Runner-JDK, Plugin-Version, `.lastUpdated` löschen, JVM-Option. **In `runIde` noch nicht durchgeklickt** |
| Gate 1 (30 eigene Logs) | **offen** – braucht deine Logs, siehe unten |
| A1 „Warum diese Version?“ | fertig in Kern, CLI (`why`) und IDE (Tab „Dependencies“, Rechtsklick in der `pom.xml`). **In `runIde` noch nicht durchgeklickt** |
| A2 Konflikt-Matrix | fertig in Kern, CLI (`matrix`, `--pin`) und IDE (Tab „Konflikt-Matrix“ mit Quick-Fix „im Parent festlegen“). **In `runIde` noch nicht durchgeklickt** |
| A3–A6 (doppelte Klassen, Exclusions, unused/undeclared, Upgrade-Simulator) | offen |

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
# Woher kommt die Version dieser Dependency? (Modul A1)
java -jar core/build/libs/maven-detective-core-*.jar why com.fasterxml.jackson.core:jackson-databind --project .
# Welche Module nutzen welche Version? (Modul A2)
mvn dependency:tree -Dverbose > tree.txt
java -jar core/build/libs/maven-detective-core-*.jar matrix --project . --tree tree.txt
# … und die Version im gemeinsamen Parent festlegen (ohne Version: die höchste)
java -jar core/build/libs/maven-detective-core-*.jar matrix --project . --pin com.google.guava:guava
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

## Warum diese Version?

Modul A1 beantwortet „Warum steht genau diese Version im Classpath?“ als Kette von Stationen, jede mit Datei und Zeile:

```
Warum com.fasterxml.jackson.core:jackson-databind 2.18.2 in com.acme:api:1.0?
Version 2.18.2: aus dem BOM com.fasterxml.jackson:jackson-bom:2.18.2, importiert über org.springframework.boot:spring-boot-dependencies:3.4.1.
  1. Deklariert ohne Version in api (api/pom.xml) – die Version kommt aus dependencyManagement  → api/pom.xml:21
  2. Importiert BOM org.springframework.boot:spring-boot-dependencies:3.4.1 <version>3.4.1</version> in api (api/pom.xml)  → api/pom.xml:14
  3. Importiert BOM com.fasterxml.jackson:jackson-bom:2.18.2 <version>${jackson-bom.version}</version> in …spring-boot-dependencies:3.4.1  → …/spring-boot-dependencies-3.4.1.pom:2199
       ${jackson-bom.version} = 2.18.2 gesetzt in org.springframework.boot:spring-boot-dependencies:3.4.1  → …/spring-boot-dependencies-3.4.1.pom:79
  4. dependencyManagement: com.fasterxml.jackson.core:jackson-databind <version>${jackson.version.databind}</version> in …jackson-bom:2.18.2  → …/jackson-bom-2.18.2.pom:96
       …
Hinweise:
  - Die Property ${jackson-bom.version} = 2.17.0 in api (api/pom.xml) wirkt hier nicht: Das BOM wird importiert, nicht geerbt, …
```

- **Stationen:** direkte Angabe (gewinnt immer), `dependencyManagement` im Modul oder geerbt aus dem Parent (mit der Parent-Kette), importierte BOMs (auch verschachtelt, auch aus dem Parent eines BOMs), die setzende Property samt überschriebener Werte weiter oben – und ohne Management der Pfad im Baum (nearest wins), die deklarierende POM der transitiven Dependency und jede verdrängte Version mit Grund.
- **Regeln wie Mavens Model-Builder:** Erst erben (Kind gewinnt), dann im Kontext des Kindes interpolieren, dann BOMs importieren (erster Import gewinnt; ein BOM löst seine Properties im eigenen Kontext auf). Deshalb wirkt `jackson-bom.version` unter `spring-boot-starter-parent`, aber nicht bei importiertem `spring-boot-dependencies` – genau darauf weist die Erklärung hin.
- **Quellen:** POMs aus dem Projekt und dem lokalen Repository, nie das Netz. Fehlt ein BOM lokal, nennt die Erklärung das wahrscheinlich zuständige. Profile werden nicht ausgewertet; setzt eines Properties oder `dependencyManagement`, steht das als Hinweis da.
- **Baum:** Die IDE nimmt ihren aufgelösten Baum (Stand des letzten Imports). Auf der Kommandozeile: `mvn dependency:tree -Dverbose > tree.txt`, dann `why g:a --tree tree.txt` (oder `--tree -` aus der Pipe). Weicht der Baum von den POMs ab, sagt die Erklärung das.
- **In der IDE:** Rechtsklick auf eine `<dependency>` in der `pom.xml` → „Warum diese Version?“, oder im Tab „Dependencies“ ein Modul und eine Dependency wählen (mit Suche und Markierung „× verdrängt“). „Als Markdown kopieren“ für Ticket oder Chat.

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

## Konflikt-Matrix

Modul A2 zeigt für den ganzen Reactor, welches Modul welche Version im Classpath hat – Zeile = Artifact, Spalte = Modul. Abweichungen stehen oben, mit Herkunft je Zelle und einem Vorschlag:

```
Konflikt-Matrix: 3 Module, 41 Artifacts, 1 mit abweichenden Versionen

com.google.guava:guava  – 2 Versionen
    32.1.3-jre: billing (direkt angegeben)
    33.3.1-jre: api (dependencyManagement), web (transitiv (Tiefe 2))
    Vorschlag: 33.3.1-jre in parent (/…/pom.xml) unter dependencyManagement festlegen
      ! Direkte Angabe <version>32.1.3-jre</version> in billing überstimmt dependencyManagement – nach dem Pin dort die Version entfernen  → /…/billing/pom.xml:31
```

- **Vorschlag:** die höchste Version (Vergleich nach Mavens Regeln: `rc < SNAPSHOT < Release < sp`) im nächsten gemeinsamen Parent unter `dependencyManagement`. Ein Eintrag dort gilt auch für transitive Vorkommen und gewinnt gegen jedes importierte BOM.
- **Was den Pin überstimmt**, steht dabei: direkte `<version>`-Angaben in Modulen und `dependencyManagement` in Modulen oder Zwischen-Parents – jeweils mit Datei und Zeile.
- **Quick-Fix `pin-version`** ändert genau eine Stelle und lässt Formatierung und Kommentare stehen: vorhandenen Eintrag anpassen (steht dort `${prop}` aus derselben POM, die Property), sonst Eintrag ergänzen, sonst `<dependencyManagement>` anlegen. Haben die Module keinen gemeinsamen Parent im Projekt, sagt die Matrix das statt zu raten.
- **Ohne Baum** (CLI ohne `--tree`, IDE vor dem ersten Import) enthält die Matrix nur direkt deklarierte Dependencies und sagt das.
- **In der IDE:** Tab „Konflikt-Matrix“ mit Suche und „Nur Abweichungen“; abweichende Zellen sind hinterlegt. Unten Herkunft (klickbar), Versionsauswahl und „In <parent> festlegen“ – als ein Undo-Schritt, danach lädt IntelliJ das Maven-Projekt neu. Doppelklick auf eine Zelle öffnet „Warum diese Version?“ für dieses Modul.

## Aufbau

```
core/              Modell, Log-Parser, Event-Format, YAML-Subset, Regel-Engine, Prüfungen, Fix-Ausführung, CLI,
                   Schnappschüsse + Diff (snapshot/), IDE-gegen-Kommandozeile (env/BuildSetup, SetupComparison),
                   Modul A (deps/: PomFile mit Zeilen, PomLocator, EffectiveModel, VersionExplainer, DependencyTreeParser,
                   ConflictMatrix, PomEditor für textgenaue POM-Änderungen, MavenVersion)
maven-extension/   EventSpy: Events als JSON-Zeilen + Diagnose-Karte im Maven-Log (bettet den Kern ein)
intellij-plugin/   Tool-Window „Maven Detective“ (Build Doctor, Dependencies, Konflikt-Matrix), Listener auf Maven-Läufe, Quick-Fixes,
                   Editor-Aktion „Warum diese Version?“;
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
- Phase 3 weiter: A1 auch per Rechtsklick im Maven-Tool-Window und unter External Libraries; A4 Exclusion-Hygiene, A5 Unused/undeclared, A3 doppelte Klassen, A6 Upgrade-Simulator.
- A1 in `runIde` an einem Spring-Boot-Projekt durchklicken (Abnahme laut Konzept: Herkunft jeder Dependency korrekt).
- A2 in `runIde` an einem Multi-Modul-Projekt durchklicken: Abweichung pinnen, Re-Import, Matrix aktualisieren.
- A2-Pin auch als Karte im Build Doctor anbieten (Regel 10 „Laufzeitkonflikt“: verschiedene Versionen über die Module).

## Lizenz

Apache License 2.0, siehe [LICENSE](LICENSE).
