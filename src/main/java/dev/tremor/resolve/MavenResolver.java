package dev.tremor.resolve;

import dev.tremor.project.Gav;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.eclipse.aether.util.repository.DefaultProxySelector;
import org.eclipse.aether.version.Version;

/**
 * Thin wrapper around Maven Resolver: fetches POMs and jars and collects dependency trees,
 * caching everything in Tremor's own local repository.
 */
public final class MavenResolver implements AutoCloseable {

    private final ResolverConfig config;
    private final RepositorySystem system;
    private final DefaultRepositorySystemSession session;
    private final List<RemoteRepository> repositories;

    public MavenResolver(ResolverConfig config) {
        this.config = config;
        this.system = new RepositorySystemSupplier().get();
        this.session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(
                system.newLocalRepositoryManager(session, new LocalRepository(config.localRepository().toFile())));
        // Keep losing nodes and pre-management versions so version mediation can be explained.
        session.setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, true);
        session.setConfigProperty(DependencyManagerUtils.CONFIG_PROP_VERBOSE, true);
        session.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_WARN);
        session.setProxySelector(systemProxySelector());
        session.setSystemProperties(System.getProperties());
        this.repositories = new ArrayList<>();
        for (int i = 0; i < config.repositoryUrls().size(); i++) {
            String url = config.repositoryUrls().get(i);
            String id = url.equals(ResolverConfig.MAVEN_CENTRAL) ? "central" : "repo" + i;
            RemoteRepository.Builder repo = new RemoteRepository.Builder(id, "default", url);
            repo.setProxy(session.getProxySelector().getProxy(repo.build()));
            repositories.add(repo.build());
        }
    }

    /** Downloads (or finds in the cache) a POM file. */
    public Optional<Path> resolvePom(Gav gav) {
        try {
            return Optional.of(resolve(new DefaultArtifact(gav.groupId(), gav.artifactId(), "pom", gav.version())));
        } catch (ResolutionException e) {
            return Optional.empty();
        }
    }

    /** Downloads (or finds in the cache) a jar. */
    public Path resolveJar(Gav gav) {
        return resolve(new DefaultArtifact(gav.groupId(), gav.artifactId(), "jar", gav.version()));
    }

    /** Downloads any artifact. */
    public Path resolve(Artifact artifact) {
        try {
            return system.resolveArtifact(session, new ArtifactRequest(artifact, repositories, null))
                    .getArtifact().getFile().toPath();
        } catch (ArtifactResolutionException e) {
            throw new ResolutionException("Could not download " + artifact + ": " + rootMessage(e), e);
        }
    }

    /** Reads the effective {@code <dependencyManagement>} of a POM, e.g. an imported BOM. */
    public List<Dependency> managedDependenciesOf(Gav pom) {
        ArtifactDescriptorResult result = describe(new DefaultArtifact(
                pom.groupId(), pom.artifactId(), "pom", pom.version()));
        return result.getManagedDependencies();
    }

    /** Reads the effective dependencies a published artifact declares. */
    public ArtifactDescriptorResult describe(Artifact artifact) {
        try {
            return system.readArtifactDescriptor(session,
                    new ArtifactDescriptorRequest(artifact, repositories, null));
        } catch (ArtifactDescriptorException e) {
            throw new ResolutionException("Could not read the POM of " + artifact + ": " + rootMessage(e), e);
        }
    }

    /** Collects the full transitive tree for the given direct and managed dependencies. */
    public DependencyNode collect(List<Dependency> direct, List<Dependency> managed) {
        CollectRequest request = new CollectRequest();
        request.setRootArtifact(new DefaultArtifact("tremor.internal", "project", "pom", "0"));
        request.setDependencies(direct);
        request.setManagedDependencies(managed);
        request.setRepositories(repositories);
        try {
            return system.collectDependencies(session, request).getRoot();
        } catch (DependencyCollectionException e) {
            throw new ResolutionException("Could not build the dependency tree: " + rootMessage(e), e);
        }
    }

    /** All published versions newer than {@code current}, oldest first. */
    public List<String> newerVersions(String groupId, String artifactId, String current) {
        Artifact range = new DefaultArtifact(groupId, artifactId, "jar", "(" + current + ",)");
        try {
            return system.resolveVersionRange(session, new VersionRangeRequest(range, repositories, null))
                    .getVersions().stream().map(Version::toString).toList();
        } catch (VersionRangeResolutionException e) {
            throw new ResolutionException("Could not list versions of " + groupId + ":" + artifactId
                    + ": " + rootMessage(e), e);
        }
    }

    public ResolverConfig config() {
        return config;
    }

    public DefaultRepositorySystemSession session() {
        return session;
    }

    @Override
    public void close() {
        system.shutdown();
    }

    private static DefaultProxySelector systemProxySelector() {
        DefaultProxySelector selector = new DefaultProxySelector();
        for (String protocol : List.of("https", "http")) {
            String host = System.getProperty(protocol + ".proxyHost");
            String port = System.getProperty(protocol + ".proxyPort");
            if (host != null && !host.isBlank()) {
                int p = port == null || port.isBlank() ? 80 : Integer.parseInt(port.trim());
                selector.add(new Proxy(protocol, host, p), System.getProperty("http.nonProxyHosts"));
            }
        }
        return selector;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t == e ? e.getMessage() : e.getMessage() + " (" + t.getMessage() + ")";
    }
}
