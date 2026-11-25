#!/usr/bin/env bash
# Lädt eine Maven-Distribution von Maven Central, prüft die SHA-512-Summe und entpackt sie.
# Gibt das bin-Verzeichnis auf stdout aus – lokal und in CI dasselbe Verfahren.
#
#   bash fixtures/install-maven.sh <version|latest-4> <zielverzeichnis>
#   export PATH="$(bash fixtures/install-maven.sh 3.8.9 "$TMPDIR/maven"):$PATH"
#
# latest-4 = neueste 4.x laut maven-metadata.xml (auch Release Candidates; Frühwarnung).
# Ein schon entpacktes Verzeichnis wird wiederverwendet.
set -euo pipefail

version="${1:?Maven-Version angeben (z. B. 3.9.11 oder latest-4)}"
dest="${2:?Zielverzeichnis angeben}"
base=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven

if [[ "$version" == latest-4 ]]; then
  # Die Metadaten führen die Versionen in Veröffentlichungsreihenfolge – die letzte 4.x ist die neueste
  version="$(curl -fsSL "$base/maven-metadata.xml" | sed -n 's|.*<version>\(4\.[^<]*\)</version>.*|\1|p' | tail -1)"
  [[ -n "$version" ]] || { echo "Keine Maven-4-Version in den Metadaten" >&2; exit 1; }
fi

versions="$(cd "$(dirname "$0")" && pwd)/maven-versions.txt"
home="$dest/apache-maven-$version"
if [[ ! -x "$home/bin/mvn" ]]; then
  mkdir -p "$dest"
  # Relative Namen: GNU tar (Git Bash) hielte das D: eines Windows-Pfads für einen entfernten Host
  cd "$dest"
  file="apache-maven-$version-bin.tar.gz"
  curl -fsSLo "$file" "$base/$version/$file"
  # Summe aus maven-versions.txt (Versionen vor 3.9: Central hat dort kein .sha512), sonst von Central
  expected="$(awk -F'|' -v v="$version" '!/^#/ { gsub(/ /, ""); if ($1 == v && NF >= 3) print $3 }' "$versions")"
  if [[ -z "$expected" ]]; then
    expected="$(curl -fsSL "$base/$version/$file.sha512" | awk '{print $1}')" || expected=""
  fi
  # sha512sum gibt es unter macOS nicht – shasum kann dasselbe
  if command -v sha512sum > /dev/null; then actual="$(sha512sum "$file" | awk '{print $1}')"
  else actual="$(shasum -a 512 "$file" | awk '{print $1}')"; fi
  if [[ -z "$expected" || "$expected" != "$actual" ]]; then
    echo "SHA-512 von $file stimmt nicht (erwartet ${expected:-nichts}, ist $actual)" >&2
    rm -f "$file"
    exit 1
  fi
  tar -xzf "$file"
  rm -f "$file"
fi
echo "$home/bin"
