# Bilder für den JetBrains Marketplace

Acht Bilder für den Bereich *Media* der Plugin-Seite, 2560 × 1600 px (16:10, doppelte Pixeldichte; JetBrains empfiehlt
mindestens 1200 × 760 und einheitliche Seitenverhältnisse). Jedes Bild = Überschrift + Ausschnitt eines **echten Screenshots**
aus dem Demo-Projekt [`demo/`](demo/README.md). Reihenfolge beim Hochladen = Dateiname.

| Folie (`src/`) | Screenshot (`shots/`) | Zeigt |
| --- | --- | --- |
| `01-build-doctor.html` | `build-doctor.webp` | Diagnose-Karte *#10 Runtime conflict in tests · certain* |
| `02-why-this-version.html` | `why.webp` | Seite „Why This Version?“: Abhängigkeiten eines Moduls und Entscheidungskette |
| `03-conflicts.html` | `conflicts.webp` | Konflikt-Matrix mit Pin-Vorschlag, darüber Inlays im `pom.xml` |
| `04-upgrade-simulator.html` | `upgrade.webp` | Editor-Reiter „Upgrade Simulation“: POM-Diff und Baum-Unterschied |
| `05-duplicate-classes.html` | `duplicates.webp` | Doppelte Klassen mit Classpath-Reihenfolge und „Add exclusion“ |
| `06-usage.html` | `usage.webp` | Undeklariert/ungenutzt, darunter die Diff-Vorschau „Clean up dependencies“ |
| `07-preflight.html` | `preflight.webp` | Preflight rot (`http://`-Repository) mit Fix „Switch the URL to https“ |
| `08-overview.html` | `overview.webp` | Übersicht mit Kacheln |

**Lokal, nicht im Git:** die Screenshots in `shots/` und die gerenderten `*.png` (siehe `.gitignore`). Im Repository liegen nur
die Rahmen (`src/`), `render.sh` und das Demo-Projekt.

Ausschnitt ändern: `data-crop="x,y,breite,höhe"` am `.shot` der Folie, in Pixeln des Screenshots. Das Bild wird so groß wie
möglich eingepasst; mehrere `.shot` in einer `.fit` stehen im selben Maßstab nebeneinander, mit `.fit.col` untereinander. Neuer Screenshot mit gleichem Fensteraufbau → nur die Datei in `shots/` ersetzen und neu rendern
(braucht Chromium oder Chrome):

```bash
docs/marketplace/render.sh
```

Schriften in `src/fonts/` (Inter, JetBrains Mono) stehen unter der SIL Open Font License, siehe die beiliegenden Lizenzdateien.
