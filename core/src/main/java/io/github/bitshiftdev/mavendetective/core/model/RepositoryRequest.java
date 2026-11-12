package io.github.bitshiftdev.mavendetective.core.model;

/** Eine Anfrage an ein Remote-Repository, wie sie die Extension aus Resolver-Events mitschreibt. */
public final class RepositoryRequest {

    private final String event;
    private final String artifact;
    private final String repositoryId;
    private final String repositoryUrl;
    private final String failure;

    public RepositoryRequest(String event, String artifact, String repositoryId, String repositoryUrl, String failure) {
        this.event = event;
        this.artifact = artifact;
        this.repositoryId = repositoryId;
        this.repositoryUrl = repositoryUrl;
        this.failure = failure;
    }

    public String getEvent() { return event; }

    /** Koordinaten {@code groupId:artifactId:extension[:classifier]:version}. */
    public String getArtifact() { return artifact; }

    public String getRepositoryId() { return repositoryId; }

    public String getRepositoryUrl() { return repositoryUrl; }

    /** Fehlermeldung, falls die Anfrage scheiterte, sonst {@code null}. */
    public String getFailure() { return failure; }

    public boolean isFailed() { return failure != null; }
}
