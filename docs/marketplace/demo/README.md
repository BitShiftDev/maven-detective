# Demo project for the Marketplace screenshots

`shop-platform` is a small reactor (5 modules) that fills every view of Maven Detective with real content.
Everything is slightly broken on purpose – **do not fix it**, except to demonstrate a quick fix (afterwards run
`git checkout docs/marketplace/demo`).

| Module | What it triggers |
| --- | --- |
| `commons` | compiles against Jackson 2.18 (`ObjectNode.withObjectProperty`, only available since 2.16) |
| `gateway` | `jackson-databind` **2.15.4** directly and `snakeyaml` 1.33 → conflicts; a test fails with `NoSuchMethodError`. Profile `green` sets Jackson to 2.18.2 → green run |
| `order-service` | Jackson without a version (BOM), `jjwt-jackson` brings an older Jackson (displaced), `httpclient` brings `commons-logging` next to `jcl-over-slf4j` (duplicate classes), stale exclusion `json-simple`, `HttpHost` only transitive (undeclared), `commons-lang3` unused |
| `billing` | `guava` 32.1.3-jre instead of 33.3.1-jre; repository `legacy-nexus` with `http://` (preflight red, build stays green) |
| `notification` | Guava 33.3.1-jre, snakeyaml 2.3 |

Checked with Maven 3.9.11 and JDK 21: `mvn verify -Pgreen` → BUILD SUCCESS, `mvn verify` → BUILD FAILURE in `gateway`.
The command line recognises the red run as **#10 Runtime conflict in tests · certain** ("comes from jackson-databind:2.15.4,
which lacks ObjectNode.withObjectProperty() … loses against 2.15.4 (nearest wins)").

## Preparation

1. Run `./gradlew :intellij-plugin:runIde` and open **this folder** (`docs/marketplace/demo`) as a project in the sandbox IDE,
   trust it and wait for the Maven import (downloads a few MB from Maven Central). Project JDK 17 or newer.
2. Theme **Dark** (default), New UI, no plugins with their own toolbars. Set the window to **1600 × 1000** so that all images
   have the same aspect ratio (16:10); take screenshots without desktop and window frame.
3. Run configurations live in `.run/` and show up at the top right: `shop-platform [verify -Pgreen]` and `shop-platform [verify]`.

## Per image

**01 Build Doctor**
1. Run `shop-platform [verify -Pgreen]` → green, becomes the reference.
2. Run `shop-platform [verify]` → red. The *Maven Detective* tool window opens with the card
   *#10 Runtime conflict in tests · certain*; under "Since the last green build" it lists the profile and
   `jackson-databind 2.18.2 → 2.15.4` (gateway).
3. Dock the tool window on the right, keep the run console with `BUILD FAILURE` and the link "← Show cause" visible on the left.
   Expand the sections *Evidence* and *Since the last green build*.

**02 Why This Version?**
1. First open *Dependencies → Conflicts* once (fills the code vision in the editor).
2. Open `order-service/pom.xml`: inlays after `jackson-databind`, `slf4j-api`, `jcl-over-slf4j`, `commons`.
3. Put the caret on `jackson-databind`, **Alt+Enter → Why This Version?** The popup shows the BOM import, `${jackson.version}` and
   the version displaced by `jjwt-jackson`.

**03 Conflict matrix** – *Dependencies → Conflicts*: 3 divergences (jackson-databind, guava, snakeyaml). Select the row
`jackson-databind` so that the suggestion "Pin 2.18.2 in shop-platform" appears below.

**04 Upgrade simulator** – `billing/pom.xml`, right-click the `guava` dependency → *Simulate Upgrade…* → `33.3.1-jre`
→ *Before/After*. Alternative with more modules: page *Upgrade Simulator*, `${jackson.version}` → `2.19.2`,
first *Download Missing POMs*, then simulate.

**05 Preflight** – action *Preflight* (Build Doctor toolbar or Maven tool window). Traffic light red because of `legacy-nexus`
(`http://`); select a finding so that "Switch the URL to https" appears. *Check repositories (network)* is optional.

**06 Duplicate classes** – *Dependencies → Duplicate Classes* → check. `order-service`: `commons-logging 1.2` ↔
`jcl-over-slf4j 2.0.16`, select it → recommendation "remove commons-logging", exclusion on `httpclient`.

More motifs: *Exclusions* (`json-simple` in order-service is stale), *Usage* (`httpcore` undeclared, `commons-lang3` unused),
*Overview* after *Check All*.

## Notes

- A green run from the IDE stores a reference under `~/.maven-detective/snapshots/demo-*`. Delete it before taking the
  screenshots to get a clean history.
- `legacy-nexus` leaves `.lastUpdated` files in the local repository (Maven remembers the blocked attempt). Harmless.
