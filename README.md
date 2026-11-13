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

## Stand: Phase 3 – Modul A (A1 bis A6 fertig)

| Baustein | Status |
| --- | --- |
| Analyse-Kern (`core`, Java 8, ohne Abhängigkeiten) | fertig, 149 Unit-Tests |
| Fehlerkatalog v1 (YAML) | 12 Kategorien aus dem Konzept + „Tests fehlgeschlagen“ + „EAR/WAR/EJB-Packaging“ |
| Log-Parser (Fallback) | fertig: Maven 3.9-Format, CI-Zeitstempel, ANSI-Farben, `[FATAL]` |
| Maven-Extension (`EventSpy`) | fertig, mit Maven 3.9.11 an 11 Fixture-Projekten getestet |
| „Was hat sich geändert?“ (Schnappschuss-Diff) | fertig in Kern, Extension, CLI (`changes`) und IDE |
| IDE gegen Kommandozeile | fertig: Vergleich im Tool-Window, Hinweis auf der Karte |
| IntelliJ-Plugin | baut gegen IntelliJ IDEA 2025.3; Tool-Window mit drei Tabs (Übersicht mit Kacheln, Build Doctor, Dependencies mit Navigation), Badge an der Leiste bei rotem Build, Ergebnisse veralten sichtbar nach jedem Maven-Import; Quick-Fixes: Neubau mit `-U`/`-am`, Runner-JDK, Plugin-Version, `.lastUpdated` löschen, JVM-Option. **In `runIde` noch nicht durchgeklickt** |
| Gate 1 (30 eigene Logs) | **offen** – braucht deine Logs, siehe unten |
| A1 „Warum diese Version?“ | fertig in Kern, CLI (`why`) und IDE (Tab „Dependencies“ → „Warum diese Version?“, Rechtsklick in der `pom.xml`). **In `runIde` noch nicht durchgeklickt** |
| A2 Konflikt-Matrix | fertig in Kern, CLI (`matrix`, `--pin`) und IDE (Tab „Dependencies“ → „Konflikte“ mit Quick-Fix „im Parent festlegen“). **In `runIde` noch nicht durchgeklickt** |
| A4 Exclusion-Hygiene | fertig in Kern, CLI (`exclusions`, `--remove`) und IDE (Inspection „Veraltete Exclusion“ in der `pom.xml` mit Quick-Fix). **In `runIde` noch nicht durchgeklickt** |
| A3 Doppelte Klassen | fertig in Kern, CLI (`duplicates`, `--exclude`) und IDE (Tab „Dependencies“ → „Doppelte Klassen“ mit Quick-Fix „Exclusion einfügen“). **In `runIde` noch nicht durchgeklickt** |
| A5 Ungenutzte/undeklarierte Dependencies | fertig in Kern, CLI (`analyze`, `--declare`, `--remove`) und IDE (Tab „Dependencies“ → „Nutzung“, Inspection „Import aus nicht deklarierter Dependency“ live im Editor). **In `runIde` noch nicht durchgeklickt** |
| A6 Upgrade-Simulator | fertig in Kern, CLI (`upgrade`, `--manage`, `--fetch`, `--apply`) und IDE (Tab „Dependencies“ → „Upgrade-Simulator“, Rechtsklick in der `pom.xml` „Upgrade simulieren…“). **In `runIde` noch nicht durchgeklickt** |

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
# Welche Exclusions schließen nichts mehr aus? (Modul A4; Exit-Code 3 = es gibt welche)
java -jar core/build/libs/maven-detective-core-*.jar exclusions --project .
# … und veraltete/doppelte entfernen
java -jar core/build/libs/maven-detective-core-*.jar exclusions --project . --remove
# Welche Klassen liegen in mehreren JARs? (Modul A3; Exit-Code 3 = verschiedene Fassungen zur Laufzeit)
java -jar core/build/libs/maven-detective-core-*.jar duplicates --project . --tree tree.txt
# … und die empfohlenen Exclusions einfügen
java -jar core/build/libs/maven-detective-core-*.jar duplicates --project . --exclude
# Was nutzt der Code, ohne es zu deklarieren – und was ist deklariert, ohne genutzt zu werden? (Modul A5; Exit-Code 3 = Befunde)
java -jar core/build/libs/maven-detective-core-*.jar analyze --project . --tree tree.txt
# … undeklarierte deklarieren und sicher ungenutzte entfernen
java -jar core/build/libs/maven-detective-core-*.jar analyze --project . --declare --remove
# Was ändert sich im Baum, wenn ich jackson-databind auf 2.18.2 hebe? (Modul A6; Exit-Code 3 = Versionen sinken oder neue Konflikte)
java -jar core/build/libs/maven-detective-core-*.jar upgrade com.fasterxml.jackson.core:jackson-databind:2.18.2 --project .
# … genauso für BOM, Parent oder Property; fehlende POMs mit Maven nachladen, danach übernehmen
java -jar core/build/libs/maven-detective-core-*.jar upgrade org.springframework.boot:spring-boot-starter-parent:3.5.0 --fetch
java -jar core/build/libs/maven-detective-core-*.jar upgrade jackson-bom.version=2.18.2 --apply
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
- **In der IDE:** Rechtsklick auf eine `<dependency>` in der `pom.xml` → „Warum diese Version?“, oder im Tab „Dependencies“ unter „Warum diese Version?“ ein Modul und eine Dependency wählen (mit Suche und Markierung „× verdrängt“). „Als Markdown kopieren“ für Ticket oder Chat.

