# Changelog

<!-- Englisch, weil der Abschnitt der jeweiligen Version als Change-Notes auf den JetBrains Marketplace
     und als Text in das GitHub-Release geht (build.gradle.kts → changeNotes, Aufgabe releaseNotes).
     Format: https://keepachangelog.com/ – je Release ein Abschnitt "## [x.y.z] - JJJJ-MM-TT",
     darunter "### …"-Gruppen und "- "-Listenpunkte. Inline erlaubt: **fett**, `Code`, [Text](URL). -->

## [Unreleased]

### Preflight

- New setting to switch the preflight check off: the action, the overview tile and the view disappear.
- New setting **Custom Nexus** (CLI: `--nexus URL`): the network check asks every repository through your own repository manager, like a mirror with `mirrorOf *`. The preflight notes whether a `settings.xml` mirror points there too.

### Fixed

- Quick fixes keep the encoding of the file they change: a POM or `settings.xml` declared as `ISO-8859-1` (or starting with a byte order mark) is written back in the same encoding instead of UTF-8, so umlauts stay intact. A file whose bytes do not match its declared encoding is left unchanged.
- The "switch to https" fix for a repository in a POM now runs like every other POM change in the IDE: with preview, as one undo step and followed by a Maven reimport. It changes only `<url>` values that are exactly the blocked address – longer URLs with the same beginning and comments stay as they are (also in `settings.xml`).
- POM fixes no longer create a second element when a container is written empty, such as `<dependencies/>`, `<exclusions/>`, `<properties/>` or `<dependencyManagement/>`; Maven rejected such a POM.
- Removing the last dependency or exclusion keeps the surrounding block if it still holds comments.
- `set-jvm-option` changes `.mvn/jvm.config` line by line and keeps comments, instead of joining everything into one line.
- A dependency declared both as `jar` and as `test-jar` is now judged and changed per type: "Remove", "set scope" and "add exclusion" hit exactly the meant entry instead of the first one with the same coordinates.
- Dependency management is matched by `groupId:artifactId:type[:classifier]` like Maven does. A managed `test-jar` no longer turns the plain `jar` into a test dependency, which led to a wrong "set scope to compile" card.
- Exclusion check: if a POM on the way is relocated or brings dependencies in a profile, the exclusion is reported as "cannot be checked" instead of stale. The editor quick fix "Remove exclusion" now runs with diff preview, as one undo step and with a Maven reimport.
- Unchecked findings stay unchecked after a search, a filter or a recalculation (for example after removing the other exclusions), so a later "Remove" no longer takes them along.
- Unused dependencies: if a named class lies in a declared dependency and also in a transitive JAR, it counts for the declared one – no more "unused" with a remove fix for it.
- The plugin version check only suggests "Insert version" for a `<plugin>` that really has no `<version>`; dependencies, configuration and comments with the same artifactId no longer count.
- Builds run from the IDE are diagnosed with the IDE's Maven settings (user `settings.xml`, local repository, runner JDK) instead of `~/.m2`. Deleting `.lastUpdated` files works with a custom local repository, including `<localRepository>` from `settings.xml`.
- Logs with common CI and logger prefixes are understood: date and time with a space (`2026-11-12 06:48:01`), GitLab timestamps with stream marker, thread names (`[main]`) and milliseconds before `[ERROR]`. Maven and Java version from `mvn -V` are read behind such prefixes too. If the CLI finds no error line although the log says `BUILD FAILURE`, it reports an unknown line format (exit code 4) instead of "no errors".
- A `403 Forbidden` or `ZipException` from a failed test no longer becomes the main diagnosis "check credentials" or "corrupt JAR"; lines from Surefire/Failsafe test output only count for test-related rules. Catalog rules can opt in with `outsideTests: true`.
- Very long log lines (class paths, Base64) no longer delay the end of the build: path and host patterns only start at word boundaries, and searched lines are cut at 4,000 characters. A 60,000-character line took about 15 s before.
- The Maven extension writes the events file thread-safe; under `-T` lines no longer get mixed up and lost.
- Network paths from a log (`\\host\share\…`) are never opened, so a foreign log cannot trigger an SMB connection on Windows.
- The preflight network check never sends credentials, not even through the IDE's own authenticator.
- Notifications show texts with angle brackets such as `<server>` instead of swallowing them. The English UI no longer shows German leftovers such as "→ guava raus".
- Preflight: commented-out settings, settings inside `<profiles>` and `pom.xml` files that are not modules of the build (examples, test data) no longer turn the light red. Placeholders in `settings.xml` such as `${env.NEXUS_URL}` or `${user.home}` are resolved like Maven does; a mirror address that stays unresolved is not requested and gets no verdict. Maven installed by the Maven Wrapper 3.3.x (`only-script`) is found, so its global `settings.xml` counts again.
- CI-friendly versions (`${revision}`): sibling modules of the reactor are found instead of reported as missing, so trees, "Why this version?", the upgrade simulator and class origin see their dependencies.
- Version comparison now follows Maven's `ComparableVersion` exactly (for example `1.0-1 < 1.0.1`, `1.0.0-rc1 == 1.0-rc1`, very long numbers); the conflict matrix and the upgrade simulator no longer mix up versions or call an upgrade a downgrade.
- POM fixes find dependencies declared with expressions such as `${project.groupId}`. A property defined twice counts with its last value, like in Maven – for "Why this version?" and when it is changed.
- "Changes since the last green build": a short green run such as `mvn compile` no longer drops test dependencies from the reference, and only scopes both runs resolved are compared – no more invented "new dependency: junit".
- Stopping an analysis ends its loading indicator and "running" state at once; the analyses also stop computing. Errors – including `OutOfMemoryError` – no longer leave a page, the setup tile, the preflight or the upgrade editor tab spinning or greyed out.
- Faster analyses in large reactors: `target/classes` of reactor modules is read once per run and afterwards only when class files change (duplicate classes, unused/undeclared dependencies, live inspection); lookups in dependency management and the reactor are indexed; the upgrade simulator reuses parsed POMs, only resolves modules again that see a changed POM and can be stopped.

