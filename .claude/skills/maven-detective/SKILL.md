---
name: maven-detective
description: Projektwissen zu Maven Detective (IntelliJ-Plugin + Maven-Core-Extension + Java-8-Kern, der gescheiterte Maven-Builds diagnostiziert). Nutze diesen Skill bei jeder Arbeit in diesem Repo – neue Regel im Fehlerkatalog, neue Prüfung (Check), neuer Quick-Fix-Typ, Log-Parser, Schnappschuss-Diff, IDE-gegen-Kommandozeile, Fixtures, CLI (diagnose/evaluate/changes/validate-catalog/why/matrix/exclusions/duplicates), IntelliJ-Plugin oder CI – und wenn von Build Doctor, Diagnose-Karte, Gate 1/2 oder Modul A (Warum diese Version?, Konflikt-Matrix, doppelte Klassen, Exclusions) die Rede ist.
---

# Maven Detective

Erklärt, warum ein Maven-Build scheitert: Diagnose-Karte mit Ursache, Belegen, Sicherheit (`certain`/`likely`) und Quick-Fix. Sprache im Projekt (Code-Kommentare, Texte, Commits, README): **Deutsch**. Paket-Präfix `io.github.bitshiftdev.mavendetective`.

## Module

| Modul | Zweck | Java |
| --- | --- | --- |
| `core` | Modell, Log-Parser, Event-Format, YAML-Subset, Regel-Engine, Prüfungen, Fix-Ausführung, CLI, Schnappschüsse (`snapshot/`), IDE-vs-CLI (`env/`), Modul A (`deps/`) | **Bytecode Java 8** (`release 8`), Tests Java 17 |
| `maven-extension` | `DetectiveEventSpy`: Events als JSONL + Karte im Maven-Log; bettet `core` ins JAR ein | Java 8 |
| `intellij-plugin` | Tool-Window „Maven Detective“, `MavenRunListener`, Quick-Fixes (`FixRunner`) | Toolchain 21, gegen IntelliJ 2025.3 (`sinceBuild 253`) |
| `fixtures/` | Ein Projekt je Fehlerkategorie, das den Fehler absichtlich auslöst | – |

Konzept/Roadmap: `Maven Detective – Plugin-Konzept.md` (Modul A Dependency-Analyse, Modul B Build Doctor, Gates). Stand und Nutzung: `README.md`.

## Harte Regeln

- **`core` hat keine Laufzeit-Abhängigkeiten.** Er läuft im Maven-Classloader; JSON (`io/Json`) und YAML (`io/MiniYaml`) sind selbst geschrieben. Keine Bibliothek hinzufügen.
- **Java-8-API in `core` und `maven-extension`**: kein `var`, keine Records, kein `List.of`, kein `String.isBlank`, keine Text-Blocks, Diamond nur wo Java 8 es erlaubt (Code nutzt explizite Typargumente `new ArrayList<String>()` – Stil beibehalten). CI-Job `extension-on-java8` führt die Fixtures unter Java 8 aus.
- **Prüfungen lesen nur** Dateien/Einstellungen, ändern nichts, gehen nie ins Netz.
- **Extension ändert nie den Build** und schluckt jeden internen Fehler.
- **IntelliJ-Maven-API nur über `IdeMaven`** (Adapter-Schicht) ansprechen.
- IDE hängt die Extension **nicht** an: IntelliJ setzt `-Dmaven.ext.class.path` selbst für seinen Listener; ein zweiter Wert würde ihn überschreiben. IDE nutzt Text-Log + eigenes Maven-Modell.

## Bauen und testen

```bash
./gradlew :core:test                    # Unit-Tests inkl. LogCatalogTest
./gradlew :maven-extension:jar          # maven-extension/build/libs/maven-detective-extension-<v>.jar
./gradlew :maven-extension:fixtureTest  # echtes mvn im PATH nötig
./gradlew :intellij-plugin:buildPlugin
./gradlew :intellij-plugin:verifyPlugin # Gate 2, lädt IDEs herunter
./gradlew :intellij-plugin:runIde
```

In der Claude-Sandbox (`~/.gradle` und `/tmp` nur lesbar, kein X11): entpackte Distribution `~/.gradle/wrapper/dists/gradle-*/…/bin/gradle --offline` mit `GRADLE_USER_HOME=$TMPDIR/gh GRADLE_RO_DEP_CACHE=~/.gradle/caches`, `JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMPDIR/jtmp -Djava.awt.headless=true"`, `DISPLAY=`; `buildPlugin -x buildSearchableOptions`.

