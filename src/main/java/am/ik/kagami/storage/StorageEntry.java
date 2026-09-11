package am.ik.kagami.storage;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A file or directory stored in a repository.
 *
 * @param name the file or directory name
 * @param type whether the entry is a file or a directory
 * @param path the path relative to the repository root
 * @param size the size in bytes, {@code null} for directories
 * @param lastModified the last modification timestamp; always present for files, may be
 * {@code null} for directories because object storage has no timestamp for a "prefix"
 */
public record StorageEntry(String name, StorageEntryType type, String path, @Nullable Long size,
		@Nullable Instant lastModified) {

	public static Builder builder() {
		return new Builder();
	}

	public boolean isFile() {
		return this.type == StorageEntryType.FILE;
	}

	public boolean isDirectory() {
		return this.type == StorageEntryType.DIRECTORY;
	}

	public static final class Builder {

		@Nullable private String name;

		@Nullable private StorageEntryType type;

		@Nullable private String path;

		@Nullable private Long size;

		@Nullable private Instant lastModified;

		private Builder() {
		}

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder type(StorageEntryType type) {
			this.type = type;
			return this;
		}

		public Builder path(String path) {
			this.path = path;
			return this;
		}

		public Builder size(@Nullable Long size) {
			this.size = size;
			return this;
		}

		public Builder lastModified(@Nullable Instant lastModified) {
			this.lastModified = lastModified;
			return this;
		}

		public StorageEntry build() {
			return new StorageEntry(Objects.requireNonNull(this.name, "name is required"),
					Objects.requireNonNull(this.type, "type is required"),
					Objects.requireNonNull(this.path, "path is required"), this.size, this.lastModified);
		}

	}

}
