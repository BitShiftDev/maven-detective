# Sicherheit / Security

## Lücke melden / Reporting a vulnerability

Bitte **kein öffentliches Issue**. Meldungen gehen vertraulich über GitHub:
*Security → Report a vulnerability* in diesem Repository (Private Vulnerability Reporting).

Please do **not** open a public issue. Report privately via *Security → Report a vulnerability* in this repository.

Hilfreich sind betroffene Version, Komponente (IntelliJ-Plugin, CLI, Maven-Extension) und ein Weg, das Verhalten nachzustellen.
Die Meldung wird so bald wie möglich beantwortet.

## Unterstützte Versionen / Supported versions

Sicherheitskorrekturen gibt es für die jeweils neueste veröffentlichte Version.
Security fixes are provided for the latest released version only.

## Was Maven Detective tut / What Maven Detective does

- Prüfungen lesen nur Dateien und Einstellungen (POMs, `settings.xml`, lokales Repository) und gehen nicht ins Netz.
- Netzzugriffe gibt es nur auf ausdrücklichen Wunsch: Preflight mit `--network` bzw. „Repositories prüfen“ (HTTP-`HEAD` an die konfigurierten Repositories, **ohne Zugangsdaten**) und „Fehlende POMs laden“ im Upgrade-Simulator (startet Maven).
- Schnappschüsse grüner Builds liegen lokal unter `~/.maven-detective/` und verlassen den Rechner nicht.
- Die Maven-Extension ändert nie den Build.