Build braucht JDK 21. Versionen in `gradle.properties` (`mavenVersion=3.9.11`, `platformVersion=2025.3`).

## Diagnose-Ablauf

Fehlerzeilen sammeln (Events-JSONL oder Text-Log via `MavenLogParser`) → jede Regel prüft `patterns` (benannte Gruppen → Variablen) → Prüfungen aus `checks` laufen in Reihenfolge → erste **bestätigende** Prüfung mit eigener Ursache ersetzt `cause` und liefert den ersten Quick-Fix, sonst gilt der Regeltext. Bei mehreren passenden Regeln gewinnt die höhere `priority`.

## Neue Regel (Fehlerkatalog)

Datei: `core/src/main/resources/io/github/bitshiftdev/mavendetective/core/catalog/build-doctor-v1.yaml`. Felder sind im Dateikopf dokumentiert (`id`, `number`, `category`, `priority`, `confidence`, `patterns`, `extract`, `unless`, `checks`, `cause`, `fix`).

1. Regel eintragen. Regex **immer in 'einfachen Anführungszeichen'** (Apostroph als `''`). MiniYaml kann keine Anker, Tags, Flow-Mappings, Mehrfach-Dokumente.
2. Platzhalter in `cause`: `{var}`, `{var|Ersatztext}`, `{a,b|Ersatz}`.
3. **Pflicht:** Testlog `core/src/test/resources/logs/<regel-id>__<beschreibung>.log`. `LogCatalogTest` prüft, dass jede Datei als die Regel im Namen erkannt wird und jede Regel ≥1 Log hat.
4. Wenn reproduzierbar: Fixture unter `fixtures/NN-name/` + Zeile in `fixtures/fixtures.txt` (`<verz> | <regel-id> | <mvn-args>`; optional `setup.sh`, bekommt lokales Repo als `$1`). `run-fixtures.sh` prüft Events-Pfad **und** Log-Fallback **und** dass die Karte im Log steht.
5. README-Tabelle/Abschnitte aktualisieren, falls Kategorien oder Prüfungen dazukommen.

## Neue Prüfung (Check)

- Interface `core/check/Check` (`id()`, `run(CheckContext)`); Ergebnis `CheckResult.notApplicable()` / `info()` / `confirmed()`, verkettbar mit `.cause(template)`, `.evidence(Evidence…)`, `.fix(FixAction)`, `.var(name, value)`.
- `CheckContext`: `failure()`, `env()`, `var()`, `lines()`, `projectPoms()`, `find(Pattern)`, `anyLineContains()`.
- Thematisch in `ResolutionChecks`, `RepositoryChecks`, `PomChecks` oder `RuntimeChecks` als `static final class` ablegen und in deren `all()` bzw. `Checks.defaults()` registrieren (doppelte IDs werfen).
- Test in `core/src/test/.../check/ChecksTest.java`. README-Liste „Vorhandene Prüfungen“ ergänzen.

## Quick-Fix-Typen

Konstanten in `core/diagnosis/FixAction` (Javadoc-Tabelle listet Parameter): `rebuild`, `delete-files`, `open-file`, `replace-in-file`, `switch-jdk`, `set-jvm-option`, `insert-plugin-version`, `show-dependency`, `pin-version` (Modul A; `file`, `artifact`, `version`), `remove-exclusion` (A4; `file`, `artifact`, `exclusion`, `section`), `add-exclusion` (A3; `file`, `artifact` = direkte Dep unter `dependencies`, `exclusion`), `manual`. `core/fix/FixExecutor` führt nur dateibasierte aus (`delete-files`, `replace-in-file`, `set-jvm-option`, `pin-version`, `remove-exclusion`, `add-exclusion`); der Rest läuft im Plugin (`FixRunner`). Neuer Typ ⇒ FixAction-Konstante + Javadoc + Katalog-Kopfkommentar + `RuleCatalog.FIX_TYPES` + Ausführung in Executor/Plugin.

## Schnappschüsse („Was hat sich geändert?“)

