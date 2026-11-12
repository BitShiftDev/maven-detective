#!/usr/bin/env bash
# Simuliert einen abgebrochenen Download: Plugin-JAR ohne gültigen ZIP-Inhalt im lokalen Repository ($1).
set -euo pipefail
dir="$1/com/example/fixture/broken-maven-plugin/1.0"
mkdir -p "$dir"
printf 'abgebrochener Download' > "$dir/broken-maven-plugin-1.0.jar"
cat > "$dir/broken-maven-plugin-1.0.pom" <<'EOF'
<project><modelVersion>4.0.0</modelVersion><groupId>com.example.fixture</groupId><artifactId>broken-maven-plugin</artifactId><version>1.0</version><packaging>maven-plugin</packaging></project>
EOF
