# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Mihon is an Android manga/comic reader, forked from Tachiyomi. Content is **not** in this repo: it comes from third-party "extension" APKs the user installs on-device, loaded at runtime (see *Sources & extensions* below).

## Commands

Gradle wrapper (Gradle 9.x). JDK **21** is used to run Gradle (`.github/.java-version`); Kotlin/Java target is 17.

```bash
./gradlew assembleDebug             # debug APK, applicationId app.mihon.dev
./gradlew spotlessApply             # format; also applies to the build-logic included build
./gradlew spotlessCheck             # CI gate
./gradlew testDebugUnitTest         # unit tests, JUnit 5 platform
./gradlew verifySqlDelightMigration # CI gate whenever .sq/.sqm change
```

Run a single test class:

```bash
./gradlew :domain:testDebugUnitTest --tests "*FetchIntervalTest*"
```

Release build exactly as CI does it:

```bash
./gradlew assembleRelease -Pinclude-telemetry -Penable-updater
```

Opt-in Gradle properties (`gradle/build-logic/src/main/kotlin/mihon/gradle/BuildConfig.kt`): `-Pinclude-telemetry` (Firebase), `-Penable-updater` (in-app update checker), `-Pinclude-dependency-info`. All are off by default, so a plain local build has no Firebase and no updater.

Build **types** (not product flavors): `debug` (`.dev` suffix), `release`, `foss` (`.foss`), `nightly` (`.debug`), `benchmark`. Release variants use ABI splits.

## Three coexisting package namespaces

The same concept appears under three roots; this is a live migration, not an accident:

| Root | Meaning |
| --- | --- |
| `eu.kanade.tachiyomi` | Legacy Tachiyomi code still in `:app`. |
| `tachiyomi.*` | Code already extracted into the `:domain` / `:data` / `:core` / `:presentation-core` modules. |
| `mihon.*` | Code written since the fork. |

**Put new code under `mihon.*`.** Touching legacy code is fine; wholesale renaming it is not.

## Module layout & layering

`:domain` → `:data` → `:app`. `:domain` has no Android dependencies beyond `compileOnly` Compose annotations.

- **`:domain`** — models, **interactor** classes, and repository *interfaces*. An interactor is a single-purpose class with `await()` (suspend) and/or `subscribe()` (Flow) — e.g. `tachiyomi.domain.history.interactor.GetHistory`. Also holds the typed preference holders (`LibraryPreferences`, `DownloadPreferences`, …).
- **`:data`** — SQLDelight and the repository *implementations* (`HistoryRepositoryImpl`), each annotated `@ContributesBinding(AppScope::class)` so they bind to the `:domain` interface automatically.
- **`:app`** — everything UI plus the legacy `data/` package (backup, downloads, tracking, extension management, notifications).
- **`:core:common`** — `PreferenceStore`, networking (`NetworkHelper`, OkHttp), logging, lang/storage utils.
- **`:core:metro`** — `GraphProvider` interface + `Context.metroGraph()`, so modules can reach the DI graph without depending on `:app`.
- **`:i18n`** — KMP module, moko-resources.
- **`:source-api` / `:source-local`** — extension ABI and the built-in local-files source.
- **`:telemetry`** — see *Telemetry* below.
- **`:presentation-core`, `:presentation-widget`, `:icons:*`, `:core:archive`, `:core-metadata`, `:baseline-profile`**.

Build logic lives in `gradle/build-logic` (an **included build**). Convention plugins are applied as `mihonx.plugins.*` (ids `mihon.plugins.*`), and there are **two version catalogs**: `libs` (`gradle/libs.versions.toml`) for dependencies and `mihonx` (`gradle/mihon.versions.toml`) for SDK/NDK/Java versions and the convention plugin ids.

The configuration cache and isolated projects are on: a build script must not reach into another project (`rootProject.file(...)` fails), so files at the repository root are read through `layout.settingsDirectory`.

## Dependency injection — Metro is the graph, Injekt is a shim

- The real DI is **Metro** (`dev.zacsweers.metro`, compile-time). The graph is `AppGraph` (`app/src/main/java/mihon/app/di/AppGraph.kt`), created in `App.onCreate()`; `App` implements `GraphProvider<AppGraph>`.
- **Injekt still exists but is read-only.** `MetroInjektRegistrar` reimplements `InjektRegistrar` over the Metro graph and exposes only a fixed handful of types (`Json`, `ProtoBuf`, `XML`, `NetworkHelper`, `JavaScriptEngine`, `Context`). Any `addSingleton`/`importModule` call throws. It exists because extension APKs compiled against `:source-api` call `Injekt.get()`.
- **New code uses Metro.** Constructor-inject with `@Inject`; scope singletons with `@SingleIn(AppScope::class)`; bind implementations with `@ContributesBinding(AppScope::class)`. If you hit "`X` is not exposed to Injekt", the fix is usually to convert the caller to Metro, not to widen `MetroInjektRegistrar`.
- Android entry points that can't be constructor-injected are listed as `fun inject(...)` members on `AppGraph`.

