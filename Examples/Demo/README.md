# Pubky Pulse Android demo

A Jetpack Compose app that exercises the Pubky Pulse Android SDK
(`pulse-android`) and its Compose UI (`pulse-android-compose`) end to end. It
mirrors the Swift SDK's `Examples/Demo` iOS app section for section:

- **Run Full Demo** — fires a scripted sequence (info event, metric, an
  operation start→complete, a backend greet + checkout, funnel steps, user
  properties, an error event) with small delays.
- **Logging** — Info / Debug / Warn / Error for the message field.
- **Metrics** — record a metric, simulate a conversion (start→complete) and a
  failed operation (start→fail), and an error **with an attachment**.
- **Funnel Demo** — Welcome / Create Account / Complete Profile / First Post steps.
- **Identity** — set / clear the user, or clear + mint a new anonymous ID.
- **User Properties** — set a single property or a demo bundle.
- **Attribution** — informational only: Apple Search Ads attribution is iOS-only
  (Apple's AdServices framework has no Android equivalent), so this section is a
  "not applicable on Android" note.
- **Feedback** — `PulseFeedbackView` in a `ModalBottomSheet`, and an embedded
  variant with `showsContactFields = false` + inline actions.
- **Questionnaires** — slug field + eligible / consent / force-show toggles, a
  "Show now" path (`fetchQuestionnaire` → `PulseQuestionnaireView` in a sheet), a
  global dismiss, a reset, the `PulseQuestionnaireGate` auto-trigger wrapping the
  whole screen, and a launch / foreground / first-launch status block.
- **Backend Demo** — POSTs to the Node demo server, stamping the Pubky Pulse
  session id on `X-Pulse-Session-Id` so client + server events correlate.
- **Event Log** — newest-first list of everything the demo did.

The root list also carries `Modifier.pulseScreen("Home")` for automatic screen
tracking.

## What it connects to

| Setting | Value |
|---|---|
| `applicationId` | `org.pubky.pulse.android.demo` |
| SDK endpoint | `http://10.0.2.2:4000` (emulator alias for the host's `localhost:4000`) |
| API key | the seeded demo client key (`pulse_client_demo_…`) |
| Backend demo | `http://10.0.2.2:4007` (the Node demo server) |

`10.0.2.2` is the standard Android emulator alias for the host machine's
`localhost`. On a **physical device**, edit `DemoApp.kt` (endpoint) and
`Backend.kt` (host) to use your machine's LAN IP instead.

The demo shares the demo client key with the iOS demo, so both report into the
same **Demo Project** in the Pubky Pulse dashboard.

## Running it

From the [`pubky-pulse`](https://github.com/Jasonvdb/pubky-pulse) monorepo, start
the dev stack so the demo has somewhere to send events:

```bash
pnpm dev:server        # Fastify API server on :4000
pnpm dev:web           # Optional — dashboard on :3000 to watch events arrive
```

For the Backend Demo section, also start the Node demo server from
[`pubky-pulse-node`](https://github.com/Jasonvdb/pubky-pulse-node):

```bash
cd pubky-pulse-node/Examples/Demo
npm install && npm start   # HTTP demo server on :4007
```

Then build and install the Android demo on a running emulator. From the
`pubky-pulse-android/` repo root:

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :examples:demo:installDebug
```

(or just `:examples:demo:assembleDebug` to produce the APK without installing).
Launch **Pubky Pulse Demo** from the emulator's app drawer.

> The dev server seeds the demo client key via `pnpm dev:seed`. If events 401,
> re-seed and confirm the key in `DemoApp.kt` matches.