Ablage `~/.maven-detective/snapshots/<projekt>-<hash>/` (`green-*.json`, `latest-*.json`); verlegbar mit `-DmavenDetective.home` / `MAVEN_DETECTIVE_HOME`, aus mit `-DmavenDetective.snapshots=false`. Grüner Lauf = neue Referenz; kurze grüne Läufe (`validate`, `-pl`) dürfen die Referenz nicht ausdünnen. Zwischen IDE- und Extension-Schnappschuss nur vergleichen, was beide gleich messen. Fixture `09-changed-since-green` testet grün → Version ändern → rot.

## CLI

```bash
java -jar core/build/libs/maven-detective-core-*.jar diagnose build.log|events.jsonl|- [--project DIR] [--markdown] [--catalog extra.yaml] [--id]
java -jar core/build/libs/maven-detective-core-*.jar evaluate logs/ [--catalog extra.yaml]   # Gate 1, optional labels.properties
java -jar core/build/libs/maven-detective-core-*.jar validate-catalog extra.yaml
java -jar core/build/libs/maven-detective-core-*.jar changes [--project DIR]
java -jar core/build/libs/maven-detective-core-*.jar why g:a [--project DIR] [--module DIR] [--tree tree.txt|-] [--markdown] [--local-repo DIR]
java -jar core/build/libs/maven-detective-core-*.jar matrix [--project DIR] [--tree tree.txt|-] [--all] [--markdown] [--pin g:a[:version]]
java -jar core/build/libs/maven-detective-core-*.jar exclusions [--project DIR] [--tree tree.txt|-] [--all] [--markdown] [--remove]  # Exit 3 = veraltete vorhanden
java -jar core/build/libs/maven-detective-core-*.jar duplicates [--project DIR] [--tree tree.txt|-] [--all] [--markdown] [--exclude]  # Exit 3 = verschiedene Fassungen zur Laufzeit
```

Extension-Properties: `-Dmaven.ext.class.path=<jar>`, `-DmavenDetective.eventsFile=…`, `-DmavenDetective.report=false`.

## Modul A (`core/deps/`, Tab „Dependencies“)

- `PomFile`: rohe POM per SAX **mit Zeilennummern** (Properties, Dependencies, dependencyManagement, Parent); Profile nicht ausgewertet, nur gemeldet.
- `PomLocator`: Parent über `relativePath` → Reactor → lokales Repo; nie Netz; fehlende POMs in `getMissing()`.
- `EffectiveModel`: Mavens Reihenfolge nachbauen – erben (Kind gewinnt), im Kontext des **Kindes** interpolieren, dann BOM-Importe (erster gewinnt, BOM interpoliert im **eigenen** Kontext). Jede Angabe behält Datei+Zeile.
- `VersionExplainer` → `VersionExplanation` (Stationen mit `Kind`, Datei, Zeile, Level; `Source` DIRECT/MANAGED/BOM/TRANSITIVE/UNKNOWN). Baum optional (`DepNode`, aus IDE `IdeMaven.dependencyTree` oder `DependencyTreeParser` für `dependency:tree -Dverbose`).
- Tests bauen Projekt + lokales Repo in `@TempDir` (`VersionExplainerTest`). Plugin: `DependenciesPanel`, `WhyThisVersionAction` (EditorPopupMenu, nur `pom.xml`).
- **A2** `ConflictMatrix.build(locator, root, poms, trees)`: Zelle je Modul aus Baum (INCLUDED, BFS), ohne Baum nur direkte Deps (`isPartial`). Abweichende Zeile → `Pin` im **nächsten gemeinsamen Parent innerhalb des Projekts**, Version = `MavenVersion.max`; `Blocker` = direkte `<version>` oder Management näher am Modul (BOM-Importe blockieren nie). Spalten ohne Zellen bei `packaging pom` fallen weg.
- `PomEditor.pinManagedVersion` → `Edit` (offset/length/text/line): eigener Tag-Scanner, nur Pfade direkt unter `project` (Profile unberührt), behält Einrückung/Zeilenenden; vorhandener Eintrag → Version bzw. `${prop}` derselben POM ändern. Kein DOM-Roundtrip. Plugin wendet `Edit` aufs `Document` an (Undo), `FixExecutor` auf die Datei.
- Plugin: Tab `ConflictMatrixPanel` („Konflikt-Matrix“), Doppelklick → `DependenciesPanel.show`; nach Pin `IdeMaven.reimport`. CLI: `matrix [--tree …] [--all] [--markdown] [--pin g:a[:version]]`.
- **A4** `ExclusionAudit.build/audit`: je `<exclusion>` (nur in der POM notierte, `dependencies` + `dependencyManagement`) transitiven Baum der Dependency aus lokalen POMs nachbauen (`Closure`: compile/runtime, ohne optional, tiefere Exclusions gelten, erster Fund je GA gewinnt, Management des **prüfenden** Moduls vor Angabe der Dependency-POM) – einmal ohne Exclusions, einmal mit den übrigen. Status `USED`/`REDUNDANT`/`STALE`/`UNKNOWN`; fehlt eine POM → `UNKNOWN`, nie `STALE`. `PomFile.Dep` hat `getExclusionEntries()` (mit Zeile, `matches` mit `*`) und `isOptional()`.
- `PomEditor.removeExclusion(xml, g, a, "eg:ea", managed)`: Scan kennt jetzt alle Deps (`DepEntry` inkl. Exclusions); letzte Exclusion nimmt `<exclusions>` mit, ganze Zeilen weg; bei Duplikaten die erste.
- Plugin: `StaleExclusionInspection` (localInspection XML, `<depends>com.intellij.modules.xml</depends>`, Beschreibung `inspectionDescriptions/MavenDetectiveStaleExclusion.html`) liest die gespeicherte Datei, bildet Befunde über Struktur (nicht Zeilen) aufs PSI ab, Quick-Fix löscht PSI-Tag. Statischer `PomCache` (mtime+Größe) über `PomLocator(root, repo, cache)`.