## IDE gegen Kommandozeile

„Im Terminal geht es, in der IDE nicht“: Der Knopf **IDE gegen Kommandozeile** im Tool-Window stellt gegenüber, womit IntelliJ baut (Maven-Installation, Runner-JDK, settings.xml, explizite Profile, VM-Optionen) und was `./mvnw` bzw. `mvn` im Terminal nehmen würde (`.mvn/wrapper`, `JAVA_HOME`/`PATH`, `.mvn/maven.config`, `.mvn/jvm.config`, `MAVEN_OPTS`). Jede Abweichung bekommt einen Satz, warum sie zählt. Gibt es Abweichungen, nennt die Diagnose-Karte sie direkt.

Die Terminal-Seite wird aus den Umgebungsvariablen der IDE abgeleitet. Eine Shell mit anderem `JAVA_HOME` kann abweichen; das steht auch so im Fenster.

**Warum die IDE die Extension nicht selbst anhängt:** IntelliJ 2025.x startet Maven über das `mvn`-Skript (Registry `maven.use.scripts`) und setzt dabei `-Dmaven.ext.class.path` für den eigenen Event-Listener. Ein zweiter Wert würde ihn überschreiben und den Build-View der IDE leeren. Die IDE nimmt deshalb den Text-Log und ihr Maven-Modell; die Extension bleibt für Terminal und CI.

## Java EE: EAR, WAR, EJB

Build Doctor hängt nicht am packaging; Reactors mit `ear`-, `war`- und `ejb`-Modulen werden wie jedes andere Projekt diagnostiziert. Dazu kommen Regeln für die typischen Fehler der Packaging-Plugins (Kategorie 14):

- `Artifact[war:…] is not a dependency of the project`: Modul im `maven-ear-plugin` eingetragen, aber nicht in den `<dependencies>` der EAR-POM. Die Prüfung `ear-module` zeigt Modul-Eintrag, EAR-POM und das Zielmodul im Reactor.
- Dependency auf ein WAR-Modul ohne `<type>war</type>`: Maven sucht dann `…:jar:…` und findet nichts. `reactor-sibling` erkennt das am packaging des Reactor-Moduls und springt zur Dependency.
- `maven-war-plugin` 2.x auf Java 16+, fehlende `web.xml`, fehlender `ejb-jar.xml` bei `ejbVersion` 2.x, unbekannte Plattform-Version im `maven-ear-plugin`.
- Fehlende `javax.*`/`jakarta.*`-Pakete beim Kompilieren: `ee-namespace` erkennt den Wechsel javax → jakarta (Jakarta EE 9) und Pakete, die Java 11 aus dem JDK entfernt hat (`javax.xml.bind`, `javax.annotation`, …).

