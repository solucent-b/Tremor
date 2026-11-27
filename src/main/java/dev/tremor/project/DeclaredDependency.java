package dev.tremor.project;

/**
 * A dependency as declared in a POM, after properties and dependency management have
 * been applied. {@code version} is null when it could not be worked out.
 */
public record DeclaredDependency(
        String groupId,
        String artifactId,
        String version,
        String scope,
        String type,
        String classifier,
        boolean optional) {

    public boolean matches(String groupId, String artifactId) {
        return this.groupId.equals(groupId) && this.artifactId.equals(artifactId);
    }

    public String key() {
        return groupId + ":" + artifactId;
    }

    @Override
    public String toString() {
        return key() + ":" + (version == null ? "?" : version) + " (" + scope + ")";
    }
}
