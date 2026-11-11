# Developer notes

Working notes for people changing this app. For build/install instructions aimed at end users, see
[README.md](README.md).

- **Package root:** `com.agentroost.gitdashboard` under `app/src/main/java/com/agentroost/gitdashboard/`
- **Kotlin** 2.0.21, **AGP** 8.7.3, **Compose BOM** 2024.12.01, **Java/Kotlin target** 17
- **No DI framework.** `di/AppContainer.kt` is built by `GitDashboardApplication` and passed into
  Compose from `MainActivity`; ViewModels get it via `factory(container)`.

## House rules

1. **No exceptions reach the UI.** Every network entry point returns `ApiResult<T>`; `safeApiCall`
   (`github/ApiCall.kt`) is the only place that catches `IOException` / `SerializationException`.
2. **ViewModels never expose Retrofit types.** They expose `StateFlow<…UiState>` plus, where needed,
   a `SharedFlow` for one-shot events (e.g. `RepoListEvent.AuthRequired`).
3. **All user-visible text lives in `app/src/main/res/values/strings.xml`.** No string literals in
   composables.
4. **One in-flight request per ViewModel.** Copy the `isBusy` guard from `RepoListViewModel` /
   `RepoDetailViewModel`; loading from `init`, never from composition.
5. **The token is never logged, never printed, never persisted outside `TokenRepository`.**
   Do not add an OkHttp logging interceptor to `GitHubApiFactory` — it prints `Authorization`.
6. **Unauthorized (401) means sign out.** Emit the auth event so `AppViewModel.onSessionExpired`
   clears the token and recreates the authenticated graph.

## Add a new screen backed by a new endpoint — 5 steps

Worked example: a per-repository **issues** list (`GET /repos/{owner}/{repo}/issues`). Every path
below already exists unless marked *new*.

### 1. Declare the endpoint

`app/src/main/java/com/agentroost/gitdashboard/github/GitHubApi.kt`

```kotlin
@GET("repos/{owner}/{repo}/issues")
suspend fun getIssues(
    @Path("owner") owner: String,
    @Path("repo") repo: String,
    @Query("state") state: String = "open",
    @Query("per_page") perPage: Int = DEFAULT_PER_PAGE,
    @Query("page") page: Int = 1,
): Response<List<IssueDto>>
```

Return `Response<T>`, **not** `T`: the response headers carry the rate-limit and `Link` values that
`safeApiCall` and `LinkHeader` rely on.

### 2. Add the DTO and the domain model, then map between them

- DTO → `app/src/main/java/com/agentroost/gitdashboard/github/model/GitHubDtos.kt` (*add a class to
  the existing file*)
- Domain model → `app/src/main/java/com/agentroost/gitdashboard/domain/model/` (e.g. `Issue.kt`)
- Mapping → `app/src/main/java/com/agentroost/gitdashboard/github/GitHubMappers.kt`
  (`fun IssueDto.toDomain(): Issue`)

### 3. Expose it from the repository

`app/src/main/java/com/agentroost/gitdashboard/github/GitHubRepository.kt`

```kotlin
suspend fun issues(owner: String, repo: String, page: Int): ApiResult<Paged<Issue>> {
    val response = safeApiCall { api.getIssues(owner, repo, page = page) }
    return when (response) {
        is ApiResult.Failure -> response
        is ApiResult.Success -> {
            val dtos = response.data
            val nextPage = LinkHeader.nextPage(response.meta.linkHeader, page)
                ?: if (dtos.size >= GitHubApi.DEFAULT_PER_PAGE) page + 1 else null
            ApiResult.Success(Paged(dtos.map { it.toDomain() }, page, nextPage, response.meta), response.meta)
        }
    }
}
```

Nothing else changes in `AppContainer`: it already constructs the single `GitHubRepository`.

### 4. Add a UiState + ViewModel

New files under `app/src/main/java/com/agentroost/gitdashboard/ui/issues/`:

- `IssueListUiState.kt` — modelled on `ui/repos/RepoListUiState.kt` (`Loading` / `Empty` / `Content`
  / `Error`).
