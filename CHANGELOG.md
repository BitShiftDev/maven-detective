# Changelog

<!-- Each version's section becomes the change notes on the JetBrains Marketplace
     and the text of the GitHub release (build.gradle.kts → changeNotes, task releaseNotes).
     Format: https://keepachangelog.com/ – one section "## [x.y.z] - YYYY-MM-DD" per release,
     with "### …" groups and "- " list items below. Allowed inline: **bold**, `code`, [text](URL). -->

## [Unreleased]

### Changed

- The Maven extension is tested in CI against Maven 3.8, 3.9, 3.10 and the latest Maven 4 release candidate (3.6.3 is measured as well); the README names the Maven Central coordinates instead of a placeholder note.

### Fixed

- Maven extension: compiler errors are recognised on the card in the Maven log and from the events file; the extension only kept "Compilation failure", not the `javac` lines below it, so every compiler error showed "Unknown error" there (the IDE reads the text log and was not affected).
- Compiler errors from a `javac` running in German, Japanese or Chinese (`Package … ist nicht vorhanden`, `Symbol nicht gefunden`, …) are recognised; Maven compiles in its own JVM, so the messages follow its locale – before, the card showed "Unknown error".
- A plugin without `<version>` that is found in no plugin repository (`Error resolving version for plugin …`) is recognised as *Plugin missing or without version* instead of "Unknown error".

## [0.1.2] - 2026-11-19

### Fixed

