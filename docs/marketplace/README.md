# Images for the JetBrains Marketplace

Eight images for the *Media* section of the plugin page, 2560 × 1600 px (16:10, double pixel density; JetBrains recommends
at least 1200 × 760 and consistent aspect ratios). Each image = a headline + a crop of a **real screenshot**
from the demo project [`demo/`](demo/README.md). Upload order = file name.

| Slide (`src/`) | Screenshot (`shots/`) | Shows |
| --- | --- | --- |
| `01-build-doctor.html` | `build-doctor.webp` | Diagnosis card *#10 Runtime conflict in tests · certain* |
| `02-why-this-version.html` | `why.webp` | Page "Why This Version?": a module's dependencies and the decision chain |
| `03-conflicts.html` | `conflicts.webp` | Conflict matrix with the pin suggestion, inlays in the `pom.xml` above |
| `04-upgrade-simulator.html` | `upgrade.webp` | Editor tab "Upgrade Simulation": POM diff and tree differences |
| `05-duplicate-classes.html` | `duplicates.webp` | Duplicate classes with classpath order and "Add exclusion" |
| `06-usage.html` | `usage.webp` | Undeclared/unused, with the diff preview "Clean up dependencies" below |
| `07-preflight.html` | `preflight.webp` | Preflight red (`http://` repository) with the fix "Switch the URL to https" |
| `08-overview.html` | `overview.webp` | Overview with tiles |

**Local, not in Git:** the screenshots in `shots/` and the rendered `*.png` (see `.gitignore`). The repository only contains
the frames (`src/`), `render.sh` and the demo project. Smaller WebP copies of the rendered slides (1600 × 1000) are
committed under [`docs/images/screenshots/`](../images/screenshots) for the README; `render.sh` updates them when
ImageMagick (`magick`) is installed.

To change a crop: set `data-crop="x,y,width,height"` on the slide's `.shot`, in screenshot pixels. The image is fitted as
large as possible; several `.shot`s inside one `.fit` are placed side by side at the same scale, with `.fit.col` one below
the other. New screenshot with the same window layout → just replace the file in `shots/` and render again
(requires Chromium or Chrome):

```bash
docs/marketplace/render.sh
```

The fonts in `src/fonts/` (Inter, JetBrains Mono) are licensed under the SIL Open Font License, see the included license files.
