package io.github.bitshiftdev.mavendetective.idea.ui;

import com.intellij.openapi.util.IconLoader;

import javax.swing.Icon;

/** Eigene Icons; {@code _dark}-Varianten lädt die Plattform im dunklen Theme selbst. */
public final class DetectiveIcons {

    /** 13 × 13, Tool-Window-Leiste. */
    public static final Icon TOOL_WINDOW = IconLoader.getIcon("/icons/mavenDetective.svg", DetectiveIcons.class);

    private DetectiveIcons() {
    }
}
