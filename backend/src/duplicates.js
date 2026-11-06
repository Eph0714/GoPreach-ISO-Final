import { loadActor } from './policy/index.js';
import { recordVisibility } from './ministryScope.js';

/**
 * Possible duplicate persons for a Searching / Return Visit / Bible Study record that is about to be enrolled (POST /v1/duplicates/check).
 * ONE implementation for the website and the Android app. Nothing is ever merged, changed or deleted: this only reports what may already exist.
 *
 * A record is reported only when BOTH hold:
 *   1. the person's name (first and family name together, in any order, ignoring middle names/initials, suffixes, spacing, punctuation, case and
 *      small spelling mistakes) is similar enough to the new one; a surname alone never matches;
 *   2. there is geographic evidence: the same Province + Municipality + Barangay, or coordinates within the configured radius.
 * Confidence: "strong" = similar name + same Province/Municipality/Barangay + nearby coordinates;
 * "possible" = similar name + same administrative address with no coordinates to compare, or nearby coordinates with a different/unknown address.
 *
 * The thresholds live in the `appSettings` document "duplicateMatching" ({ nameThreshold, radiusMeters, ... }); these are the starting values.
 */
export const DEFAULTS = Object.freeze({
  nameThreshold: 0.85,        // minimum combined-name similarity (0..1; a value above 1 is read as a percentage)
  radiusMeters: 50,           // "nearby" coordinates
  addressWithoutCoordinates: true,   // same Province, Municipality and Barangay is enough evidence when one side has no coordinates
  nearbyWithDifferentAddress: true,  // nearby coordinates are enough evidence even if the typed address differs (a wrong or old address)
  // Most records only hold a name and a place of origin: when the new person or the existing record has NO location at all (no coordinates, no Province / Municipality /
  // Barangay), there is nothing to compare, so a name that is the same (or nearly) is reported as a possible duplicate to check. When both have a location, it must agree.
  nameOnlyWhenNoLocation: true,
  nameOnlyThreshold: 0.88,
  maxResults: 10,
});

const SUFFIXES = new Set(['jr', 'sr', 'ii', 'iii', 'iv', 'v']);
const PARTICLES = new Set(['de', 'del', 'dela', 'delos', 'la', 'los', 'las', 'dos', 'van', 'von', 'ng', 'mga']);

const fold = (s) => String(s ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

/** The name's meaningful words: no punctuation, middle initials, suffixes or particles ("de la", "del"). */
export function nameTokens(name) {
  return fold(name).replace(/[^a-z0-9\s]/g, ' ').split(/\s+/).filter((t) => t.length > 1 && !SUFFIXES.has(t) && !PARTICLES.has(t));
}

/** A rough sound key, so "Ramos"/"Ramoz" and "Estefanie"/"Stephanie" style spellings compare as the same word. */
function soundKey(token) {
  let t = token.replace(/ph/g, 'f').replace(/ck|q/g, 'k').replace(/c(?=[aourl])/g, 'k').replace(/c/g, 's').replace(/z/g, 's').replace(/v/g, 'b').replace(/j/g, 'h').replace(/y/g, 'i');
  t = t[0] + t.slice(1).replace(/[aeiou]/g, '');
  return t.replace(/(.)\1+/g, '$1');
}

function levenshtein(a, b) {
  if (a === b) return 0;
  if (!a.length) return b.length;
  if (!b.length) return a.length;
  let prev2 = [];
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) {
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) cur[j] = Math.min(cur[j], prev2[j - 2] + 1);   // two neighbouring letters swapped
    }
    prev2 = prev;
    prev = cur;
  }
  return prev[b.length];
}
const ratio = (a, b) => (a.length || b.length ? 1 - levenshtein(a, b) / Math.max(a.length, b.length) : 0);

/**
 * Similarity (0..1) of two full names, first and family name evaluated together. Null when either has fewer than two meaningful words
 * (then the name cannot be judged, so nothing is reported).
 */
export function nameSimilarity(a, b) {
  const x = nameTokens(a), y = nameTokens(b);
  if (x.length < 2 || y.length < 2) return null;
  const sx = [...x].sort(), sy = [...y].sort();
  let best = ratio(sx.join(' '), sy.join(' '));
  // the given order and the "first last" / "last first" readings of the first and last word
  const fl = (t) => t[0] + ' ' + t[t.length - 1], lf = (t) => t[t.length - 1] + ' ' + t[0];
  best = Math.max(best, ratio(fl(x), fl(y)), ratio(fl(x), lf(y)));
  // the same words that sound alike
  if (sx.map(soundKey).join(' ') === sy.map(soundKey).join(' ')) best = Math.max(best, 0.9);
  return Math.min(1, best);
}