- `IssueListViewModel.kt` — copy the shape of `RepoListViewModel`: `MutableStateFlow` UI state,
  `isBusy` guard, `load(page, replacing)` on `viewModelScope`, `ApiError.Unauthorized` →
  `_events.tryEmit(...AuthRequired)`, and

  ```kotlin
  companion object {
      fun factory(container: AppContainer, owner: String, repo: String): ViewModelProvider.Factory =
          viewModelFactory {
              initializer { IssueListViewModel(container.gitHubRepository, owner, repo) }
          }
  }
  ```

### 5. Add the composable and register the route

- Screen: `app/src/main/java/com/agentroost/gitdashboard/ui/issues/IssueListScreen.kt` — copy the
  scaffold/top-bar/state-switch pattern from `RepoListScreen.kt`; reuse `LoadingPane`, `EmptyPane`,
  `ErrorPane` and `apiErrorMessage` from `ui/components/`.
- Route: add a constant and a builder to `Routes` in `ui/GitDashboardApp.kt` (follow
  `REPO_DETAIL` / `repoDetail(owner, repo)`, which URL-encodes with `Uri.encode`), then register it
  with a `composable(...)` entry, `navArgument`s for path parameters, and collect the auth event with
  `LaunchedEffect` → `onAuthRequired = onSessionExpired`.
- Strings: add them to `app/src/main/res/values/strings.xml` in the matching `<!-- section -->` block.
- Navigate to it from the existing screen's callback, the way the browser is wired:
  `RepoDetailScreen`'s `onBrowse` → `Routes.browse(owner, repo)`, and `BrowseScreen`'s `onOpenFile`
  → `Routes.code(owner, repo, path)`.

Nothing needs to be added to `MainActivity` or `AppContainer` for a screen that only reads GitHub
data.

### Contents and README pipeline (all wired into screens)

| Piece | File | Consumer |
| --- | --- | --- |
| `repositoryContents`, `filePreview`, `imageBytes` | `github/GitHubRepository.kt` | browser, code viewer, README viewer |
| Contents body decoding (`object` vs `array`) | `github/GitHubContentDecoder.kt` | `GitHubRepository` |
| Base64 decode + size/binary guards | `github/GitHubContentDecoder.kt`, `domain/model/Contents.kt` | code viewer, README viewer |
| Path encoding, web URLs | `github/GitHubUrls.kt` (`encodeContentPath`, `GitHubWebUrls`) | every screen |
| File-type icons | `ui/components/FileIcons.kt` | file browser |
| Syntax highlighting | `ui/code/CodeHighlighter.kt` | code viewer |
| Markdown parsing | `ui/readme/MarkdownParser.kt` | README viewer |
| README image source resolution | `ui/readme/ReadmeImageRefs.kt` | README viewer |
| Authenticated/plain image fetching + bitmap decode | `ui/readme/ReadmeImageLoader.kt` | README viewer |

Navigation entry points: `RepoDetailScreen` has **Browse files** and **View README**; the root file
browser routes a `README.md`/`README.markdown` row to the README screen and every other file to the
code viewer.

### README viewer: route, files and parser

**Route.** `Routes.README = "repos/{owner}/{repo}/readme"`, built by `Routes.readme(owner, repo)`
(`Uri.encode` on both segments) in `ui/GitDashboardApp.kt`. The `composable` entry declares `owner`
and `repo` as `NavType.StringType` arguments and constructs
`ReadmeScreen(container, owner, repo, onBack, onAuthRequired)`. Entry points are the **View README**
button on `RepoDetailScreen` and a root-level README row in `BrowseScreen` (matched with
`ReadmeFiles.isReadme`, so a nested `docs/*.md` still opens in the code viewer).

**Files** (all under `app/src/main/java/com/agentroost/gitdashboard/ui/readme/`):

