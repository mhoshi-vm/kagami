# Decouple the storage layer from the local filesystem

Difficulty: High

Prerequisite for [002-s3-storage-backend.md](002-s3-storage-backend.md). No new backend
is added here; the observable behaviour must stay the same.

## Goal

Make `StorageService` the only path to stored artifacts, so that a non-filesystem
implementation becomes possible without touching any other feature package.

## Background

`StorageService` claims to be a minimal, cloud-ready abstraction, but three places bypass
it and talk to the filesystem directly:

1. `RemoteRepositoryService#createSession` points Maven Resolver's `LocalRepository` at
   `kagami.storage.path/{repositoryId}`, so resolved artifacts are written to disk by the
   resolver and never pass through `StorageService`
   (`src/main/java/am/ik/kagami/repository/RemoteRepositoryService.java`).
   `cleanupEmptyDirectories` in the same class is filesystem-only as well.
2. `BrowserService` walks the filesystem with `Files.list` / `Files.walk` for listing,
   stats and file info (`src/main/java/am/ik/kagami/browser/BrowserService.java`).
3. `StorageService` has no listing or metadata operation at all, so (2) has nothing to
   call (`src/main/java/am/ik/kagami/storage/StorageService.java`).

Only `ArtifactController` and the `RestClient` fallback for non-standard files
(`maven-metadata.xml`, checksums) already go through `StorageService`.

## Requirements

### 1. Extend the storage abstraction

Extend `StorageService` with the operations `BrowserService` needs, keeping the interface
cloud-friendly (no `Path`, no `File`):

- `List<StorageEntry> list(ArtifactLocation location)` - one level of children, files and
  directories, like `Files.list`.
- `Optional<StorageEntry> stat(ArtifactLocation location)` - size / last modified for a
  single object.
- `StorageStats stats(String repositoryId)` - artifact count, total size, last updated.

Define `StorageEntry` (name, `StorageEntryType` FILE/DIRECTORY, path relative to the
repository root, nullable size, nullable last modified) and `StorageStats` as records in
the `storage` package. Keep the existing `store` / `retrieve` / `delete` signatures.

Design constraints that only matter for the object-storage implementation to come, but
have to be baked into the contract now:

- `delete` must keep working for both a single object and a "directory"; the contract is
  "remove everything at or under this location".
- A directory has no meaningful last-modified timestamp in object storage
  (`CommonPrefixes` carry none), so `StorageEntry.lastModified` is nullable for
  directories. Consequently `BrowserService.RepositoryEntry.lastModified` becomes
  nullable (`@JsonInclude(NON_NULL)`), and `ui/src/components/DirectoryBrowser.tsx` plus
  the matching type in `ui/src/types/` must tolerate a missing timestamp. The local
  implementation may keep returning one.
- `stats` is O(objects) by nature. Accept that; no caching in this item.
- Directory operations need an empty `artifactPath` (repository root). Move the path
  validation that currently lives in `LocalStorageService#validatePath` (reject `..`,
  `~`, absolute paths) into `ArtifactLocation` so every implementation inherits it, and
  let `store` / `retrieve` reject an empty path while `list` / `stats` allow it.

### 2. Rewrite BrowserService on top of StorageService

Remove every `java.nio.file` usage from `BrowserService`:

- `getRepositories` - use `stats(repositoryId)`.
- `browseRepository` - use `list(location)`.
- `getFileInfo` - use `stat(location)` for size and timestamp, and `retrieve(location)`
  for the sibling `.sha1` / `.sha256` contents.

The REST response shape stays as it is, apart from the nullable `lastModified` noted
above. Path validation and the repository-boundary check move to `ArtifactLocation`.

### 3. Decouple Maven Resolver from the filesystem

Stop using Kagami's storage directory as Maven Resolver's local repository. Instead:

- Resolve into a scratch local repository (temp directory, removed afterwards).
- On success, stream the resolved file into `StorageService#store`.
- Delete the scratch directory; drop `cleanupEmptyDirectories`.

Consequences to keep in mind:

- Resolver bookkeeping files (`_remote.repositories`, `maven-metadata-{repoId}.xml`) stay
  in the scratch directory and no longer pollute the served layout. Checksum files
  continue to be served through the existing `RestClient` fallback, unchanged. Confirm
  against the existing tests whether any of them assert on those files.
- Cost: one extra copy per cache miss. Acceptable - the cache-miss path is already
  dominated by network I/O.
- Concurrency: a scratch directory per fetch removes the shared resolver local-repository
  locking between requests.
- Rejected alternative: a custom Aether `LocalRepositoryManager` backed by
  `StorageService`. Correct in principle but far more code and coupled to resolver
  internals.

### 4. Storage contract test

Add an abstract contract test covering store / retrieve / stat / list / delete (single
object and directory) / stats and path-traversal rejection, run against
`LocalStorageService`. The S3 implementation subclasses it later, so both backends are
held to the same contract.

### 5. Playwright E2E harness

`com.microsoft.playwright:playwright:1.62.0` is already a test dependency but unused.
This item changes the browser JSON contract and the UI, so introduce the E2E test here
as the regression net; 002 reuses it against S3.

- `@SpringBootTest(webEnvironment = RANDOM_PORT)` importing `MockConfig` so the mock
  remote repository serves a small artifact.
- The UI must be built into `target/classes/META-INF/resources` first; the
  `frontend-maven-plugin` already does this in the `compile` phase, so a plain
  `./mvnw test` is enough.
- Scenario: open the app, get redirected to `/login`, log in with the configured user,
  see the repository list, drill into a directory and back via the breadcrumb, open the
  file info modal and assert size / checksum, then visit the token page and generate a
  token.
- Structure the test so the storage backend is a parameter (base class plus per-backend
  subclass), not hard-wired to the local one.
- Playwright downloads its browser on the first `Playwright.create()`. If CI fails on
  missing system libraries, add a
  `com.microsoft.playwright.CLI install --with-deps chromium` step (exec-maven-plugin or
  a CI step). CI runs on `ubuntu-latest` via `categolj/workflows` `unit-test.yaml`.

## Done criteria

- `./mvnw spring-javaformat:apply test` passes, including the new contract test and the
  Playwright E2E test.
- No `java.nio.file` / `java.io.File` usage left in `BrowserService`, and none in
  `RemoteRepositoryService` outside the scratch directory handling.
- No package cycles (`PackageCycleArchTest`).
- Existing API behaviour unchanged except for the nullable `lastModified` on directory
  entries.
