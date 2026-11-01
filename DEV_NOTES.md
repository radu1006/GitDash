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
- Navigate to it from the existing screen's callback (`RepoDetailScreen` already receives an
  `onBrowse`-style callback you can extend, or add a new one).

Nothing needs to be added to `MainActivity` or `AppContainer` for a screen that only reads GitHub
data.

### Already-built pieces waiting for a screen

The contents/file-preview chain exists but has no destination in the navigation graph:

| Piece | File |
| --- | --- |
| `repositoryContents`, `filePreview` | `github/GitHubRepository.kt` |
| Contents body decoding (`object` vs `array`) | `github/GitHubContentDecoder.kt` |
| Base64 decode + size/binary guards | `github/GitHubContentDecoder.kt`, `domain/model/Contents.kt` |
| Path encoding, web URLs | `github/GitHubUrls.kt` (`encodeContentPath`, `GitHubWebUrls`) |
| File-type icons | `ui/components/FileIcons.kt` |
| Syntax highlighting | `ui/code/CodeHighlighter.kt` |

`RepoDetailScreen`'s **Browse files** button currently passes an empty `onBrowse = {}` in
`ui/GitDashboardApp.kt`. Wiring a browser screen is steps 4–5 above plus replacing that lambda.

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

**Current state (verified):** there are no test sources in the workspace — `app/src` contains only
`main`. Test dependencies *are* declared in `app/build.gradle.kts`:

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

Start with the network layer, which is designed for it:

- `safeApiCall` / `toApiError`: drive a `MockWebServer` and assert the returned `ApiError`, e.g. a
  403 with `X-RateLimit-Remaining: 0` and `X-RateLimit-Reset` becoming `RateLimited(resetAt, …)`.
- `GitHubContentDecoder`: feed both object and array payloads, plus malformed base64.
- `LinkHeader.nextPage`: header present / absent / malformed.
- ViewModels: `kotlinx-coroutines-test` with a fake `GitHubRepository` (the class is not an
  interface, so either construct it with a fake `GitHubApi` — it takes `api` in the constructor — or
  add a small interface first).

### Commands

```sh
./gradlew :app:testDebugUnitTest          # JVM unit tests        (not verified here)
./gradlew :app:test                       # all unit test variants (not verified here)
./gradlew :app:connectedDebugAndroidTest  # instrumented tests     (not verified here)
```

**"Not verified here":** this workspace has no Gradle wrapper (`gradlew` and
`gradle/wrapper/gradle-wrapper.jar` are absent) and the machine has no JDK/Gradle/Android SDK, so no
test task could be executed. Android Studio's green gutter arrows work regardless of the wrapper.

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
- This file → the **Already-built pieces** table if you wire the file browser.
