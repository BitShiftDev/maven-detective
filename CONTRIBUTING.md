# Mitmachen

> **English:** Contributions are welcome in English or German. Code comments, commit messages and the README are
> German; please keep new user-facing texts in both `…properties` files (English is the default). Build with JDK 21:
> `./gradlew :core:test :intellij-plugin:test`. For a misdiagnosed build, open a “Wrong diagnosis” issue with an
> anonymised log excerpt.

## Fehler melden

- **Falsch oder gar nicht erkannter Build:** Vorlage „Falsche Diagnose“. Am wertvollsten ist der Log-Ausschnitt rund um `BUILD FAILURE` und die tatsächliche Ursache. Im Plugin legt „Falsch erkannt?“ auf der Karte das Log unter `~/.maven-detective/testfaelle/` ab – **vor dem Hochladen Namen, Hosts, Pfade und Zugangsdaten entfernen.**
- **Fehler im Plugin, in der CLI oder der Extension:** Vorlage „Fehler“ mit IDE-, Maven- und JDK-Version.
- **Sicherheitslücken** nicht als Issue, sondern wie in [SECURITY.md](SECURITY.md).

## Bauen

Voraussetzung: JDK 21 (Kern und Extension werden trotzdem als Java-8-Bytecode gebaut).

```bash
./gradlew :core:test                    # Unit-Tests inkl. Fehlerkatalog
./gradlew :intellij-plugin:test         # Plugin-Tests (Texte, Farben, Fix-Planung)
./gradlew :maven-extension:fixtureTest  # löst jeden Fehler aus fixtures/ mit echtem Maven aus (mvn im PATH)
./gradlew :intellij-plugin:runIde       # IntelliJ mit dem Plugin starten
```

## Regeln für Änderungen

- **`core` bleibt ohne Laufzeit-Abhängigkeiten** und auf Java-8-API (kein `var`, keine Records, kein `List.of`, keine Text-Blocks): Er läuft im Classloader von Maven, auch unter Java 8. CI prüft das im Job `extension-on-java8`.
- **Prüfungen lesen nur**, ändern nichts und gehen nie ins Netz. Netz gibt es nur auf ausdrücklichen Wunsch (Preflight `--network`, Upgrade-Simulator „Fehlende POMs laden“).
- **Die Extension ändert nie den Build** und schluckt jeden eigenen Fehler.
- **Kein sichtbarer Text im Code:** Kern über `Texts.t(…)` (`texts.properties` = Englisch, `texts_de.properties`), Plugin über `MavenDetectiveBundle.message(…)`. Jeder neue Schlüssel gehört in beide Dateien; `TextsTest` und `MavenDetectiveBundleTest` prüfen das.
- **Jede POM-Änderung im Plugin** läuft über `FixRunner.editPoms` (Diff-Vorschau, ein Undo-Schritt).

### Neue Regel im Fehlerkatalog

1. Regel in `core/src/main/resources/io/github/bitshiftdev/mavendetective/core/catalog/build-doctor-v1.yaml` eintragen (Felder im Dateikopf; Regex in einfachen Anführungszeichen, zu jedem Text ein `…_en:`).
2. Testlog `core/src/test/resources/logs/<regel-id>__<beschreibung>.log` anlegen – `LogCatalogTest` verlangt mindestens eines je Regel.
3. Wenn reproduzierbar: Fixture unter `fixtures/NN-name/` und Zeile in `fixtures/fixtures.txt`.
4. README-Abschnitte ergänzen, falls neue Kategorien oder Prüfungen dazukommen.

Eigene Regeln lassen sich auch ohne Änderung am Projekt ausprobieren: `diagnose … --catalog eigene.yaml` und `validate-catalog eigene.yaml` (siehe README, „Fehlerkatalog erweitern“).

## Pull Requests

- Ein Thema je Pull Request; Tests für neues Verhalten.
- Commit-Nachrichten auf Deutsch oder Englisch, kurze erste Zeile im Stil von `git log`.
- Neue Funktionen kurz unter `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md) eintragen (Englisch – der Abschnitt wird zu den Release-Notes).

Mit einem Beitrag stimmst du zu, dass er unter der [Apache License 2.0](LICENSE) steht.
