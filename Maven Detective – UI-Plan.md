# Maven Detective – UI-Plan

Nov 13, 2026 · Plan, die Oberfläche des IntelliJ-Plugins moderner, innovativer und benutzerfreundlicher zu machen. Betrifft nur `intellij-plugin`; Kern und Extension bleiben unverändert.

## Ausgangslage

Das Konzept sah ein Tool-Window mit **zwei Tabs** vor (Build Doctor, Dependency-Analyse). Gebaut sind inzwischen **sechs gleichrangige Tabs** im unteren Tool-Window: Build Doctor, Dependencies, Konflikt-Matrix, Doppelte Klassen, Ungenutzt/undeklariert, Upgrade-Simulator. Fachlich ist alles da, die Oberfläche ist aber Modul für Modul gewachsen.

| Befund | Wo | Folge |
| --- | --- | --- |
| Knöpfe als `JButton` in `FlowLayout`-Leisten, keine Icons | alle Panels | Wirkt nicht wie IntelliJ; im schmalen unteren Tool-Window brechen die Leisten um oder werden abgeschnitten; nicht über *Find Action* oder Tastatur erreichbar |
| Inhalte als HTML in `JEditorPane` | Karte, alle Detailbereiche | HTML 3.2: keine echten Karten, Badges, Abstände; Links nur als Text; schlecht in Dark-Theme und HiDPI |
| Sechs flache Tabs, Modul A und B gemischt | `BuildDoctorToolWindowFactory` | Keine Orientierung, wo man anfängt; der Build Doctor als häufigster Einstieg geht unter |
| Uneinheitlicher Start: Matrix rechnet sofort, A3/A5 erst auf „Prüfen“, A6 nach Eingabe | Panels | Nutzer weiß nicht, ob ein leerer Tab „nichts gefunden“ oder „noch nicht geprüft“ heißt |
| Leer- und Ladezustände nur als Text im Status-Label | alle Panels | Kein Fortschritt, kein Abbrechen, keine Aktion im leeren Zustand |
| Ergebnisse veralten nach Maven-Import still | A2–A6 | Nach einem Quick-Fix stehen alte Zahlen da, bis man „Aktualisieren“ klickt |
| POM-Änderungen ohne Vorschau | `FixRunner.editPoms`, Pin, Upgrade | Undo existiert, aber man sieht vorher nicht, was sich ändert – heikel bei „Falsche Diagnose kostet Vertrauen“ |
| Tool-Window-Icon = Standard-Problems-Icon | `plugin.xml` | Nicht unterscheidbar vom Problems-Fenster |
| Texte fest im Code, gemischt Deutsch/Englisch (Marketplace) | überall | Keine Übersetzung möglich, Texte schwer konsistent zu halten |
| Wiederholter Code: `esc`, `html`, Generationszähler, Status, „Als Markdown kopieren“, Hyperlink → `openLocation` | 5 Panels | Jede UI-Verbesserung müsste fünfmal gebaut werden |
| Editor-Integration nur über Kontextmenü | `WhyThisVersionAction`, `SimulateUpgradeAction` | „Warum diese Version?“ springt ins Tool-Window, statt die Antwort am Cursor zu zeigen |
| Plugin in `runIde` noch nie durchgeklickt | README | Kein belastbares Bild der echten Darstellung |

## Leitlinien

1. **Wie IntelliJ aussehen, nicht wie ein Formular.** Plattform-Bausteine (`SimpleToolWindowPanel`, `ActionToolbar`, `JBLoadingPanel`, `StatusText`, `JBPopup`, Diff-Viewer, Inlays) statt eigener Swing-Konstruktionen. Dann stimmen Theme, HiDPI, Tastatur und Barrierefreiheit von selbst.
2. **Antwort dort, wo die Frage entsteht.** In der `pom.xml` und im Build-Fenster antworten; das Tool-Window ist die Vertiefung, nicht der einzige Ort.
3. **Erst zeigen, dann ändern.** Jede Änderung an einer POM hat eine Vorschau als Diff; Sicherheit und Belege sind sichtbar, nicht im Fließtext versteckt.
4. **Ein Zustand, eine Bedeutung.** Jeder Bereich unterscheidet sichtbar: noch nicht geprüft · läuft · nichts gefunden · Befunde · veraltet.
5. **Weniger Text, mehr Struktur.** Ursache in einem Satz, alles andere aufklappbar.

## Zielbild

### Informationsarchitektur