### Distribution

- Core/CLI and the Maven extension are published to Maven Central as `io.github.bitshiftdev.mavendetective:maven-detective-core` and `io.github.bitshiftdev.mavendetective:maven-detective-extension`. The extension can now be added to a project via `.mvn/extensions.xml`.

## [0.1.0] - 2026-11-13

First public release: IntelliJ plugin, Maven core extension and command line share one analysis core.

### Build Doctor

- Explains a failed Maven build as a card with cause, evidence, confidence (`certain`/`likely`) and a quick fix – in the IDE, in the Maven log (extension) and on the command line.
- Error catalog v1 with 14 rules: cached failed downloads (`.lastUpdated`), missing credentials, blocked http repositories, corrupt JARs, invalid or unresolvable POMs, reactor problems, wrong JDK, missing plugins, enforcer rules, out of memory, runtime class conflicts, missing compile dependencies, EAR/WAR/EJB packaging and failed tests.
- Class origin for `NoSuchMethodError`, `NoClassDefFoundError` and `package … does not exist`: which JAR the JVM loads, whether it has the member, which version lost or which exclusion removed the class.
- **What changed?** Every green run becomes the reference; a later red run lists JDK, Maven, profile, parent, plugin and dependency changes since then.
- **IDE vs. command line:** compares Maven, JDK, `settings.xml`, profiles and `.mvn/` configuration.
- **Preflight:** traffic light for `settings.xml`, JDK, Maven, project, local repository and (on request) remote repositories before the build runs.
- **Log navigator:** modules, plugin steps, the failed module and the first real error; download noise folded, also in the console.
- Quick fixes: rebuild with `-U`/`-am`, runner JDK, plugin version, delete `.lastUpdated` files, JVM option; fix-and-check loop rebuilds and reports "Fixed ✓" or the new diagnosis.

### Dependency analysis

- **Why this version?** Decision chain for every dependency – declaration, dependencyManagement, imported BOM, property, nearest-wins path – each step links to its POM line.
- **Conflict matrix:** artifact by module for the whole reactor; pins a deviating version in the common parent.
- **Duplicate classes:** classes in more than one JAR of a module, also under different coordinates, with the exclusion that removes the redundant JAR.
- **Exclusion hygiene:** inspection and page for exclusions that no longer exclude anything.
- **Unused and undeclared dependencies:** based on the classes your sources name, with declare, remove and change-scope fixes; live inspection on imports.
- **Upgrade simulator:** shows what goes up, down, appears or disappears in every module's tree before a version, BOM, parent or property changes; applies exactly the simulated change.

### IntelliJ plugin

- Tool window with Overview, Build Doctor and Dependencies; badge on red builds; results marked stale after each Maven import.
- Hints in `pom.xml`: managed version inlay, "3 modules · 2 versions" code vision, gutter icons, banner on the file the last build failed on.
- Every quick fix that edits a `pom.xml` shows a diff first (can be switched off).
- Settings under *Tools | Maven Detective*; user interface and diagnoses in English or German.
- Supports IntelliJ IDEA 2025.3 and later.

### Command line and Maven extension

- CLI `java -jar maven-detective-core.jar` with `diagnose`, `changes`, `why`, `matrix`, `exclusions`, `duplicates`, `analyze`, `upgrade`, `preflight`, `outline`, `evaluate` and `validate-catalog`.
- Maven core extension (`-Dmaven.ext.class.path=…`) prints the diagnosis card into the Maven log and can write structured events; runs on Maven 3.9 with Java 8 and later and never changes the build.

[Unreleased]: https://github.com/BitShiftDev/maven-detective/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/BitShiftDev/maven-detective/releases/tag/v0.1.0
