# Review: bugs and performance (2026-11-14, commit 71794bc)

## Summary

The code is carefully built: the extension reliably swallows errors, XXE is disabled everywhere, `delete-files` is protected against path traversal, the plugin never swallows PCE, and all core tests pass (200 tests). The most dangerous findings are where the user's files get written. `FixExecutor` reads and writes every file as UTF-8 and thereby destroys umlauts in POMs with `encoding="ISO-8859-1"`; in the IDE, the https fix for repository URLs takes exactly this path, bypassing the editor, without preview and without undo (#1, #10). `PomEditor` produces duplicate tags for self-closing containers like `<dependencies/>`, which Maven can no longer read (#2). In the diagnosis, three things stand out: logs with common CI prefixes yield "no errors" (#8), generic patterns like `403 Forbidden` or `ZipException` beat failed tests (#9), and the preflight treats commented-out or profile-only `<release>` values as a requirement (#5). The biggest performance lever lies in two places. Check regexes using `find()` over long lines without spaces grow quadratically: a 60,000-character line costs 14.8 s at the end of the build (#7). And `JarIndex` re-reads `target/classes` of the reactor modules on every query: A3 and A5 take about 5 s each with 200 modules, and the live inspection does it in the daemon (#20).

## Overview

| # | Severity | Category | Confidence | Module | Location | Short description |
|---|---|---|---|---|---|---|
| 1 | Critical | correctness | confirmed | core | `fix/FixExecutor.java:67-104` | Fixes always read/write UTF-8 → umlauts in Latin-1 POMs destroyed |
| 2 | Critical | correctness | confirmed | core | `deps/PomEditor.java:737-754` | `<dependencies/>`, `<properties/>`, `<exclusions/>` → duplicate container, POM unreadable |
| 3 | Critical | correctness | confirmed | core | `deps/PomEditor.java:160, 287` | Removing the last entry deletes comments in the block (CLI without undo) |
| 4 | Critical | security | plausible | core | `check/RepositoryChecks.java:155-186`, `PomChecks`, `MavenLogParser:149` | UNC paths from the log → SMB access/NTLM hash on Windows |
| 5 | High | correctness | confirmed | core | `preflight/PreflightChecks.java:201-259` | Preflight evaluates comments, profiles and non-module POMs → false RED |
| 6 | High | correctness | confirmed | core | `env/MavenSettings.java:152-174` | `${env.X}` in settings.xml not interpolated → false RED, wrong repository |
| 7 | High | performance | confirmed | core | `check/ResolutionChecks.java:82`, `RuntimeChecks:86`, `RepositoryChecks:155`, catalog `:96` | Regex O(L²) on long lines: 60k characters = 14.8 s |
| 8 | High | correctness | confirmed | core | `io/MavenLogParser.java:22-28`, `LogOutline.java:21` | Timestamp with spaces/thread prefix → "no errors" |
| 9 | High | correctness | confirmed | core | catalog `build-doctor-v1.yaml:66-93` | `403 Forbidden`/`ZipException` from tests → wrong primary diagnosis |
| 10 | High | correctness, rule-violation | confirmed | plugin, core | `FixRunner.java:65`, `FixExecutor.java:65-74`, `RepositoryChecks.java:114-136` | https fix writes the POM bypassing the editor, also replaces prefix matches |
| 11 | High | correctness | confirmed | core | `check/PomChecks.java:311-328` | `plugin-version` confirms without checking → wrong fix "insert version" |
| 12 | High | correctness | confirmed | plugin | `BuildDoctorService.java:213` | Diagnosis of IDE runs ignores the IDE's settings file and repository |
| 13 | High | correctness | confirmed | plugin | `FixRunner.java:172-177` | `delete-files` refused when `<localRepository>` ≠ `~/.m2/repository` |
| 14 | High | correctness | confirmed | core | `deps/PomLocator.java:155` | `${revision}`: reactor siblings "missing", tree incomplete |
| 15 | High | correctness | confirmed | core | `PomEditor.java:481-503`, `DependencyUsage.java:269-273, 673-683` | `type` (test-jar) ignored → UNUSED fix removes the jar that is used |
| 16 | High | correctness | confirmed | core | `EffectiveModel.java:230-241` + 8 callers | Management lookup ignores type/classifier → wrong scope, wrong diagnosis |
| 17 | High | correctness | confirmed | core | `ExclusionAudit.java:303-339`, `DependencyResolver.java:150-165` | Relocation ignored → exclusion wrongly STALE |
| 18 | High | correctness | confirmed | core | `ExclusionAudit.java:314` | Profile dependencies missing → exclusion wrongly STALE |
| 19 | High | correctness | confirmed | core | `maven-extension/…/Snapshots.java:101-106`, `snapshot/SnapshotStore.java:81-94` | Green `compile` after green `verify` thins out the reference → invented changes |
| 20 | High | performance | confirmed | core, plugin | `JarIndex.java:128-131`, `DuplicateClasses:309`, `DependencyUsage:593, 693` | Directories never cached: A3/A5 ~5 s each, inspection reads in the daemon |
| 21 | Medium | threading | confirmed | plugin | `ui/DetectivePanel.java:352-364, 420-465`, `IdeUsages.java:66-74` | "Stop" only discards the result; loading indicator and "running" stay |
| 22 | Medium | correctness | confirmed | plugin | `ui/FindingTree.java:109-132` | Unchecked boxes come back after search/reimport |
| 23 | Medium | correctness | confirmed | plugin | `AnalysisResults.java:124-131`, `DependencyUsagePanel.java:114-162` | Analysis started before the import counts as current afterwards |
| 24 | Medium | robustness | confirmed | plugin | `BuildDoctorPanel:302-341`, `OverviewPanel:271-326`, `UpgradeSimulatorPanel:397` | Loading/"running" states stay after an error or abort |
| 25 | Medium | correctness | confirmed | plugin | `PomInsights.java:104-153` | Discarded matrix is never recomputed → code vision disappears |
| 26 | Medium | correctness | confirmed | plugin | `BuildDoctorService.java:358-363`, `FixRunner.java:94, 137` | Notifications insert text with `<server>` unescaped as HTML |
| 27 | Medium | correctness | confirmed | plugin | `BuildFailureConsoleFilter.java:68` | Console link shows the newest diagnosis, not the one of this console |
| 28 | Medium | performance | confirmed | plugin | `IdeMaven.java:458-466` | Full forced sync of all Maven projects after every POM fix |
| 29 | Medium | performance | confirmed | plugin | `PomInsights.java:162-165`, `StaleExclusionInspection.java:58` | Editor caches on the global VFS counter; every recomputation scans the reactor |
| 30 | Medium | memory | confirmed | plugin, core | `ClasspathChecks.java:46`, `IdeUsages.java:46`, 3× `PomCache` | Static caches duplicated, never cleared, up to ~50 MB per index |
| 31 | Medium | correctness | confirmed | core | `deps/MavenVersion.java:61-118` | 13 of 58 comparisons differ from `ComparableVersion` |
| 32 | Medium | correctness | confirmed | core | `PomEditor.java:147-487` | `${project.groupId}` in a declaration → POM fixes "not found" |
| 33 | Medium | correctness | confirmed | core | `PomFile.java:289`, `PomEditor.java:783` | Duplicate property: the tool takes the first, Maven the last |
| 34 | Medium | robustness | confirmed | core | `PomFile.java:151-159` | UTF-8 BOM → upgrade simulation "not valid XML" |
| 35 | Medium | memory | confirmed | core | `EffectiveModel.java:299-322` | Self-referencing property → OOM after 7.8 s |
| 36 | Medium | correctness | confirmed | core | `fix/FixExecutor.java:80-92` | `set-jvm-option` merges `.mvn/jvm.config` into one line |
| 37 | Medium | correctness | confirmed | core | `RuntimeChecks:86`, `RepositoryChecks:155`, `PomChecks:34`, catalog | Paths with spaces are cut off |
| 38 | Medium | correctness | confirmed | core | `io/MavenLogParser.java:84-97` | Exceptions in lines without a prefix → card on `BUILD SUCCESS` |
| 39 | Medium | correctness | confirmed | core | `io/MavenLogParser.java:26-28, 135-152` | Maven/Java version only read without a line prefix |
| 40 | Medium | memory | confirmed | core | `io/MavenLogParser.java:40, 60-62` | Unused `logLines` (20,000 lines) – 20 runs = 60 MB |
| 41 | Medium | correctness | confirmed | core | `ExclusionAudit.java:175` | Exclusion in the parent only checked in the parent's context → STALE wrong |
| 42 | Medium | correctness | confirmed | core | `DependencyResolver.java:182`, `ExclusionAudit.Closure:333` | Exclusions from the dependency POM's management missing |
| 43 | Medium | correctness, performance | confirmed | core | `ModuleClasspath.java:245-303` | Classpath without a tree (`fromPoms`) differs from Maven and is 2× slower |
| 44 | Medium | correctness | confirmed | core | `DependencyUsage.java:640-655` | Class in two JARs → declared dependency certainly UNUSED |
| 45 | Medium | performance | confirmed | core | `EffectiveModel.managed`, `PomLocator.byCoordinates:154` | Linear searches = ~80 % CPU in resolver/matrix/simulator |
| 46 | Medium | performance, memory | confirmed | core, plugin | `UpgradeSimulator.java:507-551`, `UpgradeSimulatorPanel:414` | A6 re-parses everything, holds ~98 MB, cannot be cancelled |
| 47 | Medium | correctness | confirmed | core | `env/BuildSetup.java:190` | Maven Wrapper 3.3.x (`dists/apache-maven-X/`) not found |
| 48 | Medium | threading | plausible | extension | `DetectiveEventSpy.java:278-290` | JSONL without synchronisation → torn lines under `-T` |
| 49 | Medium | security | plausible | core | `preflight/RepositoryProbe.java:59-66` | HEAD probe uses the IDE's JVM `Authenticator` → possibly credentials |
| 50 | Medium | threading | plausible | plugin | `LogNavigatorView.java:134-211`, `BuildDoctorActions:52` | A large log is built entirely on the EDT |
| 51 | Medium | memory | plausible | plugin | `BuildDoctorService.java:59-129`, `MavenRunListener:110` | History holds 20 `ExecutionEnvironment`s and raw logs (UTF-16) |
| 52 | Medium | robustness | plausible | plugin | `ui/DetectivePanel.java:432-438` | `Error` in the background → loading indicator forever |
| 53 | Medium | robustness | plausible | plugin | `FixRunner.java:396-407` | Read-only status not checked → half-applied multi-file fix |
| 54 | Medium | correctness | plausible | extension | `Snapshots.java:87, 123-134` | Effective POM hash at the end of the build contains runtime properties |
| 55 | Medium | correctness | plausible | core | `env/BuildSetup.java:262-304` | Homebrew Maven: version unknown, no global settings.xml |
| 56 | Medium | correctness | plausible | core | `preflight/VersionRange.java:526` | Enforcer `requireJavaVersion 8` → false ERROR on Java 8 |
| 57 | Medium | robustness | plausible | core | `deps/PomFetcher.java:225-235` | Cancelling does not stop `mvn.cmd` on Windows |
| 58 | Low | rule-violation | confirmed | all | see finding | Visible texts/colours in code in 13 places |
| 59 | Low | rule-violation | confirmed | plugin | `BuildDoctorService`, `MavenRunListener`, `FixRunner` | Maven API outside of `IdeMaven` |
| 60 | Low | rule-violation | confirmed | plugin | `StaleExclusionInspection.java:155-166` | Quick fix changes the POM via PSI, without `editPoms`/reimport |
| 61 | Low | security | confirmed | core | `PomFetcher.java:41-160`, `PomEditor.java:404-550` | Values unescaped in XML; commented-out `<repositories>` used |
| 62 | Low | correctness | confirmed | core | `PomEditor.java:382, 776` | Entity/CDATA in the value "not found"; comment in `<version>` deleted |
| 63 | Low | correctness | confirmed | core | `deps/SourceScanner.java:203-224` | Text block with `\"""`, Kotlin `${…}` → usages lost |
| 64 | Low | correctness | confirmed | core | `DependencyResolver.java:217, 276` | Cycle back to the root → module appears in its own tree |
| 65 | Low | robustness | confirmed | core | `io/Json.java:45-173`, `EventLog.java:70` | No depth limit (StackOverflowError), large numbers discard the line |
| 66 | Low | correctness | confirmed | core | `preflight/RemoteRepositories.java:482-495`, `env/MavenSettings.java:202` | Proxy/mirror selection differs from Maven |
| 67 | Low | correctness | confirmed | core | `env/BuildSetup.java:162`, `PreflightContext:468` | Relative `-Dmaven.repo.local` resolved against the wrong directory |
| 68 | Low | robustness | confirmed | core | several | Minor robustness issues (DependencyTreeParser, JarIndex, ClassFile, catalog BOM, Template, Coordinates, 600-JAR limit, system scope) |
| 69 | Low | correctness | confirmed | plugin | several | Minor UI bugs (preflight selection, column widths, apply despite outdated, Ctrl+C, view jump …) |
| 70 | Low | performance | confirmed | core, plugin | `SnapshotDiff:388`, `DiagnosisCard:456`, `ConflictMatrixPanel:578` | Linear search in the snapshot diff, regex over the whole log on the EDT, expensive renderer |
| 71 | Low | security, robustness | plausible | core | `SnapshotStore.java:52`, `Snapshots.java:72` | Storage key not canonical, grows without bounds; credentials in mirror URLs in plain text |

