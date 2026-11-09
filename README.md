# RepoLens — a pocket IDE for GitHub repos (Android)

Paste the URL of any **public GitHub repository** and browse its code on your phone the way you would in an IDE:

- **Go to definition** – tap any function, class or variable name and jump to where it's defined, even in another file. If several definitions match, you choose from a list, closest first.
- **"N references" links** – every function and class shows a small `N references` link above it, like CodeLens in VS Code. Tap it to see each file and line that uses it, grouped by folder, and tap any result to jump there.
- **Phone-friendly reading** – lines never wrap (a single code line never turns into 3–4 lines). Long lines scroll sideways while line numbers stay in place. Pinch to zoom, or use *Text size → Fit lines to screen width*. Markdown and text files wrap like prose.
- File tree with filter, symbol search ("go to symbol"), back history, syntax highlighting for ~25 languages, and highlighting of every place the selected word appears in the file.
- Works offline once a repo is downloaded. You can also share a GitHub link from your browser to **RepoLens**.

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

Navigation is **search-based**, like GitHub's own code navigation and ctags, not a compiler. Every file gets the same per-language rules, so it works for any language and is fast enough to index a whole repo on a phone. The trade-off: if two unrelated classes both define `save()`, "Go to definition" lists both and ranks the one in the same file or nearest folder first.

## Build locally

Requires JDK 17+ and the Android SDK (platform 36).

```bash
./gradlew :app:testDebugUnitTest   # engine tests + Robolectric UI tests (screenshots in app/build/screenshots)
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Builds are signed with `app/repolens-shared.keystore`, a key committed to the repo on purpose so every build can install over the previous one. It is **not** for Play Store publishing.
