# Releasing

A release consists of three artifacts built from the same commit: the IntelliJ plugin (`maven-detective-<v>.zip`), the command line tool (`maven-detective-cli-<v>.jar`) and the Maven extension (`maven-detective-extension-<v>.jar`). The command line tool and the extension are also published to Maven Central. The GitHub release, the JetBrains Marketplace and Maven Central are handled by two workflows:

| Workflow | Trigger | Result |
| --- | --- | --- |
| `release.yml` | Tag `v<version>` pushed | Tests, build, signed plugin, **draft** of a GitHub release with all artifacts, `SHA256SUMS` and the changelog section as text; builds the Central artifacts as a dry run (unsigned) |
| `publish.yml` | Draft published on GitHub (or manually with tag and target) | Job `marketplace`: upload to the Marketplace; pre-release versions (`0.2.0-beta.1`) → channel `eap`. Job `maven-central`: signed bundle to the Central Portal |

Published to Maven Central:

| Coordinates | Contents |
| --- | --- |
| `io.github.bitshiftdev.mavendetective:maven-detective-core` | Core and command line tool (`java -jar`), without dependencies; with Gradle module metadata |
| `io.github.bitshiftdev.mavendetective:maven-detective-extension` | Extension with the core embedded, POM **without** dependencies – for `.mvn/extensions.xml` |

Both come with `-sources.jar`, `-javadoc.jar` and `.asc` signatures (mandatory on Central). POM metadata (license, SCM, developers) and signing are configured in the root `build.gradle.kts`.

## One-time setup

1. **Create a signing certificate** ([Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)):

   ```bash
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem
   openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
   ```

   Do not put these files into the repository (keep them safe, e.g. in a password manager).
2. **Marketplace token:** on plugins.jetbrains.com → profile → *My Tokens* → new token.
3. **Secrets** in the repository (*Settings → Secrets and variables → Actions*), ideally in an environment `marketplace` (*Settings → Environments*, optionally with required approval):

   | Secret | Contents |
   | --- | --- |
   | `CERTIFICATE_CHAIN` | Contents of `chain.crt` |
   | `PRIVATE_KEY` | Contents of `private.pem` |
   | `PRIVATE_KEY_PASSWORD` | Password from step 1 |
   | `PUBLISH_TOKEN` | Marketplace token |

   `release.yml` reads the three signing secrets from the repository secrets (without them: an unsigned ZIP and a warning), `publish.yml` reads all four from the `marketplace` environment or the repository.
4. **Upload the first version manually:** the Marketplace accepts a new plugin only through the web interface (*Upload plugin*, license Apache 2.0, category *Build Tools*, source code URL pointing to the repository). Use the signed ZIP from the first release draft. Every later version goes through `publish.yml`; JetBrains' review of the first version takes a few working days.

### Maven Central

Once, before the first version goes to Central. **Caution:** Maven Central is permanent – a published version can be neither deleted nor replaced, and the `-sources.jar` files make the source code public, even while the repository is private.

