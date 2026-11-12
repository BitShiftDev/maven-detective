rootProject.name = "maven-detective"

// core            – reine Java-8-Bibliothek: Modell, Log-Parser, Regel-Engine, Fehlerkatalog, CLI
// maven-extension – Maven-Core-Extension (EventSpy), bringt die Diagnose auf Kommandozeile und CI
// intellij-plugin – IntelliJ-IDEA-Plugin (Tool-Window, Quick-Fixes)
include("core", "maven-extension", "intellij-plugin")