```
Tool-Window „Maven Detective“ (eigenes Icon, Badge bei rotem Build)
├── Übersicht          Projekt-Zustand auf einen Blick, Einstieg in alles
├── Build Doctor       Diagnose-Karte + Verlauf der letzten Läufe
└── Dependencies       linke Navigation statt vier Tabs:
      ├── Warum diese Version?   (A1)
      ├── Konflikte              (A2)  Zähler
      ├── Doppelte Klassen       (A3)  Zähler
      ├── Exclusions             (A4)  Zähler (heute nur Inspection)
      ├── Nutzung                (A5)  Zähler
      └── Upgrade-Simulator      (A6)
```

Drei Tabs statt sechs, wie im Konzept gedacht. Die Navigation links (wie in den Einstellungen) zeigt Zähler und Zustand je Analyse („3“, „nicht geprüft“, „veraltet“). Das Upgrade-Ergebnis öffnet zusätzlich als Editor-Tab (siehe U5), weil ein Vorher/Nachher Breite braucht, die ein unteres Tool-Window nicht hat.

### Übersicht (neu)

Kacheln, jede klickbar und mit Zustand:

| Kachel | Inhalt | Klick |
| --- | --- | --- |
| Letzter Build | grün/rot, Zeitpunkt, bei rot Kategorie + Sicherheit | Build Doctor |
| Seit grün geändert | „JDK 21 → 17, 2 Dependencies“ | Schnappschuss-Diff |
| IDE ≠ Terminal | Anzahl Abweichungen | Vergleich |
| Konflikte | n Artifacts mit abweichenden Versionen | Konflikte |
| Doppelte Klassen | n, davon m mit verschiedenem Inhalt | Doppelte Klassen |
| Nutzung | n undeklariert / m ungenutzt | Nutzung |
| Exclusions | n veraltet | Exclusions |

Teure Analysen (A3, A5) rechnen nicht automatisch; die Kachel zeigt dann „nicht geprüft · Prüfen“. Ein Knopf „Alles prüfen“ startet sie als ein abbrechbarer Hintergrund-Task.

### Diagnose-Karte (Build Doctor)

Statt HTML eine echte Karte aus Swing-Komponenten:

```
┌──────────────────────────────────────────────────────────────────────┐
│ ⚠  #1 Artifact nicht auflösbar                     [ sicher ]  14:02 │
│ Der Download von com.acme:lib:1.4 ist als gescheitert gecacht         │
│ (.lastUpdated) – Maven fragt erst nach Ablauf wieder nach.            │
│                                                                      │
│ [ .lastUpdated löschen und mit -U neu bauen ]   Weitere Fixes ▾       │
│                                                                      │
│ ▸ Belege (3)                                                          │
│ ▾ Seit dem letzten grünen Build (12.11. 07:45)                        │
│     JDK         21.0.4  →  17.0.12                                    │
│     Parent      spring-boot-starter-parent 3.4.1 → 3.5.0  (2 Module)  │
│ ▸ IDE und Terminal unterscheiden sich (2)                             │
│                                                                      │
│ Bericht kopieren · Falsch erkannt?                                    │
└──────────────────────────────────────────────────────────────────────┘
```

- **Sicherheit als Badge** (grün „sicher“, gelb „wahrscheinlich“), Kategorie-Icon und Nummer im Kopf.
- **Ein primärer Fix** als Standard-Knopf, weitere im Menü. Nicht unterstützte Fixes erscheinen als Anleitung, nicht als ausgegrauter Knopf ohne Erklärung.
- **Belege, Änderungen, IDE-Vergleich aufklappbar** (`HideableDecorator` / `CollapsiblePanel`); Logzeilen in Monospace, Dateien als `ActionLink` mit Sprung.
- **Fix-und-Prüf-Schleife** (aus dem Konzept: „danach Build automatisch neu starten“): Nach dem Fix startet dieselbe Run-Konfiguration erneut; die Karte zeigt „Neu bauen läuft …“ und danach „Behoben ✓“ oder die neue Diagnose.
- **Verlauf** links: die letzten Läufe (rot/grün, Zeit, Kategorie). Das ersetzt das stille Überschreiben durch den nächsten Lauf.
- „Falsch erkannt?“ als kleiner Link mit kurzem Dialog (richtige Kategorie wählen, Log anhängen) statt gleichrangigem Knopf.

## Maßnahmen in Phasen

Jede Phase ist für sich auslieferbar. Reihenfolge nach Nutzen pro Aufwand.

### U0 – Ist-Zustand festhalten (½ Tag)