1. **Account** on [central.sonatype.com](https://central.sonatype.com) via *Sign in with GitHub* as BitShiftDev. The namespace `io.github.bitshiftdev` is verified automatically (otherwise add `io.github.bitshiftdev` under *Namespaces* and verify it as instructed). It also covers `io.github.bitshiftdev.mavendetective`.
2. **User token:** *Account* → *Generate User Token* → note the name and password (not the login password).
3. **Signing key** (a dedicated one, for releases only):

   ```bash
   gpg --quick-gen-key "Maven Detective Releases <…@users.noreply.github.com>" rsa4096 sign 3y
   gpg --list-secret-keys --keyid-format long          # read the key ID
   gpg --keyserver keyserver.ubuntu.com --send-keys <ID>
   gpg --armor --export-secret-keys <ID> > maven-signing.asc   # delete after adding the secret
   ```

   Central verifies the signatures against public key servers (`keyserver.ubuntu.com`, `keys.openpgp.org`); without an uploaded public key the validation fails. Keep the private key safe (password manager), never in the repository.
4. **Create the environment** `maven-central` (*Settings → Environments*, optionally with required approval) with these secrets:

   | Secret | Contents |
   | --- | --- |
   | `MAVEN_SIGNING_KEY` | Contents of `maven-signing.asc` (ASCII-armored, private) |
   | `MAVEN_SIGNING_PASSWORD` | Password of the key |
   | `CENTRAL_USERNAME` | Name from the user token |
   | `CENTRAL_PASSWORD` | Password from the user token |

   If all four are missing, `publish.yml` skips Maven Central with a warning; if only some are missing, the job fails.
5. **Publishing type:** the default is `USER_MANAGED` – the portal validates the bundle, and it is published only when you click *Publish* under [Deployments](https://central.sonatype.com/publishing/deployments) (until then it can be discarded with *Drop*). Once the first releases went smoothly: set the variable `CENTRAL_PUBLISHING_TYPE=AUTOMATIC` in the environment.

To try it locally (e.g. before the first release):

```bash
./gradlew centralBundle -PsigningGpg=true -Psigning.gnupg.keyName=<ID>   # → build/central-bundle-<v>.zip
CENTRAL_USERNAME=… CENTRAL_PASSWORD=… bash .github/scripts/central-upload.sh build/central-bundle-<v>.zip
```

Without a signature `centralBundle` fails; it does not accept `-SNAPSHOT` versions. With `USER_MANAGED` the upload is not yet final – review it in the portal and click *Drop* if it was only a test.

## Steps for each release

1. `CHANGELOG.md`: move the entries under `## [Unreleased]` into a new section `## [x.y.z] - YYYY-MM-DD` (in English – the section becomes the change notes and the release text) and update the comparison links at the bottom.
2. `gradle.properties`: `version=x.y.z` (without `-SNAPSHOT`).
3. Check locally:

   ```bash
   ./gradlew :core:test :intellij-plugin:test :maven-extension:fixtureTest :intellij-plugin:buildPlugin :intellij-plugin:releaseNotes
   ```

   `releaseNotes` fails if the changelog section is missing. The Plugin Verifier (Gate 2) runs in `build.yml` on `main` – wait for it to be green before tagging.
4. Commit, then create and push the tag:

   ```bash
   git tag -a vx.y.z -m "Maven Detective x.y.z"
   git push origin main vx.y.z
   ```

5. `release.yml` creates the draft. Check the text and the attachments, install the ZIP once via *Install Plugin from Disk…*, then click **Publish release** → `publish.yml` uploads to the Marketplace and the Central Portal.
   With `USER_MANAGED`: review the validated deployment in the portal under *Deployments* and click **Publish**. After 10–30 minutes the version is available on Maven Central (`https://repo1.maven.org/maven2/io/github/bitshiftdev/mavendetective/`).
6. Afterwards set `gradle.properties` to the next `-SNAPSHOT` version.

## Making the repository public

Already prepared: the README (English, with installation), `CONTRIBUTING.md`, `SECURITY.md`, issue and PR templates. Before switching, do this manually:

- [ ] **Author address in the Git history:** all previous commits carry the private email address, which becomes public once the repository is switched. Workaround: use the GitHub `noreply` address from now on (`git config user.email …@users.noreply.github.com`); the old commits could only be changed by rewriting the history (force push, all commit IDs change).
- [x] Planning documents (`Maven Detective – Plugin-Konzept.md`, `… – UI-Plan.md`) and the internal status (`STATUS.local.md`, previously in the README) are kept local only (`.gitignore`).
- [ ] **Old versions in the Git history:** earlier commits contain the planning documents and the README with open acceptance items. If they should not become public, clean the history before switching (e.g. `git filter-repo --path "Maven Detective – Plugin-Konzept.md" --path "Maven Detective – UI-Plan.md" --invert-paths`; the old README could only be removed by rewriting every commit) – or start over with a fresh commit without history.
- [ ] Review `.claude/skills/maven-detective/SKILL.md`: the section *Open items* and the references to plan files and the README's *Next steps* (now `STATUS.local.md`) – keep public, shorten or remove from Git?
- [ ] *Settings → General*: description "Explains why a Maven build fails – IntelliJ plugin, Maven extension and CLI", topics (`maven`, `intellij-plugin`, `build-tools`, `dependency-analysis`), website = Marketplace page.
- [ ] *Settings → Code security*: enable private vulnerability reporting, Dependabot alerts and secret scanning.
- [ ] *Settings → Branches*: protection rule for `main` (CI must be green).
- [ ] Optionally *Discussions* for questions.
- [ ] Only then *Settings → Danger Zone → Change visibility → Public*.
