package am.ik.kagami.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Contract every {@link StorageService} implementation has to satisfy. A backend test
 * subclasses this and provides a fresh, empty service per test.
 */
public abstract class StorageServiceContractTest {

	protected static final String REPOSITORY_ID = "test-repo";

	/**
	 * @return the service under test, backed by empty storage
	 */
	protected abstract StorageService storageService();

	@Test
	void storeAndRetrieve() throws IOException {
		StorageService storage = storageService();
		ArtifactLocation location = location("org/example/lib/1.0/lib-1.0.jar");
		store(storage, location, "jar content");

		Optional<Resource> retrieved = storage.retrieve(location);
		assertThat(retrieved).isPresent();
		assertThat(read(retrieved.get())).isEqualTo("jar content");
		assertThat(retrieved.get().getFilename()).isEqualTo("lib-1.0.jar");
	}

	@Test
	void storeReplacesExistingContent() throws IOException {
		StorageService storage = storageService();
		ArtifactLocation location = location("org/example/lib/1.0/lib-1.0.pom");
		store(storage, location, "first");
		store(storage, location, "second");

		assertThat(read(storage.retrieve(location).orElseThrow())).isEqualTo("second");
	}

	@Test
	void retrieveMissingReturnsEmpty() throws IOException {
		StorageService storage = storageService();
		assertThat(storage.retrieve(location("org/missing.jar"))).isEmpty();
		store(storage, location("org/example/lib.jar"), "x");
		// A directory is not retrievable as an object
		assertThat(storage.retrieve(location("org/example"))).isEmpty();
	}

	@Test
	void storeAndRetrieveRejectRepositoryRoot() {
		StorageService storage = storageService();
		ArtifactLocation root = ArtifactLocation.root(REPOSITORY_ID);
		assertThatIllegalArgumentException()
			.isThrownBy(() -> storage.store(root, new ByteArrayInputStream(new byte[0])));
		assertThatIllegalArgumentException().isThrownBy(() -> storage.retrieve(root));
	}

	@Test
	void statFile() throws IOException {
		StorageService storage = storageService();
		ArtifactLocation location = location("org/example/lib/1.0/lib-1.0.jar");
		store(storage, location, "jar content");

		Optional<StorageEntry> stat = storage.stat(location);
		assertThat(stat).isPresent();
		StorageEntry entry = stat.get();
		assertThat(entry.name()).isEqualTo("lib-1.0.jar");
		assertThat(entry.type()).isEqualTo(StorageEntryType.FILE);
		assertThat(entry.path()).isEqualTo("org/example/lib/1.0/lib-1.0.jar");
		assertThat(entry.size()).isEqualTo(11L);
		assertThat(entry.lastModified()).isNotNull();
	}

	@Test
	void statDirectory() throws IOException {
		StorageService storage = storageService();
		store(storage, location("org/example/lib/1.0/lib-1.0.jar"), "jar content");

		Optional<StorageEntry> stat = storage.stat(location("org/example"));
		assertThat(stat).isPresent();
		StorageEntry entry = stat.get();
		assertThat(entry.name()).isEqualTo("example");
		assertThat(entry.type()).isEqualTo(StorageEntryType.DIRECTORY);
		assertThat(entry.path()).isEqualTo("org/example");
		assertThat(entry.size()).isNull();

		Optional<StorageEntry> root = storage.stat(ArtifactLocation.root(REPOSITORY_ID));
		assertThat(root).isPresent();
		assertThat(root.get().type()).isEqualTo(StorageEntryType.DIRECTORY);
		assertThat(root.get().path()).isEmpty();
	}

	@Test
	void statMissingReturnsEmpty() throws IOException {
		StorageService storage = storageService();
		assertThat(storage.stat(location("org/missing.jar"))).isEmpty();
		assertThat(storage.stat(ArtifactLocation.root(REPOSITORY_ID))).isEmpty();
	}

	@Test
	void listReturnsDirectChildrenSortedByName() throws IOException {
		StorageService storage = storageService();
		store(storage, location("org/example/lib/1.0/lib-1.0.jar"), "jar content");
		store(storage, location("org/example/lib/1.0/lib-1.0.pom"), "pom");
		store(storage, location("org/example/lib/1.0/lib-1.0.jar.sha1"), "sha1");
		store(storage, location("org/example/lib/maven-metadata.xml"), "metadata");
		store(storage, location("org/another/README"), "readme");

		List<StorageEntry> root = storage.list(ArtifactLocation.root(REPOSITORY_ID));
		assertThat(root).extracting(StorageEntry::name).containsExactly("org");
		assertThat(root.getFirst().type()).isEqualTo(StorageEntryType.DIRECTORY);
		assertThat(root.getFirst().path()).isEqualTo("org");

		List<StorageEntry> org = storage.list(location("org"));
		assertThat(org).extracting(StorageEntry::name).containsExactly("another", "example");
		assertThat(org).extracting(StorageEntry::path).containsExactly("org/another", "org/example");
		assertThat(org).allMatch(StorageEntry::isDirectory);
		assertThat(org).extracting(StorageEntry::size).containsOnlyNulls();

		List<StorageEntry> lib = storage.list(location("org/example/lib"));
		assertThat(lib).extracting(StorageEntry::name).containsExactly("1.0", "maven-metadata.xml");
		assertThat(lib.get(0).type()).isEqualTo(StorageEntryType.DIRECTORY);
		assertThat(lib.get(1).type()).isEqualTo(StorageEntryType.FILE);
		assertThat(lib.get(1).size()).isEqualTo(8L);
		assertThat(lib.get(1).lastModified()).isNotNull();

		List<StorageEntry> version = storage.list(location("org/example/lib/1.0/"));
		assertThat(version).extracting(StorageEntry::name)
			.containsExactly("lib-1.0.jar", "lib-1.0.jar.sha1", "lib-1.0.pom");
		assertThat(version).allMatch(StorageEntry::isFile);
		assertThat(version).extracting(StorageEntry::path)
			.containsExactly("org/example/lib/1.0/lib-1.0.jar", "org/example/lib/1.0/lib-1.0.jar.sha1",
					"org/example/lib/1.0/lib-1.0.pom");
	}