Grenzen: Die Konflikt-Matrix (A2) vergleicht den Maven-Classpath je Modul, nicht die Classloader im Server. Sie kennt weder `lib/` im EAR gegenüber `WEB-INF/lib` noch `skinnyWars` oder die Bibliotheken des Applikationsservers. Zeilen sind nach `groupId:artifactId` geschlüsselt, `ejb` und `ejb-client` desselben Moduls fallen zusammen.

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
    number: 15
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

Vorhandene Prüfungen: `offline-mode`, `network-failure`, `last-updated-files`, `reactor-sibling`, `requested-repository`, `server-id`, `http-repository`, `corrupt-jar`, `pom-location`, `parent-relative-path`, `child-module`, `plugin-prefix`, `plugin-version`, `plugin-declaration`, `ear-module`, `ee-namespace`, `jdk-version`, `source-location`, `enforcer-rule`, `memory-settings`. Prüfungen lesen nur Dateien und Einstellungen und gehen nie ins Netz.

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
- **In der IDE:** Tab „Dependencies“ → „Konflikte“ mit Suche und „Nur Abweichungen“; abweichende Zellen sind hinterlegt. Unten Herkunft (klickbar), Versionsauswahl und „In <parent> festlegen“ – als ein Undo-Schritt, danach lädt IntelliJ das Maven-Projekt neu. Doppelklick auf eine Zelle öffnet „Warum diese Version?“ für dieses Modul. Nach jedem Maven-Import rechnet die Matrix von selbst neu.

## Exclusion-Hygiene

Modul A4 findet Exclusions, die nichts mehr ausschließen – typisch nach einem Upgrade, wenn die Dependency das ausgeschlossene Artifact gar nicht mehr mitbringt:

```
Exclusion-Hygiene: 7 Exclusions in 4 POMs – 2 veraltet, 1 doppelt, 0 nicht prüfbar

veraltet  /…/web/pom.xml:42
    Exclusion commons-logging:commons-logging schließt nichts aus: org.springframework:spring-core:6.2.1 bringt commons-logging:commons-logging nicht (mehr) transitiv mit – kann weg.
doppelt  /…/web/pom.xml:46
    Exclusion org.deep:deep ist doppelt: org.deep:deep käme nur über org.web:web:1.0 → org.mid:mid:1.0 → org.deep:deep:1.0, und den Pfad kappt schon org.mid:mid.
```

- **So wird geprüft:** Der transitive Baum der Dependency wird aus den POMs im lokalen Repository nachgebaut – ohne die geprüfte Exclusion. Regeln wie bei Maven: nur `compile`/`runtime`, keine `optional`en Dependencies, Exclusions tiefer im Baum gelten weiter, der erste Fund gewinnt, Versionen aus dem `dependencyManagement` des Moduls gehen vor. Parents, Properties und BOM-Importe der Dependency-POMs werden aufgelöst.
- **Befunde:** *veraltet* (Artifact kommt nicht vor), *doppelt* (kommt nur über einen Pfad, den eine andere Exclusion derselben Dependency schon kappt), *wirkt* (mit Pfad; mit `--tree` auch der Hinweis, wenn das Artifact über einen anderen Pfad trotzdem im Classpath landet), *offen* (POMs fehlen lokal – keine Aussage statt einer falschen). Geprüft werden Exclusions unter `dependencies` und `dependencyManagement`, Platzhalter `*` inklusive.
- **Quick-Fix `remove-exclusion`** entfernt genau das `<exclusion>` (bei der letzten samt `<exclusions>`) und lässt den Rest der Datei stehen; Profile bleiben unberührt. `--remove` entfernt eine nach der anderen und prüft nach jeder Änderung neu.
- **In der IDE:** Inspection „Veraltete Exclusion“ (Gruppe Maven Detective) graut veraltete Exclusions in der `pom.xml` aus, doppelte werden schwach markiert; Alt+Enter → „Exclusion … entfernen“ als ein Undo-Schritt. Projektweit über *Code | Inspect Code*. Repository-POMs werden zwischen den Läufen gecacht (`PomCache`, prüft Änderungszeit).

