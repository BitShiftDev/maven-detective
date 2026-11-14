#!/usr/bin/env bash
# Lädt ein Bundle aus `./gradlew centralBundle` ins Maven Central Portal und wartet auf die Prüfung.
#
#   CENTRAL_USERNAME=… CENTRAL_PASSWORD=… bash .github/scripts/central-upload.sh build/central-bundle-<v>.zip
#
# CENTRAL_USERNAME/CENTRAL_PASSWORD = User-Token aus central.sonatype.com → Account → Generate User Token
# (nicht das Login-Passwort). CENTRAL_PUBLISHING_TYPE:
#   USER_MANAGED (Standard) – Portal prüft das Bundle; veröffentlicht wird erst per Klick auf „Publish“
#                             unter central.sonatype.com → Deployments (bis dahin noch löschbar).
#   AUTOMATIC               – nach bestandener Prüfung sofort veröffentlicht. Maven Central ist
#                             endgültig: eine Version lässt sich weder löschen noch ersetzen.
# Braucht curl und jq. Exit 0 = geprüft (USER_MANAGED) bzw. wird veröffentlicht (AUTOMATIC).
set -euo pipefail

BUNDLE="${1:?Pfad zum Bundle angeben (build/central-bundle-<version>.zip)}"
: "${CENTRAL_USERNAME:?CENTRAL_USERNAME fehlt (User-Token-Name)}"
: "${CENTRAL_PASSWORD:?CENTRAL_PASSWORD fehlt (User-Token-Passwort)}"
TYPE="${CENTRAL_PUBLISHING_TYPE:-USER_MANAGED}"
API="${CENTRAL_API:-https://central.sonatype.com/api/v1/publisher}"
TIMEOUT="${CENTRAL_TIMEOUT_SECONDS:-1200}"

case "$TYPE" in USER_MANAGED|AUTOMATIC) ;; *) echo "CENTRAL_PUBLISHING_TYPE muss USER_MANAGED oder AUTOMATIC sein" >&2; exit 2;; esac
[[ -f "$BUNDLE" ]] || { echo "Bundle nicht gefunden: $BUNDLE" >&2; exit 2; }

auth="Authorization: Bearer $(printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_PASSWORD" | base64 | tr -d '\n')"
name="$(basename "$BUNDLE" .zip)"

echo "Lade $BUNDLE hoch ($TYPE) …"
id="$(curl --silent --show-error --fail-with-body -X POST -H "$auth" \
  -F "bundle=@$BUNDLE" "$API/upload?publishingType=$TYPE&name=$name")"
echo "Deployment: $id"
[[ -n "${GITHUB_OUTPUT:-}" ]] && echo "deployment=$id" >> "$GITHUB_OUTPUT"

deadline=$((SECONDS + TIMEOUT))
last=""
while (( SECONDS < deadline )); do
  status="$(curl --silent --show-error --fail-with-body -X POST -H "$auth" "$API/status?id=$id")"
  state="$(jq -r '.deploymentState' <<<"$status")"
  [[ "$state" != "$last" ]] && echo "Stand: $state" && last="$state"
  case "$state" in
    FAILED)
      echo "Prüfung im Portal gescheitert:" >&2
      jq '.errors' <<<"$status" >&2
      exit 1;;
    VALIDATED)
      echo "Geprüft. Veröffentlichen unter https://central.sonatype.com/publishing/deployments (Publish oder Drop)."
      exit 0;;
    PUBLISHING|PUBLISHED)
      echo "Wird veröffentlicht – in Maven Central nach etwa 10–30 Minuten sichtbar."
      jq -r '.purls[]?' <<<"$status"
      exit 0;;
  esac
  sleep 10
done
echo "Zeitüberschreitung nach ${TIMEOUT}s, letzter Stand: $last – im Portal nachsehen (Deployment $id)" >&2
exit 1