- IDE: the tool window docked left or right (also floating or dragged narrow) switches to a narrow layout: lists, history and outline move above the content, toolbars, the diagnosis card's title and its buttons wrap instead of being cut off.
- IDE: the explanation on empty pages (Conflicts, Duplicate Classes, Exclusions, Usage, Upgrade Simulator) wraps to the visible width.
- IDE: the Preflight view scrolls as a whole, like the diagnosis card; in a low tool window the list of checks was squeezed to two rows.
- Maven 4: a missing child project (`Child subproject … does not exist`) and a malformed or non-parseable POM (`Unable to read model: …`) are recognised again – from the extension's events and from the text log – instead of showing "Unknown error".
- IDE: undoing a POM quick fix takes a single **Undo** again; before, the first one only moved the caret back from the changed line.
- A text log with a very long error line full of spaces no longer stalls the diagnosis; removing the trailing `-> [Help 1]` took minutes on such lines.
- POM quick fixes no longer fail on a POM that contains an empty `<exclusion/>`.
- Custom catalogs (`--catalog`): a folded block (`>`) turns a blank line into one line break, as YAML does, instead of two.
- Blocked http repository: a longer URL that only starts like the blocked one (`…/m2-old` for `…/m2`) is no longer taken for it; before, the card offered a quick fix that found nothing to replace.
- Compiler errors from Windows (`/C:/project/src/…/App.java:[12,9]`) open the right file; the drive letter was lost.
- A missing plugin version is found when the plugin's configuration contains other `<plugin>` entries (e.g. `reportPlugins`).
- Network errors: `Unknown host repo.example.com` at the end of a line names the host; the mirror from `settings.xml` is shown for repositories that only the extension's events name, and the cause names the first repository that failed, not the last.
- Line numbers, class file versions and `-Xmx` values too large for a number no longer end a check with an error ("Check '…' failed"), and doubling a huge `-Xmx` no longer gives a negative value.
- IDE: cancelling a diagnosis also stops reading the project's POMs and skips the remaining rules.
- Dependency analysis: coordinates written as expressions (`<groupId>${project.groupId}</groupId>`, also in `artifactId`, `type` and `classifier`) are resolved like in Maven; before, such sibling dependencies got no managed version and were missing from the tree. `${project.parent.artifactId}` and `${parent.groupId}` are resolved too.
- A dependency declared twice in the same POM counts with its last declaration, as in Maven (before: the first); duplicate `dependencyManagement` entries are merged the way Maven applies them.
- Dependency tree without Maven: a scope set by `dependencyManagement` is taken as is for transitive dependencies (e.g. `provided` below a `runtime` dependency stays `provided`), and a `system` dependency that wins keeps its scope – both as in Maven.
- A dependency on the `test-jar` of another module in the reactor points to its `target/test-classes`, not to its main classes.
- `dependency:tree` output whose module version contains `+` (`1.0.0+build.5`) is read; before, that module's tree was dropped.
- IDE: a `pom.xml` saved twice within the same second with the same length is no longer served from the cache in its old version (file systems with one- or two-second timestamps).
- Preflight: an unreadable version range such as `(1.0)` in an enforcer rule is ignored instead of reported as violated.
- Preflight and network check choose mirrors exactly as Maven does: a `mirrorOf` list with spaces (`central, snapshots`) does not mirror `snapshots` (Maven compares the entries untrimmed), an exact repository id ends the list, and `external:*` / `external:http:*` look at the host like Maven (`localhost.corp.example` is external, `HTTP://` and `dav:http://` count as http).
- Proxies from `settings.xml`: `nonProxyHosts` is split at `|` only, as in Maven – a comma does not separate hosts; `file:` repositories get no proxy; a repository URL with credentials (`https://user:token@host/…`) is matched by its real host.
- `settings.xml`: `${NAME}` without `env.` is resolved from the environment, as Maven does.
- Preflight: http repositories on `0.0.0.0` or on a host that merely starts with `localhost` are reported as blocked, like Maven blocks them; a relative `<localRepository>` in `settings.xml` is resolved against the project, not against the IDE's working directory; the own repository manager is matched to a `settings.xml` mirror with case-sensitive paths.
- IDE vs. command line: with several downloads of the same Maven version under `~/.m2/wrapper/dists`, the one for the project's current `distributionUrl` is used (and with it its `conf/settings.xml`); a Java version with absurdly long numbers no longer breaks the comparison.
- "What changed?": the most recent snapshot is chosen by time, not by text – across the switch from summer time and between runs in different time zones.
- Command line: an unknown option (`--markdwon`), an option without its value or a second file name is reported with exit code 2 instead of being ignored; options also work as `--option=value` (before, `--project=dir` was taken for the file name). `why` and `matrix --pin` reject coordinates with an empty part (`g:`, `:a`).
- Command line: a `pom.xml` that cannot be parsed is reported as unreadable, with the reason, instead of "No pom.xml"; `changes` shows both times in the same format.
- The console card no longer fails on a quick fix without file parameters, and the plain-ASCII card (consoles without UTF-8) writes accented letters without their accent (`é` → `e`) and an emoji as a single `?`.
- Counts in reports use singular and plural correctly ("1 module", "1 rule", "1 line").
- `upgrade --manage` no longer refers to a `--pin` option and says that a pinned version does not override a direct `<version>`.
- Extension: an exception from a plugin whose message cannot be read no longer costs the card, the other build errors and the snapshot; a repository event without a readable failure and a POM problem without a severity are kept as well.
- Extension: a read-only or full snapshot folder (e.g. a restored CI cache) no longer hides "Since the last green build" on the card.
- IDE: the quick fix **Rebuild without offline mode** now rebuilds without `-o`/`--offline` and without the IDE's *Work offline* setting; before, it restarted the same offline run (or, for a log from the clipboard, did nothing).
- IDE, Duplicate Classes: the button counts the exclusions it inserts; the same exclusion recommended by two findings counted twice.
- IDE, Upgrade Simulator: a version with spaces, `=` or `:` is rejected with a message instead of being simulated and written into the POM.

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

- CLI `java -jar maven-detective-cli-<version>.jar` with `diagnose`, `changes`, `why`, `matrix`, `exclusions`, `duplicates`, `analyze`, `upgrade`, `preflight`, `outline`, `evaluate` and `validate-catalog`.
- Maven core extension (`-Dmaven.ext.class.path=…`) prints the diagnosis card into the Maven log and can write structured events; runs on Maven 3.9 with Java 8 and later and never changes the build.
- Release workflow prepared for Maven Central (`io.github.bitshiftdev.mavendetective:maven-detective-core` and `io.github.bitshiftdev.mavendetective:maven-detective-extension`); the artifacts are not on Central yet, so `.mvn/extensions.xml` does not work until then – use the JAR from the GitHub release.

[Unreleased]: https://github.com/BitShiftDev/maven-detective/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/BitShiftDev/maven-detective/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/BitShiftDev/maven-detective/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/BitShiftDev/maven-detective/releases/tag/v0.1.0
