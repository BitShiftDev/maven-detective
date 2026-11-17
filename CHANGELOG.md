# Changelog

<!-- Each version's section becomes the change notes on the JetBrains Marketplace
     and the text of the GitHub release (build.gradle.kts → changeNotes, task releaseNotes).
     Format: https://keepachangelog.com/ – one section "## [x.y.z] - YYYY-MM-DD" per release,
     with "### …" groups and "- " list items below. Allowed inline: **bold**, `code`, [text](URL). -->

## [Unreleased]

### Fixed

- IDE: undoing a POM quick fix takes a single **Undo** again; before, the first one only moved the caret back from the changed line.

## [0.1.1] - 2026-11-17

### Fixed

- **What changed?** in the IDE no longer reports "No change" when the green and the red run differ only in their profiles or `-D` arguments (e.g. `verify -Pgreen` vs. `verify`). Profiles and properties are now read from the run configuration's goals and command-line options too, the card lists the switched profile and the versions the POMs declare directly under it (e.g. `jackson-databind 2.18.2 → 2.15.4 (gateway)`), and it says when transitive effects could not be compared because the IDE reads dependencies from its Maven import.
- Maven extension: a failing Surefire/Failsafe test (`NoSuchMethodError`, `NoClassDefFoundError` …) is now diagnosed from the test reports instead of showing "Unknown error"; only reports written by the current run are read.
- Maven extension: the reference of a green `verify` run kept only the dependencies of the last plugin (runtime scope), so the next red run listed test dependencies such as JUnit as "new".
- Upgrade simulator: no more "version changed from X to X" steps for modules that already have the target version.
- Upgrade simulator: when POMs are missing locally, an unknown version is no longer shown as a downgrade ("→ null"); lower versions, removals and new conflicts of such a module only appear once the POMs are there.
- POM quick fixes find dependencies written with entities or CDATA (`<groupId>org&#46;acme</groupId>`), keep a comment next to the value (`<version>1.0<!-- LTS --></version>`) and escape what they write: a property value like `a&b` no longer produces invalid XML, and an invalid property name is rejected.
- Loading missing POMs for the upgrade simulator ignores commented-out `<repositories>` and coordinates whose version contains XML characters.
- IDE: the quick fix "Remove exclusion" (when an exclusion removes the class a `NoClassDefFoundError` is looking for) is now offered on the diagnosis card and runs with diff preview and undo; before, the button was missing.
- Paths with spaces (`C:\Users\Jane Doe\…`, `OneDrive - Company`) are kept whole: the compiler error opens the right file, a corrupt JAR is offered for deletion, a missing child module is named.
- An exception that the application logs without `[ERROR]` (e.g. from a passing test) no longer produces a diagnosis for a successful build and no longer outranks a real Maven error.
- Dependency analysis: exclusions from the `dependencyManagement` of a dependency's own POM apply; a path back to the module itself is a cycle; transitive `system` dependencies are kept like in Maven. Duplicate classes without a resolved tree use the same resolution as the other analyses.
- Exclusion hygiene: an exclusion in a parent POM only counts as stale when no inheriting module still needs it.
- A property that references itself (`<x>${x}${x}</x>` or `a → b → a`) is reported as unresolved instead of filling the memory; a POM with a UTF-8 BOM works in the upgrade simulator.
- Unused dependencies (command line): text blocks with `\"""`, Groovy `'''` strings and classes named inside `${…}` in Kotlin/Groovy strings are read correctly.
- Preflight: proxies and mirrors are chosen like Maven across user and global `settings.xml`; `requireJavaVersion 8` accepts Java `1.8`; Homebrew Maven and a relative `-Dmaven.repo.local` are found.
- Cancelling "Download missing POMs" also stops Maven on Windows.
- **What changed?**: plugins that set properties during the build (jacoco `argLine`, build numbers) no longer make every run look like a changed effective POM; the same project opened via different spellings or symlinks shares one reference; credentials in mirror URLs are masked.
- IDE: "Show cause" in a console opens the diagnosis of that run, not the newest one.
- IDE: an analysis that was running while Maven re-imported is marked as outdated; a quick fix re-imports only the changed modules (and those inheriting from them) instead of the whole project; read-only POMs are checked before anything is written.
- IDE: "Fix all" for undeclared dependencies declares each dependency once; Apply in the upgrade simulator is off while the result is outdated or a new simulation runs; the preflight can be stopped; Ctrl+C works in the preflight and setup views; column widths in the conflict matrix survive a search; opening a page no longer saves half-edited POMs.

### Performance

- Editor hints and the stale-exclusion inspection are recomputed only when a `pom.xml` is saved or Maven re-imports, not on every file change; reactor POMs are read once.
- One shared cache for JARs and POMs in the IDE, cleared when memory is low or a project closes; older runs in the Build Doctor history no longer keep their console and full log.
- The log navigator reads large logs in the background; logs pasted from the clipboard are capped like logs of runs.
- "What changed?" with thousands of changes across hundreds of modules, the diagnosis card's log tail and the conflict matrix's cells are faster.

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

[Unreleased]: https://github.com/BitShiftDev/maven-detective/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/BitShiftDev/maven-detective/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/BitShiftDev/maven-detective/releases/tag/v0.1.0
