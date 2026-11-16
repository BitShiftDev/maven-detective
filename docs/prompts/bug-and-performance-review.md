# Prompt: bug and performance review

> To paste into a new Claude Code session in the `maven-detective` repository.

---

You are an expert on this project (Maven Detective: Java 8 core `core`, Maven core extension `maven-extension`, IntelliJ plugin `intellij-plugin`) and an experienced developer of IntelliJ Platform plugins (threading model, PSI, VFS, indexes, daemon, Disposer, message bus, Plugin Verifier, dynamic loading/unloading).

First load the `maven-detective` skill and read `README.md`, `Maven Detective – Plugin-Konzept.md` and `Maven Detective – UI-Plan.md`. The hard rules from the skill (no runtime dependencies in the core, Java 8 API, checks only read and never use the network, the extension never changes the build, Maven API only through `IdeMaven`, no texts in code) count as requirements: a violation is a finding.

## Task

Search the entire code base for **bugs** and **performance problems** and document them. **Do not change any production code and do not commit anything.** Put reproductions (test cases, mini logs, measurement scripts) only in the scratchpad or under `build/`.

## What to look for

### IntelliJ plugin
- **Threading:** slow work on the EDT (file I/O, `JarIndex`, `DependencyResolver`, POM parsing, process starts); PSI/VFS access without a read action; writes without a write action/`WriteCommandAction`; `invokeLater` without a suitable `ModalityState`; `ReadAction.nonBlocking()` without `expireWith`/`coalesceBy`; `executeSynchronously()` where it blocks the caller.
- **`ActionUpdateThread`:** every `AnAction` must override `getActionUpdateThread()` correctly; `update()` must not do anything expensive and must not touch Swing when BGT is chosen.
- **Cancellation:** long loops without `ProgressManager.checkCanceled()` or without checking our own `Cancellation`; `ProcessCanceledException` must never be swallowed or logged.
- **Lifetime and memory:** static caches (`JarIndex`, `PomCache`, `IdeUsages.JARS`, `ClasspathChecks.JARS`) – do they grow without bounds, hold on to projects/PSI/editors, survive closing a project or unloading the plugin? Listeners and message bus connections without a parent `Disposable`; editors from `EditorFactory.createViewer` without `releaseEditor`; PSI elements in fields without `SmartPsiElementPointer`.
- **Daemon/editor:** inspections, line markers, inlays and code vision must not compute expensive things in the daemon (see the `PomInsights` rule in the skill). Line markers only on leaf elements. Are `CachedValue` dependencies complete (VFS, POM, Maven import)? Are results discarded correctly after a Maven import?
- **Dumb mode and indexes:** accesses that fail in dumb mode; `DumbAware` where it is wrong.
- **`plugin.xml`:** missing/wrong registrations, `<depends>` without `optional`/`config-file` where needed, constructs that block dynamic unloading, internal or deprecated API (what would `verifyPlugin` report?).
- **UI:** Swing access outside the EDT, rebuilding whole component trees for small changes, table renderers with expensive logic, generations in `DetectivePanel.background` (do outdated results overwrite new ones?).
- **POM changes:** do all of them really go through `FixRunner.editPoms` with preview and exactly one undo step? Is the planned text consistent with the written one?

### Core (`core`)
- **Java 8:** forbidden API (`var`, `List.of`, `isBlank`, `Optional.isEmpty`, `readString` …) – also hidden in helper classes.
- **Correctness:** `MavenLogParser`, `LogOutline`, `MiniYaml`, `Json`, `PomFile`, `PomEditor` (CRLF, BOM, comments, CDATA, namespaces, profiles, indentation, duplicates), `EffectiveModel` (order of inheritance/interpolation), `DependencyResolver` (Maven semantics for scope, management, exclusions, cycles), `MavenVersion`/`VersionRange` comparisons, the `ClassFile` parser with unusual bytecode.
- **Performance:** quadratic loops over log lines or tree nodes, regexes with catastrophic backtracking (also in `build-doctor-v1.yaml`), regex compilation inside loops, repeated parsing of the same POM/JAR without a cache, `searchRepository` and repository scans on large `~/.m2` directories, memory usage with very large logs (> 100 MB) and reactor projects with > 200 modules.
- **Resources and robustness:** unclosed `ZipFile`s/streams, charset assumptions (platform default instead of UTF-8), paths on Windows, recursion without a depth limit, thread safety of the caches.
- **Security:** `delete-files`/`replace-in-file` outside the project or the local repository (path traversal via catalog variables), process start in `PomFetcher` (arguments from POMs), network access only with an explicit option, never send credentials from `settings.xml`.

### Maven extension
- Every internal error is swallowed, the build is never changed or slowed down (overhead per event, writing the JSONL, flush on abort, class loader conflicts with Maven 3.6–3.9 and 4).

## Approach

1. **Overview:** map the modules, entry points (`plugin.xml`, `DetectiveEventSpy`, CLI `main`) and hot paths.
2. **Review per module**, with the focus areas above. You may let subagents examine larger areas in parallel; you verify each of their findings yourself.
3. **Verification:** back every finding with the code (`file:line`) and give a concrete scenario (input/state → wrong behaviour). Reproduce where possible: run the existing tests (`./gradlew :core:test`, sandbox notes in the skill), a mini test or mini log in the scratchpad, and for performance a measurement (time, memory, input size).
4. **Filter:** pure style questions, matters of taste and guesses without a scenario do not belong in the report. Briefly list what you checked and found fine under "Checked, no findings".

## Rating per finding

- **Severity:** Critical (data loss, broken build, IDE hangs/freezes, security vulnerability) · High (wrong diagnosis/wrong fix, memory leak, noticeable delay) · Medium · Low
- **Confidence:** `confirmed` (reproduced or beyond doubt from the code) · `plausible` (scenario is coherent, not reproduced)
- **Category:** `threading`, `memory`, `performance`, `correctness`, `robustness`, `security`, `api-compatibility`, `java8`, `rule-violation`

## Result

Write the report in English to `docs/review/<YYYY-MM-DD>-bugs-and-performance.md`:

```markdown
# Review: bugs and performance (<date>, commit <commit>)

## Summary
3–5 sentences: overall impression, the three most important findings, the biggest performance lever.

## Overview
| # | Severity | Category | Confidence | Module | Location | Short description |

## Findings
### <#>. <title>
- **Location:** `path/File.java:123` (further places)
- **Problem:** What is wrong?
- **Scenario:** input/state → observed or expected misbehaviour
- **Evidence:** code excerpt (short), test output or measurements
- **Suggestion:** sketch of a solution incl. affected tests; effort (S/M/L)

## Performance measurements
What, with what, input size, result, bottleneck.

## Checked, no findings
Bullet points.

## Open questions
What could not be clarified without `runIde`, network or the Plugin Verifier.
```

Sort the findings by severity, and within the same severity by confidence. At the end, give me the summary, the overview table and the path to the report in the chat.