	@Test
	void listMissingOrFileReturnsEmpty() throws IOException {
		StorageService storage = storageService();
		assertThat(storage.list(ArtifactLocation.root(REPOSITORY_ID))).isEmpty();
		assertThat(storage.list(location("org/missing"))).isEmpty();
		store(storage, location("org/example/lib.jar"), "x");
		assertThat(storage.list(location("org/example/lib.jar"))).isEmpty();
	}

	@Test
	void deleteSingleObject() throws IOException {
		StorageService storage = storageService();
		ArtifactLocation jar = location("org/example/lib/1.0/lib-1.0.jar");
		ArtifactLocation pom = location("org/example/lib/1.0/lib-1.0.pom");
		store(storage, jar, "jar");
		store(storage, pom, "pom");

		assertThat(storage.delete(jar)).isTrue();
		assertThat(storage.retrieve(jar)).isEmpty();
		assertThat(storage.stat(jar)).isEmpty();
		// Siblings are untouched
		assertThat(storage.retrieve(pom)).isPresent();
		assertThat(storage.list(location("org/example/lib/1.0"))).extracting(StorageEntry::name)
			.containsExactly("lib-1.0.pom");
	}

	@Test
	void deleteDirectoryRemovesEverythingUnderneath() throws IOException {
		StorageService storage = storageService();
		store(storage, location("org/example/lib/1.0/lib-1.0.jar"), "jar");
		store(storage, location("org/example/lib/1.0/lib-1.0.pom"), "pom");
		store(storage, location("org/example/lib/2.0/lib-2.0.jar"), "jar");
		store(storage, location("org/other/x.jar"), "x");

		assertThat(storage.delete(location("org/example/"))).isTrue();
		assertThat(storage.stat(location("org/example"))).isEmpty();
		assertThat(storage.retrieve(location("org/example/lib/1.0/lib-1.0.jar"))).isEmpty();
		assertThat(storage.retrieve(location("org/example/lib/2.0/lib-2.0.jar"))).isEmpty();
		assertThat(storage.list(location("org"))).extracting(StorageEntry::name).containsExactly("other");
	}

	@Test
	void deleteMissingReturnsFalse() throws IOException {
		StorageService storage = storageService();
		assertThat(storage.delete(location("org/missing.jar"))).isFalse();
		assertThat(storage.delete(location("org/missing"))).isFalse();
	}

	@Test
	void statsCountArtifactsButNotAuxiliaryFiles() throws IOException {
		StorageService storage = storageService();
		assertThat(storage.stats(REPOSITORY_ID)).isEqualTo(StorageStats.EMPTY);

		store(storage, location("org/example/lib/1.0/lib-1.0.jar"), "jar content"); // 11
		store(storage, location("org/example/lib/1.0/lib-1.0.jar.sha1"), "sha1"); // 4
		store(storage, location("org/example/lib/1.0/lib-1.0.jar.sha256"), "sha256"); // 6
		store(storage, location("org/example/lib/1.0/lib-1.0.jar.md5"), "md5"); // 3
		store(storage, location("org/example/lib/1.0/lib-1.0.pom"), "pom"); // 3
		store(storage, location("org/example/lib/maven-metadata.xml"), "metadata"); // 8

		StorageStats stats = storage.stats(REPOSITORY_ID);
		assertThat(stats.artifactCount()).isEqualTo(2);
		assertThat(stats.totalSize()).isEqualTo(35);
		assertThat(stats.lastUpdated()).isNotNull();
		assertThat(stats.lastUpdated())
			.isEqualTo(storage.stat(location("org/example/lib/maven-metadata.xml")).orElseThrow().lastModified());
	}

	@Test
	void statsOfUnknownRepositoryIsEmpty() throws IOException {
		assertThat(storageService().stats("unknown")).isEqualTo(StorageStats.EMPTY);
	}

	protected static ArtifactLocation location(String artifactPath) {
		return new ArtifactLocation(REPOSITORY_ID, artifactPath);
	}

	protected static void store(StorageService storage, ArtifactLocation location, String content) throws IOException {
		try (InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
			storage.store(location, inputStream);
		}
	}

	protected static String read(Resource resource) throws IOException {
		try (InputStream inputStream = resource.getInputStream()) {
			return StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
		}
	}

}
