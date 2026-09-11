package am.ik.kagami.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ArtifactLocationTest {

	@Test
	void rootHasEmptyArtifactPath() {
		ArtifactLocation root = ArtifactLocation.root("central");
		assertThat(root.repositoryId()).isEqualTo("central");
		assertThat(root.artifactPath()).isEmpty();
		assertThat(root.isRoot()).isTrue();
		assertThat(root.name()).isEqualTo("central");
	}

	@Test
	void blankArtifactPathDenotesRoot() {
		assertThat(new ArtifactLocation("central", "  ").isRoot()).isTrue();
	}

	@Test
	void trailingSlashesAreStripped() {
		ArtifactLocation location = new ArtifactLocation("central", "org/example/");
		assertThat(location.artifactPath()).isEqualTo("org/example");
		assertThat(location.name()).isEqualTo("example");
		assertThat(location.isRoot()).isFalse();
	}

	@Test
	void resolveAndSibling() {
		ArtifactLocation dir = new ArtifactLocation("central", "org/example");
		assertThat(dir.resolve("lib.jar").artifactPath()).isEqualTo("org/example/lib.jar");
		assertThat(ArtifactLocation.root("central").resolve("org").artifactPath()).isEqualTo("org");
		ArtifactLocation file = new ArtifactLocation("central", "org/example/lib.jar");
		assertThat(file.sibling("lib.jar.sha1").artifactPath()).isEqualTo("org/example/lib.jar.sha1");
		assertThat(new ArtifactLocation("central", "lib.jar").sibling("lib.jar.sha1").artifactPath())
			.isEqualTo("lib.jar.sha1");
	}

	@Test
	void requireArtifactPathRejectsRoot() {
		assertThatIllegalArgumentException().isThrownBy(() -> ArtifactLocation.root("central").requireArtifactPath());
		ArtifactLocation file = new ArtifactLocation("central", "lib.jar");
		assertThat(file.requireArtifactPath()).isSameAs(file);
	}

	@ParameterizedTest
	@ValueSource(strings = { "../etc/passwd", "org/../../etc/passwd", "org/..", "~/secret", "org/~user/x",
			"/etc/passwd", "\\windows", "org\\example", "org//example", ".", "./org", "org/./example", "org/." })
	void rejectsPathTraversal(String artifactPath) {
		assertThatIllegalArgumentException().isThrownBy(() -> new ArtifactLocation("central", artifactPath));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "central/other", "..", "a\\b" })
	void rejectsInvalidRepositoryId(String repositoryId) {
		assertThatIllegalArgumentException().isThrownBy(() -> new ArtifactLocation(repositoryId, "lib.jar"));
	}

}
