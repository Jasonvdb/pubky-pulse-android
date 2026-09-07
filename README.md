# Pubky Pulse Android SDK

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)
[![Platforms](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-brightgreen)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-blue)](#requirements)

Kotlin SDK for Android — event logging, structured metrics, funnels, identity,
screen tracking, a drop-in user feedback view, and in-app questionnaires, all
delivered to your own Pubky Pulse server. It mirrors the
[Swift SDK](https://pulse.pubky.org/docs/sdks/swift) feature-for-feature with
Android-native idioms. The core module has a single runtime dependency
(`kotlinx-coroutines`, the analog of Swift Concurrency); the optional Jetpack
Compose UI lives in a separate artifact so non-Compose apps stay lean.

**Full setup guide & API reference:
[pulse.pubky.org/docs/sdks/android](https://pulse.pubky.org/docs/sdks/android)**

## Modules

Add the core module always; add the Compose module only if you want the drop-in UI.

| Artifact | Purpose |
|---|---|
| `org.pubky.pulse:pulse-android` | Core SDK — analytics, metrics, funnels, identity, plus the programmatic feedback and questionnaire APIs. Framework-only + coroutines. |
| `org.pubky.pulse:pulse-android-compose` | Optional Jetpack Compose UI — `PulseFeedbackView`, `PulseQuestionnaireGate` / `PulseQuestionnaireView`, and the `Modifier.pulseScreen` screen-tracking modifier. |

## Install

```kotlin
dependencies {
    implementation("org.pubky.pulse:pulse-android:0.1.0")
    // optional drop-in Compose UI:
    implementation("org.pubky.pulse:pulse-android-compose:0.1.0")
}
```

> See [releases](https://github.com/Jasonvdb/pubky-pulse-android/releases/latest)
> for the latest version.

## Quickstart

Configure the SDK once, as early as possible — `Application.onCreate()` is the
right place.

```kotlin
import android.app.Application
import org.pubky.pulse.android.Pulse

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Pulse.configure(
            context = this,
            endpoint = "https://ingest.pulse.pubky.org",
            apiKey = "pulse_client_...",
        )
        Pulse.info("app_launched")
    }
}
```

Register the `Application` subclass in your manifest:

```xml
<application android:name=".MyApp" ... >
```

`configure` validates its input and throws `PulseConfigurationError` on an
invalid endpoint, API key, or missing package name.

## Examples

### Logging

```kotlin
Pulse.info("feed_loaded", screenName = "Feed")
Pulse.warn("cache_miss", attributes = mapOf("key" to "user_profile"))

// Attribute values may be null — null entries are dropped before the event
// ships, so optional strings flow through without unwrapping at the call site.
Pulse.info("draft_created", attributes = mapOf("draftId" to session.draftId))

// Report a Throwable — the runtime type, stack trace, and cause chain are
// extracted into `_error_*` attributes the server uses to cluster issues.
try {
    upload()
} catch (e: Exception) {
    Pulse.error(e, message = "while uploading photos")
}
```

### Identify a user

The SDK stamps events with an anonymous device id until you identify the user.
Call `setUser` after login; previously-sent anonymous events are retroactively
claimed for the real user.

```kotlin
Pulse.setUser("user_12345")
Pulse.setUserProperties(mapOf("plan" to "premium"))

// On logout — reverts to the anonymous id for future events.
Pulse.clearUser()
// On a shared device, mint a fresh anonymous id:
Pulse.clearUser(newAnonymousId = true)
```

### Measure an operation

```kotlin
val op = Pulse.startOperation("photo-upload", attributes = mapOf("format" to "heic"))
// … do work …
op.complete(attributes = mapOf("size_kb" to "512"))
// or op.fail(error = "network")
// or op.cancel()

// Single-shot metric (no lifecycle):
Pulse.recordMetric("checkout")
```

### Record a funnel step

```kotlin
Pulse.step("welcome-screen")
Pulse.step("create-account")
Pulse.step("first-post")
```

### Track screens (Compose)

```kotlin
import org.pubky.pulse.android.compose.pulseScreen

@Composable
fun HomeScreen() {
    Column(modifier = Modifier.pulseScreen("Home")) { ... }
}
```

`pulseScreen` emits a screen-appeared event on enter and a screen-disappeared
event with the visible duration on exit.

### Collect user feedback (Compose)

Drop `PulseFeedbackView` into a `ModalBottomSheet`, a full screen, or an embedded
section — the host owns presentation and dismissal:

```kotlin
import androidx.compose.material3.ModalBottomSheet
import org.pubky.pulse.android.compose.PulseFeedbackView

if (showFeedback) {
    ModalBottomSheet(onDismissRequest = { showFeedback = false }) {
        PulseFeedbackView(
            onSubmitted = { showFeedback = false },
            onCancel = { showFeedback = false },
        )
    }
}
```

Every label, placeholder, and error message is overridable via
`PulseFeedbackStrings`. To submit from your own form instead, call
`Pulse.sendFeedback(message = …, email = …)`.

### In-app questionnaires (Compose)

Wrap a screen in `PulseQuestionnaireGate` to auto-present a questionnaire when
its trigger fires and the server reports the user eligible. The SDK saves partial
drafts as the user advances and resumes a half-finished questionnaire mid-flow:

```kotlin
import org.pubky.pulse.android.PulseQuestionnaireTrigger
import org.pubky.pulse.android.compose.PulseQuestionnaireGate

PulseQuestionnaireGate(
    slug = "post-onboarding-nps",
    trigger = PulseQuestionnaireTrigger.afterLaunch,
) {
    HomeScreen()
}
```

For full control, fetch with `Pulse.fetchQuestionnaire(slug)` and submit with
`Pulse.saveQuestionnaireResponse(slug, answers, isComplete)`, rendering
`PulseQuestionnaireView` or your own UI.

## Privacy

The SDK collects **analytics events, diagnostics/crash data, and product
interaction**, plus — only when you opt in — a **user id** (`Pulse.setUser`) and
**feedback name/email** (the `PulseFeedbackView` contact fields). It is **not**
used for tracking or advertising (no Advertising ID, no `AD_ID` permission), is
**not shared** with third parties (events go only to your own ingest endpoint),
and is **encrypted in transit** over HTTPS.

Before publishing, complete Google Play's **Data safety** form. The SDK ships a
guide that maps each SDK behavior to the exact form entries:

**→ [docs/play-data-safety.md](./docs/play-data-safety.md)**

### Anonymous id persistence (optional)

The SDK stores a stable anonymous id (`pulse_anon_*`) in a private
`SharedPreferences` file named `org.pubky.pulse.sdk`. On iOS the equivalent id
lives in the Keychain and survives an app delete + reinstall; Android wipes a
package's `SharedPreferences` on uninstall, and there is **no framework-only
way** for the SDK to replicate that. By default a returning user is therefore
minted a fresh anonymous id after a reinstall.

If you want best-effort reinstall persistence, opt that prefs file into Android's
Auto Backup. The SDK ships two ready-made rule files — reference them from your
app's `<application>` tag:

```xml
<application
    android:fullBackupContent="@xml/pubky_pulse_backup_rules"
    android:dataExtractionRules="@xml/pubky_pulse_data_extraction_rules"
    ... >
```

- `@xml/pubky_pulse_backup_rules` — legacy `fullBackupContent` (Android 6–11).
- `@xml/pubky_pulse_data_extraction_rules` — Android 12+ cloud-backup +
  device-transfer.

Both include only the `org.pubky.pulse.sdk` prefs file, so the anonymous id can
roam to a reinstall via the user's own Google backup. This is **best-effort**
(Auto Backup is opportunistic and user-disablable) and **opt-in** — the SDK
cannot force the host app's backup config. If your app already defines its own
backup rules, merge the SDK's
`<include domain="sharedpref" path="org.pubky.pulse.sdk.xml" />` lines into them
rather than overwriting.

## Requirements

- minSdk 24 (Android 7.0)
- Kotlin 2.0+, AGP 8.7+, JDK 17+
- The core module merges two install-time, no-prompt permissions into your app:
  `INTERNET` and `ACCESS_NETWORK_STATE`.

## Build & test

```bash
./gradlew assemble   # build all modules
./gradlew test       # JVM unit tests (Robolectric)
```

## License

MIT. See [LICENSE](./LICENSE).
