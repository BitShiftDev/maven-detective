---
name: java-clean-code
description: Java house style + clean-code rules for maven-detective (core, maven-extension, intellij-plugin); use when writing, refactoring or reviewing Java code here
---
[project]
clean code here = match house style, not modern-Java idioms; refactor only code the task touches
identifiers English; Javadoc + comments German
NEVER streams or Optional, any module (house style: for-each loops, null = absent + Javadoc says when null)
classes final by default; helpers as nested `static final class`
NEVER user-visible string literal (core `Texts.t`, plugin `MavenDetectiveBundle.message`; key in both language files)
case/format: `toLowerCase(Locale.ROOT)`, `String.format(Locale.ROOT, …)` (Turkish dotless i)
`Pattern`: `private static final`, never compiled per log line
log-line regex: NEVER lazy token matchable at every position like `(\S+?): ` (O(L²), build hangs after BUILD FAILURE); start at token boundary `(?<!\S)`, prefilter with `contains`
static cache: bounded + clear path (unbounded caches leaked in review 2026-11-14)
intentionally swallowed exception ⇒ `catch (IOException ignored)`
[global]
general clean-code rules; project rules above/below win on conflict
Clean-Code conflicts resolved: null returns allowed (house style), German Javadoc stays, existing big classes/long param lists not split unasked
format: 4 spaces, no tabs, no wildcard imports, lines ≤ ~120 chars (98% today)
names: intention-revealing; class noun, method verb, boolean `is/has/can`; abbreviations only domain (GA, GAV, POM, BOM, JDK) or tiny-scope locals (`m` Matcher, `e`, `i`)
method: one task, one abstraction level; guard clause + early return over nesting; nesting > 3 ⇒ extract
new methods: ≤ 4 params, else parameter object; no boolean flag switching behaviour ⇒ 2 methods
no magic numbers/strings ⇒ named `static final`, unit in name or Javadoc (`MAX_LINE_CHARS`, ms)
duplication: same logic 3× ⇒ extract; 2× with different domain meaning ⇒ keep
no dead code, commented-out code, unused params/imports/fields
comments: why, not what; public API Javadoc: contract, when null, units, thread safety
fields `final` where possible; static mutable state only for documented caches
fail fast at public boundary (validate args); exception message carries context (file, line, value)
broad `catch (Exception|RuntimeException)` only at module boundary (extension spy, background task, CLI main)
every `Closeable` (ZipFile, streams, JarFile) in try-with-resources
`equals` + `hashCode` together; strings via `equals`, enums via `==`
no quadratic work over log lines, tree nodes, repo JARs ⇒ index in Map first
tests: one behaviour per test, arrange-act-assert; bug fix ⇒ failing regression test first
formatting-only or rename-only changes never mixed with behaviour changes (separate commit)
[core + maven-extension]
NEVER Java 9+ API: var, records, List/Set/Map.of, isBlank, strip, Optional.isEmpty, Files.readString/writeString, text blocks, switch expressions (runs in Maven classloader on Java 8; CI `extension-on-java8`)
explicit type args `new ArrayList<String>()`, no diamond (house style, keep)
NEVER add runtime dependency (core embedded in extension JAR)
no logger, no System.out/err; CLI output via `PrintStream` parameter
returned result collections: `Collections.unmodifiable*`; mutable only on parser-filled records (`ErrorRecord`)
bad argument ⇒ `IllegalArgumentException`; parsers throw own type with line (`CatalogException`, `YamlException`)
own files: explicit `StandardCharsets.UTF_8`, never platform default
user files (POM, settings.xml, .mvn/jvm.config): read/write only via `io/TextFile` (keeps charset + BOM, aborts instead of U+FFFD); NEVER `Files.readAllBytes` + UTF-8 rewrite (destroyed umlauts in Latin-1 POMs)
path from log or session text ⇒ `check/Text.localPath(s)` before any `Files.*`; null ⇒ skip (UNC path opens SMB, leaks NTLM hash)
core compiled with `-Xlint:all` ⇒ add no rawtypes/unchecked/deprecation warnings (extension too, though not enforced)
[intellij-plugin]
Java 21: diamond, lambdas, method refs, records ok (streams/Optional still banned)
public API: `@NotNull`/`@Nullable` from org.jetbrains.annotations; user text params `@Nls`
NEVER catch+swallow or log `ProcessCanceledException` (rethrow; platform cancellation)
background task ⇒ reset loading/"running" state in `finally` (stuck indicator after error/null/cancel)
[tests]
JUnit 5, static import `Assertions`; no AssertJ, no Mockito ⇒ fakes via injection (`Options.probe(...)`)
files: build project + local repo in `@TempDir`
names: English camelCase sentence `prefixIsOnlyStrippedWhenItLooksLikeOne`
core tests run with `mavenDetective.language=de` ⇒ asserted texts German
after refactor: `./gradlew :core:test :intellij-plugin:test`
