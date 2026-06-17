# Android development reference

## App structure
- Modules build to APK/AAB. Manifest declares components + permissions. Gradle (Kotlin DSL) builds.
- Single-Activity + Jetpack Compose is the modern default; Views/XML is legacy.

## Jetpack Compose
```kotlin
@Composable
fun Greeting(name: String) { Text("Hi $name") }

@Composable
fun Counter() {
    var n by remember { mutableStateOf(0) }   // state survives recomposition
    Button(onClick = { n++ }) { Text("Count: $n") }
}
```
- Recomposition re-runs composables when observed state changes. Hoist state up; pass data down,
  events up. Use `remember` for in-composition state, `rememberSaveable` to survive config changes.
- `LaunchedEffect(key)` runs a coroutine tied to composition; `derivedStateOf` for computed state.

## Architecture
- ViewModel holds UI state (survives rotation); expose `StateFlow`, collect with
  `collectAsStateWithLifecycle()`. Repository wraps data sources. Keep Composables dumb.
- Lifecycle: onCreate/onStart/onResume/onPause/onStop/onDestroy. Don't leak Context/Activity.

## Common pieces
- Room: SQLite ORM — `@Entity`, `@Dao` with `@Query`, `@Database`. Use suspend/Flow returns.
- Coroutines: `viewModelScope`, `Dispatchers.IO` for disk/net, `Main` for UI.
- Permissions: declare in Manifest; request dangerous ones at runtime (e.g. RECORD_AUDIO).
- Foreground service needs a notification + a `foregroundServiceType` (e.g. microphone) on API 34+.
- DataStore for key/value prefs (replaces SharedPreferences).

## Gotchas
- Never block the main thread (ANR after ~5s). Off-load to Dispatchers.IO.
- Store small data in app filesDir; media via Storage Access Framework (GetContent).
