# RepoLens — a pocket IDE for GitHub repos (Android)

Paste the URL of any **public GitHub repository** and browse its code on your phone the way you would in an IDE:

- **Go to definition** – tap any function, class or variable name and jump to where it's defined, even in another file. If several definitions match, you choose from a list, closest first.
- **"N references" links** – every function and class shows a small `N references` link above it, like CodeLens in VS Code. Tap it to see each file and line that uses it, grouped by folder, and tap any result to jump there.
- **Phone-friendly reading** – code lines don't wrap by default (a single code line never turns into 3–4 lines). Long lines scroll sideways while line numbers stay in place. Pinch to zoom, or use *Text size → Fit lines to screen width*; *Wrap long lines* is there if you prefer it. Markdown and text files wrap like prose.
- File tree with filter, symbol search ("go to symbol"), back history, syntax highlighting for 18 programming languages plus JSON/YAML/XML/CSS/SQL/Makefiles, and highlighting of every place the selected word appears in the file.
- Light and dark themes that follow the system setting.
- Works offline once a repo is downloaded (one copy per repo; *Download latest* in a recent's menu refreshes it, and the old copy is kept if that fails). You can also share a GitHub link from your browser to **RepoLens**.
- If the app ever crashes, the next launch shows a *RepoLens crashed last time* card with a report you can copy into a GitHub issue. A repo that was open during the crash isn't reopened automatically.

## Install

Each push to the default branch builds an APK and publishes it under **Releases** (`build-N` → `RepoLens.apk`). Open that page on your phone, download the APK, and allow your browser to install unknown apps. You can also download the APK from any run on the **Actions** tab (`RepoLens-apk` artifact).

## How it works

| Piece | File |
|---|---|
| Download a repo as one zip (one request, so the GitHub API rate limit isn't a problem) and unpack it safely | `app/src/main/java/com/repolens/app/core/GitHubRepo.kt` |
| Lexer: comments, strings, keywords, multi-line state | `core/Lexer.kt` |
| Per-language definition rules (Kotlin, Java, C/C++, C#, JS/TS, Python, Go, Rust, Swift, Ruby, PHP, Dart, Scala, Groovy, Lua, Shell) | `core/Language.kt`, `core/DefinitionFinder.kt` |
| Repo-wide symbol index: definitions and every use, built in parallel, with no matches across language families | `core/SymbolIndex.kt` |
| Canvas-drawn code view: two-axis scrolling, pinch zoom, fixed line numbers, reference links | `ui/CodeView.kt` |
| Screens (Jetpack Compose) | `ui/HomeScreen.kt`, `ui/WorkspaceScreen.kt` |

Navigation is **search-based**, like GitHub's own code navigation and ctags, not a compiler. Every file gets the same per-language rules, so it works for any language and is fast enough to index a whole repo on a phone. The trade-offs:

- If two unrelated classes both define `save()`, "Go to definition" lists both and ranks the one in the same file or nearest folder first. A library call with the same name as a method in the repo (e.g. `ResponseEntity.status(...)` vs. your own `status()`) is treated as a use of the repo's method.
- "N references" counts uses of the *name* in files of the same language family. For Java and Kotlin functions only calls (`f(…)`, `f { … }`), method references (`::f`) and imports count, so a parameter called `refreshToken` doesn't inflate the count of a `refreshToken()` method; in other languages, where functions are passed around as values, every use counts. A constructor doesn't get its own link: its calls are counted by its class's link.
- Java local variables and parameters without modifiers, enum constants, and annotation usages (`@Foo`) are not navigable; tapping them offers a text search instead.

## Limits

- Public repositories only (no sign-in). Without an API token GitHub allows 60 lookups an hour per network; when that runs out RepoLens falls back to the `HEAD.zip` archive, and the branch is then shown as `HEAD`.
- The archive is what GitHub's "Download ZIP" gives: no git history, no submodules, and Git LFS files appear as small pointer files.
- Downloads are capped at 2 GB and unpacked repos at 3 GB / 250,000 files; the file tree lists at most 300,000 files.
- Indexing is scaled to the phone's memory: on very large repos navigation covers a prioritised subset (tests, vendor and build folders last) and a notice says so. Files over 1 MB or with minified lines aren't indexed; files over a few MB (depending on memory) aren't displayed, and lines over 20,000 characters are cut.

## Build locally

Requires JDK 17+ and the Android SDK (platform 36).

```bash
./gradlew :app:testDebugUnitTest   # engine tests + Robolectric UI tests (screenshots in app/build/screenshots)
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew :app:lintRelease
```

On a restricted network, set `ROBOLECTRIC_REPO_URL` to a Maven Central mirror for the Android runtime jars the UI tests download. Release builds are minified with R8 but not obfuscated; CI uploads `mapping.txt` (`RepoLens-mapping` artifact) to map crash-report line numbers back to the source.

Builds are signed with `app/repolens-shared.keystore`, a key committed to the repo on purpose so every build can install over the previous one. It is **not** for Play Store publishing.