const ADDRESS_WORDS = /\b(city|municipality|municipal|of|the|brgy|bgy|barangay|brg|poblacion|pob)\b/g;
/** An administrative name without capitals, accents, punctuation, spacing differences and the words "City of", "Municipality of", "Brgy." ... */
export function normalizePlace(s) {
  const t = fold(s).replace(/\(.*?\)/g, ' ').replace(ADDRESS_WORDS, ' ').replace(/[^a-z0-9]/g, '');
  return t;
}

/** Same Province, then the Municipality within it, then the Barangay within that: all three must be given and equal. */
export function sameAddress(a, b) {
  const p = [normalizePlace(a.province), normalizePlace(b.province)];
  const m = [normalizePlace(a.cityMunicipality), normalizePlace(b.cityMunicipality)];
  const g = [normalizePlace(a.barangay), normalizePlace(b.barangay)];
  if (![...p, ...m, ...g].every(Boolean)) return false;
  return p[0] === p[1] && m[0] === m[1] && g[0] === g[1];
}

export function haversineMeters(lat1, lng1, lat2, lng2) {
  const rad = (d) => (d * Math.PI) / 180;
  const a = Math.sin(rad(lat2 - lat1) / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(rad(lng2 - lng1) / 2) ** 2;
  return 2 * 6371000 * Math.asin(Math.sqrt(a));
}

const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const hasPoint = (lat, lng) => num(lat) != null && num(lng) != null && !(lat === 0 && lng === 0);

export function settingsFrom(doc) {
  const s = { ...DEFAULTS };
  const d = doc ?? {};
  if (num(d.nameThreshold) != null) s.nameThreshold = d.nameThreshold > 1 ? d.nameThreshold / 100 : d.nameThreshold;
  s.nameThreshold = Math.min(1, Math.max(0.5, s.nameThreshold));
  if (num(d.radiusMeters) != null && d.radiusMeters > 0) s.radiusMeters = Math.min(5000, d.radiusMeters);
  if (typeof d.addressWithoutCoordinates === 'boolean') s.addressWithoutCoordinates = d.addressWithoutCoordinates;
  if (typeof d.nearbyWithDifferentAddress === 'boolean') s.nearbyWithDifferentAddress = d.nearbyWithDifferentAddress;
  if (typeof d.nameOnlyWhenNoLocation === 'boolean') s.nameOnlyWhenNoLocation = d.nameOnlyWhenNoLocation;
  if (num(d.nameOnlyThreshold) != null) s.nameOnlyThreshold = Math.min(1, Math.max(0.5, d.nameOnlyThreshold > 1 ? d.nameOnlyThreshold / 100 : d.nameOnlyThreshold));
  if (num(d.maxResults) != null && d.maxResults >= 1) s.maxResults = Math.min(50, Math.floor(d.maxResults));
  return s;
}

/**
 * Compares the new person [input] ({ name, province, cityMunicipality, barangay, lat, lng }) with one stored record. Returns the match
 * ({ confidence, similarity, distanceMeters, reasons }) or null when the record is not a possible duplicate.
 */
export function compare(input, record, settings = DEFAULTS) {
  const similarity = nameSimilarity(input.name, record.name);
  if (similarity == null || similarity < settings.nameThreshold) return null;
  const coords = hasPoint(input.lat, input.lng) && hasPoint(record.gpsLat, record.gpsLng);
  const distance = coords ? haversineMeters(input.lat, input.lng, record.gpsLat, record.gpsLng) : null;
  const nearby = coords && distance <= settings.radiusMeters;
  const address = sameAddress(input, { province: record.province, cityMunicipality: record.cityMunicipality, barangay: record.barangay });

  let confidence = null;
  if (address && nearby) confidence = 'strong';
  else if (nearby && settings.nearbyWithDifferentAddress) confidence = 'possible';
  else if (address && !coords && settings.addressWithoutCoordinates) confidence = 'possible';
  // one of the two has no location at all: the name is the only thing there is to compare
  const known = (o) => hasPoint(o.lat, o.lng) || [o.province, o.cityMunicipality, o.barangay].some((v) => normalizePlace(v));
  const noLocation = !known(input) || !known({ lat: record.gpsLat, lng: record.gpsLng, province: record.province, cityMunicipality: record.cityMunicipality, barangay: record.barangay });
  if (!confidence && noLocation && settings.nameOnlyWhenNoLocation && similarity >= settings.nameOnlyThreshold) confidence = 'possible';
  if (!confidence) return null;

  const reasons = [similarity >= 0.995 ? 'Same name' : 'Similar combined name'];
  if (address) reasons.push('matching Province, Municipality and Barangay');
  if (nearby) reasons.push(distance < 1 ? 'identical coordinates' : 'nearby coordinates');
  if (!address && nearby) reasons.push('the typed address differs or is incomplete');
  if (address && !coords) reasons.push('no coordinates to compare');
  if (!address && !nearby && noLocation) reasons.push('one of the records has no saved location to compare');
  return { confidence, similarity, distanceMeters: distance == null ? null : Math.round(distance), reasons };
}

const fullName = (p) => [p?.firstName, p?.lastName].filter(Boolean).join(' ').trim();

export function mountDuplicates(app, store, auth) {
  /** POST { name, province?, cityMunicipality?, barangay?, lat?, lng?, excludeId?, congregationId? } -> { matches: [...], settings } */
  app.post('/v1/duplicates/check', auth, async (req, res) => {
    const b = req.body ?? {};
    if (typeof b.name !== 'string' || !b.name.trim() || b.name.length > 200) return res.status(400).json({ error: 'name is required' });
    for (const k of ['province', 'cityMunicipality', 'barangay']) if (b[k] != null && typeof b[k] !== 'string') return res.status(400).json({ error: `${k} must be text` });
    const lat = b.lat == null ? null : Number(b.lat), lng = b.lng == null ? null : Number(b.lng);
    if ((lat != null && !(Math.abs(lat) <= 90)) || (lng != null && !(Math.abs(lng) <= 180))) return res.status(400).json({ error: 'Enter a valid coordinate' });
    try {
      const cache = new Map();
      const get = (c, i) => { const key = c + '/' + i; if (!cache.has(key)) cache.set(key, store.get(c, i)); return cache.get(key); };
      const actor = await loadActor(req.personId, get, (c, cong) => store.list(c, cong));
      if (!actor.known) return res.status(403).json({ error: 'Unknown user' });
      // Only the caller's own congregation (the Super Admin may name one, or none for all). A Circuit Overseer has no congregation of their own to enroll into.
      const congregation = actor.isSuperAdmin ? (typeof b.congregationId === 'string' && b.congregationId ? b.congregationId : undefined) : actor.congregationId;
      if (!actor.isSuperAdmin && !congregation) return res.json({ matches: [] });

      const cfg = settingsFrom((await get('appSettings', 'duplicateMatching'))?.data);
      // the same visibility the record lists use (see ministryScope.js)
      const { visible } = await recordVisibility(store, get, actor, congregation);

      const input = { name: b.name, province: b.province, cityMunicipality: b.cityMunicipality, barangay: b.barangay, lat, lng };
      const found = [];
      for (const row of await store.list('interestedPeople', congregation)) {
        const r = row.data ?? {};
        if (row.id === b.excludeId || (r.status ?? 'ACTIVE') !== 'ACTIVE' || !visible(r)) continue;
        const m = compare(input, r, cfg);
        if (m) found.push({ row, r, m });
      }
      found.sort((x, y) => (x.m.confidence === y.m.confidence ? 0 : x.m.confidence === 'strong' ? -1 : 1) || y.m.similarity - x.m.similarity || (x.m.distanceMeters ?? 1e9) - (y.m.distanceMeters ?? 1e9));
      const matches = [];
      for (const { row, r, m } of found.slice(0, cfg.maxResults)) {
        const owner = r.publisherPersonId ? (await get('people', r.publisherPersonId))?.data : null;
        matches.push({
          id: row.id, name: r.name ?? '', stage: r.pipelineStage ?? 'SEARCHING', congregationId: r.congregationId ?? null,
          province: r.province ?? null, cityMunicipality: r.cityMunicipality ?? null, barangay: r.barangay ?? null,
          lat: num(r.gpsLat), lng: num(r.gpsLng), publisherPersonId: r.publisherPersonId || null, publisherName: owner ? fullName(owner) : null,
          confidence: m.confidence, similarity: Math.round(m.similarity * 100), distanceMeters: m.distanceMeters, reasons: m.reasons,
        });
      }
      res.json({ matches, settings: { nameThreshold: cfg.nameThreshold, radiusMeters: cfg.radiusMeters } });
    } catch (e) {
      console.error('duplicate check failed', e);
      res.status(500).json({ error: 'Server error' });
    }
  });
}