## Doppelte Klassen

Modul A3 findet Klassen, die in mehr als einem JAR eines Moduls liegen – auch unter verschiedenen Koordinaten. Welche Fassung geladen wird, entscheidet die Reihenfolge im Classpath; bei abweichendem Inhalt sind das die `NoSuchMethodError`s, die nur in manchen Umgebungen auftreten:

```
Doppelte Klassen: 3 Funde in 2 Modulen – 2 mit verschiedenem Inhalt, 3 im Laufzeit-Classpath

Modul app (84 Einträge)
  verschieden  2 Klassen in commons-logging:1.2 und jcl-over-slf4j:1.7.36 – alle verschieden; geladen wird die aus commons-logging:1.2.
      1. commons-logging:1.2  (über org.web:web:1.0, compile)
         jcl-over-slf4j:1.7.36  (direkt, compile)
      Empfehlung: commons-logging:1.2 entfernen: bekanntes Paar – jcl-over-slf4j bzw. spring-jcl ersetzen commons-logging absichtlich …
        → Exclusion commons-logging:commons-logging bei org.web:web einfügen
```

- **Classpath:** mit Baum (IDE-Modell oder `--tree`) genau die Knoten, die Maven aufgenommen hat, in Mavens Reihenfolge (Tiefe zuerst); ohne Baum aus den POMs im lokalen Repository nachgebaut (nearest wins, bei Gleichstand die zuerst deklarierte) – dann ist die Reihenfolge geschätzt. Dazu `target/classes` des Moduls selbst und anderer Reactor-Module. JARs, die lokal fehlen, werden genannt statt geraten.
- **Vergleich:** Klassennamen und CRC32 aus dem Zentralverzeichnis der JARs (nichts wird entpackt; gecacht nach Änderungszeit und Größe). Innere Klassen, `module-info`, `package-info` und `META-INF/` zählen nicht. Funde sind nach JAR-Gruppe zusammengefasst, mit Anzahl gleicher und verschiedener Klassen; *nur Tests*, wenn höchstens ein beteiligtes JAR zur Laufzeit dabei ist.
- **Empfehlung:** zuerst eine Tabelle bekannter Paare (`commons-logging`/`jcl-over-slf4j`/`spring-jcl`, `log4j`/`log4j-over-slf4j`, `javax.xml.bind`/`jakarta.xml.bind-api` 2.x, `javax.activation`, `javax.annotation`, `javax.validation`, `servlet-api`, `jsr311-api`, `google-collections`/`guava`, `asm:asm`, `hamcrest-core`, `bcprov-jdk15on`, `stax-api`). Sonst: ein JAR, dessen Klassen alle auch in anderen liegen, kann ohne Verlust weg – bei gleichem Umfang das, das heute verliert (die Laufzeit ändert sich nicht). Bringen beide eigene Klassen mit, bleibt es eine Entscheidung; angezeigt wird, welches heute gewinnt.
- **Quick-Fix `add-exclusion`** fügt die Exclusion bei jeder direkten Dependency ein, über die das JAR kommt (auch über verdrängte Pfade, sonst käme es dort wieder hoch) – in vorhandene `<exclusions>` oder neu vor `</dependency>`, Einrückung wie in der Datei. Steht die Dependency im Parent, kommt die Exclusion dorthin (gilt dann für alle erbenden Module). Ist das JAR direkt deklariert, springt der Fix zur Deklaration.
- **In der IDE:** Tab „Dependencies“ → „Doppelte Klassen“ – prüft erst auf Klick „Prüfen“ (öffnet alle JARs); nach einem Maven-Import steht oben „veraltet – Neu prüfen“. Filter für nur-Test und inhaltsgleiche Funde, Suche auch nach Klassennamen; unten Reihenfolge, Pfade, Klassen und „Aus dem Classpath nehmen“ mit freier Wahl des JARs (vorbelegt mit der Empfehlung). Alle Exclusions eines Klicks sind ein Undo-Schritt, danach Maven-Import.

