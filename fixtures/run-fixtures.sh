#!/usr/bin/env bash
# Löst jeden Fehler aus fixtures.txt mit einem echten Maven aus – einmal mit der Extension
# (strukturierte Events) und einmal nur über das Text-Log (Fallback-Parser) – und prüft,
# ob Build Doctor beide Male die erwartete Regel liefert.
#
#   bash fixtures/run-fixtures.sh /pfad/maven-detective-extension.jar
#
# Voraussetzungen: mvn im PATH, Java 8+. Jede Fixture bekommt ein eigenes, leeres lokales Repository.
set -uo pipefail

EXT_JAR="${1:?Pfad zum Extension-JAR angeben}"
EXT_JAR="$(cd "$(dirname "$EXT_JAR")" && pwd)/$(basename "$EXT_JAR")"
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

# Schnappschuss-Vergleich: erst grün, dann Version anheben und scheitern lassen
dir=09-changed-since-green
work="$WORK/$dir"; mkdir -p "$work/repo"; cp "$HERE/$dir/pom.xml" "$work/pom.xml"
common=(-B -o -Dmaven.repo.local="$work/repo" -Dmaven.ext.class.path="$EXT_JAR" -DmavenDetective.home="$work/home")
(cd "$work" && mvn "${common[@]}" validate > "$work/green.log" 2>&1)
green_exit=$?
sed -i.bak 's/33.0.0-jre/33.3.1-jre/' "$work/pom.xml"
(cd "$work" && mvn "${common[@]}" nosuchprefix:goal > "$work/red.log" 2>&1)
expected="Property guava.version 33.0.0-jre → 33.3.1-jre"
if [[ $green_exit -eq 0 ]] && grep -q "Seit dem letzten grünen Build" "$work/red.log" && grep -qF "$expected" "$work/red.log"; then
  pass=$((pass + 1)); printf '  ✓ %-28s %s\n' "$dir" "$expected"
else
  fail=$((fail + 1)); printf '  ✗ %-28s Karte nennt die Änderung nicht (grün: exit %s)\n' "$dir" "$green_exit"
  grep -A10 "Maven Detective" "$work/red.log" | sed 's/^/      /'
fi

echo "$pass bestanden, $fail fehlgeschlagen"
[[ $fail -eq 0 ]]