- Plugin in `runIde` an einem Spring-Boot-Multi-Modul-Projekt durchklicken (steht ohnehin in „Nächste Schritte“), Screenshots je Tab in Light und Dark ablegen.
- Liste der Stellen, die schon heute brechen (abgeschnittene Leisten, unlesbare Farben, Fehler).
- **Fertig, wenn:** Screenshot-Satz „vorher“ liegt vor; gefundene Fehler sind als Punkte in U1 aufgenommen.

### U1 – Fundament: gemeinsame Bausteine (2–3 Tage)

> **Stand 13.11.2026: umgesetzt, in `runIde` noch nicht geklickt.** Alle sechs Seiten erben `DetectivePanel`; Toolbars aus registrierten Aktionen (`MavenDetective.Refresh`, `.Stop`, `.CopyMarkdown`, `.AnalyzeClipboard`, `.CompareSetup`, `.ReportMisdiagnosis`), Filter als Menü, Hauptaktion je Seite als Standardknopf in der `FixBar`. Offen aus U1: die HTML-Detailtexte stehen noch im Code (Bundle hat Tabs, Aktionen, Knöpfe, Filter, leere Zustände); `GotItTooltip` und New-UI-Icon kamen mit U6.

Neues Paket `io.github.bitshiftdev.mavendetective.idea.ui`:

- `DetectivePanel` (erbt `SimpleToolWindowPanel`): Toolbar, Inhalt, Generationszähler, `disposed`, Hintergrund-Rechnen mit `ProgressIndicator` und Abbrechen, Zustände *nicht geprüft / läuft / leer / Befunde / veraltet*.
- Toolbars als `ActionToolbar` mit `AnAction`s und Icons (`AllIcons.Actions.Refresh`, `Execute`, `Copy`, `Filter` …). Filter-Checkboxen als `ToggleAction`; selten Gebrauchtes ins Zahnrad-Menü. Alle Aktionen mit ID in `plugin.xml` → über *Find Action* und Tastenkürzel erreichbar.
- Ladezustand über `JBLoadingPanel`, leere Tabellen über `getEmptyText()` mit Aktionslink („Noch nicht geprüft – **Prüfen**“, „Kein Baum – **Maven-Projekt neu laden**“).
- `TableSpeedSearch`/`TreeSpeedSearch` überall; Strg+C kopiert die Zeile; Kontextmenü auf Zeilen (Springen, Warum diese Version?, Fix).
- Navigation über `Navigatable`/`OpenFileDescriptor` und `uiDataSnapshot`, damit F4 / *Jump to Source* funktionieren.
- Eigenes Tool-Window-Icon (13 × 13 SVG, Light/Dark, New-UI-Variante unter `expui/`), Icons für Sicherheit und Befundart.
- Texte in `messages/MavenDetectiveBundle.properties` (`DynamicBundle`), Farben als benannte `JBColor`s, Abstände über `JBUI.Borders`/`JBUI.scale`.
- **Fertig, wenn:** alle fünf Panels auf `DetectivePanel` laufen, kein `FlowLayout`-Knopf und kein doppeltes `esc`/`html` mehr übrig ist, alle Aktionen in *Find Action* auftauchen.

### U2 – Neue Struktur: drei Tabs und Übersicht (2 Tage)

> **Stand 13.11.2026: umgesetzt, in `runIde` noch nicht geklickt.** Tabs „Übersicht“ (`OverviewPanel`, sieben Kacheln aus `ui/Tile`, „Alles prüfen“ über die Aktion *Aktualisieren*, Abbrechen über *Prüfung abbrechen*), „Build Doctor“ und „Dependencies“ (`DependencyAnalysisView`: `JBList` mit Zähler/Zustand links, `CardLayout` rechts, letzte Seite gemerkt). `AnalysisResults` (Projekt-Service) hält den Stand je Analyse (*nicht geprüft / läuft / fertig / veraltet*); `IdeMaven.onImport` (`MavenImportListener`) markiert nach jedem Import alles Fertige als veraltet – Konflikte, Exclusions und „Warum diese Version?“ rechnen von selbst neu, doppelte Klassen, Nutzung und Upgrade-Simulation zeigen den Balken „Seit dem letzten Maven-Import veraltet – Neu prüfen“ (`DetectivePanel.setStale`); veraltete Kacheln zeigen keine Zahl. Badge über `BadgeIconSupplier` bei rotem IDE-Lauf, weg beim nächsten grünen; Sprechblase nur bei verstecktem Tool-Window. Alle Sprünge über `Navigator` (nach Klasse, nicht Tab-Name). Abweichend vom Plan: Exclusions (A4) haben noch keine eigene Seite – die Kachel zählt veraltete/doppelte und springt zur ersten in der POM (Seite folgt in U5).