## Ungenutzte und undeklarierte Dependencies

Modul A5 vergleicht, welche Klassen der Quelltext eines Moduls **beim Namen nennt**, mit dem, was die `pom.xml` deklariert. Jede genannte Klasse wird dem Classpath-Eintrag zugeordnet, aus dem der Compiler sie nimmt (Classpath wie bei A3):

```
Dependency-Nutzung: 1 undeklariert, 1 ungenutzt, 1 nur in Tests – 2 Module
Grundlage: Klassen, die der Quelltext beim Namen nennt (Imports, voll qualifizierte Namen).

Modul app (42 Quell-, 17 Testdateien)
  undeklariert  slf4j-api:2.0.16 ist nicht deklariert, wird aber genannt: org.slf4j.Logger und 1 weitere Klasse – kommt nur transitiv über web:1.0.
      Pfad: org.web:web:1.0 → org.slf4j:slf4j-api:2.0.16
      genannt: org.slf4j.Logger (App.java:5)
      genannt: org.slf4j.LoggerFactory (App.java:5)
        → org.slf4j:slf4j-api in app deklarieren
  nur in Tests  assertj-core:3.26.3 (Scope compile) wird nur in Tests genannt, z. B. org.assertj.core.api.Assertions – Scope test genügt.
        → Scope von org.assertj:assertj-core auf test setzen
  ungenutzt     commons-lang3:3.17.0 ist deklariert, aber keine seiner Klassen wird im Code genannt.
        → org.apache.commons:commons-lang3 aus app entfernen
```

- **undeklariert:** genannt, aber nur transitiv da – fällt die vermittelnde Dependency weg oder ändert sie ihre Abhängigkeiten, bricht der Build, ohne dass sich die eigene POM geändert hat. Quick-Fix `add-dependency` deklariert sie im Modul: ohne Version, wenn sie verwaltet ist (Parent, BOM), mit Scope `test`, wenn nur Tests sie nennen. Kommt die Klasse über einen **Starter ohne eigene Klassen** (`spring-boot-starter-web` → `spring-web`), wird das nur mit `--all` bzw. „Auch über Starter“ gezeigt – dort ist Deklarieren üblich nicht nötig.
- **ungenutzt:** deklariert, aber keine Klasse daraus genannt. Starter zählen als genutzt, wenn etwas genannt wird, das sie mitbringen. Nie gemeldet werden Scope `runtime` und bekannte Laufzeit-Bibliotheken (JDBC-Treiber, Logging-Backends und -Brücken, Test-Engines, Annotation-Prozessoren, `spring-boot-devtools` …). Bringt das JAR Registrierungen mit (`META-INF/services`, `spring.factories`, `META-INF/spring/*.imports`, `web-fragment.xml`, `beans.xml`), wirkt es womöglich ohne Nennung – dann *unsicher* (`?`), ohne Exit-Code 3 und ohne `--remove`. Quick-Fix `remove-dependency`; ist die Dependency im Parent deklariert, springt der Fix nur dorthin (gilt für alle erbenden Module).
- **nur in Tests:** Scope `compile`/`provided`, aber nur Testquellen nennen sie. Quick-Fix `set-scope` setzt `test`.
- **Was „genannt“ heißt:** Imports (auch statische und `import a.b.*` samt der Namen der Datei), voll qualifizierte Namen, in der IDE jede aufgelöste Typ-Referenz. Nicht gezählt: Kommentare, Zeichenketten, Reflection, Klassen, die nur über Typinferenz vorkommen. Reactor-Module, die noch nicht gebaut sind, werden aus ihren Quelldateien erschlossen. Ohne Quelldateien wird „ungenutzt“ nicht geprüft.
- **Kommandozeile:** Text-Scanner über `src/{main,test}/{java,kotlin,groovy}` und `target/generated(-test)-sources` – ohne Compiler. `--declare` deklariert die undeklarierten (ohne Starter-Fälle), `--remove` entfernt sicher ungenutzte aus dem eigenen POM; ein Durchgang, danach neu prüfen.
- **In der IDE:** Tab „Dependencies“ → „Nutzung“ – prüft auf Klick (nach einem Maven-Import als veraltet markiert), löst die Referenzen über den Code-Index auf (Java über die PSI, Kotlin/Groovy über den Text-Scanner), Belege mit Sprung zur Zeile, Mehrfachauswahl, „Beheben“ als ein Undo-Schritt mit anschließendem Maven-Import. Live im Editor markiert die Inspection „Import aus nicht deklarierter Dependency“ (Gruppe Maven Detective) solche Imports, Alt+Enter deklariert die Dependency; Starter-Fälle per Option.

