# Bilder für den JetBrains Marketplace

Sechs Screenshots für den Bereich *Media* der Plugin-Seite, 2560 × 1600 px (16:10, doppelte Pixeldichte;
JetBrains empfiehlt mindestens 1200 × 760 und einheitliche Seitenverhältnisse). Reihenfolge beim Hochladen = Dateiname.

| Datei | Zeigt |
| --- | --- |
| `01-build-doctor.png` | Diagnose-Karte neben der Run-Konsole: Ursache, Quick-Fix, Belege, „Seit dem letzten grünen Build“ |
| `02-why-this-version.png` | `pom.xml` mit Inlays, Code Vision, Gutter-Markern und dem Popup „Why This Version?“ |
| `03-conflict-matrix.png` | Konflikt-Matrix Artefakt × Modul mit Pin-Vorschlag im gemeinsamen Parent |
| `04-upgrade-simulator.png` | Editor-Reiter „Upgrade Simulation“: POM-Diff und Baum-Unterschied je Modul |
| `05-preflight.png` | Preflight-Ampel mit Befunden und Fixes |
| `06-duplicate-classes.png` | Doppelte Klassen mit Classpath-Reihenfolge und Exclusion-Empfehlung |

Die Bilder sind **nachgebaute Oberflächen** (HTML in `src/`, Texte aus `MavenDetectiveBundle.properties` und echten
Kern-Diagnosen, IDE im Standard-Theme Dark), keine Bildschirmfotos aus `runIde`. Sobald das Plugin in der IDE durchgeklickt
ist, sollten echte Screenshots sie ersetzen oder ergänzen – der Marketplace verlangt, dass Bilder das Plugin nicht falsch darstellen.
Für echte Aufnahmen gibt es das Demo-Projekt [`demo/`](demo/README.md) mit einer Klick-Anleitung je Bild.

Neu erzeugen (braucht Chromium oder Chrome):

```bash
docs/marketplace/render.sh
```

Schriften in `src/fonts/` (Inter, JetBrains Mono) stehen unter der SIL Open Font License, siehe die beiliegenden Lizenzdateien.
