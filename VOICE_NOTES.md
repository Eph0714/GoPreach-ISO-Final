# Voice Notes & Meeting Minutes

Publisher App > menu > TOOLS > **Voice Notes & Minutes** (every signed-in role; each person sees only their own notes).

## What it does
- **Record Voice**: record (pause / resume / stop, timer, live level bars), preview, rename, **Save Draft**, **Transcribe Recording**, **Generate Meeting Minutes**, **Re-record** (with confirmation). The microphone permission is asked only when Record is tapped; a red "microphone is on" indicator shows while recording and recording pauses when the app leaves the screen. Mono AAC, 48 kbit/s (about 0.36 MB a minute), 60 minutes at most.
- **My Voice Notes**: search (title, transcript, minutes, category), filters (status, date range, category), sort (newest, oldest, title, last modified), Read / Listen / Rename / Delete with confirmation.
- **A note's page**: *Original Audio* (play, pause, stop, seek, 0.75x to 2x), *Transcript* (find, copy, share, edit), *Meeting Minutes* (short or detailed, find, edit, regenerate, restore the previous version, show the transcript beside it, Listen to the minutes with a computer voice).
- **Offline first**: a recording is saved on the phone at once. Transcribe / Generate are queued while offline or while the server is not ready and run when possible ("Pending Transcriptions", status *Waiting for Connection*). Interrupted work is recovered when the module opens. A step that really fails shows its reason and a **Retry** button; the recording is never lost.

## Server setup (required, one time)
The AI steps run on the Hostinger backend; the key never reaches the app.
1. Deploy the updated `backend/` (new `src/voice.js`, changes in `files.js`, `policy/rules.js`, `app.js`).
2. In the server's `.env` add `OPENAI_API_KEY=...` (optional: `OPENAI_TRANSCRIBE_MODEL`, default `whisper-1`; `OPENAI_MINUTES_MODEL`, default `gpt-4o-mini`). Without the key the routes answer 503 and recordings simply wait.

## Routes
- `PUT /v1/files?path=voice-notes/<personId>/<noteId>.m4a`: upload; only to your own folder; up to 24 MB.
- `POST /v1/voice/transcribe {path, language?}` -> `{text, language, durationSeconds}`: only the owner; 40 requests an hour per person.
- `POST /v1/voice/minutes {transcript, detail, title, recordedAt}` -> `{minutes}`: the prompt forbids inventing participants, decisions, owners or deadlines.
- Notes sync as the `voiceNotes` collection: readable and writable only by `publisherPersonId` (plus the platform-wide Super-Admin exemption that applies to every collection).

## Privacy and deletion
Audio stays in the app's private folder. It is uploaded only when the person asks to transcribe, and it goes to the server's private file store and from there to OpenAI for transcription. **Delete** removes the recording, transcript and minutes from the phone and the server (if the phone is offline the note stays and says so, so nothing is half-deleted). The text of a transcript and the minutes is also sent to OpenAI to write the minutes.

## Not in this first pass
PDF export / printing, TXT/PDF/audio file export (copy and share as text are there), server-generated speech (the minutes are read by the phone's own voice, so nothing to cache), live highlighting of the sentence being read, a date-range picker (7 / 30 days instead).