## Upgrade-Simulator

Modul A6 beantwortet „Was passiert, wenn ich X auf Version Y hebe?“ – **bevor** die POM geändert wird:

```
Upgrade-Simulation: org.apache.maven:maven-model 3.1.1 → 3.2.5 (POMs unverändert)

Änderung an den POMs:
  1. Property ${maven.version} von 3.1.1 auf 3.2.5 gesetzt (wirkt auf alle Stellen, die sie nutzen)  → /…/pom.xml:7
     Direkt angegeben in demo über die Property ${maven.version}, definiert in demo – alles, was sie nutzt, ändert sich mit

Modul demo: 5 gehoben
  ↑ org.apache.maven:maven-model 3.1.1 → 3.2.5 (direkt)
  ↑ org.apache.maven:maven-plugin-api 3.1.1 → 3.2.5 (direkt)
  ↑ org.codehaus.plexus:plexus-utils 3.0.15 → 3.0.20 über org.apache.maven:maven-model:3.2.5 → org.codehaus.plexus:plexus-utils:3.0.20
  ↑ org.apache.maven:maven-artifact 3.1.1 → 3.2.5 über org.apache.maven:maven-plugin-api:3.2.5 → org.apache.maven:maven-artifact:3.2.5
  ↑ org.eclipse.sisu:org.eclipse.sisu.plexus 0.0.0.M5 → 0.3.0.M1 über …

Unvollständig – lokal fehlen 2 POMs: org.codehaus.plexus:plexus-utils:3.0.20, org.eclipse.sisu:org.eclipse.sisu.plexus:0.3.0.M1
  Was darunter hängt, fehlt in der Simulation. Laden mit --fetch (mvn dependency:collect) und erneut simulieren.
```

