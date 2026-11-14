# Prompt: Fehler- und Performance-Review

> Zum Einfügen in eine neue Claude-Code-Sitzung im Repo `maven-detective`.

---

Du bist Experte für dieses Projekt (Maven Detective: Java-8-Kern `core`, Maven-Core-Extension `maven-extension`, IntelliJ-Plugin `intellij-plugin`) und erfahrener Entwickler von IntelliJ-Plattform-Plugins (Threading-Modell, PSI, VFS, Indizes, Daemon, Disposer, Message Bus, Plugin Verifier, dynamisches Laden/Entladen).

Lade zuerst den Skill `maven-detective` und lies `README.md`, `Maven Detective – Plugin-Konzept.md` und `Maven Detective – UI-Plan.md`. Die harten Regeln aus dem Skill (keine Laufzeit-Abhängigkeiten im Kern, Java-8-API, Prüfungen nur lesend und ohne Netz, Extension ändert nie den Build, Maven-API nur über `IdeMaven`, keine Texte im Code) gelten als Anforderungen: Ein Verstoß ist ein Befund.

## Auftrag

Suche im gesamten Code nach **Fehlern** und **Performance-Problemen** und dokumentiere sie. **Ändere keinen Produktivcode und committe nichts.** Reproduktionen (Testfälle, Mini-Logs, Messskripte) legst du nur im Scratchpad oder unter `build/` ab.

## Worauf du achtest

### IntelliJ-Plugin
- **Threading:** Langsame Arbeit auf dem EDT (Datei-I/O, `JarIndex`, `DependencyResolver`, POM-Parsen, Prozessstarts); PSI-/VFS-Zugriff ohne Read-Action; Schreibzugriffe ohne Write-Action/`WriteCommandAction`; `invokeLater` ohne passenden `ModalityState`; `ReadAction.nonBlocking()` ohne `expireWith`/`coalesceBy`; `executeSynchronously()` dort, wo es den Aufrufer blockiert.
- **`ActionUpdateThread`:** Jede `AnAction` muss `getActionUpdateThread()` korrekt überschreiben; `update()` darf nichts Teures tun und nicht auf Swing zugreifen, wenn BGT gewählt ist.
- **Abbruch:** Lange Schleifen ohne `ProgressManager.checkCanceled()` bzw. ohne Prüfung der eigenen `Cancellation`; `ProcessCanceledException` darf nie geschluckt oder geloggt werden.
- **Lebensdauer und Speicher:** Statische Caches (`JarIndex`, `PomCache`, `IdeUsages.JARS`, `ClasspathChecks.JARS`) – wachsen sie unbegrenzt, halten sie Projekte/PSI/Editoren fest, überleben sie das Schließen eines Projekts oder das Entladen des Plugins? Listener und Message-Bus-Verbindungen ohne Parent-`Disposable`; Editoren aus `EditorFactory.createViewer` ohne `releaseEditor`; PSI-Elemente in Feldern ohne `SmartPsiElementPointer`.
- **Daemon/Editor:** Inspections, Line-Marker, Inlays und Code Vision dürfen im Daemon nicht teuer rechnen (siehe `PomInsights`-Regel im Skill). Line-Marker nur an Blatt-Elementen. `CachedValue`-Abhängigkeiten vollständig (VFS, POM, Maven-Import)? Werden Ergebnisse nach Maven-Import korrekt verworfen?
- **Dumb Mode und Indizes:** Zugriffe, die im Dumb Mode fehlschlagen; `DumbAware` dort, wo es falsch ist.
- **`plugin.xml`:** Fehlende/falsche Registrierungen, `<depends>` ohne `optional`/`config-file` wo nötig, dynamisches Entladen blockierende Konstrukte, interne oder veraltete API (was würde `verifyPlugin` melden?).
- **UI:** Swing-Zugriffe außerhalb des EDT, Neuaufbau ganzer Komponentenbäume bei kleinen Änderungen, Tabellen-Renderer mit teurer Logik, Generationen in `DetectivePanel.background` (veraltete Ergebnisse überschreiben neue?).
- **POM-Änderungen:** Laufen wirklich alle über `FixRunner.editPoms` mit Vorschau und genau einem Undo-Schritt? Konsistenz zwischen geplantem und geschriebenem Text?

