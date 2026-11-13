# Release

Ein Release besteht aus drei Artefakten aus demselben Stand: IntelliJ-Plugin (`maven-detective-<v>.zip`), Kommandozeile (`maven-detective-cli-<v>.jar`) und Maven-Extension (`maven-detective-extension-<v>.jar`). GitHub-Release und JetBrains Marketplace laufen über zwei Workflows:

| Workflow | Auslöser | Ergebnis |
| --- | --- | --- |
| `release.yml` | Tag `v<version>` gepusht | Tests, Build, signiertes Plugin, **Entwurf** eines GitHub-Releases mit allen Artefakten, `SHA256SUMS` und dem Changelog-Abschnitt als Text |
| `publish.yml` | Entwurf auf GitHub veröffentlicht (oder von Hand mit Tag) | Upload auf den Marketplace; Vorabversion (`0.2.0-beta.1`) → Kanal `eap` |

## Einmalig einrichten

1. **Signierzertifikat** erzeugen ([Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)):

   ```bash
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem
   openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
   ```

   Dateien nicht ins Repository legen (sicher aufbewahren, z. B. im Passwort-Manager).
2. **Marketplace-Token:** auf plugins.jetbrains.com → Profil → *My Tokens* → neues Token.
3. **Secrets** im Repository (*Settings → Secrets and variables → Actions*), am besten in einer Umgebung `marketplace` (*Settings → Environments*, optional mit Freigabe-Pflicht):

   | Secret | Inhalt |
   | --- | --- |
   | `CERTIFICATE_CHAIN` | Inhalt von `chain.crt` |
   | `PRIVATE_KEY` | Inhalt von `private.pem` |
   | `PRIVATE_KEY_PASSWORD` | Passwort aus Schritt 1 |
   | `PUBLISH_TOKEN` | Marketplace-Token |

   `release.yml` liest die drei Signier-Secrets aus den Repository-Secrets (ohne sie: unsigniertes ZIP und eine Warnung), `publish.yml` alle vier aus der Umgebung `marketplace` oder dem Repository.
4. **Erste Version von Hand hochladen:** Der Marketplace nimmt ein neues Plugin nur über die Weboberfläche an (*Upload plugin*, Lizenz Apache 2.0, Kategorie *Build Tools*, Quellcode-URL auf das Repository). Dafür das signierte ZIP aus dem ersten Release-Entwurf nehmen. Danach läuft jede weitere Version über `publish.yml`; die Prüfung durch JetBrains dauert bei der ersten Version ein paar Werktage.

## Ablauf je Release

1. `CHANGELOG.md`: Einträge unter `## [Unreleased]` in einen neuen Abschnitt `## [x.y.z] - JJJJ-MM-TT` verschieben (Englisch – der Abschnitt wird zu Change-Notes und Release-Text), Vergleichslinks unten anpassen.
2. `gradle.properties`: `version=x.y.z` (ohne `-SNAPSHOT`).
3. Lokal prüfen:

   ```bash
   ./gradlew :core:test :intellij-plugin:test :maven-extension:fixtureTest :intellij-plugin:buildPlugin :intellij-plugin:releaseNotes
   ```

   `releaseNotes` schlägt fehl, wenn der Changelog-Abschnitt fehlt. Plugin Verifier (Gate 2) läuft in `build.yml` auf `main` – vor dem Tag grün abwarten.
4. Commit, dann Tag setzen und pushen:

   ```bash
   git tag -a vx.y.z -m "Maven Detective x.y.z"
   git push origin main vx.y.z
   ```

5. `release.yml` legt den Entwurf an. Text und Anhänge prüfen, ZIP einmal per *Install Plugin from Disk…* einspielen, dann **Publish release** → `publish.yml` lädt auf den Marketplace.
6. Danach `gradle.properties` auf die nächste `-SNAPSHOT`-Version setzen.

## Repository öffentlich stellen

Vorbereitet sind README (mit englischer Kurzfassung und Installation), `CONTRIBUTING.md`, `SECURITY.md`, Issue- und PR-Vorlagen. Vor dem Umschalten von Hand:

- [ ] **Autor-Adresse in der Git-Historie:** Alle bisherigen Commits tragen die private Mail-Adresse, die mit dem Umschalten öffentlich wird. Ausweg: künftig die GitHub-`noreply`-Adresse verwenden (`git config user.email …@users.noreply.github.com`); die alten Commits ließen sich nur durch Umschreiben der Historie ändern (Force-Push, alle Commit-IDs ändern sich).
- [ ] Planungsdokumente (`Maven Detective – Plugin-Konzept.md`, `… – UI-Plan.md`) und `.claude/skills/` durchsehen: Sollen sie öffentlich sein?
- [ ] *Settings → General*: Beschreibung „Explains why a Maven build fails – IntelliJ plugin, Maven extension and CLI“, Topics (`maven`, `intellij-plugin`, `build-tools`, `dependency-analysis`), Website = Marketplace-Seite.
- [ ] *Settings → Code security*: Private Vulnerability Reporting, Dependabot Alerts und Secret Scanning einschalten.
- [ ] *Settings → Branches*: Schutzregel für `main` (CI muss grün sein).
- [ ] Optional *Discussions* für Fragen.
- [ ] Erst dann *Settings → Danger Zone → Change visibility → Public*.