## Findings

### 1. `FixExecutor` always reads and writes UTF-8 → umlauts in non-UTF-8 files destroyed
- **Location:** `core/src/main/java/io/github/bitshiftdev/mavendetective/core/fix/FixExecutor.java:67,72` (`replace-in-file`), `:80,92` (`set-jvm-option`), `:97,104` (POM fixes). Reached from the IDE via `FixRunner.runFileFix` (`FixRunner.java:104-140`), from the CLI via `analyze --remove/--declare`, `exclusions --remove`, `duplicates --exclude`, `upgrade --apply`, `matrix --pin`.
- **Problem:** `new String(Files.readAllBytes(p), UTF_8)` → edit → `getBytes(UTF_8)`. The encoding from the BOM or the XML declaration is ignored. Invalid bytes become U+FFFD when decoding and are lost afterwards.
- **Scenario:** A POM with `<?xml version="1.0" encoding="ISO-8859-1"?>` and `<name>Größenprüfung Müller</name>`. After a `set-version` fix the file contains `Grï¿½ï¿½enprï¿½fung Mï¿½ller`. In the IDE this hits the https fix (#10), there without undo.
- **Evidence:** `xxd` before `4d fc 6c`, after `4d ef bf bd` (repros `scratchpad/core-pom/.../Repro2.java`, `scratchpad/plugin-a/ReplaceRepro.java`, `scratchpad/core-log/Repro.java latin1`).
- **Suggestion:** Determine the encoding from the BOM/prolog and write back in the same encoding. Additionally decode with `CharsetDecoder`/`CodingErrorAction.REPORT` and abort on errors instead of writing. Test in `FixExecutorTest` with a Latin-1 POM. Effort M.

### 2. Self-closing containers lead to duplicate tags
- **Location:** `core/.../deps/PomEditor.java:737-754` (the scan records no range for `empty`), consequences in `:92-121`, `:216`, `:254`, `:405`.
- **Problem:** For `<dependencies/>`, `<exclusions/>`, `<properties/>`, `<dependencyManagement/>` and `<dependencyManagement><dependencies/></dependencyManagement>` the ranges stay empty. `addDependency`, `addExclusion`, `setProperty` and `pinManagedVersion` therefore create a second container with the same name.
- **Scenario:** `analyze --declare` or the quick fix "Declare" on a POM with `<dependencies/>`. Afterwards Maven reports `Duplicated tag: 'dependencies'`, the build is broken.
- **Evidence:** Reproduced myself (`scratchpad/me/MyPom.java`):
  ```
  <dependencies/>
  <dependencies>
    <dependency>…g:a:1.0…</dependency>
  </dependencies>
  ```
  `setProperty` on `<properties/>` likewise creates a second `<properties>`. `MavenXpp3Reader` (strict) rejects all four cases.
- **Suggestion:** In the scan, record the range of the empty tag for `empty` and replace `<x/>` with `<x>…</x>`. One case each in `PomEditorTest`. Effort S–M.

### 3. Removing the last entry deletes comments in the container
- **Location:** `core/.../deps/PomEditor.java:160` (`removeExclusion`), `:287` (`removeDependency`).
- **Problem:** If it is the last entry, the whole `<exclusions>` or `<dependencies>` block is deleted, even if it still contains comments.
- **Scenario:** `<dependencies>` contains a commented-out old dependency and g:a. `analyze --remove` (CLI, no undo) removes g:a; the comment with the old dependency is gone. In the plugin, preview and undo catch this.
- **Evidence:** Reproduced myself: `<project><dependencies><!-- <dependency>…old:x… --><dependency>g:a</dependency></dependencies></project>` becomes `<project>\n</project>`.
- **Suggestion:** Remove the container only if nothing but whitespace remains; otherwise remove only the element. Effort S.

### 4. UNC paths from the log lead to network access on Windows
- **Location:** catalog `build-doctor-v1.yaml:92,96` (corrupt-jar `extract`), `check/RepositoryChecks.java:155,164-168,179-186` (`Files.isRegularFile`, `ZipFile`, SHA-1), `check/PomChecks.java:62-65,85-88,108-110,164-166`, `io/MavenLogParser.java:149-151` with `env/Environment.forBuild:67-68` (`Maven home:`).
- **Problem:** Paths from the log text go to `Files.*` unchecked. On Windows `\\host\share\x.jar` is a UNC path; the first access opens an SMB connection and sends the user's NTLM hash. This also violates the rule "checks never use the network".
- **Scenario:** A foreign log (CI download, clipboard, test output with third-party data) contains `[ERROR] … error reading \\attacker.example\share\evil.jar; zip END header not found`. The `corrupt-jar` check opens the file. Likewise, `Maven home: \\attacker\share` causes `conf/settings.xml` to be read from there.
- **Evidence:** `scratchpad/core-log/Unc.java`: `extract {file=\\attacker.example\share\evil.jar}`, `globalSettings=\\attacker.example\share/conf/settings.xml`. I did not reproduce the SMB access itself on Windows.
- **Suggestion:** A central helper (e.g. `Text.localPath`) that rejects `\\…`, `//…` and other non-drive paths before any `Files.*` call; for log variables and session hints. Test with a UNC log in `ChecksTest`. Effort S.

### 5. Preflight evaluates comments, profiles and non-module POMs → false RED
- **Location:** `core/.../preflight/PreflightChecks.java:201-217` (`<release>` via a line regex), `:236-259` (enforcer via `findText`), `:136`, `:326-357`; `PreflightContext.java:504`. Everything via `ProjectPoms.all()` (directory scan up to depth 6, not via `<modules>`).
- **Problem:** Comments and `<profiles>` are not filtered out; every `pom.xml` below the project counts.
- **Scenario:** All cases reproduced with JDK 17:
  - (a) `<!-- <release>21</release> -->` → "JDK 17 is too old: the project requires Java 21".
  - (b) `<release>21` only in the profile `jdk21` → the same message.
  - (c) Commented-out `requireJavaVersion [21,)` → "Enforcer rejects Java 17.0.9".
  - (d) `examples/legacy/pom.xml` is not a module → evaluated anyway.
- **Evidence:** Run myself (`scratchpad/core-env/src/repro/ReproPreflight.java`), all three cases with `ERROR JDK …`, traffic light RED.
- **Suggestion:** Read release, enforcer and toolchains from `PomFile`/`EffectiveModel` without profiles; determine the project POMs via `<modules>` from the root. Tests in `PreflightTest`. Effort M.

### 6. Placeholders in settings.xml are not interpolated
- **Location:** `core/.../env/MavenSettings.java:152,169-174` (mirror URL, proxy host, `localRepository` raw), `PreflightContext.java:480` and `Environment.java:350` (only `${user.home}`); `Preflight.Options.env` (`Preflight.java:233,257`) is used nowhere.
- **Scenario:**
  - Mirror `<url>${env.NEXUS_URL}/repository/maven-public/</url>` with `--network` → `ERROR REPOSITORY: central via mirror nexus: request to null failed`, traffic light RED, although Maven works.
  - `<localRepository>${env.HOME}/m2repo</localRepository>` → relative path `${env.HOME}/m2repo`. The preflight then reports parents as missing, and the card's `.lastUpdated` checks look in the wrong directory.
- **Evidence:** Run myself (`ReproPreflight` cases 2 and 3).
- **Suggestion:** Interpolate like Maven (`${env.*}`, `${user.home}`, system properties) from `Options.env` or `System.getenv`. Where `${` remains afterwards, treat the value as unknown: no request, no ERROR. Effort S–M.

### 7. Check regexes grow quadratically with the length of a line without spaces
- **Location:** `core/.../check/ResolutionChecks.java:82-83` (`UNKNOWN_HOST` `(\S+?): …`), `RuntimeChecks.java:86` (`LOCATION`), `RepositoryChecks.java:155` (`PATH`), catalog `build-doctor-v1.yaml:96` (corrupt-jar `extract`). `ctx.find` runs over all error lines.
- **Problem:** `find()` starts at every position of a long token and runs to its end, i.e. O(L²) per line. The extension diagnoses synchronously at the end of the build; the build then "hangs" after `BUILD FAILURE`.
- **Scenario:** A continuation line after `[ERROR]` with 60,000 characters of classpath, Base64 or minified text; `network-failure` runs for `artifact-not-resolvable` and `invalid-pom`.
- **Evidence:** Measured myself with `Main diagnose`: 20,000 characters 1.3 s, 60,000 characters 14.8 s. Single regexes at 40,000 characters 2–9 s (`scratchpad/core-log/RegexBench.java`).
- **Suggestion:** Start only at token boundaries (`(?<!\S)(\S+?): …`), filter by the literal with `contains` first, cut searchable lines to e.g. 4 KB (`BuildFailure.searchableLines`). Test with a long line and a time limit in `ChecksTest`. Effort S.

### 8. Logs with common CI prefixes yield "no errors"
- **Location:** `core/.../io/MavenLogParser.java:22-23` and `LogOutline.java:21-22` (shared `PREFIX`), CLI `Main.java:168-171`.
- **Problem:** Before `[LEVEL]` only a single token without spaces that contains `hh:mm:ss` is allowed.
- **Scenario:** Not recognised:
  - `2026-11-12 06:48:01 [ERROR]` (simplelogger with date, Bamboo)
  - GitLab 17 timestamps (`…Z 01O [ERROR]`)
  - `[main] [ERROR]` (`showThreadName=true`)
  - `1234 [ERROR]` (showDateTime without a format)

  Result: 0 errors, `diagnose` reports "no errors" with exit 0, and the outline has neither a first error nor a result.
- **Evidence:** Run myself (`scratchpad/core-log/FormatCheck.java`): `yyyy-MM-dd HH:mm:ss errors=0 rule=- firstError=-1 result=null`, likewise for GitLab, millis, thread name, time + thread. GitHub ISO, Jenkins `[ISO]` and `HH:mm:ss` work.
- **Suggestion:** Generalise the prefix (a limited number of tokens before `[LEVEL]`) or strip known prefixes first. If `diagnose` recognises no error although `BUILD FAILURE` is in the log, do not report "no errors". Test logs per format in `core/src/test/resources/logs`. Effort S.

### 9. Generic patterns of high-priority rules beat failed tests
- **Location:** catalog `build-doctor-v1.yaml:66,68` (`repository-access-denied`, priority 92) and `:87-93` (`corrupt-jar`, 90) against `test-failures` (`:442`, priority 10); selection in `BuildDoctor.java:80-93`.
- **Scenario:**
  - An integration test fails with `WebClientResponseException$Forbidden: 403 Forbidden from GET http://localhost:8080/api/user`. Primary diagnosis: "Repository answers with 403 – check the credentials in settings.xml".
  - A unit test fails with `java.util.zip.ZipException: invalid LOC header` from application code. Primary diagnosis: "JAR in the local repository is corrupt".
- **Evidence:** Run myself (`scratchpad/core-log/Repro.java forbidden`): `PRIMARY repository-access-denied (LIKELY)` and `PRIMARY corrupt-jar (LIKELY)`; `test-failures (CERTAIN)` only appears under "also".
- **Suggestion:** Bind the patterns to Maven context (`Could not transfer`, `Failed to deploy`, `from/to <repo>`, `.m2`) or exclude hits inside Surefire failure blocks via `unless`; in addition, a LIKELY rule should not beat a CERTAIN rule. Test logs for both cases. Effort M.

### 10. The https fix for repository URLs writes the POM bypassing the editor and replaces too much
- **Location:** `intellij-plugin/.../idea/FixRunner.java:65` → `runFileFix` (`:104-140`) → `core/.../fix/FixExecutor.java:65-74`. The fixes are created in `check/RepositoryChecks.java:114-136` and `preflight/RemoteRepositories.java:272`.
- **Problem:**
  - `replace-in-file` on a project POM does not go through `FixRunner.editPoms`: no diff, no undo step, no reimport. This contradicts the rule "every POM change with a preview".
  - Encoding broken as in #1.
  - `content.replace(search, …)` replaces every occurrence, including prefix matches and comments.
- **Scenario:** Blocked repository `http://nexus.corp/repo`. The POM also contains `http://nexus.corp/repository/thirdparty` and a comment with `…/repo-guide.html`. All three become https. If the second repository cannot do https, the build is newly broken, and undo is not possible.
- **Evidence:** `scratchpad/core-log/Repro.java http` (file content after the fix), `scratchpad/plugin-a/ReplaceRepro.java`.
- **Suggestion:** For `pom.xml`, run it as a `PomEditor` edit of exactly the `<url>` value via `editPoms`. For settings.xml, replace only exact `<url>` values and keep the encoding. Effort M.

### 11. The `plugin-version` check confirms without checking and yields a wrong fix
- **Location:** `core/.../check/PomChecks.java:311-328`.
- **Problem:**
  - The check runs for every pattern of the rule `plugin-missing` and confirms as soon as `<artifactId>X</artifactId>` appears anywhere. It does not check whether `<version>` is missing.
  - For `g:a:v` from "Could not find goal", `substring(':')` yields `a:v`, and the check reports "plugin is not declared in any project POM".
- **Scenario:** `No plugin found for prefix 'spring-boot'`, the plugin is declared with `<version>3.3.5</version>` in the module web. The card (CERTAIN) shows the evidence "plugin without <version> (web/pom.xml:4)" and the fix `insert-plugin-version`.
- **Evidence:** Run myself (`Repro.java prefix`): `fix: insert version for org.springframework.boot:spring-boot-maven-plugin [insert-plugin-version …]`, `ev: plugin without <version>`.
- **Suggestion:** Run only if the pattern `build.plugins.plugin.version` matched; check for `<version>` within the found `<plugin>` block; take the artifactId as the second segment of `g:a[:v]`. Test in `ChecksTest`. Effort S.

### 12. Diagnosis of IDE runs ignores the IDE's Maven settings
- **Location:** `intellij-plugin/.../idea/BuildDoctorService.java:213-214` → `core/.../env/Environment.java:37-38,84-90`. `MavenLogParser` never sets user settings or the local repository.
- **Problem:** `Environment.forBuild(failure, projectDir())` always sees `~/.m2/settings.xml` and the repository from it, or `~/.m2/repository`. `IdeMaven.ideSetup` knows the override file, the repository and the runner JDK (`IdeMaven.java:145-169`), but is not used here.
- **Scenario:** The IDE is configured with `/corp/settings.xml` containing `<server id="nexus">`, and the build fails with 401. The check reads `~/.m2/settings.xml` and reports with certainty "settings.xml has no <server> with the ID 'nexus'"; the fix opens the wrong file. Likewise the `.lastUpdated` check searches in the wrong repository.
- **Evidence:** Code read, call chain as above.
- **Suggestion:** In `analyzeLog`, take the user settings, local repository and runner Java from `IdeMaven.ideSetup` into the `Environment.Builder`. Effort S–M.

### 13. `delete-files` is refused when the local repository differs
- **Location:** `intellij-plugin/.../idea/FixRunner.java:172-177` against `core/.../fix/FixExecutor.java:122-128`.
- **Problem:** The deletion root is `failure.getSession().getLocalRepository()`, which is always `null` for text logs (IDE), i.e. `~/.m2/repository`. The checks, however, find the files via `env.getLocalRepository()`, including `<localRepository>` from settings.xml.
- **Scenario:** `<localRepository>/data/m2</localRepository>`. The diagnosis offers to delete `/data/m2/…/x.jar.lastUpdated`; `FixExecutor` throws `exec.delete.refused`, an error message appears, nothing is deleted.
- **Evidence:** `roots.add(repo != null ? Paths.get(repo) : Paths.get(System.getProperty("user.home"), ".m2", "repository"))`.
- **Suggestion:** Pass the repository the diagnosis worked with along in the `Result` (plus `IdeMaven.localRepository`). Solve together with #12. Effort S.

### 14. CI-friendly `${revision}`: reactor siblings are not found
- **Location:** `core/.../deps/PomLocator.java:155` compares the interpolated version with the raw `p.effectiveVersion()` (`${revision}`).
- **Scenario:** Root with `<revision>1.0-SNAPSHOT</revision>`, module b depends on a with `${project.version}`. a is reported as missing, a's transitive dependencies are missing from the tree. If an old installed copy exists in `~/.m2`, that outdated POM is used. A1, A3, A5, A6 and `class-origin` are affected; A6 suggests `--fetch` for a reactor artifact.
- **Evidence:** Run myself (`scratchpad/core-pom/.../RevisionCase.java`): `missing: [g:a:1.0-SNAPSHOT]`, tree `g:b:${revision}` → `g:a:1.0-SNAPSHOT` without `org.x:lib`.
- **Suggestion:** Interpolate the reactor version in the model of the reactor POM (`model(load(p.file)).interpolate(…)`); if `${` remains, compare by g:a only. Test in `VersionExplainerTest`/`DependencyResolverTest`. Effort S–M.

### 15. `type` is ignored: with jar + test-jar the fix hits the wrong dependency
- **Location:** `core/.../deps/PomEditor.java:481-503` (`find` compares only g:a:classifier), `:193-201`, `:244-249`; `DependencyUsage.java:673-683` (`directEntry` accepts test-jar for jar), fixes without `type` in `:269-273`.
- **Scenario:** A module declares `lib` as `test-jar`/`test` before `lib` (jar); the main code uses `org.lib.Foo`. A5 reports two `UNUSED` findings, both with the fix `{artifact=org.lib:lib}`. Applied with multi-selection, both declarations disappear and the main code no longer compiles. Conversely, `setScope` and `addExclusion` change the wrong entry.
- **Evidence:** Run myself (`scratchpad/core-deps/.../Repro.java`, R7): two lines `UNUSED org.lib:lib:1.0 … fixes={…, artifact=org.lib:lib}`, with and without a resolver tree.
- **Suggestion:** Carry `type` (default `jar`) in `PomEditor.find`, `directEntry` and the FixActions; new parameter in the FixAction Javadoc and the catalog header. Effort M.

### 16. Management lookup ignores type and classifier
- **Location:** `core/.../deps/EffectiveModel.java:230-241` (falls back to any entry with the same g:a). Called in `DependencyResolver.java:121,170,177`, `ExclusionAudit.java:244,369,380`, `ModuleClasspath.java:249`, `ConflictMatrix.java:295`.
- **Scenario:**
  - The parent manages only `core` as `test-jar`/`test`, the module declares `core` (jar). The resolver yields `com.acme:core:1.0 (test)` including its children in test; Maven takes compile. Consequence: on a compiler error `class-location` confirms "only in test scope" and suggests `set-scope compile` – a wrong diagnosis.
  - Conversely (test-jar without scope, jar and test-jar managed) compile is assumed instead of test.
- **Evidence:** Run myself (R3a/R3b): `com.acme:core:1.0 (test)` and `(compile)` respectively.
- **Suggestion:** Look up by the full management key g:a:type[:classifier], as `PomHints:85` already does; the fallback only if type/classifier are unknown. Effort S.

### 17. Relocation is ignored → exclusion wrongly STALE
- **Location:** `core/.../deps/ExclusionAudit.java:226-227,303-339`, `DependencyResolver.java:150-165`. `PomFile` does not read `distributionManagement/relocation`.
- **Scenario:** `mysql:mysql-connector-java:8.0.33` with the exclusion `com.google.protobuf:protobuf-java`. The POM is a relocation to `com.mysql:mysql-connector-j`, which brings protobuf. A4 reports STALE with the fix `remove-exclusion`; after removing it, protobuf comes back. The resolver stops at the relocation artifact without children and without an entry in `missing`.
- **Evidence:** Run myself (R1): `STALE removable=true fix=remove-exclusion`.
- **Suggestion:** Read the relocation in `PomFile` and follow it in the resolver and `Closure`; at least UNKNOWN instead of STALE. Effort M.

### 18. Dependencies in profiles are missing → exclusion wrongly STALE
- **Location:** `core/.../deps/ExclusionAudit.java:314` (only `model.getDependencies()`); `PomFile` does not capture `profiles/profile/dependencies`.
- **Scenario:** The dependency's POM brings `jaxb-api` in a profile with `activeByDefault` or JDK activation; the project excludes it. A4 reports STALE.
- **Evidence:** Run myself (R2): `STALE missing=[]`.
- **Suggestion:** Treat POMs with profile dependencies as incomplete (UNKNOWN). Effort S–M.

### 19. Snapshot: green runs resolved to different depths thin out the reference
- **Location:** `maven-extension/.../Snapshots.java:101-106`, `core/.../snapshot/SnapshotStore.java:81-94` (`carryOver` only for `!isDependenciesResolved()`), `SnapshotDiff.java:300-301`.
- **Problem:** Maven resolves only the scopes of the planned mojos. `mvn compile` knows no test dependencies, `mvn verify` does; both count as `dependenciesResolved=true`. This violates the requirement "short green runs must not thin out the reference".
- **Scenario:** Green `verify`, then green `compile`: the reference loses junit and mockito. A later red `verify` without any change shows on the card "new dependency: junit-jupiter 5.10.0, mockito-core 5.5.0".
- **Evidence:** Run myself (`scratchpad/core-env/src/repro/ReproSnapshot.java`): "reference after a short green compile: {org.slf4j:slf4j-api=2.0.9}", followed by the invented changes.
- **Suggestion:** Store the scope per dependency and the set of resolved scopes, compare only scopes resolved by both, `carryOver` per scope. Effort M.

### 20. `JarIndex` never caches directories: A3/A5 slow, inspection reads in the daemon
- **Location:** `core/.../deps/JarIndex.java:128-131` (directories always fresh, every class file with CRC). Callers: `DuplicateClasses.java:309`, `DependencyUsage.java:593-595` and `:693`, in the plugin `UndeclaredDependencyInspection.java:101` → `DependencyUsage.undeclared`.
- **Problem:** Reactor modules are on the classpath with `target/classes` (`ModuleClasspath.java:322-328`). Every module re-reads the `target/classes` of every reactor module it depends on. The inspection does this for every matching import in the daemon's read action, without `checkCanceled`.
- **Scenario:** 200 modules, ¼ of them reactor dependencies. "Duplicate classes" and "Unused/undeclared" take ~5 s each, almost only file I/O. With 30 reactor dependencies per module with 1000 classes each, the extrapolation is ≥ 20 s. In the editor every affected import costs 17–48 ms warm (3000 classes), several times that cold; a write action has to wait that long.
- **Evidence:** `scratchpad/core-deps/.../Bench.java`: 10,778 directory reads, `DuplicateClasses.build` 5.2 s with a warm JarIndex; `scratchpad/plugin-a/DirCost.java`.
- **Suggestion:** Cache directory results per run (`Map<Path, Contents>`) or by the newest mtime in the tree; A5 needs no CRCs; in the inspection keep reactor entries precomputed in the CachedValue. Effort S.

### 21. "Stop" only discards the result; loading indicator and "running" stay
- **Location:** `intellij-plugin/.../idea/ui/DetectivePanel.java:352-364` (`cancelRunning`), `:443-446`, `:460-462` (`cancel() { generation++; }`). Without a cancellation point: `ConflictMatrixPanel.java:253,276-300`, `ExclusionsPanel.java:130,143-152`, `UpgradeSimulatorPanel.java:397,414` (`Cancellation.NONE`), `DependenciesPanel.java:197,278`, `IdeUsages.java:66-74` (`inSmartMode().executeSynchronously()` without `expireWhen`).
- **Problem:** `running--` and `stopOverlay()` only run in the callback after the worker has finished. Computations that never check `c` therefore keep running.
- **Scenario:** A Maven import triggers indexing; the user clicks "Check All" and then "Stop". The status line says "Cancelled", the "Usage" page keeps showing "Checking …" until indexing ends (minutes). Another "Check All" silently skips the page (`!page.isRunning()`); on the exclusions page, refresh stays disabled.
- **Evidence:** Code as above; `invokeLater(() -> { running--; … stopOverlay(); …` only after `work.apply(check)`.
- **Suggestion:** Release immediately on cancel (a token per run, so that the late callback does not count twice); pass the cancellation into `ConflictMatrix.build`, `ExclusionAudit.build`, `UpgradeSimulator` (panel); `IdeUsages` with `.expireWhen(…)`. Effort M.

### 22. Unchecked boxes come back after search, filter or reimport
- **Location:** `intellij-plugin/.../idea/ui/FindingTree.java:109-110,132`; `ExclusionsPanel.java:94-96,198` (`checkedByDefault = f -> true`). The findings have no `equals`.
- **Problem:** `known = all()` contains only the currently visible nodes. Hidden findings and findings recreated after a reload get the default again.
- **Scenario:** The user unchecks exclusion E because it should stay, and removes the others. `FixRunner` reimports, the page recomputes, and E is checked again. The next click on "Remove" deletes E as well; with the preview switched off, without asking. The same happens when typing into the search field and clearing it again.
- **Evidence:** `n.setChecked(can && (known.contains(item) ? wasChecked.contains(item) : checkedByDefault.test(item)));`
- **Suggestion:** Keep the user's choice as a map over a stable key (file + artifact + exclusion), independent of visibility and reload. Effort S–M.

### 23. An analysis started before a Maven import counts as current afterwards
- **Location:** `intellij-plugin/.../idea/AnalysisResults.java:124-131` (for RUNNING only `before` set to STALE), `DependencyUsagePanel.java:114-118,157-162`, analogously `DuplicateClassesPanel.java:120-124,163-168`, `UpgradeSimulatorPanel.java:550`.
- **Problem:** `mavenImported` calls `setStale(true)` only if `result != null`; the result of the running pass (trees from the start) then calls `setStale(false)` and `DONE`.
- **Scenario:** "Check usage" is running (it waits for smart mode anyway), the user adds dependency X in the editor, auto-reload imports. The result reports X as "undeclared", without the "outdated" bar; "Fix" wants to declare X a second time.
- **Evidence:** Code as above; I did not reproduce the time window in `runIde`.
- **Suggestion:** An import counter in `AnalysisResults`, remembered at the start and compared in `done`. Effort S.

### 24. Loading/"running" states stay after an error, `null` or abort
- **Location:**
  - (a) `BuildDoctorPanel.java:302-309` + `SetupComparisonView.java:57,83-88`
  - (b) `BuildDoctorPanel.java:333-341` + `PreflightView.java:117-123`
  - (c) `OverviewPanel.java:150-151,271-277,319-326`
  - (d) `UpgradeSimulatorPanel.java:397-404` + `UpgradeResultEditor.java:182,253-255`
- **Scenario:**
  - (a) `compareSetups` returns `null` on an exception; the table permanently shows "Comparing IDE and command line …".
  - (b) If the preflight throws, "Checking …" stays forever.
  - (c) "Check All" → Stop: the setup tile keeps spinning.
  - (d) If the simulation throws, "Apply" and "Simulate again" stay greyed out in the editor tab.
- **Evidence:** `aborted()`/`failed()` are not overridden in these pages.
- **Suggestion:** Override `aborted()`/`failed()`, reset the views or call `fireResult()`/`showSetup()`; a dedicated empty text for `null`. Effort S.

### 25. `PomInsights.matrix()`: a discarded result is never recomputed
- **Location:** `intellij-plugin/.../idea/PomInsights.java:130-153` (`finally computing=false`, generation check at 147), `invalidate` (`:104-111`, no daemon restart).
- **Scenario:** Save pom.xml twice in quick succession, or the Maven import finishes while the first matrix is still computing. The result is discarded, and neither a new computation nor a `DaemonCodeAnalyzer.restart` follows. A highlighting pass in that window has cached facts with empty `conflicts`. The code vision "3 modules · 2 versions" disappears until the user types again.
- **Evidence:** Code read (race, not reproduced in `runIde`).
- **Suggestion:** Recompute on a generation change (loop) or trigger `changed()`; `invalidate()` restarts the daemon. Effort S.

### 26. Notifications insert diagnosis and error texts unescaped as HTML
- **Location:** `intellij-plugin/.../idea/BuildDoctorService.java:358-363` (`d.getCause()`), `:230-232`; `FixRunner.java:94,137` (`e.getMessage()`).
- **Scenario:** The cause `settings.xml has no <server> with the ID 'nexus'` (`texts.properties:63`) appears in the balloon as "settings.xml has no  with the ID 'nexus'", because `<server>` is treated as a tag.
- **Evidence:** `createNotification(…, d.getCause(), …)`; `FixRunner.apply` deliberately escapes elsewhere.
- **Suggestion:** `Html.esc` or `StringUtil.escapeXmlEntities` before `createNotification`. Effort S.

### 27. The console link "BUILD FAILURE" shows the newest diagnosis, not the one of this console
- **Location:** `intellij-plugin/.../idea/BuildFailureConsoleFilter.java:68` (`BuildDoctorPanel::showLatest`).
- **Scenario:** Run A fails, then run B fails or a log from the clipboard is analysed. Clicking in A's console shows B's diagnosis.
- **Evidence:** `HyperlinkInfo open = p -> Navigator.buildDoctor(p, BuildDoctorPanel::showLatest);`
- **Suggestion:** Associate the run via the console or ExecutionEnvironment and show the matching history entry. Effort M.

### 28. A full, forced sync of all Maven projects after every POM fix
- **Location:** `intellij-plugin/.../idea/IdeMaven.java:458-466`, called from `FixRunner.java:423`.
- **Scenario:** One exclusion in a reactor with 300 modules triggers `forceUpdateAllProjectsOrFindAllAvailablePomFiles()`, i.e. `MavenSyncSpec.full` over all modules (minutes). Afterwards all analyses are "outdated".
- **Evidence:** Code + platform bytecode.
- **Suggestion:** Update only the affected projects (`forceUpdateProjects(…)` or an incremental sync). Effort S.

### 29. Editor caches depend on the global VFS counter; every recomputation scans the reactor
- **Location:** `intellij-plugin/.../idea/PomInsights.java:162-165`, `StaleExclusionInspection.java:58-59` (dependency `VirtualFileManager.getInstance()`); in addition a new `PomLocator` each time → `PomLocator.byCoordinates` (`:154`) → `ProjectPoms.all()` (`:113-121`) with a DOM parse of every module POM, without `PomCache`.
- **Scenario:** Every saved file, including build output, invalidates the facts and the exclusion audit. The next highlighting pass of the open pom.xml recomputes in the daemon: reactor scan 30–170 ms with 316 modules per consumer, plus ExclusionAudit.
- **Evidence:** `scratchpad/plugin-a/ScanCost.java`.
- **Suggestion:** Depend only on our own tracker + POM event + import counter; cache `ProjectPoms` per project or attach it to `PomCache`. Effort M.

### 30. Static caches exist several times and are never cleared
- **Location:** `core/.../check/ClasspathChecks.java:46` and `intellij-plugin/.../IdeUsages.java:46` (one `JarIndex` each, up to 5000 entries); `PomInsights.java:53`, `StaleExclusionInspection.java:44`, `UndeclaredDependencyInspection.java:55` (one `PomCache` each, up to 20,000 entries). `searchRepository` (`ClasspathChecks.java:232`) reads up to 600 JARs that are on no classpath into the cache.
- **Scenario:** In the IDE the same JARs are in memory twice and the same POMs three times. Neither closing the project nor `LowMemoryWatcher` clears them.
- **Evidence:** 590 JARs × 1000 classes = 52 MB (`Bench3.java`); 1165 JARs / 287k classes ≈ 30 MB per index (`JarMem.java`).
- **Suggestion:** One shared cache each, limited by class count, cleared via `LowMemoryWatcher.register(…, parentDisposable)` and when the project closes; repository search with a local, short-lived index. Effort S.

### 31. `MavenVersion` differs from `ComparableVersion`
- **Location:** `core/.../deps/MavenVersion.java:61-90` (no sub-lists at `-`, zeros trimmed only at the end), `:100` (truncation to 18 digits), `:110-118` (`a`/`b`/`m` always qualifiers).
- **Scenario:** `ConflictMatrix.java:181` merges "equal" but different versions via `TreeMap(ORDER)`, while `isDivergent` counts strings; the display becomes inconsistent. `UpgradeSimulator:599` may rate an upgrade as a downgrade.
- **Evidence:** Run myself against `maven-artifact-3.9.11` (`scratchpad/core-pom/.../VersionCmp.java`), 13 of 58 pairs differ, among them:

  | Pair | Maven | Tool |
  |---|---|---|
  | `1.0-1` vs `1.0.1` | -1 | 0 |
  | `1.0.0-rc1` vs `1.0-rc1` | 0 | 1 |
  | `2.0.0-M1` vs `2.0-M1` | 0 | 1 |
  | `1.0-a` vs `1.0` | 1 | -1 |
  | `2.13.4.2` vs `2.13.4-2` | 1 | 0 |
  | `1.2.3-b` vs `1.2.3-beta` | 1 | 0 |
  | 20-digit numbers | ≠ | 0 |
- **Suggestion:** Rebuild the `ComparableVersion` algorithm (ListItem at `-`, normalisation per sub-list, `BigInteger`); the pairs as a test table in `MavenVersionTest`. Effort M.

### 32. Declaration with `${project.groupId}`: POM fixes do not find the dependency
- **Location:** `core/.../deps/PomEditor.java:147,194,245,366,487` (raw text comparison).
- **Scenario:** Reactor siblings with `<groupId>${project.groupId}</groupId>`. A5, A6 and `add-exclusion` pass the interpolated GA; `removeDependency`/`setScope("com.acme","a")` report "dependency com.acme:a not found under dependencies", and the offered fix fails.
- **Evidence:** `scratchpad/core-pom/.../EditorCases.java` (output in `editor-cases.txt`).
- **Suggestion:** Give the editor an interpolator (properties of the chain) or resolve candidates with `${` via the model. Effort M.

### 33. Property defined twice: the tool takes the first, Maven the last
- **Location:** `core/.../deps/PomFile.java:289`, `PomEditor.java:783-784`.
- **Scenario:** `<v>1</v><v>2</v>` in `<properties>`. Maven takes 2, "Why This Version?" explains 1. `setProperty(v,3)` changes the first definition and therefore has no effect.
- **Evidence:** `MavenXpp3Reader` yields `v=2`, `PomFile` yields 1 (`EditorCases.java`).
- **Suggestion:** Let the last one win, when reading as well as in the editor. Effort S.

### 34. POM with a UTF-8 BOM: upgrade simulation fails
- **Location:** `core/.../deps/PomFile.java:151-159` (`parse(path, xml)` via `StringReader`), callers `UpgradeSimulator.java:522`, `PomLocator.withOverrides`.
- **Scenario:** The POM starts with a BOM. A6 reports "… would not be valid XML after the change: Content is not allowed in prolog", although `PomFile.read` and Maven read the same file.
- **Evidence:** `scratchpad/core-pom/.../Repro2.java`.
- **Suggestion:** Strip a leading `\uFEFF` in `parse`. Effort S.

### 35. A self-referencing property blows up memory
- **Location:** `core/.../deps/EffectiveModel.java:299-322`.
- **Scenario:** `<x>${x}${x}${x}${x}${x}${x}</x>` ends after 7.8 s with an `OutOfMemoryError` (1 GB heap), because the length grows with 6^10. In the plugin this happens in the background or in the daemon. The simple cycle `a→b→a` yields `value="${a}"` with `isResolved()==true`.
- **Evidence:** `scratchpad/core-pom/.../ModelCases.java`. Maven itself rejects such POMs, hence only Medium.
- **Suggestion:** A `visiting` set or resolution stack, report the cycle as unresolved, a length limit. Effort S.

### 36. `set-jvm-option` merges `.mvn/jvm.config` into one line
- **Location:** `core/.../fix/FixExecutor.java:80-92`.
- **Scenario:** `# Memory for large builds\n-Xmx1g\n-Dfile.encoding=UTF-8` becomes `# Memory for large builds -Dfile.encoding=UTF-8 -Xmx2g`. If Maven strips `#` comments line by line, all options are gone, including the new one; otherwise `#` ends up as a JVM argument in the call.
- **Evidence:** `scratchpad/core-log/Repro.java jvmconfig`.
- **Suggestion:** Line-based: replace the option in its line or append it as a line of its own, keep comments. Effort S.

### 37. Paths with spaces are cut off
- **Location:** `core/.../check/RuntimeChecks.java:86`, `RepositoryChecks.java:155`, `PomChecks.java:34`, catalog `:92,96,115,151,155`.
- **Scenario:** Project under `C:\Users\Max Mustermann\…` or `OneDrive - Company`:
  - Compiler error: `open-file` jumps to `mustermann/proj/src/main/java/A.java`.
  - Corrupt JAR: `/.m2/repository/…` is checked, the diagnosis says "no longer exists" instead of offering to delete it.
  - `Child module … does not exist` yields `unknown`.
- **Evidence:** `scratchpad/core-log/SpacePaths.java`.
- **Suggestion:** Anchor on literals, e.g. `error reading (?<file>.+?\.jar)`, `^(.+?\.(?:java|kt|groovy)):\[`, `@ (.+?\.xml), line`. Test log with a path containing spaces. Effort S.

### 38. Exceptions in lines without a prefix lead to diagnoses for successful builds
- **Location:** `core/.../io/MavenLogParser.java:84-86,91-97`.
- **Scenario:** An application log line `WARN … falling back: java.lang.NoClassDefFoundError: org/optional/Feature` from a passing test, followed by `BUILD SUCCESS`: `diagnose` prints the card "#10 Runtime conflict in tests". In a red multi-module run, this rule (priority 60) beats a genuine `artifact-not-resolvable` (50).
- **Evidence:** `scratchpad/core-log/success.log`.
- **Suggestion:** Evaluate such lines only inside Surefire failure blocks or on `BUILD FAILURE`, or treat them as weaker evidence. Effort S.

### 39. Maven and Java versions are only read without a line prefix
- **Location:** `core/.../io/MavenLogParser.java:26-28,135-152` (`^Apache Maven`, `^Java version`).
- **Scenario:** CI logs with timestamps and `mvn -V` (GitHub Actions, Jenkins): the Maven and Java versions stay empty. `jdk-version` cannot confirm, the cause stays generic ("JDK of an older version").
- **Evidence:** `FormatCheck.java` (run myself): `maven=null java=null` for every prefix.
- **Suggestion:** Apply the session hints to the text after the prefix; together with #8. Effort S.

### 40. Unused `logLines` occupy memory
- **Location:** `core/.../io/MavenLogParser.java:40,60-62`, `BuildFailure.getLogLines`.
- **Problem:** The first 20,000 lines, i.e. the beginning of the log, are stored; they are read nowhere.
- **Scenario:** The plugin keeps 20 `Result`s with `failure` in its history.
- **Evidence:** `scratchpad/core-log/Retained.java`: 20 runs hold 60 MB of heap.
- **Suggestion:** Remove them or replace them with a ring buffer of the last N lines. Effort S.

### 41. An exclusion in the parent is only checked in the parent's context
- **Location:** `core/.../deps/ExclusionAudit.java:175` (`consumer = locator.model(pom)` of the declaring POM).
- **Scenario:** The parent passes down `org.d:d:1.0` with the exclusion `org.x:x`. The child manages `org.m:m` at 2.0, and `m:2.0` brings x. The parent reports STALE; after the fix the child's tree contains `org.x:x:1.0`.
- **Evidence:** `scratchpad/core-deps/.../Repro2.java` (R9), fix applied in memory via `withOverrides`.
- **Suggestion:** Check inherited exclusions in the context of every inheriting module; STALE only if STALE everywhere. Effort M.

### 42. Exclusions from the dependencyManagement of the dependency's POM are missing
- **Location:** `core/.../deps/DependencyResolver.java:182-183` (`own.dep.getExclusionEntries()` unused), `ExclusionAudit.Closure:333-337`.
- **Scenario:** Parents in the style of hadoop-project manage exclusions, and Maven's model builder injects them. The resolver puts `org.x:x:1.0 (compile)` into the tree, Maven leaves it out. Consequences: wrong duplicate classes, wrong deltas in A6, wrong UNDECLARED reports.
- **Evidence:** Run myself (R4): `org.l:l → org.m:m → org.x:x:1.0 (compile)`.
- **Suggestion:** If `dep` has no exclusions of its own, merge in the managed ones. Effort S.

### 43. Classpath without a tree (`fromPoms`) differs from Maven and is twice as slow
- **Location:** `core/.../deps/ModuleClasspath.java:245` (type=pom skipped), `:268-280` (closure per direct dependency, loser subtrees are counted), `:297-303` (key g:a only).
- **Scenario:** Classpath `[d1, y:1.0, x:2.0]`; Maven does not have y (it hangs off the loser x:1.0), but it has z (via the dependency of type pom). test-jar and jar collapse into one entry. The `DependencyResolver` is right in both cases.
- **Evidence:** R6 run myself; `DuplicateClasses.build` without trees 10.9 s instead of 5.2 s.
- **Suggestion:** Without a tree use `DependencyResolver.resolve` (like `ClassOrigin`) and drop `fromPoms`. Effort S–M.

### 44. A5: class in several JARs → declared dependency certainly UNUSED
- **Location:** `core/.../deps/DependencyUsage.java:640-655` (the first compile entry wins).
- **Scenario:** A starter brings `impl`, which contains `jakarta.servlet.Servlet`; in addition `jakarta.servlet-api` (provided) is declared, and the code imports Servlet. Result: `UNUSED jakarta.servlet-api certain=true fix=remove-dependency`, and `UNDECLARED impl` is hidden as a starter.
- **Evidence:** Run myself (R8).
- **Suggestion:** If a declared direct entry also provides the class, attribute it to that entry. Effort S.

### 45. Linear searches dominate resolver, matrix and simulator
- **Location:** `core/.../deps/EffectiveModel.java:230-241` (a miss runs over all managed entries), `PomLocator.java:154-161` (`byCoordinates` over the whole reactor). Callers: `DependencyResolver:170,177`, `ConflictMatrix:312,357`, `ExclusionAudit.Closure:369`.
- **Evidence:** `managed()` hit 128 ns, miss 4126 ns (1500 entries); `byCoordinates` 6.6 µs with 201 POMs. JFR: ~80 % of the CPU samples of resolver, matrix and simulator in `managed`.
- **Suggestion:** Index g:a → managed per model, map GAV → POM in the locator. Effort S.

### 46. A6 computes everything twice with a cold locator, holds ~98 MB and cannot be cancelled
- **Location:** `core/.../deps/UpgradeSimulator.java:507-510,537-551` (`withOverrides` with an empty cache), `ModuleResult` holds the trees before/after; `intellij-plugin/.../UpgradeSimulatorPanel.java:414` (`Cancellation.NONE`).
- **Evidence:** `simulate` 5.2 s with 201 modules (one resolution 3.0 s), the result holds ~98 MB.
- **Suggestion:** Reuse unchanged `PomFile`s; re-resolve only modules whose chain contains a changed file; drop the trees after the diff; pass the cancellation through. Effort M.

### 47. Maven Wrapper 3.3.x is not found
- **Location:** `core/.../env/BuildSetup.java:190`.
- **Problem:** Only `dists/apache-maven-X-bin/<hash>/…` is searched; the current mvnw (only-script, the default of `mvn wrapper:wrapper`) unpacks to `dists/apache-maven-X/<hash>/`.
- **Scenario:** `mavenHome` stays `null`; the global `conf/settings.xml` (mirrors, proxies) is missing in the CLI preflight and in the IDE vs. CLI comparison.
- **Evidence:** `ReproWrapper.java`: `mavenHome=null`.
- **Suggestion:** Check both directory names. Effort S.

### 48. Events JSONL without synchronisation
- **Location:** `maven-extension/.../DetectiveEventSpy.java:278-290` (`write(json)`, `write('\n')`, `flush` separately, `writer = null` as a data race), `:124` (`repositoryEvents++` not atomic).
- **Scenario:** Two modules fail at the same time under `-T 4`, or metadata errors come from resolver threads. Lines get interleaved, `EventLog.read` silently discards them, and both `mojoFailed` are missing for CLI/CI. The card in the log is not affected, because it comes from the synchronised list. Incidentally: on an `IOException` the writer stays open.
- **Evidence:** Replica of `record()` with 4 threads × 2000 events: 1149 of 8000 lines unreadable (run myself). Whether Maven's `EventSpyDispatcher` really calls in parallel was not measured, hence plausible.
- **Suggestion:** Synchronise `record` or write `json + "\n"` in a single call; `AtomicInteger`; close the writer on errors. Effort S.

### 49. The HEAD probe may send credentials via the IDE authenticator
- **Location:** `core/.../preflight/RepositoryProbe.java:59-66`.
- **Problem:** On 401/407, `HttpURLConnection` asks `Authenticator.getDefault()`; in IntelliJ this is `IdeaWideAuthenticator`, which may supply remembered credentials or show a dialog. This contradicts "never send credentials".
- **Scenario:** Preflight with network in the IDE against a Nexus that answers 401.
- **Evidence:** Code; I did not check the IDE authenticator's behaviour in `runIde`.
- **Suggestion:** From Java 9 on, call `setAuthenticator(…)` via reflection with an authenticator that returns `null` (the core stays Java 8 bytecode). Effort S.

### 50. The log navigator builds large logs entirely on the EDT
- **Location:** `intellij-plugin/.../idea/LogNavigatorView.java:134-139,146-160,162-211`; clipboard without a limit in `BuildDoctorActions.java:52-57`.
- **Scenario:** 8 million characters (78k lines): `clean` 12–83 ms plus `LogOutline.of` 84–163 ms plus document, editor, 7.8k highlighters and fold regions, all on the EDT. A 50 MB CI log from the clipboard freezes the IDE for an estimated 1–2 s.
- **Evidence:** `scratchpad/plugin-a/LogCost.java` (only the core part measured).
- **Suggestion:** Run `clean` and `LogOutline` via `background`, only editor and markup on the EDT; cap the clipboard. Effort S–M.

### 51. The history holds up to 20 `ExecutionEnvironment`s and raw logs
- **Location:** `intellij-plugin/.../idea/BuildDoctorService.java:59-61,116,129`; `MavenRunListener.java:39,47,110-115`.
- **Problem:** `ExecutionEnvironment` holds the `RunContentDescriptor` (console, ProcessHandler) and a `DataContext`. After the first truncation the log contains `CUT_MARKER` with "…" (not Latin-1), so the string becomes UTF-16, i.e. twice as large. Logs from the clipboard have no limit.
- **Scenario:** 20 large red runs ≈ 320 MB of raw logs plus closed consoles, released only when the project is closed.
- **Evidence:** Fields checked with `javap`; not measured with a heap dump.
- **Suggestion:** Store only the configuration in the history, the `ExecutionEnvironment` only for the last run; discard older raw logs; marker in ASCII. Effort M.

### 52. An `Error` in the background leaves the loading indicator and "running" forever
- **Location:** `intellij-plugin/.../idea/ui/DetectivePanel.java:432-438` (only `ProcessCanceledException` and `RuntimeException` are caught).
- **Scenario:** "Duplicate classes" with very many JARs → `OutOfMemoryError`, or deep recursion → `StackOverflowError`. The callback is never posted; overlay, `running` and `AnalysisResults` (RUNNING) stay until the IDE restarts.
- **Evidence:** Code.
- **Suggestion:** Post the callback in `finally`, catch `Throwable`, rethrow `ControlFlowException`/`Error` afterwards. Effort S.

### 53. `FixRunner.apply` does not check the read-only status
- **Location:** `intellij-plugin/.../idea/FixRunner.java:396-407`.
- **Scenario:** Pin or upgrade across two POMs, one of them read-only (Perforce/ClearCase). The first one is changed, `replaceString` of the second throws: what remains is half an undo step and "Fix failed".
- **Evidence:** Code; whether `replaceString` throws in the middle of the command needs to be checked in `runIde`.
- **Suggestion:** Call `ReadonlyStatusHandler.ensureFilesWritable(project, files)` first and abort on failure. Effort S.

### 54. The effective POM hash at the end of the build contains runtime properties
- **Location:** `maven-extension/.../Snapshots.java:87,123-134`; consequences in `SnapshotStore.java:88`, `SnapshotDiff.java:302-304`.
- **Scenario:** jacoco `prepare-agent` sets `argLine`, buildnumber/git-commit-id set timestamp properties. With buildnumber every red run shows "effective POM changed" for all modules; after a green `verify` with jacoco and a green `validate`, `carryOver` does not apply.
- **Evidence:** Code; plugin behaviour from their documentation, not reproduced with a real Maven.
- **Suggestion:** Compute the hash at `SessionStarted` (before the first mojo) and remember it per project. Effort S–M.

### 55. Homebrew Maven: version unknown, no global settings.xml
- **Location:** `core/.../env/BuildSetup.java:262-265,293-304`.
- **Scenario:** `/opt/homebrew/bin/mvn` points to `Cellar/maven/X/bin/mvn` (an env script), the installation lives in `Cellar/maven/X/libexec`. `mavenHome` becomes `Cellar/maven/X` without `lib`/`conf`; the preflight reports "Maven version unknown" (yellow), the global settings are missing.
- **Evidence:** `ReproWrapper.java` (Homebrew layout recreated): `version=null`.
- **Suggestion:** If `home/lib` is missing, try `home/libexec`. Effort S.

### 56. Enforcer `requireJavaVersion 8` is interpreted differently than in the enforcer
- **Location:** `core/.../preflight/VersionRange.java:526-527`, called from `PreflightChecks.java:162-163`.
- **Scenario:** Runner Java `1.8.0_392`, rule `<version>8</version>` or `[8,)` → ERROR "Enforcer rejects Java", while the enforcer itself is satisfied.
- **Evidence:** `ReproVersionRange.java`: `DIFF` in both cases. From which enforcer version on "8" counts as "1.8" was not checked offline.
- **Suggestion:** For `requireJavaVersion`, read the bounds `8`, `7`, … as `1.8`, `1.7`. Effort S.

### 57. Cancelling does not stop Maven on Windows
- **Location:** `core/.../deps/PomFetcher.java:225,231,235`.
- **Scenario:** Cancelling "Download Missing POMs" or a timeout on Windows: `process.destroy()` only ends the `cmd.exe` of `mvn.cmd`/`mvnw.cmd`, the Maven JVM keeps downloading (network, locks in the local repository).
- **Evidence:** Code; not critical on Linux (`exec`), Windows not tested.
- **Suggestion:** `ProcessHandle.descendants()` via reflection or `taskkill /T /F`. Effort M.

### 58. Visible texts and colours in code
- **Location:**
  - Core: `env/BuildSetup.java:111` (`"Wrapper (./mvnw)"`), `deps/DuplicateClasses.java:140` (`"(Standardpaket)"`), `deps/ConflictMatrix.java:426` (`"| Artifact |"`), `report/Reports.java:172,175` (`"Offline"`, `"Goals"`), `check/RuntimeChecks.java:191` (`"Surefire argLine: "`); dead German labels in `snapshot/Change.java:430-441` and `deps/DependencyUsage.java:52-54`.
  - Plugin: `DuplicateClassesPanel.java:376` (`c.getDrop().label() + " raus"`, also in the English UI), `SetupComparisonView.java:142` (`"IDE"`), `ConflictMatrixPanel.java:615` (`"BOM"`), `MavenRunListener.java:47` (fixed German text in the log), `PreflightView.java:87` (`JBColor.GRAY` instead of `DetectiveColors`, escapes the contrast test).
- **Scenario:** The English UI shows "→ guava raus".
- **Evidence:** Code (checked myself: `DuplicateClassesPanel.java:376`).
- **Suggestion:** Keys in both properties files; delete dead labels; colour via `DetectiveColors.named`. Effort S.

### 59. Maven API outside of `IdeMaven`
- **Location:** `intellij-plugin/.../idea/BuildDoctorService.java:30,63-64,217,250`; `MavenRunListener.java:12,77`; `FixRunner.java:27-29,213-219` (`MavenRunnerParameters.clone`, `MavenRunConfigurationType.runConfiguration`).
- **Suggestion:** Introduce `IdeMaven.isMavenRun(env)`, `IdeMaven.runWithExtraArgs(…)`. Effort S.

### 60. `StaleExclusionInspection.RemoveExclusionFix` changes the POM bypassing `editPoms`
- **Location:** `intellij-plugin/.../idea/StaleExclusionInspection.java:155-166`.
- **Problem:** PSI `delete()` without a diff dialog, without `IdeMaven.reimport`; `previewPomFixes` has no effect. The IDE model stays outdated until a manual reload. Together with #17/#18, Alt+Enter thus removes an exclusion that is still needed, without the usual preview.
- **Evidence:** Code checked myself.
- **Suggestion:** `remove-exclusion` as a `FixAction` via `FixRunner.editPoms`, `startInWriteAction=false`. Effort S.

### 61. Values go into XML unescaped; commented-out repositories are used
- **Location:** `core/.../deps/PomFetcher.java:41` (version `[^\s:()]+` allows `<>&`), `:130-134`, `:42,150-160` (`<repositories>` via a regex over the raw text); `PomEditor.java:404,515-550`.
- **Scenario:**
  - A foreign POM in the local repository with a crafted `<version>` ends up as additional XML in the temporary POM via `getMissing()`. Maven (strict) rejects `<build><extensions>` there, additional `<dependency>` entries remain possible. There is no path to code execution.
  - Commented-out `<repositories>` are queried as well.
  - `setProperty(p, "a&b<c")` produces invalid XML.
- **Evidence:** `scratchpad/core-pom/.../FetchInject.java`, `EditorCases.java`.
- **Suggestion:** XML-escape values, check the version against `[\w.\-+]+`, read repositories via SAX. Effort S.

### 62. `PomEditor` with an entity, CDATA or comment in the value
- **Location:** `core/.../deps/PomEditor.java:382,776`.
- **Scenario:** `<groupId>org&#46;x</groupId>` or `<![CDATA[g]]>` → "not found", although `PomFile` reads them correctly. `setVersion` on `<version>1<!-- LTS --></version>` deletes the comment.
- **Evidence:** `EditorCases.java`.
- **Suggestion:** Decode field values; replace only the text part. Effort S.

### 63. `SourceScanner` loses real usages
- **Location:** `core/.../deps/SourceScanner.java:203-224`.
- **Scenario:** A text block with `\"""` ends too early, the rest of the file counts as a string. Kotlin `"${org.x.T.f()}"` and Groovy `'''` are handled incorrectly. Consequence in the CLI: A5 reports a used dependency as UNUSED with the fix `remove-dependency` (the IDE uses PSI for Java).
- **Evidence:** `ScanCases.java`: `org.real.Used` is missing, `org.fake.Inside` is counted; Kotlin case `[]`.
- **Suggestion:** Escapes in text blocks, `'''`, `${…}` in Kotlin/Groovy strings as code. Effort S–M.

### 64. Cycle back to the module itself
- **Location:** `core/.../deps/DependencyResolver.java:217,276` (root not in `winners`).
- **Scenario:** `org.l:l:1.0 → com.acme:app:0.9`: the module appears as INCLUDED in its own tree, Maven leaves it out as a cycle; the conflict matrix gets a row for the module itself.
- **Evidence:** R5 run myself.
- **Suggestion:** Put the root's key into `winners` up front or mark it as CYCLE. Effort S.

### 65. `Json` without a depth limit; large numbers discard the line
- **Location:** `core/.../io/Json.java:45-63,93-112,173`; `EventLog.java:70-71` only catches `IllegalArgumentException`.
- **Scenario:** An events line with 100,000 `[` ends `diagnose` with a `StackOverflowError`; a number above `Long.MAX_VALUE` drops the whole event line.
- **Evidence:** `scratchpad/core-log/deep.jsonl`, `Repro.java json`.
- **Suggestion:** Limit the depth (e.g. 256 → `IllegalArgumentException`), overflow as `BigInteger`/`Double`. Effort S.

### 66. Proxy and mirror selection differs from Maven
- **Location:** `core/.../preflight/RemoteRepositories.java:482-495`; `env/MavenSettings.java:202-214` with `RemoteRepositories.java:190-198`.
- **Scenario:**
  - Proxy p1 (https, `*.example.org` bypassed) and p2 (http): the preflight takes none, Maven takes p2.
  - The user has `mirrorOf *`, the global settings have `mirrorOf central`: Maven takes the global one (exact ID across both files), the preflight the user's.
- **Evidence:** Proxy case run myself (`ReproPreflight` case 6: `activeProxy(https)=p1 bypasses=true`); mirror case derived from Maven's code.
- **Suggestion:** Merge the lists of both files, select like `DefaultProxySelector`/`DefaultMirrorSelector`. Effort S.

### 67. Relative `-Dmaven.repo.local` resolved against the JVM's working directory
- **Location:** `core/.../env/BuildSetup.java:162-165`, `PreflightContext.java:468-475`.
- **Scenario:** `.mvn/maven.config` with `-Dmaven.repo.local=.m2/repository`, `preflight --project DIR` from a different directory → wrong repository: "newly created", parents missing.
- **Evidence:** `ReproPreflight` case 4 run myself: `report.localRepository=.m2/repository`.
- **Suggestion:** Resolve with `projectDir.resolve(…)` in `detectCommandLine`. Effort S.

### 68. Minor robustness issues in the core
- **Location and problem:**
  - `deps/DependencyTreeParser.java:86-97`: with `-Dtokens=extended` (`├─`, `└─`, `│`) only the root remains, without a hint.
  - `deps/JarIndex.java:176-189`: `visitFileFailed` not overridden; a file in `target/classes` deleted while reading makes the whole directory fail.
  - `deps/ClassFile.java:147-157`: with `getSize()==-1`, `MAX_CLASS_BYTES` does not apply.
  - `catalog/RuleCatalog.java:71` / `io/MiniYaml.java:41-48`: catalog with a UTF-8 BOM → "expected 'key: value', found: \uFEFF# Katalog".
  - `catalog/Template.java:41` also normalises fix parameters (`'a  b'` → `'a b'`, custom catalogs only).
  - `check/Coordinates.java:32,45-51`: `.`/`..` allowed as artifactId/version; `directoryIn(repo)` then points to `parent(repo)` (read-only, deletion protected by `FixExecutor.allowed`).
  - `deps/ClassOrigin.java:407`: the 600-JAR limit also counts sources/javadoc/tests JARs.
  - `deps/DependencyResolver.java:173`: transitive `system` is discarded, Maven only filters test/provided.
  - `check/ClasspathChecks.java:233,547,565`: always `Cancellation.NONE`.
- **Evidence:** `TreeCases.java`, code or `Repro.java yaml/template`.
- **Suggestion:** One by one, effort S each.

### 69. Minor UI bugs in the plugin
- **Location and problem:**
  - `PreflightView.java:136-145`: if the first row is an ERROR, the first WARNING gets selected later (`select == 0` as "nothing selected").
  - `ConflictMatrixPanel.java:338-343`: column widths reset on every keystroke in the search.
  - `UpgradeSimulatorPanel.java:186-190,501-506,536`: "Apply" in the tool window stays enabled for an outdated or running result (not in the editor tab).
  - `DependenciesPanel.java:73-77,127-133,170-176,211-262`: when switching modules, the old explanation stays (also in the Markdown); `selectInList` starts two runs.
  - `ConflictMatrixPanel.java:222-226,250`, `ExclusionsPanel.java:94-96,127`: `saveUnsavedPoms` on every automatic run saves half-edited POMs.
  - `DependencyUsagePanel.java:57-59,149,252`: the setting "via starters" only takes effect after re-checking.
  - `SetupComparisonView.java:73-81`, `PreflightView.java:159-163`: Ctrl+C not wired.
  - `BuildDoctorPanel.java:171-204`: a new run pulls the user out of the log navigator.
  - `BuildDoctorPanel.java:77-99,333-341`: the network preflight cannot be cancelled, the loading indicator covers the tab.
  - `FixRunner.java:222-226`: for logs from the clipboard, `mvn -U` is copied without goals.
  - `UndeclaredDependencyInspection.java:196`: "Fix all" produces N−1 "already declared" warnings.
- **Evidence:** Code.
- **Suggestion:** One by one, effort S each.

### 70. Minor performance issues
- **Location and problem:**
  - `snapshot/SnapshotDiff.java:388-397`, `Change.java:481-485`: linear search per change. 5000 changes across 800 modules ≈ 2 s at the end of a red build.
  - `intellij-plugin/.../DiagnosisCard.java:456-468`: `tail` via regex over the whole log on the EDT, 20 MB ≈ 70–100 ms per click in the history.
  - `ConflictMatrixPanel.java:578-609`: per cell and paint the renderer computes `isDivergent` 4×, `versions()` 2× (set + sorting), `MavenVersion.max` and builds the tooltip.
- **Evidence:** `ReproDiffPerf.java` (1000/3000/5000 changes: 264/518/1976 ms), `scratchpad/plugin-b/tail/Tail.java`.
- **Suggestion:** Map by (area, subject, before, after); search `tail` backwards; cache `versions`/`suggested` per row, tooltip via `getToolTipText(MouseEvent)`. Effort S.

### 71. Snapshot storage and credentials in URLs
- **Location:** `core/.../snapshot/SnapshotStore.java:52-56` (`toAbsolutePath().normalize()`); `maven-extension/.../Snapshots.java:72`, `preflight/RemoteRepositories.java:300,381,422`.
- **Scenario:**
  - On Windows the IDE supplies `C:\Users\x\proj`, but `mvn.cmd` after `cd c:\users\x\proj` supplies `c:\…` (likewise symlinks such as macOS `/tmp`). Two stores are created, and a red run finds no green one. The storage is never cleaned up.
  - `https://user:token@nexus/…` is stored in plain text in `latest-extension.json`, on the card and in the copied Markdown (it is not sent anywhere).
- **Evidence:** Code.
- **Suggestion:** `toRealPath()` with a fallback, lower-case on Windows; remove old folders when writing; mask user info in URLs. Effort S.

## Performance measurements

All measurements with JDK 21 on Linux, core from `core/build/classes` (commit 71794bc). Unless noted otherwise, warm in the file system cache.

| What | With | Input size | Result | Bottleneck |
|---|---|---|---|---|
| `diagnose` with a long line without spaces | `Main diagnose`, measured myself | 20,000 / 60,000 characters | 1.3 s / 14.8 s | O(L²) regex in checks (#7) |
| Single regexes against pathological lines | `core-log/RegexBench.java` | 107 regexes × 23 lines, 10k / 40k characters | ≥ 20 ms only in checks at 10k; 2–9 s per regex at 40k | `UNKNOWN_HOST`, `LOCATION`, `PATH`, corrupt-jar `extract` |
| `diagnose` large log | `Main diagnose -Xmx128m`, measured myself | 100 MB, 946k lines | 0.67 s | streams; not critical |
| `outline` large log | `Main outline` | 100 MB with `-Xmx256m` / 300 MB with `-Xmx256m` | 1.0 s (RSS 331 MB) / OOM after 0.5 s (measured myself) | `LogOutline.of` holds all lines (1.5–2× log size) |
| Many errors | `Main diagnose`/`outline` | 96k lines, 4000 failed tests | 0.83 s / 0.21 s | no quadratic load |
| Memory retained by the parser | `core-log/Retained.java` | 20 runs of 100 MB each | 60 MB | unused `logLines` (#40) |
| DependencyResolver × 201 modules | `core-deps/Bench.java` | 201 POMs, BOM with 1500 entries, 500 libs × 2 versions, 502k nodes | 3.0 s (15 ms/module; cold 126 ms), +124 MB | `managed` misses, `byCoordinates` (#45) |
| ConflictMatrix.build | `Bench2` | as above, with trees | 1.38 s | `pin()` → `managed` linear |
| ExclusionAudit.build | `Bench2` | as above | 265 ms | – |
| DuplicateClasses.build | `Bench2` | with trees (JarIndex cold/warm) / without trees | 5.5 s / 5.2 s / 10.9 s | directory reads (#20), `fromPoms` (#43) |
| DependencyUsage.build | `Bench2` | as above | 5.3 s | directory reads (#20) |
| UpgradeSimulator.simulate | `Bench4` | as above | 5.2 s, result holds ~98 MB | double resolution, cold locator (#46) |
| searchRepository | `Bench3` | 590 JARs × 1000 classes / 90k files without JARs | 216 ms (+52 MB in the index) / 0.3 s | cache growth (#30) |
| Inspection: reactor `target/classes` | `plugin-a/DirCost.java` | 3000 classes, 24 MB | 17–48 ms per call (warm) | no directory cache (#20) |
| Reactor scan per editor recomputation | `plugin-a/ScanCost.java` | 316 modules | 30–170 ms | `ProjectPoms.all()` without a cache (#29) |
| `PomEditor.pinManagedVersion` | `core-pom/PerfEditor.java` | 1k / 10k / 50k deps (0.24 / 2.4 / 12 MB) | 4.8 / 37 / 148 ms | linear, not critical |
| `JarIndex` | `core-pom/JarPerf.java` | 115 JARs, 27k classes | 103 ms cold, 0.9 ms warm, ~85 B/class | – |
| Snapshot JSON | `core-env/ReproSnapshot.java`, run myself | 400 modules × 250 deps × 40 plugins (5.4 MB) | `toJson` 47 ms, `fromJson` 62 ms, `saveGreen` 44–75 ms | not critical |
| SnapshotDiff.compare | `core-env/ReproDiffPerf.java` | 1000 / 3000 / 5000 changes | 264 / 518 / 1976 ms | linear search (#70) |
| Events JSONL in parallel | `core-env/ReproJsonlInterleave.java`, run myself | 4 threads × 2000 events | 1149 of 8000 lines unreadable | missing synchronisation (#48) |

The repro and measurement files are in the scratchpad of this session (`/tmp/claude-1000/-home-timo--claude-spares-spare-6Il2xv/de9bacef-552b-4a16-aa8a-b7f732285ef9/scratchpad/`, subfolders `core-log`, `core-env`, `core-pom`, `core-deps`, `plugin-a`, `plugin-b`, `me`). They are temporary and not part of the repository.

## Checked, no findings

- **Tests:** `./gradlew :core:test` green (200 tests, 0 failures).
- **Java 8:** `core` and `maven-extension` compile with `options.release.set(8)`, so forbidden API already fails at build time. Independently verified: the whole `core/src/main/java` compiles with `javac --release 8` (major 52).
- **Charset:** apart from `FixExecutor` (#1) and the intended Maven output in `PomFetcher`, explicitly UTF-8 everywhere; no `FileReader`, no `getBytes()` without a charset.
- **Resources:** `ZipFile`, streams, `DirectoryStream`, `HttpURLConnection` (`disconnect` in `finally`) are closed.
- **XXE:** `disallow-doctype-decl` or `FEATURE_SECURE_PROCESSING` in `PomFile`, `ProjectPoms`, `MavenSettings`; an external entity is rejected.
- **Path traversal:** `delete-files` checks `normalize` + `startsWith(root)` and refuses the root itself; tested with `repo/../outside/victim.jar`. `replace-in-file`, `set-jvm-option` and POM fixes do not take their path from the log. The CLI does not execute diagnosis fixes.
- **Network and credentials:** network only with `Options.network(true)`; no server or proxy passwords read from settings.xml; timeouts set; `nonProxyHosts` and `mirrorOf` (`*`, `external:*`, `!id`) like Maven. `PomFetcher` passes no POM values in argv, reads the output in its own thread (no deadlock), timeout/cancel stop Maven on Linux.
- **Extension:** `init`, `onEvent`, `Snapshots.record` catch `Throwable`; unknown event types are caught via `NoSuchFieldError`; cause chain limited; all Maven methods exist since ≤ 3.3.1 and are exported in maven-core 3.9.11's `extension.xml`; JAR without foreign `META-INF`. Per event only `getExceptions()`/`switch`, flush only on error events.
- **Snapshots:** atomic writes (temp + `ATOMIC_MOVE`), JSON round trip with control characters/surrogates identical, missing modules with `-pl` not reported.
- **Rule engine:** stable sort by priority, checks in catalog order, the first confirming check with a cause wins; `Template`/`Texts.format` safe against `$ \ { }`; no quadratic "rules × lines" load (4000 errors in 0.83 s).
- **MiniYaml/Json:** `''` escapes, comments, block scalars, lists of maps; unsupported input aborts with a line error. Surrogate pairs are preserved.
- **Log parser:** ANSI in `[LEVEL]`/`BUILD …`, nested `[ERROR] [ERROR]`, GitHub ISO, Jenkins `[ISO]`, `HH:mm:ss(,SSS)`.
- **Texts:** EN and DE keys identical, no duplicates; all literal keys from the code present.
- **PomEditor:** CRLF, tabs, 4-space indentation, single-line POMs, PIs, DOCTYPE with internal subset, `>` in attributes, whitespace in values; profiles and plugin `<dependencies>` stay untouched; linear runtime.
- **EffectiveModel/PomLocator:** interpolation in the child's context, BOM import "first wins", parent and import cycles guarded; `relativePath` as a directory, default and empty. `PomCache` bounded and thread-safe.
- **ClassFile:** constant pool tags 1–20, long/double with 2 slots, broken attribute lengths → `IOException`.
- **DependencyResolver:** nearest wins, BFS = Maven's pre-order, losers not expanded, scopes like `JavaScopeDeriver`/`JavaScopeSelector`, exclusions cumulative with `*`, type/classifier in the conflict key, `MAX_NODES`, depth 60, cancellation per node.
- **ConflictMatrix/DuplicateClasses/UpgradeSimulator:** pin in the nearest common project parent, blockers correct, BOM imports never block; grouping in DuplicateClasses linear (no pairwise comparison); `fixesFor` writes into the declaring POM; the planner overrides external properties in the topmost project POM.
- **ExclusionAudit:** missing POM, parent or open version → UNKNOWN, never STALE (except #17, #18, #41).
- **Plugin threading:** `analyzeLog`, `recordGreen`, `preflight`, `compareSetups`, `loadGreen` in the background, display via `invokeLater(…, project.getDisposed())`; `IdeMaven.read` = `runReadAction`; PCE is rethrown before every `catch (RuntimeException)`; `invokeLater` in `Background` with `defaultModalityState()`.
- **ActionUpdateThread:** all registered actions override it correctly (`BuildDoctorActions`/`DetectiveActions` EDT; `WhyThisVersion`, `SimulateUpgrade`, `PreviewPomFixes` BGT without Swing in `update`).
- **Lifetime:** `LogNavigatorView` releases the editor; `PomDiffView` is attached to the dialog/editor; message bus connections and listeners have a parent disposable; no PSI fields without pointers.
- **Editor:** line markers only on the `XML_NAME` leaf; inlay, code vision and line markers read only cached facts; duplicate classes never from the editor; `DumbAware` plausible.
- **POM changes in the panels:** matrix, exclusions, duplicate classes, usage and upgrade go through `FixRunner.editPoms` with a re-plan comparison and one `WriteCommandAction` (exceptions: #10, #60).
- **plugin.xml:** all EPs used are `dynamic="true"` in 2025.3, listeners declarative; ASM scan against 2025.3: no `@Deprecated`/`@Internal` API, only the `@Experimental` `InlayProvider` (`BuildFailureConsoleFilter`) and the `@Obsolete` `Task.Backgroundable`/`ProgressManager.run`.
- **HTML in detail views:** user data escaped via `Html.esc` everywhere (exception: notifications, #26).
- **Tables:** no `RowSorter`; indexes converted consistently.

## Open questions

- **Plugin Verifier:** what does `verifyPlugin` actually report? Expected are the `@Experimental`/`@Obsolete` notes and possibly `impl` packages (`RunDialog`, `NamedLibraryElement`). Cannot run in the sandbox without network.
- **`runIde`:**
  - Does the balloon render `<server>` as a tag (#26)?
  - Does `replaceString` throw for a read-only document in the middle of the command (#53)?
  - How much does a run descriptor in the history hold after the console has been closed (#51, heap dump)?
  - Are Maven runs detached instead of terminated when the tab is closed? `ProcessHandler.detachProcess` reports exit 0, `MavenRunListener` would treat that as a green run and write a new reference.
  - How does "Fix all in file" behave for `DeclareFix` (#69)?
- **Windows:** UNC access (#4) and process termination (#57) were not reproduced on Windows.
- **Real Maven** (no local `mvn`, no network):
  - Does the `EventSpyDispatcher` call `onEvent` in parallel under `-T` (#48)?
  - Does the extension run on Maven 3.6.x and 4 (cling, `versionProperties`)?
  - Does Maven 3.9 strip `#` comments in `.mvn/jvm.config` (#36)?
  - How does `ClassicDependencyManager` handle managed exclusions for direct dependencies that have their own exclusions?
  - From which enforcer version on does `8` count as `1.8` (#56)?
- **IDE authenticator:** does `IdeaWideAuthenticator` send remembered credentials on a server 401 or does it only show a dialog (#49)?
- **Runtime at the end of the build:** how long does `printDiagnosis` (resolver, `JarIndex`, `searchRepository`) take on Maven's main thread for large reactors? A large local `~/.m2` is missing.
- **Intended behaviour?**
  - `pinManagedVersion` changes a shared `${prop}` and thereby all sibling artifacts (e.g. all Jackson modules).
  - Should `diagnose` diagnose logs with `BUILD SUCCESS` at all (#38)?
  - Should matrix and exclusions already compute when the tool window opens and after every import, even if the page is never viewed?
  - Is the jump to the newest run on every `historyChanged` intended (#69)?
  - Should the upgrade simulator be able to raise nested BOM imports?
- **Under `-T`:** does the outline assign interleaved lines to the right module? Not tested.
