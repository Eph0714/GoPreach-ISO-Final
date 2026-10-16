import { loadActor } from './policy/index.js';

/**
 * Voice Notes & Meeting Minutes - the two AI steps. The OpenAI key lives ONLY here (environment variable OPENAI_API_KEY); the app never sees it.
 *
 *   POST /v1/voice/transcribe  { path, language? }                         -> { text, language, durationSeconds }
 *   POST /v1/voice/minutes     { transcript, detail, title, recordedAt }   -> { minutes }
 *
 * - A recording is read from the file store by its path, and only the person who owns it may transcribe it (voice-notes/<personId>/...).
 * - Each person is limited (per hour) so a stuck client cannot run up the bill.
 * - The minutes prompt forbids inventing anything: no participants, decisions, owners or deadlines that the speaker did not say.
 * - Without OPENAI_API_KEY the routes answer 503 "not configured": the app keeps the recording and tells the person it will be processed later.
 */
export const VOICE_PATH_RE = /^voice-notes\/([A-Za-z0-9_-]{1,100})\/[A-Za-z0-9_\-.]{1,120}$/;
const MAX_TRANSCRIPT_CHARS = 120_000;

export const TRANSCRIBE_HINT =
  'Ito ay isang pag-uusap sa Tagalog at English (Taglish). Isulat ang eksaktong sinabi, may tamang bantas at talata. ' +
  'This is a recording in Filipino/Tagalog and English, often mixed. Transcribe exactly what was said with correct punctuation and paragraphs.';

export function minutesPrompt(detail) {
  const length = detail === 'short'
    ? 'Keep it SHORT: a concise record, at most a few lines per section.'
    : 'Make it DETAILED: keep the important explanations, examples and relevant details in each section.';
  return [
    'You turn a transcript of ONE spoken recording into organised meeting-minutes-style notes.',
    'Write in the same language(s) the speaker used (English, Filipino/Tagalog, or a mix). Do not translate.',
    'STRICT RULES:',
    '- Use ONLY what is in the transcript. Never invent participants, attendance, decisions, assignments, deadlines, names or statements.',
    '- Include a responsible person or deadline in an action item ONLY if the speaker stated it.',
    '- Keep the speaker\'s meaning. Do not add opinions or advice of your own.',
    '- If a section has nothing in the recording, leave it out, except "Decisions Made", which must then read exactly: No specific decision recorded.',
    '- Words marked [inaudible] or [unclear] in the transcript stay marked; do not guess them.',
    length,
    'FORMAT (Markdown, only the sections that apply, in this order):',
    '# <Title>',
    '**Date and Time:** <given by the user message>',
    '## Purpose / Overview',
    '## Main Discussion Points   (bullets, grouped by topic, in logical order)',
    '## Decisions Made',
    '## Action Items / Follow-ups   (bullets)',
    '## Important Notes',
    '## Conclusion',
    'Return only the Markdown, no preface.',
  ].join('\n');
}

/** Per-person sliding window, in memory (one process serves the whole API on Hostinger). */
function personLimit({ windowMs, max }) {
  const hits = new Map();
  return (req, res, next) => {
    const now = Date.now();
    const recent = (hits.get(req.personId) ?? []).filter((t) => now - t < windowMs);
    if (recent.length >= max) {
      res.set('Retry-After', String(Math.ceil((recent[0] + windowMs - now) / 1000)));
      return res.status(429).json({ error: 'Too many voice requests. Try again later.' });
    }
    recent.push(now);
    hits.set(req.personId, recent);
    next();
  };
}