- `BuildDoctorToolWindowFactory` legt Übersicht, Build Doctor und Dependencies an; Dependencies mit linker Navigation (`JBList` mit Zähler-Renderer) und `CardLayout`/`JBSplitter` rechts. Bestehende Panels werden zu Seiten.
- Übersicht wie oben; Kacheln aus vorhandenen Ergebnissen (Service hält das letzte Ergebnis je Analyse).
- Auf Maven-Import hören (`IdeMaven` bekommt eine Methode dafür): Ergebnisse werden als **veraltet** markiert, günstige (A1, A2) rechnen von selbst neu, teure zeigen einen Hinweisbalken „Seit dem Import veraltet – Neu prüfen“.
- Tool-Window-Stripe zeigt bei rotem Build ein Badge; Balloon nur, wenn das Tool-Window nicht sichtbar ist.
- Interne Sprünge (`WhyThisVersionAction`, Matrix-Doppelklick) über eine zentrale `Navigator`-Methode statt `findContent(TAB_NAME)`.
- **Fertig, wenn:** ein neuer Nutzer ohne Anleitung vom roten Build zur Ursache und von der Übersicht zu jedem Befund findet; nach einem Quick-Fix + Import sind keine alten Zahlen mehr sichtbar.

### U3 – Diagnose-Karte neu (3 Tage)

> **Stand 13.11.2026: umgesetzt, in `runIde` noch nicht geklickt.** `DiagnosisCard` baut die Karte aus Komponenten: Kopf mit Fehler-Icon, Nummer, Kategorie, Sicherheit als `ui/Badge` (grün „sicher“, gelb „wahrscheinlich“), Run-Konfiguration und Uhrzeit; Ursache als umbrechender, markierbarer Text; erster ausführbarer Fix als Standardknopf, weitere unter „Weitere Fixes ▾“, nicht ausführbare als Abschnitt „Von Hand“; aufklappbar (`ui/Section`, Zustand über Karten gemerkt) Belege (Logzeilen in Editor-Schrift, Dateien als `ActionLink`), „Seit dem letzten grünen Build“ (zweispaltig, Bereich · vorher → nachher), „IDE und Terminal unterscheiden sich“, „Weitere Treffer“ (Klick macht sie zur gezeigten Diagnose); Fuß „Bericht kopieren · Falsch erkannt?“. Grüne Läufe und „keine Fehlerzeile erkannt“ haben eigene Karten. Verlauf: `BuildDoctorService` hält 20 Einträge (IDE-Läufe rot/grün, Logs aus der Zwischenablage), Liste links im `JBSplitter`, neuester wird gezeigt. Fix-und-Prüf-Schleife: `FixRunner.rebuild` meldet vor dem Neustart `expectCheck(fix, ergebnis)`, `MavenRunListener` meldet Start/Abbruch; über der Karte steht „Neu bauen nach „…“ läuft …“, danach „Behoben ✓“ (grün), „weiter dieselbe Ursache“ oder „jetzt etwas anderes“. POM-Fixes aus der Karte bauen jetzt ebenfalls neu. Konsole: `BuildFailureConsoleFilter` (`consoleFilterProvider`) macht `[INFO] BUILD FAILURE` zum Link auf den neuesten Lauf, ein grauer Inlay-Hinweis dahinter nennt Maven Detective (Inlays sind in der Konsole nicht klickbar). IDE gegen Kommandozeile: `SetupComparisonView` mit `JBTable` (Warn-Icon, „≠“, fett, Hintergrund), Hinweise zur gewählten Abweichung darunter, „Zur Diagnose“. „Falsch erkannt?“ öffnet `MisdiagnosisDialog` (richtige Regel wählen, Notiz); der Testfall heißt dann `<regel-id>__erkannt-als-…log` und taugt ohne Umbenennen für `LogCatalogTest`. Abweichend vom Plan: kein Kategorie-Icon je Regel (ein Fehler-Icon für alle); der Verlauf lebt nur im Speicher.

