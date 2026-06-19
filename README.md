# OleusRUM — Android SDK

Real User Monitoring for Android. Captures sessions, views, user actions, network
requests, crashes, and ANRs, and ships them to the Oleus platform.

- **Min SDK:** 21 (Android 5.0) · **Target/Compile SDK:** 34
- **Language:** Kotlin (JVM 17)
- **Coordinates:** `io.oleus:rum-android`
- **Distribution:** Maven (GitHub Packages / Maven Central)

> This `src/` tree is the source of truth inside the platform monorepo. It is
> mirrored to the standalone **`oleus-io/oleus-rum-android`** repo and published as a
> Maven artifact on each release (see [`../scripts/release-sdk.sh`](../scripts/release-sdk.sh)).

## Installation

The SDK is published to **Maven Central** — no authentication or extra repository
config needed.

### Gradle (Kotlin DSL — `build.gradle.kts`)

```kotlin
// settings.gradle.kts — mavenCentral() is included by default in new projects;
// add it if your project doesn't already have it:
// dependencyResolutionManagement { repositories { mavenCentral() } }

dependencies {
    implementation("io.oleus:rum-android:0.8.0")
}
```

### Gradle (Groovy — `build.gradle`)

```groovy
dependencies {
    implementation 'io.oleus:rum-android:0.8.0'
}
```

`mavenCentral()` is included by default in new Android projects. If yours doesn't
have it, add it to your `settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

## Quickstart

Initialize once in your `Application.onCreate()`:

```kotlin
import io.oleus.rum.OleusRUM
import io.oleus.rum.OleusConfiguration

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OleusRUM.start(this, OleusConfiguration(
            apiKey = "<YOUR_API_KEY>",
            endpoint = "https://api.internal.oleus.io"
        ))
    }
}
```

Register the `Application` subclass in your manifest:

```xml
<application android:name=".MyApp" ... />
```

Sessions, activity/view tracking, network requests, crashes, and ANRs are captured
automatically. To record a custom user action:

```kotlin
OleusRUM.shared?.trackAction("checkout_tapped", mapOf(
    "cart_value" to 42.00,
    "item_count" to 3,
))
```

## Configuration

All fields have sensible defaults; only `apiKey` is required.

| Option | Default | Description |
| --- | --- | --- |
| `apiKey` | — | **Required.** Your Oleus ingest key. |
| `endpoint` | `https://api.internal.oleus.io` | Ingest endpoint. |
| `sessionSampleRate` | `1.0` | Fraction of sessions tracked (0.0–1.0). |
| `sessionReplayEnabled` | `true` | Enable session replay capture. |
| `sessionReplaySampleRate` | `0.1` | Fraction of sessions replayed. |
| `networkInstrumentationEnabled` | `true` | Auto-instrument OkHttp requests. |
| `crashReportingEnabled` | `true` | Capture uncaught exceptions. |
| `anrDetectionEnabled` | `true` | Detect Application Not Responding events. |
| `batchSize` | `50` | Events per upload batch. |
| `flushIntervalSeconds` | `30` | Max seconds between flushes. |

## License

Copyright © Oleus. All rights reserved.