export function mountVoice(app, store, auth, { apiKey = process.env.OPENAI_API_KEY ?? '', fetchImpl = globalThis.fetch, transcribeModel, minutesModel } = {}) {
  const tModel = transcribeModel ?? process.env.OPENAI_TRANSCRIBE_MODEL ?? 'whisper-1';
  const mModel = minutesModel ?? process.env.OPENAI_MINUTES_MODEL ?? 'gpt-4o-mini';
  const limit = personLimit({ windowMs: 3_600_000, max: 40 });
  const notConfigured = (res) => res.status(503).json({ error: 'Voice processing is not set up on the server yet. The recording is safe and will be processed later.' });

  app.post('/v1/voice/transcribe', auth, limit, async (req, res) => {
    if (!apiKey) return notConfigured(res);
    const path = String(req.body?.path ?? '');
    const m = VOICE_PATH_RE.exec(path);
    if (!m || m[1] !== req.personId) return res.status(403).json({ error: 'Not your recording' });
    try {
      const actor = await loadActor(req.personId, (c, i) => store.get(c, i));
      if (!actor.known) return res.status(403).json({ error: 'Unknown user' });
      const file = await store.getFileByPath(path);
      if (!file) return res.status(404).json({ error: 'Recording not found on the server. Upload it again.' });

      const form = new FormData();
      form.append('file', new Blob([file.data], { type: file.mime || 'audio/mp4' }), 'recording.m4a');
      form.append('model', tModel);
      form.append('response_format', 'verbose_json');
      form.append('prompt', TRANSCRIBE_HINT);
      const lang = String(req.body?.language ?? '');
      if (/^[a-z]{2}$/.test(lang)) form.append('language', lang);
      const r = await fetchImpl('https://api.openai.com/v1/audio/transcriptions', { method: 'POST', headers: { Authorization: `Bearer ${apiKey}` }, body: form });
      if (!r.ok) {
        console.error('transcription failed', r.status, (await r.text()).slice(0, 300));
        return res.status(502).json({ error: 'The speech service could not transcribe this recording. Try again.' });
      }
      const out = await r.json();
      const text = String(out.text ?? '').trim();
      if (!text) return res.status(422).json({ error: 'No speech was found in this recording.' });
      res.json({ text, language: String(out.language ?? ''), durationSeconds: Math.round(Number(out.duration ?? 0)) });
    } catch (e) {
      console.error('transcribe error', e);
      res.status(500).json({ error: 'Server error' });
    }
  });

  app.post('/v1/voice/minutes', auth, limit, async (req, res) => {
    if (!apiKey) return notConfigured(res);
    const transcript = String(req.body?.transcript ?? '').trim();
    if (!transcript) return res.status(400).json({ error: 'There is no transcript to summarise.' });
    if (transcript.length > MAX_TRANSCRIPT_CHARS) return res.status(413).json({ error: 'This transcript is too long to summarise at once.' });
    const detail = req.body?.detail === 'short' ? 'short' : 'detailed';
    const title = String(req.body?.title ?? '').slice(0, 200);
    const when = String(req.body?.recordedAt ?? '').slice(0, 80);
    try {
      const actor = await loadActor(req.personId, (c, i) => store.get(c, i));
      if (!actor.known) return res.status(403).json({ error: 'Unknown user' });
      const r = await fetchImpl('https://api.openai.com/v1/chat/completions', {
        method: 'POST',
        headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: mModel,
          temperature: 0.2,
          messages: [
            { role: 'system', content: minutesPrompt(detail) },
            { role: 'user', content: `Title (use it if sensible, else make a short one from the content): ${title || '(none)'}\nDate and time of the recording: ${when || '(not given)'}\n\nTRANSCRIPT:\n${transcript}` },
          ],
        }),
      });
      if (!r.ok) {
        console.error('minutes failed', r.status, (await r.text()).slice(0, 300));
        return res.status(502).json({ error: 'The summary service could not write the minutes. Try again.' });
      }
      const out = await r.json();
      const minutes = String(out?.choices?.[0]?.message?.content ?? '').trim();
      if (!minutes) return res.status(502).json({ error: 'The summary service returned nothing. Try again.' });
      res.json({ minutes });
    } catch (e) {
      console.error('minutes error', e);
      res.status(500).json({ error: 'Server error' });
    }
  });
}
