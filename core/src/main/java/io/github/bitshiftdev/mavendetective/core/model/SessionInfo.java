package io.github.bitshiftdev.mavendetective.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Umgebung, in der ein Maven-Lauf stattfand. Felder sind {@code null}, wenn sie nicht bekannt sind
 * (zum Beispiel, wenn nur ein Text-Log vorliegt).
 */
public final class SessionInfo {

    private String mavenVersion;
    private String javaVersion;
    private String javaHome;
    private Boolean offline;
    private String userSettings;
    private String globalSettings;
    private String localRepository;
    private String projectDirectory;
    private final List<String> activeProfiles = new ArrayList<String>();
    private final List<String> goals = new ArrayList<String>();

    public String getMavenVersion() { return mavenVersion; }
    public void setMavenVersion(String mavenVersion) { this.mavenVersion = mavenVersion; }

    public String getJavaVersion() { return javaVersion; }
    public void setJavaVersion(String javaVersion) { this.javaVersion = javaVersion; }

    public String getJavaHome() { return javaHome; }
    public void setJavaHome(String javaHome) { this.javaHome = javaHome; }

    public Boolean getOffline() { return offline; }
    public void setOffline(Boolean offline) { this.offline = offline; }

    public String getUserSettings() { return userSettings; }
    public void setUserSettings(String userSettings) { this.userSettings = userSettings; }

    public String getGlobalSettings() { return globalSettings; }
    public void setGlobalSettings(String globalSettings) { this.globalSettings = globalSettings; }

    public String getLocalRepository() { return localRepository; }
    public void setLocalRepository(String localRepository) { this.localRepository = localRepository; }

    public String getProjectDirectory() { return projectDirectory; }
    public void setProjectDirectory(String projectDirectory) { this.projectDirectory = projectDirectory; }

    public List<String> getActiveProfiles() { return activeProfiles; }

    public List<String> getGoals() { return goals; }
}
