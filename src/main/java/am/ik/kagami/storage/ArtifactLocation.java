package am.ik.kagami.storage;

import org.springframework.util.StringUtils;

/**
 * The location of an artifact or a directory within a mirrored repository.
 * <p>
 * The artifact path is validated on construction so that every {@link StorageService}
 * implementation inherits the same protection against path traversal: {@code ..}
 * segments, {@code ~} and absolute paths are rejected. An empty artifact path denotes the
 * repository root, which is only meaningful for directory operations such as
 * {@link StorageService#list(ArtifactLocation)}.
 *
 * @param repositoryId the repository identifier
 * @param artifactPath the path relative to the repository root, without leading or
 * trailing slashes; empty for the repository root
 */
public record ArtifactLocation(String repositoryId, String artifactPath) {

	public ArtifactLocation {
		if (!StringUtils.hasText(repositoryId)) {
			throw new IllegalArgumentException("Repository id cannot be null or empty");
		}
		if (repositoryId.contains("/") || repositoryId.contains("\\") || repositoryId.contains("..")) {
			throw new IllegalArgumentException("Invalid repository id: " + repositoryId);
		}
		artifactPath = normalize(artifactPath);
	}

	/**
	 * Create a location that denotes the root of the given repository.
	 * @param repositoryId the repository identifier
	 * @return the repository root location
	 */
	public static ArtifactLocation root(String repositoryId) {
		return new ArtifactLocation(repositoryId, "");
	}

	/**
	 * Whether this location denotes the repository root.
	 * @return {@code true} if the artifact path is empty
	 */
	public boolean isRoot() {
		return this.artifactPath.isEmpty();
	}

	/**
	 * The last segment of the artifact path.
	 * @return the file or directory name, or the repository id for the root
	 */
	public String name() {
		if (isRoot()) {
			return this.repositoryId;
		}
		int lastSlash = this.artifactPath.lastIndexOf('/');
		return lastSlash < 0 ? this.artifactPath : this.artifactPath.substring(lastSlash + 1);
	}

	/**
	 * The location of the entry named {@code childName} directly under this location.
	 * @param childName the child name
	 * @return the child location
	 */
	public ArtifactLocation resolve(String childName) {
		return new ArtifactLocation(this.repositoryId, isRoot() ? childName : this.artifactPath + "/" + childName);
	}

	/**
	 * The location of an entry that lives next to this one, e.g. a checksum file.
	 * @param siblingName the sibling name
	 * @return the sibling location
	 */
	public ArtifactLocation sibling(String siblingName) {
		int lastSlash = this.artifactPath.lastIndexOf('/');
		String parent = lastSlash < 0 ? "" : this.artifactPath.substring(0, lastSlash);
		return new ArtifactLocation(this.repositoryId, parent.isEmpty() ? siblingName : parent + "/" + siblingName);
	}

	/**
	 * Ensure that this location denotes a single object rather than the repository root.
	 * Object operations such as {@link StorageService#store} and
	 * {@link StorageService#retrieve} require a non-empty artifact path.
	 * @return this location
	 * @throws IllegalArgumentException if the artifact path is empty
	 */
	public ArtifactLocation requireArtifactPath() {
		if (isRoot()) {
			throw new IllegalArgumentException("Artifact path cannot be null or empty");
		}
		return this;
	}

	private static String normalize(String artifactPath) {
		String path = artifactPath.trim();
		if (path.startsWith("/") || path.startsWith("\\")) {
			throw new IllegalArgumentException("Invalid path: " + artifactPath);
		}
		if (path.contains("..") || path.contains("~") || path.contains("\\")) {
			throw new IllegalArgumentException("Invalid path: " + artifactPath);
		}
		// Strip trailing slashes so that "dir/" and "dir" denote the same location
		while (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		if (path.contains("//") || path.equals(".") || path.startsWith("./") || path.contains("/./")
				|| path.endsWith("/.")) {
			throw new IllegalArgumentException("Invalid path: " + artifactPath);
		}
		return path;
	}
}
