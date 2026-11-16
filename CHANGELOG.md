# Changelog

<!-- Englisch, weil der Abschnitt der jeweiligen Version als Change-Notes auf den JetBrains Marketplace
     und als Text in das GitHub-Release geht (build.gradle.kts → changeNotes, Aufgabe releaseNotes).
     Format: https://keepachangelog.com/ – je Release ein Abschnitt "## [x.y.z] - JJJJ-MM-TT",
     darunter "### …"-Gruppen und "- "-Listenpunkte. Inline erlaubt: **fett**, `Code`, [Text](URL). -->

## [Unreleased]

### Fixed

- **What changed?** in the IDE no longer reports "No change" when the green and the red run differ only in their profiles or `-D` arguments (e.g. `verify -Pgreen` vs. `verify`). Profiles and properties are now read from the run configuration's goals and command-line options too, the card lists the switched profile and the versions the POMs declare directly under it (e.g. `jackson-databind 2.18.2 → 2.15.4 (gateway)`), and it says when transitive effects could not be compared because the IDE reads dependencies from its Maven import.
- Maven extension: a failing Surefire/Failsafe test (`NoSuchMethodError`, `NoClassDefFoundError` …) is now diagnosed from the test reports instead of showing "Unknown error"; only reports written by the current run are read.
- Maven extension: the reference of a green `verify` run kept only the dependencies of the last plugin (runtime scope), so the next red run listed test dependencies such as JUnit as "new".
- Upgrade simulator: no more "version changed from X to X" steps for modules that already have the target version.
- Upgrade simulator: when POMs are missing locally, an unknown version is no longer shown as a downgrade ("→ null"); lower versions, removals and new conflicts of such a module only appear once the POMs are there.
- POM quick fixes find dependencies written with entities or CDATA (`<groupId>org&#46;acme</groupId>`), keep a comment next to the value (`<version>1.0<!-- LTS --></version>`) and escape what they write: a property value like `a&b` no longer produces invalid XML, and an invalid property name is rejected.
- Loading missing POMs for the upgrade simulator ignores commented-out `<repositories>` and coordinates whose version contains XML characters.
- IDE: the quick fix "Remove exclusion" (when an exclusion removes the class a `NoClassDefFoundError` is looking for) is now offered on the diagnosis card and runs with diff preview and undo; before, the button was missing.

## [0.1.0] - 2026-11-14

First public release: IntelliJ plugin, Maven core extension and command line share one analysis core.

### Build Doctor

- Explains a failed Maven build as a card with cause, evidence, confidence (`certain`/`likely`) and a quick fix – in the IDE, in the Maven log (extension) and on the command line.
- Error catalog v1 with 14 rules: cached failed downloads (`.lastUpdated`), missing credentials, blocked http repositories, corrupt JARs, invalid or unresolvable POMs, reactor problems, wrong JDK, missing plugins, enforcer rules, out of memory, runtime class conflicts, missing compile dependencies, EAR/WAR/EJB packaging and failed tests.
- Class origin for `NoSuchMethodError`, `NoClassDefFoundError` and `package … does not exist`: which JAR the JVM loads, whether it has the member, which version lost or which exclusion removed the class.
- **What changed?** Every green run becomes the reference; a later red run lists JDK, Maven, profile, parent, plugin and dependency changes since then.
- **IDE vs. command line:** compares Maven, JDK, `settings.xml`, profiles and `.mvn/` configuration.
- **Preflight:** traffic light for `settings.xml`, JDK, Maven, project, local repository and (on request) remote repositories before the build runs. Remote repositories can be checked through your own repository manager (**Custom Nexus**, CLI `--nexus URL`); the preflight can be switched off in the settings.
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
- Core/CLI and the extension are published to Maven Central as `io.github.bitshiftdev.mavendetective:maven-detective-core` and `io.github.bitshiftdev.mavendetective:maven-detective-extension`; the extension can be added to a project via `.mvn/extensions.xml`.

[Unreleased]: https://github.com/BitShiftDev/maven-detective/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/BitShiftDev/maven-detective/releases/tag/v0.1.0
