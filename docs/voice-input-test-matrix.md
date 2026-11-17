# Dictation test matrix

Dictation puts the transcript in the composer; nothing is sent until the user
taps Send. The phone's speech recogniser is used when it works, otherwise the
app records and the audio is transcribed by the server.

**Auto** checks run in the regression suite (test class in brackets).
**Manual** checks need a real phone, not an emulator.

## Composer
- [Auto] Transcript fills the field, Send lights, nothing is sent (`InputBarDictationComposeTest`)
- [Auto] Text typed before dictating is kept; partials replace each other (`DictationMergeTest`)
- [Auto] A hand edit mid-dictation is never overwritten (`DictationMergeTest`)
- [Auto] Cancel discards the transcript; attachments survive a dictation (`InputBarDictationComposeTest`)

## Controls
- [Auto] Tapping the waveform finishes; Preparing… offers only Cancel (`InputBarDictationComposeTest`)
- [Auto] The mic is hidden while preparing or transcribing (`InputBarDictationComposeTest`)
- [Auto] Stop stays usable while dictating (`InputBarInterjectComposeTest`)

## Recogniser or fallback
- [Auto] Recogniser when available, recorder when not; a silent recogniser falls back once and is remembered (`ChatViewModelTest`)
- [Auto] The request names the user's languages, most preferred first (`SpeechRecognizerIntentTest`)
- [Manual] With Google's recogniser, live text appears after at most a short Preparing…
- [Manual] With no usable recogniser, the fallback notice shows and text arrives after stopping

## Server transcription
- [Auto] Text arrives in the field; one token refresh on a 401; errors and empty results fail without sending (`VoiceTranscriptionServiceTest`, `ChatViewModelTest`)
- [Auto] Under a second: "Recording too short. Try again." and nothing uploaded (`ChatViewModelTest`)
- [Manual] Transcription is accurate, including a mixed-language sentence

## Permission
- [Manual] First tap: rationale, then the system dialog, before recording
- [Manual] Denied: the message points to Settings and typing still works

## Device
- [Manual] API 26 and 34+, light and dark: the meter renders and stays legible
- [Manual] TalkBack reads "Dictate a message", "Cancel dictation", "Finish dictation"
- [Manual] Leaving the app mid-dictation releases the microphone