| File | Responsibility |
| --- | --- |
| `ReadmeScreen.kt` | One `LazyColumn` over `MarkdownBlock`s, per-block renderers, image placeholder and toolbar (`Refresh`, `Open on GitHub`) |
| `ReadmeUiState.kt` | `Loading` / `Error` / `NoReadme(NoReadmeReason)` / `Unavailable` / `Content`; `NoReadmeReason` is `EMPTY_REPOSITORY` or `NO_README` |
| `ReadmeViewModel.kt` | Root listing → `ReadmeFiles.pick` → `filePreview`; `MAX_MARKDOWN_CHARS = 200_000`; parses on `Dispatchers.Default`; owns the lazily built `ReadmeImageLoader`; `factory(container, owner, repo)` |
| `MarkdownParser.kt` | `MarkdownBlock` / `MarkdownSpan` models and the dependency-free parser |
| `ReadmeImageRefs.kt` | Resolves an image `src` to `ReadmeImageSource.RepoPath` / `.Remote` / `.Unsupported` (percent-decodes, normalises `./` and `../`, clamps `..` at the repository root) |
| `ReadmeImageLoader.kt` | `ReadmeImageFetcher` (authenticated contents API vs. an interceptor-free client), `ReadmeImageState`, `LruCache`, bitmap downscaling; constructs `plainClient()` |

**Parser contract.**

- `MarkdownParser.parse(markdown: String): List<MarkdownBlock>` is pure and **total**: blank input
  and malformed input both return a list, and no exception escapes (the top-level `try` degrades to
  an empty list). Do not make it throw, and do not do I/O or image resolution inside it.
- Block order is document order. Images are hoisted into their own `MarkdownBlock.Image` between the
  surrounding prose so the UI can fetch them; an image inside a heading degrades to its alt text.
  `MarkdownSpan` (plain/strong/emphasis/code/link) only ever appears inside a `Heading` or
  `Paragraph`; list items and quotes hold nested `MarkdownBlock`s.
- `Image.src` is stored verbatim — `ReadmeImageRefs` resolves it later against `readmePath`, so the
  parser never needs to know the repository.
- GitHub pipe tables and raw HTML are deliberately unsupported: a table stays paragraph text with
  its pipes, and HTML tags are stripped to their text content (`<br>` becomes a newline).

**Where to add a new block type.** Three edits, and the compiler enforces the third:

1. Add the case to `sealed interface MarkdownBlock` in `MarkdownParser.kt` (data class or object,
   with KDoc).
2. Emit it from `parseBlocks` (or a `parse*` helper it calls) — remember `depth`, and return the
   index of the first unconsumed line. If the construct can appear inline, handle it in
   `parseInlinePieces` / `blocksFromInlineText` as well.
3. Render it in `MarkdownBlockView`'s `when (block)` in `ReadmeScreen.kt`; the `when` is exhaustive
   over the sealed interface, so an unhandled block fails compilation.

Add a parser test for the new syntax first (see below), and a screenshot-table row only if the block
is visually new.

**How to add a test for the image loader.**

| Entry point | File | What it covers |
| --- | --- | --- |
| JVM, no Android framework | `app/src/test/java/com/agentroost/gitdashboard/ui/readme/ReadmeImageLoaderTest.kt` | The URL-to-request decision, which is the token-privacy boundary |
| Robolectric (`@Config(sdk = [35])`, `@GraphicsMode(NATIVE)`) | `…/ui/readme/ReadmeImageLoaderEdgeCaseTest.kt` | Real `BitmapFactory`/`LruCache`: decode, failure placeholders, caching, remote size ceiling |

Copy the shape of `ReadmeImageLoaderTest` for a new fetch case:

1. Construct the fetcher directly: `ReadmeImageFetcher(repository, owner = "octocat", repo =
   "hello-world")`, where `repository = GitHubRepository(FakeGitHubApi())` (`testing/FakeGitHubApi.kt`).
2. For a repository path, stub `api.onContents = { Contents.single(Contents.fileOfBytes(...)) }` and
   assert `api.contentsRequests.single().path` — no HTTP is involved.
3. For an absolute URL, enqueue a `MockResponse` on `MockWebServer` and assert that the recorded
   request has **no** `Authorization` and no `X-GitHub-Api-Version` header, that its `Accept` is
   `image/*`, and that `api.contentsRequests` stayed empty. That assertion is the regression test for
   the privacy promise — keep it in every new remote-image test.
4. `fetch` returns `ReadmeImageFetch.Bytes` or `.Failed(reason)`; assert the reason
   (`NOT_SUPPORTED` / `NETWORK` / `TOO_LARGE` / `NOT_AN_IMAGE`) rather than a bitmap.