- Karte aus Komponenten (siehe Zielbild), primärer Fix, aufklappbare Abschnitte, Badge.
- Verlauf der letzten Läufe im `BuildDoctorService` (im Speicher, z. B. 20), Liste links.
- Fix-und-Prüf-Schleife: `FixRunner` merkt sich die Run-Konfiguration aus `MavenRunListener` und startet sie nach dem Fix neu; die Karte zeigt den Ausgang.
- **Im Build-/Run-Fenster:** `ConsoleFilterProvider` erkennt `BUILD FAILURE` und hängt einen Link „Ursache anzeigen (Maven Detective)“ an. So beginnt die Diagnose dort, wo der Nutzer gerade hinschaut.
- IDE-gegen-Kommandozeile als eigene Ansicht mit Tabelle (Abweichungen hervorgehoben, Icon + Text, nicht nur Fett) statt HTML mit „Zurück“-Link.
- **Fertig, wenn:** die README-Beispielkarte (#4 Kaputtes JAR) in Light/Dark sauber aussieht und der Weg „roter Build → Link in der Konsole → Fix → grüner Build“ ohne Tool-Window-Suche klappt.

### U4 – Antworten im Editor (3–4 Tage)

> **Stand 13.11.2026: umgesetzt, in `runIde` noch nicht geklickt.** Grundlage ist der Projekt-Service `PomInsights`: je `pom.xml` gecacht (CachedValue an VFS-Stand und eigenem Tracker), rechnet nur aus gespeicherten POMs und dem letzten Maven-Import; die Konflikt-Matrix rechnet ein Hintergrund-Thread (oder die Seite „Konflikte“ reicht ihre durch), doppelte Klassen kommen nur aus der letzten Prüfung der Seite (nach einem Import verworfen). „Warum diese Version?“ ist ein Popup am Cursor (`WhyThisVersionPopup`: Ein-Satz-Antwort, Stationen mit Icon je Art und Sprung zu Datei:Zeile, Hinweise klein, Pin und „Im Tool-Window öffnen“), erreichbar über Rechtsklick und Alt+Enter (`WhyThisVersionIntention`, dazu `SimulateUpgradeIntention`). Inlay `ManagedVersionInlayProvider` (Declarative Inlays, *Inlay Hints | Other | XML*) „2.18.2 · jackson-bom“ hinter `<dependency>` ohne Version, Klick springt zum verwalteten Eintrag; Kern: `PomHints.managedVersions`. Code Vision `ConflictCodeVisionProvider` „3 Module · 2 Versionen“ über abweichenden Dependencies, Klick → `ConflictMatrixPanel.select`. Gutter-Icon `PomFindingsLineMarkerProvider` bei verdrängter höherer Version (`UpgradeSimulator.risks` auf dem echten Baum, Fix „X in dieser POM festlegen“) und bei doppelten Klassen (Fix „Exclusion einfügen“, Sprung zum Fund). Banner `LastBuildNotificationProvider` an jeder Datei, die Belege oder Fixes des letzten roten IDE-Laufs nennen (mit Zeile, „Diagnose anzeigen“, „Ausblenden“). Rechtsklick „Warum diese Version?“/„Upgrade simulieren…“ auch in `Maven.DependencyMenu` und `ProjectViewPopupMenu` (External Libraries, `IdeMaven.selectedDependency`). Abweichend vom Plan: „neuer Konflikt“ heißt im Editor „verdrängte höhere Version“ im heutigen Baum (ein Vorher gibt es dort nicht); keine `BasePlatformTestCase`-Tests (Test-Framework offline nicht verfügbar), Logik als Unit-Tests; Tipp-Latenz nicht gemessen.

- **„Warum diese Version?“ als Popup am Cursor** (`JBPopupFactory`, kompakte Kette mit klickbaren Stationen, „Im Tool-Window öffnen“ als Pin). Zusätzlich als Intention (Alt+Enter) auf `<dependency>`, nicht nur im Kontextmenü.
- **Inlay-Hinweise in der `pom.xml`** (`DeclarativeInlayHintsProvider`, abschaltbar): hinter einer `<dependency>` ohne Version „2.18.2 · jackson-bom“; bei Abweichung über Module „3 Module · 2 Versionen“ als Code-Vision-Eintrag über der Dependency, Klick öffnet Konflikte.
- **Gutter-Icon** an Dependencies mit doppelten Klassen oder neuem Konflikt, Popup mit Kurzbefund und Fix.
- **Editor-Banner** (`EditorNotificationProvider`) in der `pom.xml`, die die letzte Diagnose betrifft: „Der letzte Build scheiterte an dieser Datei (Zeile 42) – Diagnose anzeigen“.
- Rechtsklick auch im Maven-Tool-Window und unter *External Libraries* (steht bereits in „Nächste Schritte“).
- **Fertig, wenn:** die häufigsten Fragen (Herkunft einer Version, „ist das ein Konflikt?“) in der `pom.xml` beantwortet sind, ohne das Tool-Window zu öffnen; Inlays kosten messbar keine Tipp-Latenz (Berechnung gecacht, nur gespeicherter Stand).

### U5 – Dependency-Ansichten modernisieren (4–5 Tage)

> **Stand 13.11.2026: umgesetzt, in `runIde` noch nicht geklickt.** A1: `VersionTimeline` – Antwort fett, Pfad im Baum als Breadcrumb (Klick erklärt das Glied), Stationen als senkrechte Zeitleiste (Schiene mit Icon je Art, Details eingerückt, Datei:Zeile als `ActionLink`), verdrängte Versionen durchgestrichen, Hinweise klein. A2: Artifact-Spalte als Zeilenkopf (`setRowHeaderView`, eigene `JBTable` mit gemeinsamem Modell und Auswahl), je Version eine Farbe aus `DetectiveColors.versionBackground` (sechs benannte Farben, Version bleibt Text, Abweichung fett), Kontextmenü auf der Zelle „X in parent festlegen“ / „Vorschlag … festlegen“ / „Warum diese Version? (Modul)“ (`DetectivePanel.rows` mit Zusatzaktionen), Enter = Doppelklick. A3/A5/A4: `ui/FindingTree` (`CheckboxTree` Gruppe → Befund, Häkchen/Auswahl/Aufklappen bleiben beim Neubefüllen, Speed Search, Leertaste), Art als Icon + Wort; A3 „Markierte bereinigen (n)“ setzt die Empfehlung um, A5 „Beheben“ wirkt auf Angekreuzte, sonst Gewählte. A4: neue Seite `ExclusionsPanel` (POM → Exclusion, entfernbare vorab angekreuzt, Filter „Auch wirksame und nicht prüfbare“, „Entfernen“); rechnet nach jedem Import selbst, die Übersicht-Kachel springt hin. A6: `TextFieldWithAutoCompletion` für das Artifact, Versionsliste mit Markern (lokal · heute/neuer/älter · Major-Sprung, `UpgradeLabels`), Ergebnis als Editor-Tab (`UpgradeResultEditor` + `fileEditorProvider`, `LightVirtualFile` je Simulation; Kopfzeile mit Badges, „Übernehmen“, „Fehlende POMs laden“, „Erneut simulieren“; Warnbalken bei neuen Konflikten/gesenkten Versionen; `PomDiffView` mit dem Diff-Viewer der IDE; Baum-Unterschied mit farbigem ↑ ↓ + − ~ und Wort). Fix-Vorschau: `FixRunner.editPoms` plant alle Änderungen im Speicher (`PomChanges.plan`, je Datei nacheinander), zeigt `PomChangePreviewDialog` (alle POMs, ein „Anwenden“, Häkchen „Vorschau nicht mehr zeigen“) und schreibt nur, wenn die Dokumente noch genau so aussehen; Pin, Exclusions, Nutzung, Upgrade, Karten-Fixes, Gutter-Fixes und „Plugin-Version einfügen“ laufen darüber. Schalter: Aktion „Vorschau vor POM-Änderungen“ (Find Action, Filter-Menüs der Seiten), Einstellungsseite folgt in U6. Abweichend vom Plan: Detailbereiche von A3/A4/A5 bleiben HTML (mit Sprung-Links); die Seite im Tool-Window zeigt die Upgrade-Tabelle weiter, der Editor-Tab öffnet zusätzlich (ohne Fokus) nach jeder Simulation mit Änderung; der Quick-Fix der Exclusion-Inspection ändert das PSI direkt (Vorschau dort über die Intention-Vorschau der IDE).

- **A1 Warum diese Version?** Kette als vertikale Zeitleiste (Station = Knoten mit Icon für Modul/Parent/BOM/Property, Datei:Zeile als Link, verdrängte Versionen durchgestrichen). Pfad im Baum als Breadcrumb.
- **A2 Konflikte:** erste Spalte fixiert (Row-Header des Scroll-Panes), je Version eine feste Farbe innerhalb der Zeile, damit Gruppen sofort sichtbar sind (zusätzlich zur Zahl, nicht statt). Pin per Kontextmenü auf der Zelle.
- **A3/A5:** Baum-Tabelle *Modul → Befund* statt flacher Tabelle, Befundart als Icon + Wort, Checkboxen für Sammel-Fixes (`CheckboxTree`), Belege mit Sprung im Detailbereich.
- **A4 Exclusions:** eigene Seite (heute nur Inspection) mit allen veralteten/doppelten Exclusions des Projekts und Sammel-Entfernen.
- **A6 Upgrade-Simulator:** Eingabe mit Autovervollständigung (`TextFieldWithAutoCompletion`), Versionsliste mit Markern (lokal vorhanden / neuer / Major-Sprung). Ergebnis als **Editor-Tab** mit zwei Bereichen: oben die POM-Änderung im **IntelliJ-Diff-Viewer**, unten der Baum-Unterschied mit ↑ ↓ + − als farbigen Icons, neue Konflikte als Warnbanner. „Übernehmen“ und „Fehlende POMs laden“ in der Kopfzeile.
- **Fix-Vorschau für alle POM-Fixes:** `FixRunner.editPoms` zeigt vorher einen Diff-Dialog (alle betroffenen POMs, ein „Anwenden“). Einstellung „Vorschau vor POM-Änderungen“ (an per Default).
- **Fertig, wenn:** jede Seite mit Tastatur bedienbar ist, kein Befund nur über Farbe erkennbar ist und keine POM ohne gesehene Vorschau geändert wird (außer Vorschau abgeschaltet).

### U6 – Feinschliff und Einführung (2 Tage)

> **Stand 13.11.2026: teilweise umgesetzt, in `runIde` noch nicht geklickt.** Einstellungsseite *Tools | Maven Detective* (`DetectiveConfigurable`, Zustand `DetectiveSettings` in `mavenDetective.xml`, für alle Projekte): Sprechblase bei rotem Build (Badge bleibt immer), Neubau nach Quick-Fix (aus: Meldung mit „Neu bauen“; der Fix „Neu bauen“ selbst startet immer), Vorschau vor POM-Änderungen (derselbe Schalter wie die Aktion), Schnappschüsse an/aus und Ordner (wie `-DmavenDetective.home`, Dateien unter `snapshots/`; leer = wie die Extension), „Auch über Starter“ (die Seite merkt sich den Filter jetzt dort). Inlays, Code Vision und Inspections stellt die IDE selbst ein – die Seite verlinkt dorthin statt doppelter Schalter. Erste Schritte (`FirstSteps`): `GotItTooltip` an der Ursache der ersten roten Karte und beim ersten Öffnen einer `pom.xml` eines Maven-Projekts an der ersten Projekt-Dependency (Tastenkürzel aus der Keymap); „Hinweise erneut zeigen“ auf der Einstellungsseite. Leere Zustände: `DetectivePanel.about()` + `intro(…)` – Konflikte, Doppelte Klassen, Exclusions, Nutzung und Upgrade-Simulator erklären in zwei Zeilen, was sie tun („Warum diese Version?“ im Starttext); gefilterte Listen bleiben knapp. Barrierefreiheit: Karte mit Namen (Art, Sicherheit) und Beschreibung (Ursache), Zustand der aufklappbaren Abschnitte aus dem Bundle; `DetectiveColorsTest` rechnet WCAG-Kontraste aller eigenen Farben gegen Light, Dark und High Contrast (Text 4.5:1, Änderungszeichen auf Risiko-Zeilen 3:1) – dabei fiel grauer Nebentext auf den Versionsfarben der Matrix durch (3–4.4:1), jetzt eigene Farbe `TINTED_SECONDARY`. New-UI-Icon des Tool-Windows (16 × 16 und @20x20 unter `icons/expui/`, über `iconMapper`). **Offen:** englische Übersetzung (Entscheidung zur Sprache steht aus, siehe offene Fragen; dazu kämen die HTML-Texte im Code und die deutschen Diagnose-Texte aus dem Kern), Marketplace-Screenshots „nachher“ (braucht `runIde` mit Bildschirm), Test mit einem Nutzer ohne Projektkenntnis.

- **Einstellungsseite** *Tools | Maven Detective* (`Configurable`): Benachrichtigungen, Fix-Vorschau, Inlays, „Auch über Starter“, Schnappschüsse an/aus und Ablageort, automatisch neu bauen nach Fix.
- **Erste Schritte:** `GotItTooltip` beim ersten roten Build („Hier steht, warum“) und beim ersten Öffnen der `pom.xml` („Alt+Enter → Warum diese Version?“). Leere Zustände erklären in zwei Sätzen, was der Bereich tut.
- **Barrierefreiheit:** `AccessibleContext`-Namen für Karten, Badges und Icons; Kontrast in Light, Dark und High Contrast prüfen.
- Englische Übersetzung des Bundles (Marketplace-Publikum), Deutsch bleibt Projektsprache – siehe offene Fragen.
- Marketplace-Screenshots aus U0-Satz neu erstellen („nachher“).
- **Fertig, wenn:** Screenshot-Satz „nachher“ liegt vor und ein Testnutzer ohne Projektkenntnis die drei Kernwege (roten Build verstehen, Version erklären, Upgrade prüfen) ohne Hilfe schafft.

## Ideen mit Signalwirkung

Was Maven Detective von Maven Helper und dem Dependency Analyzer sichtbar abhebt – nach den Phasen oben, je nach Echo:

- **Zeitleiste „Was hat sich geändert?“:** Alle Schnappschüsse als Zeitstrahl (grüne und rote Punkte); zwei Punkte wählen → Unterschied. Macht die vorhandenen Schnappschüsse zum eigenen Werkzeug statt nur zum Beleg auf der Karte.
- **Search Everywhere „Dependencies“:** Tab in *Search Everywhere*, der Artifacts des Projekts findet; Enter öffnet „Warum diese Version?“, Alt+Enter den Upgrade-Simulator.
- **Simulator als Beleg auf der Karte:** Bei `NoSuchMethodError` zeigt die Karte „seit dem Upgrade von X kam Y 2.0 → 1.4“ mit Mini-Diff (steht als Idee in der README).
- **Preflight vor dem Build** als Toolbar-Aktion im Maven-Tool-Window mit Ampel (Konzept, bisher nicht gebaut).
- **Log-Navigator** (Konzept): Konsole mit eingeklapptem Download-Rauschen (`FoldingBuilder`/Console-Folding) und Sprung zum ersten echten Fehler.

## Technische Leitplanken

- Plugin bleibt Java (Entscheidung im Konzept). Kotlin UI DSL ist aus Java unhandlich; Formulare mit `FormBuilder`/`JBUI`, Karten mit eigenen kleinen Komponenten.
- Kein JCEF für Karten: nicht in jeder Umgebung verfügbar, schwer, fremdes Look-and-Feel. Native Swing-Komponenten reichen.
- Weiter nur über `IdeMaven` auf die Maven-API (neu: Import-Listener, Run-Konfiguration erneut starten).
- Alles Teure im Hintergrund mit `ReadAction.nonBlocking`/`Task.Backgroundable`, abbrechbar; der EDT rechnet nichts.
- `sinceBuild 253` bleibt; neue APIs (Declarative Inlays, `uiDataSnapshot`) gegen 2025.3 prüfen, Plugin Verifier (Gate 2) nach jeder Phase.
- Tests: Renderer-/Modell-Logik als Unit-Tests ohne UI; Aktionen und Inlays mit `BasePlatformTestCase`; Darstellung per Screenshot-Satz.

## Aufwand und Reihenfolge

| Phase | Aufwand | Nutzen |
| --- | --- | --- |
| U0 Ist-Zustand | ½ Tag | Grundlage, findet Fehler vor dem Umbau |
| U1 Fundament | 2–3 Tage | Alles danach wird billiger; sofort IntelliJ-Look |
| U2 Struktur + Übersicht | 2 Tage | Orientierung, keine veralteten Ergebnisse |
| U3 Diagnose-Karte | 3 Tage | Kernfunktion wird zum Aushängeschild |
| U4 Editor | 3–4 Tage | Größter Sprung in der Benutzerfreundlichkeit |
| U5 Dependency-Ansichten | 4–5 Tage | Vertrauen durch Vorschau, Klarheit in A1–A6 |
| U6 Feinschliff | 2 Tage | Marketplace-reif |

Summe grob 17–20 Arbeitstage, als Nebenprojekt also mehrere Wochen. U0–U3 lohnen sich vor Gate 2 und dem Marketplace-Start, U4–U6 danach.

## Offene Fragen

- [ ] **Sprache der Oberfläche:** Deutsch als Standard und Englisch als Übersetzung – oder umgekehrt, weil der Marketplace international ist?
- [ ] **Upgrade-Simulator im Editor-Tab** statt (oder zusätzlich zu) der Seite im Tool-Window?
- [ ] **Fix-Vorschau als Default an?** Sicherer, aber ein Klick mehr pro Fix.
- [ ] **Tool-Window unten oder rechts?** Die Karte und A1 sind hochkant besser, die Matrix quer. Vorschlag: unten lassen, Nutzer kann umhängen.