### Kern (`core`)
- **Java 8:** Verbotene API (`var`, `List.of`, `isBlank`, `Optional.isEmpty`, `readString` …) – auch versteckt in Hilfsklassen.
- **Korrektheit:** `MavenLogParser`, `LogOutline`, `MiniYaml`, `Json`, `PomFile`, `PomEditor` (CRLF, BOM, Kommentare, CDATA, Namespaces, Profile, Einrückung, Duplikate), `EffectiveModel` (Erb-/Interpolationsreihenfolge), `DependencyResolver` (Maven-Semantik bei Scope, Management, Exclusions, Zyklen), `MavenVersion`/`VersionRange`-Vergleiche, `ClassFile`-Parser bei ungewöhnlichem Bytecode.
- **Performance:** Quadratische Schleifen über Logzeilen oder Baumknoten, Regex mit katastrophalem Backtracking (auch in `build-doctor-v1.yaml`), Regex-Kompilierung in Schleifen, wiederholtes Parsen derselben POM/JAR ohne Cache, `searchRepository` und Repository-Scans auf großen `~/.m2`, Speicherbedarf bei sehr großen Logs (> 100 MB) und Reactor-Projekten mit > 200 Modulen.
- **Ressourcen und Robustheit:** Nicht geschlossene `ZipFile`/Streams, Zeichensatz-Annahmen (Plattform-Default statt UTF-8), Pfade unter Windows, Rekursion ohne Tiefenbegrenzung, Thread-Sicherheit der Caches.
- **Sicherheit:** `delete-files`/`replace-in-file` außerhalb des Projekts bzw. lokalen Repos (Path Traversal über Katalogvariablen), Prozessstart in `PomFetcher` (Argumente aus POMs), Netzzugriff nur mit ausdrücklicher Option, nie Zugangsdaten aus `settings.xml` senden.

### Maven-Extension
- Jeder interne Fehler wird geschluckt, der Build nie verändert oder verlangsamt (Overhead je Event, Schreiben der JSONL, Flush beim Abbruch, Classloader-Konflikte mit Maven 3.6–3.9 und 4).

## Vorgehen

1. **Überblick:** Module, Einstiegspunkte (`plugin.xml`, `DetectiveEventSpy`, CLI-`main`) und heiße Pfade kartieren.
2. **Durchsicht je Modul**, Schwerpunkte wie oben. Größere Bereiche darfst du parallel von Subagenten untersuchen lassen; jeden ihrer Befunde prüfst du selbst nach.
3. **Verifikation:** Jeden Befund am Code belegen (`Datei:Zeile`) und ein konkretes Szenario angeben (Eingabe/Zustand → falsches Verhalten). Wo möglich reproduzieren: vorhandene Tests laufen lassen (`./gradlew :core:test`, Sandbox-Hinweise im Skill), Mini-Test oder Mini-Log im Scratchpad, bei Performance eine Messung (Zeit, Speicher, Größe der Eingabe).
4. **Aussortieren:** Reine Stilfragen, Geschmack und Vermutungen ohne Szenario gehören nicht in den Bericht. Was du geprüft und für in Ordnung befunden hast, nennst du knapp unter „Geprüft, ohne Befund“.

## Bewertung je Befund

- **Schwere:** Kritisch (Datenverlust, Build kaputt, IDE hängt/friert ein, Sicherheitslücke) · Hoch (falsche Diagnose/falscher Fix, Speicherleck, spürbare Verzögerung) · Mittel · Niedrig
- **Sicherheit:** `bestätigt` (reproduziert oder zweifelsfrei aus dem Code) · `plausibel` (Szenario schlüssig, nicht reproduziert)
- **Kategorie:** `threading`, `speicher`, `performance`, `korrektheit`, `robustheit`, `sicherheit`, `api-kompatibilität`, `java8`, `regelverstoß`

## Ergebnis

Schreibe den Bericht auf Deutsch nach `docs/review/<JJJJ-MM-TT>-fehler-und-performance.md`:

```markdown
# Review: Fehler und Performance (<Datum>, Stand <Commit>)

## Zusammenfassung
3–5 Sätze: Gesamteindruck, die drei wichtigsten Befunde, größter Hebel bei der Performance.

## Übersicht
| # | Schwere | Kategorie | Sicherheit | Modul | Ort | Kurzbeschreibung |

## Befunde
### <#>. <Titel>
- **Ort:** `pfad/Datei.java:123` (weitere Stellen)
- **Problem:** Was ist falsch?
- **Szenario:** Eingabe/Zustand → beobachtetes bzw. erwartetes Fehlverhalten
- **Beleg:** Codeauszug (kurz), Testausgabe oder Messwerte
- **Vorschlag:** Lösungsskizze inkl. betroffener Tests; Aufwand (S/M/L)

## Performance-Messungen
Was, womit, Eingabegröße, Ergebnis, Engpass.

## Geprüft, ohne Befund
Stichpunkte.

## Offene Fragen
Was ohne `runIde`, Netz oder Plugin Verifier nicht zu klären war.
```

Sortiere die Befunde nach Schwere, innerhalb gleicher Schwere nach Sicherheit. Am Ende gibst du mir im Chat die Zusammenfassung, die Übersichtstabelle und den Pfad zum Bericht aus.