5. To test the loader's state instead of the fetcher, build
   `ReadmeImageLoader(fetcher, CoroutineScope(SupervisorJob() + Dispatchers.IO))`, call `load(source)`
   and await `state(source).first { it !is ReadmeImageState.Loading }`; testing decode requires
   Robolectric, as in the edge-case file.

Run `./gradlew :app:testDebugUnitTest --tests '*ReadmeImage*'` for just these classes.

## Where DTOs go, and how to write them

- **Location:** all wire models live in one file,
  `app/src/main/java/com/agentroost/gitdashboard/github/model/GitHubDtos.kt`. Don't create
  per-endpoint DTO files and don't put DTOs in `domain/model/`.
- **Annotations:** `@Serializable` on every DTO; `@SerialName("snake_case_name")` for every field
  whose JSON name differs from the Kotlin name.
- **Defaults:** give every field a default (`= 0`, `= ""`, `= null`). The shared `Json` instance in
  `GitHubApiFactory` sets `ignoreUnknownKeys = true`, `coerceInputValues = true` and
  `explicitNulls = false`, so a missing or retyped field must not throw; decode only the fields the
  UI renders.
- **Domain models stay clean:** `domain/model/` has no serialization annotations. Mapping lives in
  `GitHubMappers.kt`, which is also where GitHub quirks are normalised (see the
  `watchers_count` vs `subscribers_count` comment in `RepositoryDto.toDomain`).
- **Polymorphic responses:** an endpoint that can return either a JSON object or a JSON array (the
  contents API does) should request `ResponseBody`, then decode via `GitHubContentDecoder.parse` the
  way `GitHubRepository.repositoryContents` does. Don't add a second Retrofit instance for it.

## Error mapping

- Status → `ApiError` conversion is centralised in `Response<*>.toApiError` (`github/ApiCall.kt`):
  401 → `Unauthorized`, 403 with exhausted/mentioned rate limit or 429 → `RateLimited`, other 403 →
  `Forbidden`, 404 → `NotFound`, else `Http`.
- Rate-limit metadata comes from `ApiMeta` (`X-RateLimit-*`, `Link`). If a new screen needs the reset
  time, take it from `ApiError.RateLimited.resetAtEpochSeconds`; format with
  `util/RelativeTime.kt#formatClockTime` (renders `HH:mm` in the device time zone).
- Add new user-facing error variants by extending `ApiError` **and** `ui/components/ApiErrorText.kt`
  **and** `strings.xml` — all three, or the `when` stops compiling (which is the intent).

## Tests

**Current state:** JVM unit tests exist under `app/src/test/java/com/agentroost/gitdashboard/`;
`app/src/androidTest` is still empty. Test dependencies are declared in `app/build.gradle.kts`:

| Source set | Dependencies already declared |
| --- | --- |
| `testImplementation` (JVM/Robolectric) | `junit` 4.13.2, `kotlinx-coroutines-test` 1.9.0, `okhttp-mockwebserver` 4.12.0, `robolectric` 4.14.1, `androidx.test:core` 1.6.1, Compose BOM + `ui-test-junit4` |
| `androidTestImplementation` | Compose BOM only |
| `debugImplementation` | `ui-tooling`, `ui-test-manifest` |

Unit tests also run with `isIncludeAndroidResources = true` and `isReturnDefaultValues = true`
(`testOptions.unitTests`), i.e. Robolectric-style tests are expected.

### Where to put tests

```
app/src/test/java/com/agentroost/gitdashboard/…       # JVM: repositories, mappers, paging, ViewModels
app/src/androidTest/java/com/agentroost/gitdashboard/ # instrumented: Compose UI
```

When adding a test, start with the network layer, which is designed for it:

- `safeApiCall` / `toApiError`: drive a `MockWebServer` and assert the returned `ApiError`, e.g. a
  403 with `X-RateLimit-Remaining: 0` and `X-RateLimit-Reset` becoming `RateLimited(resetAt, …)`.
