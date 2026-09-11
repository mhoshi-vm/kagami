package am.ik.kagami.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.Resource;

/**
 * Service interface for artifact storage operations.
 * <p>
 * This is the only way the rest of the application reaches stored artifacts, so that a
 * non-filesystem backend can be plugged in without touching any other feature. The
 * interface deliberately exposes neither {@code Path} nor {@code File}.
 */
public interface StorageService {

	/**
	 * Store an artifact from an input stream, replacing any existing content
	 * @param location the location of the artifact; must not be the repository root
	 * @param inputStream the input stream to read from
	 * @throws IOException if an I/O error occurs
	 */
	void store(ArtifactLocation location, InputStream inputStream) throws IOException;

	/**
	 * Retrieve an artifact as a Resource
	 * @param location the location of the artifact; must not be the repository root
	 * @return the artifact as a Resource, or {@link Optional#empty()} if not found
	 */
	Optional<Resource> retrieve(ArtifactLocation location);

	/**
	 * Delete everything at or under the given location: a single object, or a "directory"
	 * together with all of its descendants.
	 * @param location the location of the artifact or directory
	 * @return true if something was deleted, false if nothing existed at the location
	 * @throws IOException if an I/O error occurs during deletion
	 */
	boolean delete(ArtifactLocation location) throws IOException;

	/**
	 * List the direct children of a directory, files and sub-directories alike.
	 * @param location the directory location; the repository root is allowed
	 * @return the children sorted by name, or an empty list if the location does not
	 * exist or is not a directory
	 * @throws IOException if an I/O error occurs
	 */
	List<StorageEntry> list(ArtifactLocation location) throws IOException;

	/**
	 * Describe a single file or directory.
	 * @param location the location; the repository root is allowed
	 * @return the entry, or {@link Optional#empty()} if nothing exists at the location
	 * @throws IOException if an I/O error occurs
	 */
	Optional<StorageEntry> stat(ArtifactLocation location) throws IOException;

	/**
	 * Aggregate statistics over every file stored in a repository. This walks all objects
	 * and therefore costs O(objects).
	 * @param repositoryId the repository identifier
	 * @return the statistics, {@link StorageStats#EMPTY} if nothing is stored
	 * @throws IOException if an I/O error occurs
	 */
	StorageStats stats(String repositoryId) throws IOException;

}
