# Contributing

Contributions are welcome in English or German. Documentation is written in English; code comments and commit
messages in the existing code are German.

## Reporting problems

- **A build that was misdiagnosed or not recognised at all:** use the *Wrong diagnosis* template. The most valuable part is the log excerpt around `BUILD FAILURE` and the actual cause. In the plugin, *Wrong diagnosis?* on the card saves the log under `~/.maven-detective/testfaelle/` – **remove names, hosts, paths and credentials before uploading it.**
- **A bug in the plugin, the CLI or the extension:** use the *Bug* template and include your IDE, Maven and JDK versions.
- **Security vulnerabilities** must not be reported as an issue – follow [SECURITY.md](SECURITY.md) instead.

## Building

Requires JDK 21 (core and extension are still compiled to Java 8 bytecode).

```bash
./gradlew :core:test                    # unit tests incl. the error catalog
./gradlew -Pcoverage :core:test :core:jacocoTestReport  # with JaCoCo coverage → core/build/reports/jacoco/
./gradlew :intellij-plugin:test         # plugin tests (texts, colours, fix planning)
./gradlew :maven-extension:fixtureTest  # triggers every failure in fixtures/ with a real Maven (mvn on PATH)
./gradlew :intellij-plugin:runIde       # start IntelliJ IDEA with the plugin
```

## Rules for changes

- **`core` has no runtime dependencies** and sticks to the Java 8 API (no `var`, no records, no `List.of`, no text blocks): it runs inside Maven's class loader, also on Java 8. CI checks this in the `extension-on-java8` job.
- **Checks only read.** They change nothing and never access the network. The network is used only on explicit request (preflight `--network`, upgrade simulator *Download Missing POMs*).
- **The extension never changes the build** and swallows every error of its own.
- **No user-facing text in code:** the core uses `Texts.t(…)` (`texts.properties` = English, `texts_de.properties`), the plugin uses `MavenDetectiveBundle.message(…)`. Every new key belongs in both files; `TextsTest` and `MavenDetectiveBundleTest` check this.
- **Every POM change in the plugin** goes through `FixRunner.editPoms` (diff preview, a single undo step).

### Adding a rule to the error catalog

1. Add the rule to `core/src/main/resources/io/github/bitshiftdev/mavendetective/core/catalog/build-doctor-v1.yaml` (fields are documented in the file header; regexes in single quotes, an `…_en:` next to every text).
2. Add at least three test logs `core/src/test/resources/logs/<rule-id>__<description>.log` (e.g. Maven 3.9 and Maven 4, a second message form, a CI prefix or Windows) – `LogCatalogTest` requires three per rule. Windows logs with CRLF line endings get `crlf` in the file name. Near misses that must *not* be recognised as the rule go into `logs-negative/`, logs with two errors into `logs-multi/<higher-priority-rule>+<other-rule>__<description>.log`.
3. If the failure is reproducible: add a fixture under `fixtures/NN-name/` and a line in `fixtures/fixtures.txt`.
4. Update the README if new categories or checks are added: table *What it recognises* or list *Available checks*.

You can try your own rules without changing the project: `diagnose … --catalog my-rules.yaml` and `validate-catalog my-rules.yaml` (see the README, *Extend the error catalog*).

## Pull requests

- One topic per pull request; tests for new behaviour.
- Commit messages in English or German, with a short first line in the style of `git log`.
- Add new features briefly under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md) (in English – the section becomes the release notes).

By contributing, you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).
