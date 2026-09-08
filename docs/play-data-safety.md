# Google Play Data Safety — Pubky Pulse Android SDK

Every app on Google Play must complete the **Data safety** form (Play Console →
your app → **Policy → App content → Data safety**). The form describes what data
your app collects, why, whether it's shared, and how it's protected. Google
shows a summary of your answers on the store listing.

This SDK collects analytics on your behalf, so its collection becomes *your*
collection in the form. This page is the Android analog of the Swift SDK's
[privacy compliance guide](https://pubkypulse.com/docs/sdks/swift/privacy-compliance);
the Android reference lives at
[pubkypulse.com/docs/sdks/android/privacy-compliance](https://pubkypulse.com/docs/sdks/android/privacy-compliance).
It tells you exactly which Data safety entries to tick. It is guidance, not legal
advice — you remain the data controller and are responsible for the final
declaration (your own app may collect more than the SDK does).

## TL;DR

- The SDK collects **analytics events, diagnostics/crash data, and product
  interaction** — and, only if you opt in, a **user id** (`Pulse.setUser`) and
  **feedback name/email** (the `PulseFeedbackView` contact fields).
- It is **not** used for tracking or advertising. No advertising ID, no IDFA/GAID
  access, no cross-app/cross-site tracking, no ad SDKs.
- Data goes only to the configured Pubky Pulse ingest endpoint. That is a
  first-party destination — **not shared** — when you run the server; if you
  leave `endpoint` unset it defaults to Pubky's hosted host, a separate company,
  and Play would call that **Shared**.
- All transport is **encrypted in transit** over HTTPS.
- Users can **request deletion** server-side (best-effort — see below).

## What the SDK collects

Map each row below to a **Data type** in the Play Data safety form. For every
type you declare, Google asks: *collected or shared?*, *required or optional?*,
*processed ephemerally?*, and *purposes*. The SDK answers are the same across the
board:

| What the SDK sends | Play "Data type" category | When | Purpose |
|---|---|---|---|
| Analytics events — `Pulse.info/debug/warn/error`, funnel steps, metrics, screen views, session start | **App activity → App interactions / Other actions** | Always (after `Pulse.configure`) | App functionality, Analytics |
| Diagnostics & crash reports — `Pulse.error(throwable)`, error type / stack trace, network status, launch timing, OS/device model | **App info and performance → Crash logs**, **Diagnostics** | On errors + lifecycle | App functionality, Analytics |
| Product interaction — which features/screens are used, in what order | **App activity → App interactions** | Always | Analytics, App functionality |
| **User id** (your own id for the signed-in user) | **App activity → Other user-generated content** *or* a **User ids** entry if your form version exposes one | **Only if** you call `Pulse.setUser(id)` | App functionality, Analytics |
| **Feedback name & email** (free-text the user types into the feedback form) | **Personal info → Name**, **Personal info → Email address** | **Only if** you render `PulseFeedbackView` with `showsContactFields = true` and the user fills them in | App functionality |
| Free-text feedback / questionnaire answers the user submits | **App activity → Other user-generated content** | When the user submits feedback or a questionnaire | App functionality, Analytics |

Notes on the optional rows:

- **User id is opt-in.** Out of the box the SDK stamps events with an *anonymous*
  device id (`pulse_anon_*`), not a personal identifier. It only attaches a real
  user id after you call `Pulse.setUser`. If your app never calls `setUser`, omit
  the user-id row. (This mirrors the Swift manifest, which marks `UserID` as
  *Linked* only when `Pulse.setUser` is used.)
- **Feedback name/email is opt-in and user-typed.** It is collected only if you
  show the optional contact fields in `PulseFeedbackView` *and* the user chooses
  to fill them in. If you never show contact fields (or build your own form
  without them), omit the Name/Email rows. (This mirrors the Swift guidance,
  which deliberately leaves Name/Email out of the SDK manifest because they're
  typed into a developer-rendered form.)

## What the SDK does NOT do

Answer these the same way for the SDK's collection:

- **Not used for tracking.** No advertising/marketing across apps or sites, no
  data brokering. In the form, do **not** check "Used for advertising or
  marketing" for any SDK-collected type. (Android analog of the Swift manifest's
  `NSPrivacyTracking = false`.)
- **No Advertising ID.** The SDK does not request, read, or transmit the Google
  Advertising ID (GAID). It declares no `AD_ID` permission and links no ad SDKs.
- **Not shared with third parties** *when you host the endpoint.* "Sharing" in
  Play's sense means transfer to a *separate* company. The SDK transmits only to
  the URL you pass to `Pulse.configure(endpoint = …)`; if that is your own Pubky
  Pulse server, it is a first-party destination you control, so declare
  collection as **"Collected,"** not **"Shared."** Omitting `endpoint` silently
  sends to Pubky's hosted host instead — a separate company, which you must
  declare as **"Shared."**
- **No location, contacts, photos, files, messages, or audio** are collected by
  the SDK. (Your app may attach files to events via the attachments API — those
  are content *you* choose to upload, not anything the SDK harvests.)

## Security & deletion answers

The Data safety form has a **Security practices** section. For the SDK's data:

- **Encrypted in transit: Yes.** All requests to the ingest endpoint go over
  HTTPS (TLS). Use an `https://` endpoint in `Pulse.configure` — the SDK does not
  send analytics over cleartext.
- **You can request that data be deleted: Yes (best-effort).** Because the
  backend is yours, you can honor user deletion requests server-side — purge a
  user's events and `app_users` row by their id. Provide a deletion request path
  (an in-app control or a documented contact) and point Google's "users can
  request deletion" answer at it. Note this is best-effort: events already
  aggregated into anonymous time-series rollups are not personally identifiable
  and may be retained.

## Permissions the SDK adds

The SDK's manifest merges two **install-time, no-prompt** permissions into your
app — neither is a "dangerous"/runtime permission and neither needs a Data safety
declaration of its own:

- `android.permission.INTERNET` — to ship events to your ingest endpoint.
- `android.permission.ACCESS_NETWORK_STATE` — so the SDK can detect
  online/offline and queue events while offline.

Notably, the SDK declares **no** `com.google.android.gms.permission.AD_ID`
permission — confirming the "no advertising ID" answers above.

## Anonymous id storage

The SDK stores a stable anonymous id (`pulse_anon_*`) in a private
`SharedPreferences` file (`org.pubky.pulse.sdk`). It never leaves the device
except as the id stamped on events sent to your endpoint. If you opt your app
into Auto Backup for that file (see the README's "Anonymous id persistence"
section), the id may roam to a reinstall via the user's own Google backup — that
is the user's backup, governed by Google's backup terms, and is not a separate
collection or sharing event for Data safety purposes.

---

When in doubt, declare more rather than less, and keep this page in sync with the
[Android privacy compliance guide](https://pubkypulse.com/docs/sdks/android/privacy-compliance)
as the SDK evolves.
