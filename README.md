# [Pombo](https://pombo.cc)

# Pombo Android

**Pombo** is an open-source peer-to-peer messaging and social app: group
channels and private direct messages, with no company in the middle. Your
channels, your messages and your identity are yours.

This is the Android client, built on the [Streamr](https://streamr.network)
network: a native Kotlin/Jetpack Compose UI with an embedded JavaScript
transport bridge. Networking runs the official Streamr JS SDK in a headless
WebView; UI, state, storage, crypto, signing and notifications are native
Kotlin. It is a port of the Pombo web app, mirroring its wire protocol so
both clients talk to each other on the same channels and DMs.

## Architecture

```
┌───────────────────────────── Native app (Kotlin) ──────────────────────────────┐
│  Jetpack Compose UI: dark theme, pill nav (mobile viewport of the web app)    │
│  AppViewModel: channels, messages, identity, sync                            │
│  Protocol.kt: canonical message format (legacy verification only)             │
│  WalletStore: private key in EncryptedSharedPreferences (Android Keystore)   │
├──────────────────────────────── PomboBridge.kt ────────────────────────────────┤
│  The only file that knows about the WebView.                                  │
│  Kotlin → JS: evaluateJavascript → bridgeCall(id, method, argsB64)            │
│  JS → Kotlin: @JavascriptInterface Native.result / Native.message / status    │
├─────────────────────────── Headless WebView (JS) ──────────────────────────────┤
│  assets/pombo_bridge.html: StreamrClient (same config as the web app) + ethers│
│  assets/pombo-vendor.bundle.js: vendor bundle (Streamr SDK + ethers 6)       │
└─────────────────────────────────────────────────────────────────────────────────┘
```

A headless WebView runs the Streamr JS SDK as the transport layer. The
private key never enters that page: every signature is delegated to a native
oracle (`Native.signMessagePayload` / `Native.signTransactionPayload`), which
also lets Kotlin check what it is about to sign. Everything else, including
ECDH and symmetric encryption, is native Kotlin.

Key design decisions:

- **Wire-compatible with the Pombo web app**: same stream layout (up to four
  streams per channel, see `core/StreamConstants.kt`), same envelopes, and the
  same publisher proof binding an ephemeral publisher to the real account. The
  web app is the reference peer during testing, and parity is pinned by test
  vectors generated from it.
- **Crypto is native.** ECDH key agreement, sealed-sender DM envelopes, epoch
  keys and AES-GCM all run in Kotlin (`core/SealedSenderCrypto.kt`,
  `core/EpochKeyCrypto.kt`), so the push path can open a DM without waking the
  WebView.
- **No passwords**: identity is a private key (imported or generated), stored
  in `EncryptedSharedPreferences` backed by the Android Keystore. Multiple
  accounts are supported.
- The vendor bundle (`app/src/main/assets/pombo-vendor.bundle.js`) is built by
  the web app; when the SDK is updated there, the bundle is copied over.

## Features

- Five channel types: Open, Protected (AES-GCM under a shared password), and
  three backed by a per-channel PomboGate contract on Polygon: Closed (owner
  allowlist), Gated (token or NFT holding) and Paid (subscriptions, paid
  straight to the channel owner). Moderation and invites throughout.
- Epoch keys: contract-backed channels encrypt content with a rotating channel
  key handed out over a dedicated keys stream, k-of-n between members.
- Direct messages: end-to-end encrypted via ECDH + HKDF + AES-GCM, with
  sealed-sender envelopes and ephemeral presence/typing indicators.
- Reactions, message edit/delete, pinning, read/unread tracking, chat history
  via Streamr resend.
- File sharing over both transports: live P2P mesh and persistent storage.
- Explore: channel discovery, with the same curation as the web app.
- Push notifications (Firebase Cloud Messaging) waking the app to fetch new
  messages over Streamr.
- On-chain awareness: gas estimation and balance checks before any
  transaction, plus a chain-mismatch guard.
- Deterministic SVG avatars matching the web app's generator.
- Cross-device sync of channels, contacts and settings.

Each subsystem (`core/`, `data/`, `ui/`) is self-contained and documented at
the top of its file where the behavior is not obvious from the code.

## Requirements

- Android Studio (Koala or newer) with JDK 17.
- Android SDK 35 (`compileSdk`/`targetSdk`), minimum supported OS: Android 8.0
  (`minSdk` 26).
- A `google-services.json` for Firebase Cloud Messaging (already included for
  the project's Firebase project; replace it if you fork with your own).

## Build

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

Release builds are unminified (the JS bridge relies on exact global names)
and signed with the debug key for local testing only; a real signing key is
required for a store build.

## Testing

```
gradlew.bat test
```

Unit tests (25 files, `app/src/test/java/com/pombo/android/core`) cover
protocol encoding, the crypto that has to match the web app byte for byte,
and sync-merge logic.

## Project layout

```
app/src/main/java/com/pombo/android/
├── bridge/    WebView bridge (the only Streamr/ethers entry point)
├── core/      Protocol, crypto, stream constants, stores shared across UI
├── data/      Local persistence (channels, contacts, invites, settings, sync)
├── identity/  Wallet/key storage
├── push/      FCM registration and wake-up handling
└── ui/        Compose screens, theme, avatars, dialogs
```

## Status

At parity with the web app on protocol and features: all five channel types,
epoch keys, DMs, file sharing over both transports, Explore, invites, push
notifications, gas and chain guards, and cross-device sync all interoperate
with it. Ongoing work is UI polish rather than missing capability.
