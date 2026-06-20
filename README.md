# OleusMobile (Android)

Crash reporting, ANR detection, sessions, and breadcrumbs for the Oleus platform.
Packaged as an Android library (`io.oleus:oleus-mobile`) published to GitHub Packages.

## Install

```kotlin
// settings.gradle.kts
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/oleus-io/oleus-mobile-android")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("io.oleus:oleus-mobile:1.0.0")
}
```

Publish from this directory: `GITHUB_ACTOR=… GITHUB_TOKEN=… ./gradlew :oleus-mobile:publish`

## Usage

```kotlin
class RondoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OleusMobile.initialize(
            context = this,
            // HTTPS required — Android blocks cleartext HTTP by default, so an
            // http:// endpoint would silently drop every event in production.
            endpoint = "https://api.dashboard.oleus.io/otlp",
            service = "rondo-android",
            apiKey = BuildConfig.OLEUS_INGEST_KEY,
        )
    }
}
```

Optional integrations (the SDK only `compileOnly`-depends on these):

```kotlin
Timber.plant(OleusTimberTree())                                  // WARN+ logs → Oleus
OkHttpClient.Builder().addInterceptor(OleusOkHttpInterceptor())  // Apollo operation tags, slow/5xx events
```

## Identifying users

Every event carries a `distinct_id`. Before login it's a persisted anonymous id;
call `identify` after login to tie the anonymous history to the user — Oleus
resolves both to one person.

```kotlin
// after login
OleusMobile.identify(user.id, mapOf("email" to user.email, "plan" to user.plan))

// same person across devices (e.g. links a web session)
OleusMobile.alias(webDistinctId)

// on logout — forget the user, rotate to a fresh anonymous id
OleusMobile.reset()

OleusMobile.getDistinctId()   // id currently being sent
```

`identify` emits a `$identify` event containing the prior anonymous id, so
pre-login activity stitches to the identified person. The id persists across
launches (in `SharedPreferences`) until `reset()`.

## What it captures

- **Crashes** — uncaught JVM exceptions (chains the previously installed
  handler, so Play reporting still works). Persisted synchronously at crash
  time, shipped on next launch through the disk-backed queue.
- **ANRs** — live main-thread watchdog (5s threshold) plus
  `ApplicationExitInfo` reaping on next launch for system-recorded ANRs and
  native crashes (Play threshold for visibility penalties: 0.47%).
- **Sessions** — `session_start`/`session_end` with `release`, `session.id`,
  `device.id`; powers crash-free-sessions and release adoption.
- **Real `app_version`** — `versionName+longVersionCode` from PackageManager,
  attached to every record as `release`.
- **Screens** — automatic `screen_view` per Activity; Compose routes via
  `OleusMobile.trackScreen()` from a NavController listener.
- **Native (NDK) crashes** — e.g. Agora libs — are captured via
  `ApplicationExitInfo` tombstone traces on next launch (API 30+, tagged
  `NativeCrash`). Devices on API 28–29 record the crash count but not the
  trace; a dedicated NDK signal handler is the escalation path if those
  devices matter.

## R8 / ProGuard

Stacks from minified builds are obfuscated; upload `mapping.txt` per release
so the platform retraces them (apply [r8-upload.gradle](r8-upload.gradle), or in CI):

```bash
curl -F "service=rondo-android" -F "version=1.4.0+140" \
     -F "mapping=@app/build/outputs/mapping/release/mapping.txt" \
     https://api.dashboard.oleus.io/api/symbols/r8-mapping
```

Consumer ProGuard rules keep `SourceFile`/`LineNumberTable` so retraced
frames carry line numbers.
