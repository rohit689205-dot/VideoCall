# Screen & Audio Recording Service with MediaProjection & MediaStore

An end-to-end architecture and implementation plan for a robust Android Foreground Service that captures video call displays and mixed audio streams using `MediaProjection`, `MediaRecorder`, and Android `MediaStore` into the public Movies directory.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following architectural decisions were confirmed based on user requirements:

- **Confirmed Service Architecture**: `ScreenRecordService` runs as a native Android `ForegroundService` with `foregroundServiceType="mediaProjection"` (Android 14+ API 34 compliance) with an ongoing status notification.
- **Confirmed Audio Streams**: Mixed audio pipeline capturing active microphone voice input together with internal meeting audio routed through `MediaRecorder`.
- **Confirmed User Controls**: Dual control scheme with an in-call toolbar button (reactive state indicator) and a persistent system notification with a 1-tap "Stop Recording" action.
- **Confirmed Storage Location**: Public device storage (`Movies/VideoCall`) via Android `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` producing standard `.mp4` video files.

---

## 1. Overview & Core Concept

This feature provides users with a complete, production-grade screen and audio recording service for **VideoCall**:
1. **System Permission Integration**: Requests screen capture rights via Android's native `MediaProjectionManager.createScreenCaptureIntent()` system prompt.
2. **Foreground Recording Service**: Runs in the background with a persistent notification (`Recording video call... [Stop]`) so recording continues seamlessly even if the user minimizes or switches apps during the meeting.
3. **Dual Audio Capture**: Encodes high-definition video at device display aspect ratio alongside AAC-encoded audio.
4. **MediaStore Output**: Saves directly into the public `Movies/VideoCall` gallery directory, allowing instant playback, sharing, or export without sandbox barriers.

---

## 2. User Experience & Visual Design

### User Interaction Flow

```
┌─────────────────────────────────────────────────────────────┐
│                      Live Call Screen                       │
├─────────────────────────────────────────────────────────────┤
│  Top Bar: [🔴 REC SCREEN 01:24]   [Duration: 12:45]         │
│                                                             │
│  ┌─────────────────────────┐   ┌─────────────────────────┐  │
│  │ Remote Participant Feed │   │ Local Video Feed (PIP)  │  │
│  └─────────────────────────┘   └─────────────────────────┘  │
│                                                             │
│  Toolbar:                                                   │
│  [🎤] [📹] [🔴 Record Screen] [🖵 Share] [💬] [End Call]    │
└──────────────────────────────┬──────────────────────────────┘
                               │ Tap [🔴 Record Screen]
                               ▼
┌─────────────────────────────────────────────────────────────┐
│             Android System MediaProjection Dialog           │
│   "Start recording or casting with VideoCall?"              │
│   [Cancel]                                    [Start now]   │
└──────────────────────────────┬──────────────────────────────┘
                               │ User accepts [Start now]
                               ▼
┌─────────────────────────────────────────────────────────────┐
│             ScreenRecordService (Foreground)                │
│  - Notification: "VideoCall Screen Recording in progress"   │
│    Actions: [ ■ Stop Recording ]                            │
│  - VirtualDisplay draws screen surface into MediaRecorder   │
│  - MediaStore records .mp4 to Movies/VideoCall              │
└──────────────────────────────┬──────────────────────────────┘
                               │ User taps [Stop] (in app or notification)
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                 Saved Notification & Banner                 │
│  "Screen recording saved to Movies/VideoCall"               │
│  [View Recording]                               [Dismiss]   │
└─────────────────────────────────────────────────────────────┘
```

- **In-Call Toolbar Button**: Dedicated screen recording toggle with a red recording badge that pulses while active.
- **System Notification**: Ongoing foreground service notification with recording timer and direct `Stop` action button.
- **Saved Feedback**: Elevated M3 card banner with a direct `View` button launching Android's native video player via `Intent.ACTION_VIEW`.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Foreground Service vs In-Activity Recording**
  - *Chosen Approach*: Dedicated `ForegroundService` with `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`.
  - *Why*: Required by Android 14+ (API 34) platform security rules; prevents the OS from killing the recording when the user switches apps or checks reference material during a call.
