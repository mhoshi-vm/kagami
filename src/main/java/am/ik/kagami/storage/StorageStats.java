package am.ik.kagami.storage;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Aggregated statistics of a repository.
 *
 * @param artifactCount the number of stored artifacts, excluding checksum and metadata
 * files
 * @param totalSize the total size of all stored files in bytes
 * @param lastUpdated the most recent modification timestamp, {@code null} when the
 * repository is empty
 */
public record StorageStats(long artifactCount, long totalSize, @Nullable Instant lastUpdated) {

	public static final StorageStats EMPTY = new StorageStats(0, 0, null);

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Whether a file counts as an artifact. Checksums and Maven metadata are auxiliary
	 * files and are not counted.
	 * @param fileName the file name
	 * @return {@code true} if the file is a main artifact
	 */
	public static boolean isArtifact(String fileName) {
		return !fileName.endsWith(".sha1") && !fileName.endsWith(".sha256") && !fileName.endsWith(".md5")
				&& !fileName.endsWith(".sha512") && !fileName.equals("maven-metadata.xml")
				&& !fileName.equals("_remote.repositories");
	}

	/**
	 * Builder that also acts as an accumulator so that every backend applies the same
	 * counting rules while walking its objects.
	 */
	public static final class Builder {

		private long artifactCount;

		private long totalSize;

		@Nullable private Instant lastUpdated;

		private Builder() {
		}

		public Builder artifactCount(long artifactCount) {
			this.artifactCount = artifactCount;
			return this;
		}

		public Builder totalSize(long totalSize) {
			this.totalSize = totalSize;
			return this;
		}

		public Builder lastUpdated(@Nullable Instant lastUpdated) {
			this.lastUpdated = lastUpdated;
			return this;
		}

		/**
		 * Account for a single stored file.
		 * @param fileName the file name
		 * @param size the size in bytes
		 * @param lastModified the modification timestamp, may be {@code null}
		 * @return this builder
		 */
		public Builder addFile(String fileName, long size, @Nullable Instant lastModified) {
			if (isArtifact(fileName)) {
				this.artifactCount++;
			}
			this.totalSize += size;
			if (lastModified != null && (this.lastUpdated == null || lastModified.isAfter(this.lastUpdated))) {
				this.lastUpdated = lastModified;
			}
			return this;
		}

		public StorageStats build() {
			return new StorageStats(this.artifactCount, this.totalSize, this.lastUpdated);
		}

	}

}