- **Die richtige Stelle:** Zuerst sucht der Simulator, was die Version heute festlegt, und ändert genau das – wie man es von Hand täte: die Property (auch eine aus einem Parent außerhalb des Projekts, etwa `spring-boot-starter-parent` – dann wird sie im Projekt überschrieben), die direkte `<version>`, den Eintrag im `dependencyManagement`, den BOM-Import (kommt das Artifact aus einem BOM mit derselben Versionsnummer, wird das BOM gehoben, z. B. `jackson-bom`) oder den `<parent>`. Kommt das Artifact nur transitiv, wird es im `dependencyManagement` der obersten Projekt-POM festgelegt; `--manage` bzw. „Im dependencyManagement festlegen“ erzwingt das. Ziel kann auch direkt ein BOM, ein Parent oder eine Property (`name=wert`) sein.
- **Vorher und nachher gleich gerechnet:** Die Änderung wird nur im Speicher auf den POM-Text angewendet (derselbe `PomEditor` wie beim Übernehmen). Dann baut der Kern den kompletten Baum jedes Moduls zweimal aus POMs nach – mit den Regeln von Mavens Collector: nearest wins, bei gleicher Tiefe die erste Deklaration, `dependencyManagement` für Version, Scope und Exclusions transitiver Dependencies, Scope-Ableitung, `optional`/`test`/`provided` nicht transitiv. Weil beide Bäume mit demselben Verfahren entstehen, kommen Unterschiede nur von der Änderung.
- **Was gezeigt wird:** je Modul gehobene (`↑`), gesenkte (`↓`), neue (`+`), wegfallende (`−`) Artifacts und geänderte Scopes, jeweils mit Pfad; dazu **neue Konflikte**, bei denen eine verlangte höhere Version verliert (Gefahr von `NoSuchMethodError`). Bleibt das Artifact in einem Modul trotzdem alt (etwa weil dort eine direkte `<version>` steht), sagt ein Hinweis warum. Liegt der echte Baum vor (IDE-Modell oder `--tree`), meldet der Simulator, wo er schon heute davon abweicht (Profile, Versionsbereiche).
- **Fehlende POMs:** Die neue Version liegt oft noch nicht im lokalen Repository. Dann ist das Ergebnis als unvollständig markiert und nennt die Koordinaten. `--fetch` bzw. „Fehlende POMs laden“ ruft Maven (`./mvnw` oder die Maven-Installation der IDE) mit `dependency:collect` auf einer temporären POM auf – lädt nur POMs, übernimmt die `<repositories>` des Projekts – und simuliert erneut, bis nichts mehr fehlt (höchstens vier Runden). Das ist der einzige Netzzugriff in Modul A und passiert nur auf ausdrücklichen Wunsch.
- **Übernehmen:** `--apply` bzw. „Übernehmen“ schreibt genau die simulierte Änderung (Quick-Fix `set-version` bzw. `pin-version`); in der IDE als ein Undo-Schritt mit anschließendem Maven-Import.
- **In der IDE:** Tab „Dependencies“ → „Upgrade-Simulator“ – Artifact aus dem Projekt wählen (Vorschläge aus den Bäumen, Parents und BOM-Importen), Zielversion aus den lokal vorhandenen oder eintippen. Rechtsklick in der `pom.xml` auf eine `<dependency>` oder den `<parent>` → „Upgrade simulieren…“ belegt beides vor und simuliert gleich, wenn lokal eine neuere Version liegt.
- **Grenzen:** Profile, Versionsbereiche und Relocations werden nicht ausgewertet; `${revision}` als Parent-Version und zusammengesetzte Versionen (`${a}.${b}`) ändert der Simulator nicht selbst, er meldet die Stelle.

## Aufbau