### ViewModels

`androidx.lifecycle.ViewModel` (not Voyager `ScreenModel` — those are nearly gone). Declare:

```kotlin
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class HistoryViewModel(private val getHistory: GetHistory, ...) : ViewModel()
```

and obtain it in Compose with `metroViewModel<HistoryViewModel>()`. For runtime arguments, use `@AssistedFactory` + `ManualViewModelAssistedFactory` (see `RestoreBackupScreen.kt`).

## UI

Jetpack Compose + Material 3, navigation by **Voyager**. Compose opt-ins are set once globally in `app/build.gradle.kts` `freeCompilerArgs` — don't add file-level `@OptIn` for those.

Legacy features split a feature across two packages:
- `eu/kanade/tachiyomi/ui/<feature>/` — the Voyager `Screen`/`Tab` and the ViewModel.
- `eu/kanade/presentation/<feature>/` — the stateless composables.

New features are self-contained under `mihon/feature/<feature>/`. Prefer that.

`Screen` and `Tab` base types are in `eu/kanade/presentation/util/Navigator.kt`; `Tab.onReselect` handles re-tapping the active bottom-nav tab.

## Database (SQLDelight)

Schema in `data/src/main/sqldelight/tachiyomi/`: tables in `data/*.sq`, views in `view/*.sq`, migrations in `migrations/N.sqm`. The driver is **async** (`generateAsync = true`), so queries are consumed with `awaitAsList()` / `awaitAsOne()` / `awaitAsOneOrNull()`, or `subscribeToList()` for Flows.

Any schema change needs **both** the `.sq` edit and a new `migrations/<next>.sqm`; `verifySqlDelightMigration` is a CI gate.

`:app` has no access to `Database` (SQLDelight is not on its classpath): queries live in `:data`, behind a repository interface in `:domain`.

## Preferences

`PreferenceStore` (`:core:common`) is wrapped by typed holder classes in `:domain` and `:app` (`LibraryPreferences`, `ReaderPreferences`, `SourcePreferences`, …). Each exposes `Preference<T>`, which is Flow-backed (`.changes()`) as well as directly readable. Add new settings to the relevant holder rather than touching `PreferenceStore` directly.

## Internationalization

moko-resources. **Only edit `i18n/src/commonMain/moko-resources/base/strings.xml` and `base/plurals.xml`** — every other locale directory is written by Weblate, and the CI path filter deliberately ignores changes to them.

Reference strings as `MR.strings.foo`, resolved with `stringResource(MR.strings.foo)` from `tachiyomi.presentation.core.i18n` (Compose) or `tachiyomi.core.common.i18n` (Context).

## Sources & extensions

`:source-api` is the **ABI third-party extension APKs compile against** — changes there break every installed extension. `ExtensionLoader` discovers APKs declaring the `tachiyomi.extension` feature, reads `tachiyomi.extension.class` / `.factory` metadata, and loads them with a delegate-last class loader; `SUPPORTED_LIB_VERSIONS` gates which extension-lib versions are accepted. Extensions can be *shared* (installed system-wide) or *private* (in the app's `files/exts`).

## App migrations (distinct from DB migrations)

`mihon/core/migration/` runs version-gated one-off tasks at startup (preference cleanups, job rescheduling). Add a `Migration` in `mihon/core/migration/migrations/`; they are collected into the graph as a `Set<Migration>` and injected into `App`.

## Telemetry

The `:telemetry` module swaps **source sets** based on `-Pinclude-telemetry`: `src/firebase/kotlin` or `src/noop/kotlin`, both providing `TelemetryConfig`. All Firebase/Crashlytics code must stay inside that module so FOSS builds compile without it.

## Conventions

- ktlint via Spotless, 120-column limit, 4-space indent for Kotlin/XML/`.sq`, trailing commas allowed. `.editorconfig` disables several ktlint standard rules and exempts `@Composable` from function-naming — read it before "fixing" style.
- Star imports are effectively banned (`name_count_to_use_star_import` is `Int.MAX_VALUE`).
- User-facing changes get a `CHANGELOG.md` entry under `[Unreleased]` in the right category (`Added` / `Changed` / `Improved` / `Removed` / `Fixed` / `Other`), with contributor link and PR link, matching the existing lines.
