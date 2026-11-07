import { loadActor } from './policy/index.js';

/**
 * Capture Coordinate - the AI reader. POST /v1/vision/read-place { imageBase64 (JPEG, no data: prefix) } -> { name, lat, lng }.
 * The OpenAI key lives ONLY here (OPENAI_API_KEY). The picture is sent to the model for this one request and is never stored.
 * The model may only report what is written in the picture (a name, coordinates); anything it is not sure of is null, and a coordinate
 * outside the valid range is dropped. Without a key the route answers 503 and the apps fall back to their on-device reader.
 */
export const VISION_PROMPT = [
  'You read ONE picture of a place (for example a photo or screenshot with a GPS stamp, a map pin, a gate sign, a house number plate or a handwritten note).',
  'Report ONLY what is actually written or shown in the picture. Never guess or invent.',
  'Reply with JSON only: {"name": string|null, "lat": number|null, "lng": number|null}.',
  '"name" is the full name of the person or household the picture is about (a name tag, a sign, a "Name:" line, a label next to the pin). It is NEVER a street, barangay, city, province, business slogan, app name or date. Use null if there is no clear personal name.',
  '"lat" and "lng" are the latitude and longitude written in the picture, as decimal degrees (convert degrees/minutes/seconds; S and W are negative). Use null if no coordinates are written. Never estimate them from a map.',
].join(' ');

const limitPerHour = (max) => {
  const hits = new Map();
  return (req, res, next) => {
    const now = Date.now();
    const recent = (hits.get(req.personId) ?? []).filter((t) => now - t < 3_600_000);
    if (recent.length >= max) return res.status(429).json({ error: 'Too many picture reads. Try again later.' });
    recent.push(now); hits.set(req.personId, recent); next();
  };
};

export function parseReading(content) {
  let o; try { o = JSON.parse(content); } catch { return { name: null, lat: null, lng: null }; }
  const name = typeof o?.name === 'string' ? o.name.replace(/\s+/g, ' ').trim().slice(0, 120) : '';
  const lat = Number(o?.lat), lng = Number(o?.lng);
  const ok = o?.lat != null && o?.lng != null && Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180 && !(lat === 0 && lng === 0);
  return { name: name && !/\d/.test(name) ? name : null, lat: ok ? lat : null, lng: ok ? lng : null };
}

export function mountVision(app, store, auth, { apiKey = process.env.OPENAI_API_KEY ?? '', fetchImpl = globalThis.fetch, model } = {}) {
  const m = model ?? process.env.OPENAI_VISION_MODEL ?? 'gpt-4o-mini';
  app.post('/v1/vision/read-place', auth, limitPerHour(60), async (req, res) => {
    if (!apiKey) return res.status(503).json({ error: 'The AI reader is not set up on the server yet.' });
    const b64 = String(req.body?.imageBase64 ?? '');
    if (!/^[A-Za-z0-9+/=\s]{100,}$/.test(b64)) return res.status(400).json({ error: 'Send the picture as a base64 JPEG.' });
    try {
      const actor = await loadActor(req.personId, (c, i) => store.get(c, i));
      if (!actor.known) return res.status(403).json({ error: 'Unknown user' });
      const r = await fetchImpl('https://api.openai.com/v1/chat/completions', {
        method: 'POST',
        headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          model: m, temperature: 0, response_format: { type: 'json_object' },
          messages: [
            { role: 'system', content: VISION_PROMPT },
            { role: 'user', content: [{ type: 'text', text: 'Read this picture.' }, { type: 'image_url', image_url: { url: `data:image/jpeg;base64,${b64.replace(/\s/g, '')}`, detail: 'high' } }] },
          ],
        }),
      });
      if (!r.ok) { console.error('vision failed', r.status, (await r.text()).slice(0, 300)); return res.status(502).json({ error: 'The AI reader could not read this picture. Try again.' }); }
      const out = await r.json();
      res.json(parseReading(String(out?.choices?.[0]?.message?.content ?? '')));
    } catch (e) { console.error('vision error', e); res.status(500).json({ error: 'Server error' }); }
  });
}
