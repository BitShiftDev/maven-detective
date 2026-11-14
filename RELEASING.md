# Release

Ein Release besteht aus drei Artefakten aus demselben Stand: IntelliJ-Plugin (`maven-detective-<v>.zip`), Kommandozeile (`maven-detective-cli-<v>.jar`) und Maven-Extension (`maven-detective-extension-<v>.jar`). Kommandozeile und Extension gehen zusätzlich nach Maven Central. GitHub-Release, JetBrains Marketplace und Maven Central laufen über zwei Workflows:

| Workflow | Auslöser | Ergebnis |
| --- | --- | --- |
| `release.yml` | Tag `v<version>` gepusht | Tests, Build, signiertes Plugin, **Entwurf** eines GitHub-Releases mit allen Artefakten, `SHA256SUMS` und dem Changelog-Abschnitt als Text; baut die Central-Artefakte probehalber (unsigniert) |
| `publish.yml` | Entwurf auf GitHub veröffentlicht (oder von Hand mit Tag und Ziel) | Job `marketplace`: Upload auf den Marketplace; Vorabversion (`0.2.0-beta.1`) → Kanal `eap`. Job `maven-central`: signiertes Bundle ins Central Portal |

In Maven Central landen:

| Koordinaten | Inhalt |
| --- | --- |
| `io.github.bitshiftdev.mavendetective:maven-detective-core` | Kern und Kommandozeile (`java -jar`), ohne Abhängigkeiten; mit Gradle-Modul-Metadaten |
| `io.github.bitshiftdev.mavendetective:maven-detective-extension` | Extension mit eingebettetem Kern, POM **ohne** Abhängigkeiten – für `.mvn/extensions.xml` |

Beide mit `-sources.jar`, `-javadoc.jar` und `.asc`-Signaturen (Pflicht in Central). POM-Angaben (Lizenz, SCM, Entwickler) und Signatur stehen in `build.gradle.kts` im Wurzelverzeichnis.

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

### Maven Central

Einmalig, bevor die erste Version nach Central geht. **Achtung:** Maven Central ist endgültig – eine veröffentlichte Version lässt sich weder löschen noch ersetzen, und die `-sources.jar` machen den Quellcode öffentlich, auch solange das Repository privat ist.

1. **Konto** auf [central.sonatype.com](https://central.sonatype.com) mit *Sign in with GitHub* als BitShiftDev anlegen. Der Namespace `io.github.bitshiftdev` wird dabei automatisch verifiziert (sonst unter *Namespaces* → `io.github.bitshiftdev` hinzufügen und wie angegeben bestätigen). Er deckt `io.github.bitshiftdev.mavendetective` mit ab.
2. **User-Token:** *Account* → *Generate User Token* → Name und Passwort notieren (nicht das Login-Passwort).
3. **Signierschlüssel** (eigener, nur für Releases):

   ```bash
   gpg --quick-gen-key "Maven Detective Releases <…@users.noreply.github.com>" rsa4096 sign 3y
   gpg --list-secret-keys --keyid-format long          # Schlüssel-ID ablesen
   gpg --keyserver keyserver.ubuntu.com --send-keys <ID>
   gpg --armor --export-secret-keys <ID> > maven-signing.asc   # nach dem Eintragen löschen
   ```

   Central prüft die Signaturen gegen öffentliche Keyserver (`keyserver.ubuntu.com`, `keys.openpgp.org`); ohne hochgeladenen öffentlichen Schlüssel scheitert die Prüfung. Den privaten Schlüssel sicher aufbewahren (Passwort-Manager), nicht ins Repository.
4. **Umgebung** `maven-central` anlegen (*Settings → Environments*, optional mit Freigabe-Pflicht) mit den Secrets:

   | Secret | Inhalt |
   | --- | --- |
   | `MAVEN_SIGNING_KEY` | Inhalt von `maven-signing.asc` (ASCII-armored, privat) |
   | `MAVEN_SIGNING_PASSWORD` | Passwort des Schlüssels |
   | `CENTRAL_USERNAME` | Name aus dem User-Token |
   | `CENTRAL_PASSWORD` | Passwort aus dem User-Token |

   Fehlen alle vier, überspringt `publish.yml` Maven Central mit einer Warnung; fehlen einzelne, schlägt der Job fehl.
5. **Freigabe-Art:** Standard ist `USER_MANAGED` – das Portal prüft das Bundle, veröffentlicht wird erst per *Publish* unter [Deployments](https://central.sonatype.com/publishing/deployments) (bis dahin per *Drop* verwerfbar). Wenn die ersten Releases glatt liefen: Variable `CENTRAL_PUBLISHING_TYPE=AUTOMATIC` in der Umgebung setzen.

Lokal ausprobieren (z. B. vor dem ersten Release):

```bash
./gradlew centralBundle -PsigningGpg=true -Psigning.gnupg.keyName=<ID>   # → build/central-bundle-<v>.zip
CENTRAL_USERNAME=… CENTRAL_PASSWORD=… bash .github/scripts/central-upload.sh build/central-bundle-<v>.zip
```

Ohne Signatur bricht `centralBundle` ab; `-SNAPSHOT`-Versionen nimmt es nicht. Der Upload ist mit `USER_MANAGED` noch nicht endgültig – im Portal ansehen und *Drop* drücken, wenn es nur ein Versuch war.

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

5. `release.yml` legt den Entwurf an. Text und Anhänge prüfen, ZIP einmal per *Install Plugin from Disk…* einspielen, dann **Publish release** → `publish.yml` lädt auf den Marketplace und ins Central Portal.
   Mit `USER_MANAGED`: im Portal unter *Deployments* den geprüften Stand ansehen und **Publish** drücken. Nach 10–30 Minuten liegt die Version in Maven Central (`https://repo1.maven.org/maven2/io/github/bitshiftdev/mavendetective/`).
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