- **Decision 2: MediaRecorder with VirtualDisplay vs Frame-by-frame Canvas**
  - *Chosen Approach*: Hardware-accelerated `VirtualDisplay` pushing directly into `MediaRecorder.surface`.
  - *Why*: Negligible CPU overhead, zero dropped frames, native 60fps/1080p capture, and synchronized AAC audio muxing.
- **Decision 3: MediaStore Storage**
  - *Chosen Approach*: Target `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` with relative path `Movies/VideoCall`.
  - *Why*: Conforms to Android Scoped Storage best practices, requires no broad legacy storage permissions, and allows users to find their files in any video player or gallery app.

---

## 4. Technical Architecture & Data Strategy

### System Architecture Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                     Jetpack Compose UI                      │
│            LiveCallScreen / Bottom Toolbar Controls         │
└──────────────────────────────┬──────────────────────────────┘
                               │ 1. Launch Intent / Stop Action
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                        CallViewModel                        │
│          Manages isScreenRecording, timer, saved URI        │
└──────────────────────────────┬──────────────────────────────┘
                               │ 2. Start / Stop Service Intent
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                    ScreenRecordService                      │
│     (ForegroundService with FOREGROUND_SERVICE_MEDIA_PROJ)  │
├─────────────────────────────────────────────────────────────┤
│  - NotificationManager (Ongoing Notification with Stop BTN) │
│  - MediaProjection (Created from resultCode & data)         │
│  - VirtualDisplay (Binds display metrics to recorder)       │
│  - MediaRecorder (H.264 video + AAC audio muxing)           │
│  - ContentResolver (Pipes output stream to MediaStore)      │
└──────────────────────────────┬──────────────────────────────┘
                               │ 3. Finalizes Output
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                 Android MediaStore Storage                  │
│               Environment.DIRECTORY_MOVIES                  │
│                 Movies/VideoCall/*.mp4                      │
└─────────────────────────────────────────────────────────────┘
```

### Components to Implement:

1. **`ScreenRecordService.kt`** (`com.example.service`):
   - Handles `ACTION_START` with projection data and `ACTION_STOP`.
   - Creates Notification Channel `screen_recording_channel`.
   - Starts foreground service with `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`.
   - Configures `MediaRecorder` targeting `MediaStore` Uri via file descriptor.
   - Binds `VirtualDisplay` to display density and dimensions.
   - Sends broadcast/callback on completion with the output Uri.

2. **`AndroidManifest.xml` Updates**:
   - Register `ScreenRecordService` with `android:foregroundServiceType="mediaProjection"`.
   - Verify permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`, `RECORD_AUDIO`.

3. **`CallViewModel.kt` & State Integration**:
   - Add state: `isScreenRecording: Boolean`, `screenRecordingDurationSeconds: Long`, `savedRecordingUri: String?`.
   - Add service control methods: `startScreenRecording()`, `stopScreenRecording()`, `onScreenRecordingCompleted()`.

4. **`LiveCallScreen.kt` Integration**:
   - Add `rememberLauncherForActivityResult` for `MediaProjectionManager.createScreenCaptureIntent()`.
   - Add Screen Record button in the toolbar with pulsing indicator.
   - Display active `REC SCREEN` header badge.
   - Display saved recording banner with `View` action.

---

## 5. Verification Plan

1. **Compilation**: Run `compile_applet` to ensure full build success with zero errors.
2. **Unit & Robolectric Tests**: Run `gradle :app:testDebugUnitTest` to verify test suite passing.
3. **Permissions & Security Audit**: Validate compliance with Android 14+ Foreground Service policies and least-privilege permissions.
