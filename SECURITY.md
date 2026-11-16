# Security

## Reporting a vulnerability

Please do **not** open a public issue. Report privately via GitHub:
*Security → Report a vulnerability* in this repository (private vulnerability reporting).

Helpful details are the affected version, the component (IntelliJ plugin, CLI, Maven extension) and a way to reproduce
the behaviour. You will get an answer as soon as possible.

## Supported versions

Security fixes are provided for the latest released version only.

## What Maven Detective does

- Checks only read files and settings (POMs, `settings.xml`, the local repository) and do not access the network.
- The network is used only on explicit request: preflight with `--network` or *Check repositories (network)* (an HTTP `HEAD` request to the configured repositories, **without credentials**) and *Download Missing POMs* in the upgrade simulator (starts Maven).
- Snapshots of green builds are stored locally under `~/.maven-detective/` and never leave your machine.
- The Maven extension never changes the build.