```
core/              Modell, Log-Parser, Event-Format, YAML-Subset, Regel-Engine, Prüfungen, Fix-Ausführung, CLI,
                   Schnappschüsse + Diff (snapshot/), IDE-gegen-Kommandozeile (env/BuildSetup, SetupComparison),
                   Modul A (deps/: PomFile mit Zeilen, PomLocator, EffectiveModel, VersionExplainer, DependencyTreeParser,
                   ConflictMatrix, ExclusionAudit, ModuleClasspath, JarIndex, DuplicateClasses,
                   ClassUsages, SourceScanner, DependencyUsage, DependencyResolver, UpgradeSimulator, PomFetcher,
                   PomEditor für textgenaue POM-Änderungen, MavenVersion, PomCache)
maven-extension/   EventSpy: Events als JSON-Zeilen + Diagnose-Karte im Maven-Log (bettet den Kern ein)
intellij-plugin/   Tool-Window „Maven Detective“ mit drei Tabs: Übersicht (Kacheln, „Alles prüfen“), Build Doctor,
                   Dependencies (Navigation: Warum diese Version?, Konflikte, Doppelte Klassen, Nutzung,
                   Upgrade-Simulator; AnalysisResults hält den Stand, Navigator springt), Listener auf Maven-Läufe, Quick-Fixes, Editor-Aktionen „Warum diese Version?“ und
                   „Upgrade simulieren…“, Inspections „Veraltete Exclusion“
                   und „Import aus nicht deklarierter Dependency“ (IdeUsages löst Referenzen über den Code-Index auf);
                   IdeMaven kapselt alle Zugriffe auf IntelliJs Maven-API (Adapter-Schicht);
                   ui/: DetectivePanel (Basis aller Seiten: Toolbar aus Aktionen, Ladeanzeige, Statuszeile,
                   Hintergrund mit Abbruch, F4/Strg+C/Kontextmenü auf Zeilen), DetectiveActions, HtmlView, FixBar;
                   Texte in messages/MavenDetectiveBundle.properties
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
- Phase 3 weiter: A1 auch per Rechtsklick im Maven-Tool-Window und unter External Libraries.
- A6 in `runIde` an einem Spring-Boot-Projekt durchklicken: Parent 3.4 → 3.5 simulieren, fehlende POMs laden, Unterschied prüfen, übernehmen, nach dem Import erneut simulieren (kein Unterschied mehr).
- A6 schärfen: Simulation gegen `mvn dependency:tree -Dverbose` an echten Projekten vergleichen (Abweichungen zeigt der Simulator schon an); Upgrade-Kandidaten aus den Repository-Metadaten vorschlagen (nur auf Klick, Netz); Simulator als Beleg auf der Build-Doctor-Karte („nach dem Upgrade von X kam Y 2.0 → 1.4“).
- A1 in `runIde` an einem Spring-Boot-Projekt durchklicken (Abnahme laut Konzept: Herkunft jeder Dependency korrekt).
- A2 in `runIde` an einem Multi-Modul-Projekt durchklicken: Abweichung pinnen, Re-Import, Matrix aktualisieren.
- A4 in `runIde` an einem Spring-Boot-Projekt durchklicken: eine bekannte veraltete Exclusion (z. B. `commons-logging` bei `spring-core` 6) wird ausgegraut, Quick-Fix entfernt sie.
- A3 in `runIde` an einem Spring-Boot-Projekt durchklicken: z. B. `commons-logging` neben `spring-jcl` erzwingen, Fund erscheint mit Empfehlung, „Exclusion einfügen“ ändert die POM, nach dem Import ist der Fund weg.
- A3 erweitern: Versionspaare in die Tabelle (z. B. `plexus-utils` < 4 neben `plexus-xml` 3 → plexus-utils heben statt ausschließen); doppelte Klassen als Beleg auf der Build-Doctor-Karte bei `NoSuchMethodError`/`NoClassDefFoundError` in Tests.
- A5 in `runIde` an einem Spring-Boot-Projekt durchklicken: Import aus `spring-web` ohne Deklaration (mit „Auch über Starter“), ungenutzte Dependency entfernen, compile → test; prüfen, wie lange „Prüfen“ bei großen Modulen braucht.
- A5 erweitern: Kotlin über die Kotlin-PSI statt Text-Scanner; die Liste bekannter Laufzeit-Bibliotheken an echten Projekten schärfen; „ungenutzt“ live in der `pom.xml` (braucht einen modulweiten Cache der Nennungen).
- Konflikt-Matrix mit Deployment-Sicht für EARs: `lib/` gegen `WEB-INF/lib`, `skinnyWars`, `provided` vom Server; `ejb-client` als eigene Zeile.
- A2-Pin auch als Karte im Build Doctor anbieten (Regel 10 „Laufzeitkonflikt“: verschiedene Versionen über die Module).

## Lizenz

Apache License 2.0, siehe [LICENSE](LICENSE).
