# System architecture

## Purpose and scope

ConnectOnion is an Android client built with Jetpack Compose and a hand-written
dependency-injection composition root in `di/AppContainer.kt`. It connects to
agents through an OkHttp WebSocket, using `oo.openonion.ai` as the default relay,
and persists agents, sessions, messages, and relay session state locally with
Room.

This document explains why the code is divided into layers, how those layers
interact, and which state and dependency rules must remain true. For the package
inventory, see the directory tree in
[README.md § Architecture](../README.md#architecture).

## Layer responsibilities

| Layer | Responsibility | Must not |
|---|---|---|
| `domain/model/` | Model application data, events, and state using pure Kotlin types such as `ConnectionState`, `ChatItem`, and `ChatEvent`. | Depend on Android, Compose, Room, OkHttp, or concrete repositories. |
| `domain/usecase/` | Coordinate repositories and the network-facing connection flow with coroutines and `Flow`. `ConnectToAgentUseCase` owns connection orchestration; `ConversationHistoryUseCase` coordinates persisted history. | Hold Compose/UI state or render user-interface decisions. |
| `data/repository/` | Present stable interfaces over Room, encrypted preferences, account services, discovery, and the live connection. Repository implementations translate infrastructure details into application-facing results. | Allow UI code to bypass the repository boundary and call `network/` directly. |
| `network/` | Own OkHttp WebSocket lifecycle and wire-protocol parsing/serialization through `AgentConnection`, `ProtocolParser`, and protocol DTOs. | Know about Compose, navigation, screens, or make presentation decisions. |
| `ui/` | Render Compose screens and hold screen state in ViewModels. ViewModels consume interfaces/use cases supplied by `AppContainer`. | Construct repository implementations directly or create a parallel connection-state Boolean outside `ConnectionState`. |

Besides those two orchestrating use cases, `domain/usecase/` holds a set of
small single-purpose collaborators, each pure or near-pure and each testable
without a device: `SessionFreshness` (whether a resumed local conversation is
behind the transcript the server replays on connect, and what it is missing),
`StaleSessionDetector` (whether a server `ERROR` means the session is attached
to another connection), `ServerErrorText` (relay developer strings into text
worth showing a user), `ResumableConversationLookup` (which conversation an
agent's next connect resumes), and `PersistenceTransaction` (message row plus
session title and preview in one Room transaction). Keeping these out of the
two large use cases is what lets the layer be covered by plain JVM tests.

The important dependency direction is UI → domain/use-case or repository
interfaces → data/network implementations. Infrastructure may translate into
domain models, but domain models do not import infrastructure.

## Composition root and dependency graph

`ConnectOnionApplication` owns one lazily-created `DefaultAppContainer`. A
`Context` can resolve that application-scoped container through
`Context.appContainer`. `AppContainer` is an interface so tests can replace the
production graph without changing screen or ViewModel code.

The construction order is:

```text
Android Context
  → AppDatabase + SafePreferencesWrapper + KeyManager
  → Room DAOs / DataStore / encrypted preferences
  → repository implementations
  → AppContainer repository interfaces
  → ViewModels and use cases
  → Compose screens
```

`DefaultAppContainer` seeds itself from three private values — the application
`Context`, the singleton `AppDatabase`, and a `SafePreferencesWrapper` — and
exposes twenty-one properties built from them:

| Container property | Production implementation | Built from |
|---|---|---|
| `keyManager` | `KeyManager` | `Context` |
| `configRepository` | `EncryptedPreferencesConnectionConfigRepository` | `Context` |
| `agentRepository` | `AgentRepositoryImpl` | `agentDao()`, `AgentSecureConfigRepositoryImpl` |
| `sessionRepository` | `SessionRepositoryImpl` | `sessionDao()` |
| `messageRepository` | `MessageRepositoryImpl` | `messageDao()` |
| `ignoredIdsStorage` | `DataStoreIgnoredIdsStorage` | `Context` |
| `defaultAgentRepository` | `DefaultAgentRepository` | `SafePreferencesWrapper`, `agentRepository` |
| `discoveryRepository` | `AgentDiscoveryRepositoryImpl` | — |
| `accountRepository` | `AccountRepositoryImpl` | `keyManager` |
| `imageAttachmentStore` | `ImageAttachmentStoreImpl` | `Context` |
| `fileAttachmentStore` | `FileAttachmentStoreImpl` | `Context` |
| `voiceRecorderStore` | `VoiceRecorderStoreImpl` | `Context` |
| `voiceTranscriptionService` | `VoiceTranscriptionServiceImpl` | `accountRepository` |
| `speechRecognitionService` | `AndroidSpeechRecognitionService` | `Context` |
| `appSettings` | `DataStoreAppSettings` | `Context` |
| `persistenceTransaction` | `PersistenceTransaction` | `AppDatabase` |
| `isSecureStorage` | A `Boolean`, not a dependency — whether the keystore-backed preference store is genuinely encrypted | `SafePreferencesWrapper` |
| `sessionStore` | `MemoryGatedSessionStore` wrapping `SessionStoreImpl` | `sessionStateDao()`, `appSettings.memoryEnabled` |
| `agentDiscovery` | `AgentDiscoveryService` | — |
| `connectToAgentUseCase` | `ConnectToAgentUseCase` | `keyManager`, `sessionStore`, `agentDiscovery`, and a `RoomPendingMessageSink` over `pendingMessageDao()` spilling to `filesDir/pending-attachments` |
| `networkMonitor` | `ConnectivityManagerNetworkMonitor` | `Context` |

`agentDiscovery` is a container property rather than something
`ConnectToAgentUseCase` constructs, so the discovery cache is shared with every
other caller instead of being rebuilt per connection.

Container properties use `by lazy` and are application-scoped singletons.
`connectToAgentUseCase` is one of them, so the app holds exactly one live
connection. It replaced a per-ViewModel factory, which gave `LoadingScreen`'s
probe and `ChatViewModel` a socket each: two server sessions then raced to
write `session_states` (keyed on agent address, `REPLACE`) and every
backgrounding cost double the reconnect churn. Nothing closes the use case from
`onCleared()` — a shared connection must outlive any one screen — so it lives
for the process, and `disconnect()` is the user-driven teardown.

To add a repository:

1. Define the application-facing interface under `data/repository/`.
2. Implement infrastructure access behind that interface.
3. Add an interface property to `AppContainer`.
4. Register the implementation in `DefaultAppContainer`, using existing
   container dependencies rather than constructing a second database or
   preference stack.
5. Inject the interface into the consuming ViewModel/use case. Do not construct
   the implementation in `ui/`.

## Connection state machine

`ConnectionState` is the single source of truth for connection status. Its
states are:

- `Idle`: no attempt has been made.
- `Connecting`: an initial connection attempt is active.
- `Connected`: the socket is ready and carries the agent address and optional
  `SessionSnapshot`.
- `Reconnecting`: a previously active connection is being restored.
- `Disconnected`: there is no active connection.
- `Error`: a terminal or user-visible connection failure, with optional
  technical detail and cause.

Legal transitions and their triggers are:

| From | To | Trigger |
|---|---|---|
| `Idle` | `Connecting` | First explicit or automatic connection attempt. |
| `Disconnected` | `Connecting` | Connect or retry after a clean disconnect. |
| `Connecting` | `Connected` | `AgentConnection` emits `ConnectionEvent.Connected`. |
| `Connecting` | `Error` | Opening, handshake, timeout, or protocol failure. |
| `Connecting` | `Reconnecting` | The connection layer starts a recovery attempt. |
| `Connected` | `Reconnecting` | Unexpected socket loss schedules reconnect. |
| `Connected` | `Disconnected` | Clean close or explicit disconnect. |
| `Connected` | `Error` | A non-recoverable connection error is emitted. |
| `Reconnecting` | `Connected` | Recovery handshake succeeds. |
| `Reconnecting` | `Error` | Recovery attempts are exhausted or fail terminally. |
| `Reconnecting` | `Disconnected` | Recovery is cancelled or explicitly disconnected. |
| `Error` | `Connecting` | User or automatic retry. |
| `Error` | `Disconnected` | Failure is dismissed/reset without retry. |

`isIdle()` distinguishes first-launch inactivity from a later disconnect.
`isConnecting()` intentionally returns true for both `Connecting` and
`Reconnecting`, allowing UI progress indicators to share presentation logic
without introducing a second state source.

The runtime flow is:

```text
AgentConnection
  → ConnectionEvent
  → ConnectionRepositoryImpl
  → StateFlow<ConnectionState> and Flow<ChatEvent>
  → ConnectToAgentUseCaseContract
  → ChatViewModel
  → ChatUiState
  → ChatScreen
```

`AgentConnection` owns socket events. `ProtocolParser` converts server frames
into `ConnectionEvent` values. `ConnectionRepositoryImpl` maps connection
events into `ConnectionState` and chat events. `ChatViewModel` observes both
streams; `ChatEventReducer` deterministically reduces chat events into the list
rendered by Compose.

## End-to-end sequence: sending a message

```text
User → ChatScreen/InputBar: enter text and optional images
InputBar → ChatViewModel.sendMessage: submit content
ChatViewModel → ImageAttachmentStore.store: persist/compress each attachment (optional)
ChatViewModel → ConversationHistoryUseCase: identify the active persisted session
ChatViewModel → sendWhenTheAgentIsIdle: hold the send until no turn is running
ChatViewModel → ConnectToAgentUseCase.sendMessage: send text and encoded images
ConnectToAgentUseCase → ConnectionRepositoryImpl.sendMessage: cross the repository boundary
ConnectionRepositoryImpl → AgentConnection.sendMessage: build and send the WebSocket INPUT frame
Relay/agent → AgentConnection: return llm_call, llm_result, assistant, and OUTPUT frames
AgentConnection → ProtocolParser: parse wire JSON
ProtocolParser → ConnectionRepositoryImpl: emit ConnectionEvent values
ConnectionRepositoryImpl → ChatViewModel: expose ChatEvent values
ChatViewModel → ChatEventReducer: merge, update, de-duplicate, or complete chat items
ChatViewModel → ConversationHistoryUseCase: persist the resulting message/session state
ChatViewModel → ChatScreen: publish updated uiState
```

The UI adds an optimistic user item, but server events remain authoritative for
agent output and session metadata. Attachments are stored before transmission so
the displayed history refers to durable local paths rather than temporary picker
URIs.

`sendWhenTheAgentIsIdle` is a client-side workaround for a server-side limit, and
is not inferable from reading the client alone. The relay accepts a message sent
into a running turn and acknowledges it with `RUNTIME_INPUT_ACK`, but the agent
drains that queue only at the start of an iteration: a turn that calls a tool has
another iteration and answers the message, while a turn that answers directly has
exactly one and never does. Since most chat turns call no tools, the message
would simply vanish. Holding it until the running turn's `OUTPUT` lands makes it
an ordinary `INPUT`, which always starts its own turn. The busy signal is
`serverTurnActive` — deliberately not `isAgentWorking`, which clears at
`llm_result`, roughly three seconds before the turn actually ends, and a message
released into that gap hits the same trap. The framework has since closed the
gap on its side — a no-tool turn now drains accepted runtime input before it
closes, and an agent that cannot take it rejects rather than acknowledging — so
this hold can go once the agents we connect to are known to be current.

When the hold times out, or the client's view of the turn was stale, the message
does reach a running turn. It is then shown as `AWAITING_ACK` ("Sending…") and
settled by the host's `RUNTIME_INPUT_ACK` or `RUNTIME_INPUT_REJECTED`; a
retryable refusal falls back to the held-then-ordinary-`INPUT` path above, once.
The send lock is held until the host answers (or
`ChatViewModel.RUNTIME_INPUT_REPLY_TIMEOUT_MS` passes), which is what lets a
reply be linked to its message when nothing on the wire identifies it. See the
"Runtime input" section of `protocol.md` for the details and the known gaps.

## End-to-end sequence: cold-start recovery

```text
Android → ConnectOnionApplication: create the process Application
ConnectOnionApplication → DefaultAppContainer: lazily compose dependencies
NavigationGraph → LoadingViewModel: start automatic connection
LoadingViewModel → ConnectionConfigRepository: read saved server/agent configuration
LoadingViewModel → ConnectToAgentUseCase.connect: begin the connection flow
ConnectToAgentUseCase → ConnectionRepositoryImpl.peekPersistedSessionId: inspect saved relay state
ConnectionRepositoryImpl → MemoryGatedSessionStore/SessionStoreImpl: read the persisted session from Room
ConnectToAgentUseCase → AgentConnection: connect with the saved session identifier
AgentConnection → ProtocolParser: parse CONNECTED, onboarding, or failure events
LoadingViewModel → LoadingEvent.ConnectSucceeded: navigate to chat after success
ChatViewModel → ConversationHistoryUseCase: load Room-backed sessions and messages
ChatViewModel → ChatScreen: render restored history and continue the live session
```

`LoadingViewModel` races connection success against an
`OnboardRequired` event, so an agent requiring onboarding does not appear as a
long timeout. `MemoryGatedSessionStore` applies the memory preference while
keeping Room access behind the store boundary.

## Identity

The app's identity is one Ed25519 key pair; its address is `0x` plus the
public key in hex. `KeyManager` (`crypto/`) owns it, keeps it in the
`connectonion_keys_secure` encrypted store, and signs `CONNECT` and every
signed command with it.

### From recovery phrase to key

A phrase derives the key the way the connectonion Python SDK does (1.5.5 and
later, `address._account_key()`), so the same words give the same address here
and in `co` (W16AAPPLE-97):

1. BIP39: the phrase (12, 15, 18, 21 or 24 words, lower case, single-spaced)
   stretches to a 64-byte seed with PBKDF2-HMAC-SHA512, empty passphrase.
2. SLIP-0010 for ed25519 (`Slip10`, a port of the SDK's `derive.py`), down the
   SLIP-0013 path for the account URI `https://oo.openonion.ai`:
   `m/13'/2107038128'/851645955'/1509754396'/381167091'`. Every level is
   hardened; ed25519 has no other kind.
3. The 32 bytes at that path are the Ed25519 seed (RFC 8032 expansion, as
   libsodium and PyNaCl do). The stored secret key is libsodium's 64-byte form:
   that seed, then the public key.

The account URI is a domain separator, not a server address. It must not follow
the relay or backend URL, or one phrase would be a different identity per
environment.

| Path | What it does |
|---|---|
| First launch, Reset Identity | Mints a 12-word phrase and derives the key from it. Both are stored. |
| Import a phrase | Derives the key from it. Both are stored. |
| Import a hex key | 128 hex characters: the secret key as is, refused if its public half is not the one its seed gives. No phrase is involved, and any stored phrase is dropped. |
| Every later launch | Reads the stored key. Nothing is derived again. |

### Identities made before SLIP-0010

Until W16AAPPLE-97 the app took the first 32 bytes of the BIP39 seed as the
Ed25519 seed. An update does not touch a key made that way: launches read the
stored key, so the address does not change unless the user restores. What has
changed is that the phrase stored beside it now derives a different key, so
restoring from those words gives a new, empty identity.

`KeyManager.hasLegacyDerivation()` checks for this the way the SDK's `load()`
sets `legacy_derivation`: there is a stored phrase, and it does not derive the
stored key. When it is true, Settings says so under the wallet address, and
"Back up seed phrase" shows the private key instead of the phrase, since the key
is the only backup that restores this identity. Importing the phrase again moves
the user onto the SLIP-0010 address and clears the warning.

The web client (connectonion-react) still takes `seed[:32]`. Until it moves,
the same phrase gives a different identity on the web from the one on the phone
and in `co`.

## Test architecture

The project uses three complementary test levels:

- Plain JVM tests cover pure domain models, reducers, parsers, mappers, use-case
  orchestration, and ViewModels with hand-written fakes and
  `kotlinx-coroutines-test`.
- Robolectric tests cover code that needs Android framework shadows or a real
  in-memory Room database, including repository, preference, database, and
  keystore-adjacent behavior.
- `androidTest` covers Compose semantics and critical integrated user workflows
  that require instrumentation, such as connection, message send/reply, and
  recovery UI.

Run JVM tests with:

```bash
./gradlew :app:testDebugUnitTest
```

Run instrumented tests on an emulator/device with:

```bash
./gradlew :app:connectedDebugAndroidTest
```

`jacocoTestReport` reports debug JVM-test coverage without requiring a device.
Its filter excludes two different things, and the distinction matters when
reading the percentage:

- **Generated or synthetic classes** — `R`, `BuildConfig`, `Manifest`, Room
  `*_Impl`, Compose singleton/lambda classes, and `di/`. Nobody hand-writes
  these, so counting them would dilute the result without indicating a missing
  test.
- **The hand-written Compose presentation layer** — `ui/**/components/`,
  `ui/theme/`, `ui/navigation/`, the markdown renderer, and the `*ScreenKt` /
  `*ScreenWrapper` classes. This is authored code, and it is excluded
  deliberately: JaCoCo measures whether a recomposition scope *ran*, not whether
  what it rendered was correct, so including it would inflate the number with
  meaningless "executed" hits and bury business-logic coverage underneath. This
  layer is tested a different way — Compose UI tests under `androidTest/`.

**ViewModels are not excluded.** `XViewModel` compiles to its own class even
though it sits in the same package as `XScreen.kt`, so it stays counted, and it
is where the JVM-testable UI logic actually lives.

Robolectric classes without a normal code-source location are included, while
`jdk.internal.*` is excluded for JDK 9+ compatibility.

The package inventory remains in
[README.md § Architecture](../README.md#architecture); this document should be
updated when dependency direction, composition-root registrations, state
transitions, or end-to-end ownership changes.