- **A3** `ModuleClasspath.of(locator, pom, tree, reactor)`: mit Baum INCLUDED-Knoten in Preorder (= Maven-3-Classpath), `directDependenciesBringing(ga)` zählt auch CONFLICT/DUPLICATE-Pfade; ohne Baum je direkter Dep `ExclusionAudit.Closure` (hat jetzt `decls`), nearest wins, Gleichstand → erste Deklaration, Reihenfolge geschätzt. Managed Scope/Exclusions werden eingemischt. `target/classes` des Moduls (`isSelf`) und von Reactor-Modulen. JAR-Pfad nach Repo-Layout (`test-jar`→`-tests`, `ejb-client`→`-client`, Timestamp-Snapshots → `-SNAPSHOT`-Ordner).
- `JarIndex` (thread-sicher, Cache mtime+Größe; Plugin hält eine statische Instanz): Klassenname + CRC32 aus dem Zentralverzeichnis; ohne innere Klassen, `module-info`, `package-info`, `META-INF/`.
- `DuplicateClasses.build(locator, poms, trees, index, cancellation)` → `ModuleResult` (Clashes, `unreadable`) → `Clash` je JAR-Gruppe (`differing`, `isMain` = ≥2 nicht-Test-JARs). Empfehlung: Tabelle `KNOWN` (drop/keep-GAs) vor „komplett enthalten“ (bei mehreren: transitiv vor direkt, sonst der heutige Verlierer); sonst `drop == null`. `fixesFor(entry)`: `add-exclusion` je direkter Dep (im POM, wo sie deklariert ist), direkt deklariert → `open-file`.
- `PomEditor.addExclusion(xml, g, a, "eg:ea")`: nur `project/dependencies`, bevorzugt ohne Classifier, Duplikat → `IllegalArgumentException`. Plugin: Tab `DuplicateClassesPanel` (prüft erst auf Klick), `FixRunner.addExclusions` = ein Undo-Schritt + Reimport.

## Offene Punkte (Stand README)

Gate 1 (30 echte Logs mit `evaluate` messen) steht aus; Plugin (inkl. A1) in `runIde` noch nicht durchgeklickt; Extension nicht veröffentlicht (`.mvn/extensions.xml` in CI geht noch nicht); Modul A: A1, A2, A3, A4 fertig (alle nicht in `runIde` geklickt), A5, A6 offen. Vor größeren Features README „Nächste Schritte“ prüfen.

## Git

Konto BitShiftDev. **Keine Claude-Attribution** in Commits oder PRs (kein `Co-Authored-By`, kein „Generated with Claude“). Commit-Nachrichten auf Deutsch, Stil wie `git log`.
