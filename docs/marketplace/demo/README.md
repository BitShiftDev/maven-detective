# Demo-Projekt für die Marketplace-Screenshots

`shop-platform` ist ein kleiner Reactor (5 Module), der jede Ansicht von Maven Detective mit echtem Inhalt füllt.
Alles ist absichtlich ein wenig kaputt – **nicht reparieren**, außer zum Zeigen eines Quick-Fixes (danach
`git checkout docs/marketplace/demo`).

| Modul | Was es auslöst |
| --- | --- |
| `commons` | kompiliert gegen Jackson 2.18 (`ObjectNode.withObjectProperty`, gibt es erst ab 2.16) |
| `gateway` | `jackson-databind` **2.15.4** direkt und `snakeyaml` 1.33 → Konflikte; Test scheitert mit `NoSuchMethodError`. Profil `green` setzt Jackson auf 2.18.2 → grüner Lauf |
| `order-service` | Jackson ohne Version (BOM), `jjwt-jackson` bringt eine ältere Jackson mit (verdrängt), `httpclient` bringt `commons-logging` neben `jcl-over-slf4j` (doppelte Klassen), veraltete Exclusion `json-simple`, `HttpHost` nur transitiv (undeklariert), `commons-lang3` ungenutzt |
| `billing` | `guava` 32.1.3-jre statt 33.3.1-jre; Repository `legacy-nexus` mit `http://` (Preflight rot, Build bleibt grün) |
| `notification` | Guava 33.3.1-jre, snakeyaml 2.3 |

Mit Maven 3.9.11 und JDK 21 geprüft: `mvn verify -Pgreen` → BUILD SUCCESS, `mvn verify` → BUILD FAILURE in `gateway`.
Die Kommandozeile erkennt den roten Lauf als **#10 Runtime conflict in tests · certain** („comes from jackson-databind:2.15.4,
which lacks ObjectNode.withObjectProperty() … loses against 2.15.4 (nearest wins)“).

## Vorbereitung

1. `./gradlew :intellij-plugin:runIde` und im Sandbox-IDE **diesen Ordner** (`docs/marketplace/demo`) als Projekt öffnen,
   vertrauen, Maven-Import abwarten (lädt einige MB aus Maven Central). Projekt-JDK 17 oder neuer.
2. Theme **Dark** (Standard), New UI, keine Plugins mit eigenen Toolbars. Fenster auf **1600 × 1000** stellen, damit alle Bilder
   dasselbe Seitenverhältnis (16:10) haben; Bildschirmfotos ohne Desktop und Fensterrahmen.
3. Run-Konfigurationen liegen in `.run/` und tauchen oben rechts auf: `shop-platform [verify -Pgreen]` und `shop-platform [verify]`.

## Je Bild

**01 Build Doctor**
1. `shop-platform [verify -Pgreen]` starten → grün, wird zur Referenz.
2. `shop-platform [verify]` starten → rot. Tool-Window *Maven Detective* öffnet sich mit der Karte
   *#10 Runtime conflict in tests · certain*; unter „Since the last green build“ stehen das Profil und
   `jackson-databind 2.18.2 → 2.15.4` (gateway).
3. Tool-Window rechts andocken, links die Run-Konsole mit `BUILD FAILURE` und dem Link „← Show cause“ sichtbar lassen.
   Abschnitte *Evidence* und *Since the last green build* aufklappen.

**02 Why This Version?**
1. Zuerst *Dependencies → Conflicts* einmal öffnen (füllt Code Vision im Editor).
2. `order-service/pom.xml` öffnen: Inlays hinter `jackson-databind`, `slf4j-api`, `jcl-over-slf4j`, `commons`.
3. Cursor auf `jackson-databind`, **Alt+Enter → Why This Version?** Das Popup zeigt BOM-Import, `${jackson.version}` und die von
   `jjwt-jackson` verdrängte Version.

**03 Conflict matrix** – *Dependencies → Conflicts*: 3 Abweichungen (jackson-databind, guava, snakeyaml). Zeile
`jackson-databind` auswählen, damit unten der Vorschlag „Pin 2.18.2 in shop-platform“ steht.

**04 Upgrade simulator** – `billing/pom.xml`, Rechtsklick auf die `guava`-Dependency → *Simulate Upgrade…* → `33.3.1-jre`
→ *Before/After*. Alternative mit mehr Modulen: Seite *Upgrade Simulator*, `${jackson.version}` → `2.19.2`,
erst *Download Missing POMs*, dann simulieren.

**05 Preflight** – Aktion *Preflight* (Build-Doctor-Toolbar oder Maven-Tool-Window). Ampel rot wegen `legacy-nexus`
(`http://`); ein Befund auswählen, damit „Switch the URL to https“ erscheint. *Check repositories (network)* ist optional.

**06 Duplicate classes** – *Dependencies → Duplicate Classes* → prüfen. `order-service`: `commons-logging 1.2` ↔
`jcl-over-slf4j 2.0.16`, auswählen → Empfehlung „remove commons-logging“, Exclusion an `httpclient`.

Weitere Motive: *Exclusions* (`json-simple` in order-service veraltet), *Usage* (`httpcore` undeclared, `commons-lang3` unused),
*Overview* nach *Check All*.

## Hinweise

- Ein grüner Lauf aus der IDE speichert eine Referenz unter `~/.maven-detective/snapshots/demo-*`. Für einen sauberen Verlauf
  vor den Aufnahmen löschen.
- `legacy-nexus` hinterlässt `.lastUpdated`-Dateien im lokalen Repository (Maven merkt sich den blockierten Versuch). Harmlos.