- `GitHubContentDecoder`: feed both object and array payloads, plus malformed base64.
- `LinkHeader.nextPage`: header present / absent / malformed.
- ViewModels: `kotlinx-coroutines-test` with a fake `GitHubRepository` (the class is not an
  interface, so either construct it with a fake `GitHubApi` — it takes `api` in the constructor — or
  add a small interface first).

### What the suite covers

| Area | Test classes |
| --- | --- |
| GitHub layer | `ApiErrorMappingTest`, `AuthInterceptorTest`, `GitHubContentDecoderTest`, `GitHubRepositoryContentsTest` |
| README viewer | `MarkdownParserTest`, `MarkdownParserEdgeCaseTest`, `ReadmeImageRefsTest`, `ReadmeImageRefsEdgeCaseTest`, `ReadmeImageLoaderTest`, `ReadmeImageLoaderEdgeCaseTest`, `ReadmeViewModelTest`, `ReadmeViewModelEdgeCaseTest` |
| README rendering | `ReadmeBodyTextStyleTest`, `ReadmeBodyTextRenderTest`, `ReadmeLinkTapTest`, `ReadmeImagePlaceholderContrastTest` |
| Screens / ViewModels | `BrowseViewModelTest`, `CodeHighlighterTest`, `CodeViewerViewModelTest`, `RepoDetailViewModelTest`, `RepoListViewModelTest` |
| Token storage | `TokenRepositoryTest` |

Shared fixtures live in `app/src/test/java/com/agentroost/gitdashboard/testing/`:
`FakeGitHubApi` (records `contentsRequests`, stubs `onContents`), `Contents` (DTO builders),
`MainDispatcherRule` and `Assertions` (`assertInstanceOf`, `awaitState`).

`ReadmeImageLoaderTest`, `ReadmeImageRefsTest`, `ReadmeBodyTextStyleTest` and
`ReadmeImagePlaceholderContrastTest` are plain JVM tests; the `*EdgeCaseTest` README classes are
Robolectric tests (`@Config(sdk = [35])`, `@GraphicsMode(NATIVE)`) because they decode real bitmaps,
and `ReadmeBodyTextRenderTest` / `ReadmeLinkTapTest` run the real composables under Robolectric so
the laid-out style and the link tap can be observed.

### Commands

```sh
./gradlew :app:testDebugUnitTest          # JVM (incl. Robolectric) unit tests
./gradlew :app:test                       # all unit test variants
./gradlew :app:connectedDebugAndroidTest  # instrumented tests     (no test sources; not verified)
```

The Gradle wrapper **is committed** (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar`, Gradle 8.9), so these tasks run from a plain clone with a
JDK 17+ and an Android SDK. Only `:app:assembleDebug` has been executed in this workspace
(**BUILD SUCCESSFUL**, Linux/JDK 17/SDK 35). The unit-test task is **not verified**: the environment
used for the README-viewer documentation had no JDK on `PATH` (`./gradlew` stops with
`JAVA_HOME is not set`), so run `:app:testDebugUnitTest` before trusting the suite.
Android Studio's green gutter arrows work regardless of the wrapper.

Two caveats before relying on `connectedDebugAndroidTest`:

- `defaultConfig` sets no `testInstrumentationRunner`; instrumented tests will need
  `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` plus the
  `androidx.test:runner` / `androidx.test.ext:junit` dependencies.
- Only the Compose BOM is declared for `androidTestImplementation`, so UI-test artifacts must be
  added there too.

There is **no linter/formatter** configured in the project: `gradle/libs.versions.toml` declares only
the application, Kotlin, Compose and serialization plugins — no ktlint, detekt or spotless task
exists. Match the surrounding style (4-space indent, trailing commas, KDoc on public API) by hand.

## Keeping the docs honest

When you add, remove or rewire a screen, update in the same change:

- `README.md` → **Usage walkthrough**, **Known limitations**, and the **Troubleshooting** table if a
  new failure mode becomes possible.
- `README.md` → **Architecture** file table if you add a package.
- This file → the **Contents and README pipeline** table if you wire or extend the file browser or
  README viewer.
- This file → the **README viewer** section if you change its route, the `MarkdownBlock` contract or
  the image-fetching/privacy split.
