#!/usr/bin/env bash
# Löst jeden Fehler aus fixtures.txt mit einem echten Maven aus – einmal mit der Extension
# (strukturierte Events) und einmal nur über das Text-Log (Fallback-Parser) – und prüft,
# ob Build Doctor beide Male die erwartete Regel liefert.
#
#   bash fixtures/run-fixtures.sh /pfad/maven-detective-extension.jar [/pfad/maven-repo]
#
# Mit zweitem Argument (Verzeichnis im Maven-Layout mit der veröffentlichten Extension, wie es
# `./gradlew :maven-extension:fixtureTest` anlegt) läuft zusätzlich eine Fixture über
# .mvn/extensions.xml – so wie Nutzer die Extension aus Maven Central einbinden.
#
# Voraussetzungen: mvn im PATH, Java 8+. Jede Fixture bekommt ein eigenes, leeres lokales Repository.
set -uo pipefail

# Die Prüfungen unten suchen deutsche Texte der Karte; Englisch ist sonst Standard außerhalb deutscher Systeme
export MAVEN_DETECTIVE_LANGUAGE=de

EXT_JAR="${1:?Pfad zum Extension-JAR angeben}"
EXT_JAR="$(cd "$(dirname "$EXT_JAR")" && pwd)/$(basename "$EXT_JAR")"
EXT_REPO="${2:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/md-fixtures.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0
while IFS='|' read -r dir rule args; do
  dir="$(echo "$dir" | xargs)"; rule="$(echo "$rule" | xargs)"; args="$(echo "$args" | xargs)"
  [[ -z "$dir" || "$dir" == \#* ]] && continue
  repo="$WORK/$dir/repo"
  mkdir -p "$repo"
  [[ -x "$HERE/$dir/setup.sh" || -f "$HERE/$dir/setup.sh" ]] && bash "$HERE/$dir/setup.sh" "$repo"

  events="$WORK/$dir/events.jsonl"
  log="$WORK/$dir/build.log"
  # shellcheck disable=SC2086
  (cd "$HERE/$dir" && mvn -B -Dmaven.repo.local="$repo" -Dmaven.ext.class.path="$EXT_JAR" \
      -DmavenDetective.eventsFile="$events" -DmavenDetective.home="$WORK/$dir/home" $args > "$log" 2>&1)

  from_events="$(java -cp "$EXT_JAR" io.github.bitshiftdev.mavendetective.core.cli.Main diagnose "$events" --id 2>/dev/null)"
  from_log="$(java -cp "$EXT_JAR" io.github.bitshiftdev.mavendetective.core.cli.Main diagnose "$log" --project "$HERE/$dir" --id 2>/dev/null)"
  card_in_log="nein"; grep -q "Maven Detective .* Build Doctor" "$log" && card_in_log="ja"

  if [[ "$from_events" == "$rule" && "$from_log" == "$rule" && "$card_in_log" == "ja" ]]; then
    pass=$((pass + 1)); printf '  ✓ %-28s %s\n' "$dir" "$rule"
  else
    fail=$((fail + 1)); printf '  ✗ %-28s erwartet %s | Events: %s | Log: %s | Karte im Log: %s\n' "$dir" "$rule" "$from_events" "$from_log" "$card_in_log"
    sed -n '1,40p' "$log" | sed 's/^/      /'
  fi
done < "$HERE/fixtures.txt"

# Schnappschuss-Vergleich: erst rot (Ablage hat schon einen Stand), dann grün, dann Version anheben und
# scheitern lassen. Der grüne Lauf muss die Referenz schreiben und „latest“ ersetzen, der zweite rote die Karte zeigen.
dir=09-changed-since-green
work="$WORK/$dir"; mkdir -p "$work/repo"; cp "$HERE/$dir/pom.xml" "$work/pom.xml"
common=(-B -o -Dmaven.repo.local="$work/repo" -Dmaven.ext.class.path="$EXT_JAR" -DmavenDetective.home="$work/home")
(cd "$work" && mvn "${common[@]}" nosuchprefix:goal > "$work/red0.log" 2>&1)
(cd "$work" && mvn "${common[@]}" validate > "$work/green.log" 2>&1)
green_exit=$?
snapdir="$(ls -d "$work"/home/snapshots/*/ 2>/dev/null | head -1)"
green_ok="nein"
[[ -f "$snapdir/green-extension.json" ]] && grep -q '"successful": *true' "$snapdir/latest-extension.json" && green_ok="ja"
sed -i.bak 's/33.0.0-jre/33.3.1-jre/' "$work/pom.xml"
(cd "$work" && mvn "${common[@]}" nosuchprefix:goal > "$work/red.log" 2>&1)
expected="Property guava.version 33.0.0-jre → 33.3.1-jre"
if [[ $green_exit -eq 0 && "$green_ok" == "ja" ]] && grep -q "Maven Detective .* Build Doctor" "$work/red0.log" \
    && grep -q "Seit dem letzten grünen Build" "$work/red.log" && grep -qF "$expected" "$work/red.log"; then
  pass=$((pass + 1)); printf '  ✓ %-28s %s\n' "$dir" "$expected"
else
  fail=$((fail + 1)); printf '  ✗ %-28s Karte nennt die Änderung nicht (grün: exit %s, Referenz geschrieben: %s)\n' "$dir" "$green_exit" "$green_ok"
  grep -A10 "Maven Detective" "$work/red.log" | sed 's/^/      /'
fi

# Einbindung über .mvn/extensions.xml: Maven lädt Extension und POM aus einem Remote-Repository
# (hier file://, per settings.xml statt Central) – genau der Weg, den Nutzer mit Maven Central gehen
if [[ -n "$EXT_REPO" ]]; then
  dir=06-unknown-plugin-prefix
  rule=plugin-missing
  work="$WORK/extensions-xml"
  EXT_REPO="$(cd "$EXT_REPO" && pwd)"
  repo_url="file://$EXT_REPO"
  # Git Bash (Windows): Maven versteht file:///D:/…, nicht file:///d/…
  if pwd -W > /dev/null 2>&1; then repo_url="file:///$(cd "$EXT_REPO" && pwd -W)"; fi
  ext_dir="$EXT_REPO/io/github/bitshiftdev/mavendetective/maven-detective-extension"
  version="$(ls "$ext_dir" 2>/dev/null | grep -v '^maven-metadata' | sort -V | tail -1)"
  mkdir -p "$work/project/.mvn" "$work/repo"
  cp "$HERE/$dir/pom.xml" "$work/project/pom.xml"
  cat > "$work/project/.mvn/extensions.xml" <<XML
<extensions>
  <extension>
    <groupId>io.github.bitshiftdev.mavendetective</groupId>
    <artifactId>maven-detective-extension</artifactId>
    <version>$version</version>
  </extension>
</extensions>
XML
  cat > "$work/settings.xml" <<XML
<settings>
  <mirrors>
    <mirror><id>no-central</id><mirrorOf>central</mirrorOf><url>$repo_url</url></mirror>
  </mirrors>
  <profiles>
    <profile>
      <id>fixture</id>
      <repositories>
        <repository><id>fixture</id><url>$repo_url</url><snapshots><enabled>true</enabled></snapshots></repository>
      </repositories>
      <!-- Extensions kommen über die Plugin-Repositories; SNAPSHOTs liefert der Central-Spiegel nicht -->
      <pluginRepositories>
        <pluginRepository><id>fixture</id><url>$repo_url</url><snapshots><enabled>true</enabled></snapshots></pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
  <activeProfiles><activeProfile>fixture</activeProfile></activeProfiles>
</settings>
XML
  (cd "$work/project" && mvn -B -s "$work/settings.xml" -Dmaven.repo.local="$work/repo" \
      -DmavenDetective.eventsFile="$work/events.jsonl" -DmavenDetective.home="$work/home" nosuchprefix:goal \
      > "$work/build.log" 2>&1)
  from_events="$(java -cp "$EXT_JAR" io.github.bitshiftdev.mavendetective.core.cli.Main diagnose "$work/events.jsonl" --id 2>/dev/null)"
  if [[ -n "$version" && "$from_events" == "$rule" ]] && grep -q "Maven Detective .* Build Doctor" "$work/build.log"; then
    pass=$((pass + 1)); printf '  ✓ %-28s %s (Extension %s)\n' "extensions.xml" "$rule" "$version"
  else
    fail=$((fail + 1)); printf '  ✗ %-28s Version: %s | Events: %s\n' "extensions.xml" "${version:-keine}" "$from_events"
    sed -n '1,40p' "$work/build.log" | sed 's/^/      /'
  fi
fi

echo "$pass bestanden, $fail fehlgeschlagen"
[[ $fail -eq 0 ]]
