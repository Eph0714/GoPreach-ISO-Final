import { classify, addressOf } from './landmarks.js';
import { rateLimit } from './rateLimit.js';

/**
 * Search for places on the Territory Map (Android and website): landmarks, streets and public places from OpenStreetMap by name, category, barangay,
 * municipality or province, through the public Overpass API.
 *
 * GET /v1/map/search?q=<text>&lat=<lat>&lng=<lng>[&radius=<m>]
 *   -> { results: [{ id, name, cat, tier, lat, lng, address?, phone?, website?, hours?, distance? }], interpreted: { categories, text, place } }
 *
 * "church in Roxas", "school Solano", "hospital", "Bayombong Central" and "Rizal Street" all work: category words (church, school, clinic, market,
 * bridge, ...) choose the kind of place, "in / near / at / sa" or a trailing place name narrows it to that barangay / municipality / province, and what
 * is left is matched against the name (partial words are fine). With a position (lat, lng) results around it come first and carry their distance.
 * Nothing is invented: a place that OpenStreetMap does not have is simply not found.
 *
 * One server asks Overpass, answers are kept for an hour and the endpoint is rate limited per person, so the phones and the website never hit the public
 * service directly.
 */
const OVERPASS_URLS = ['https://overpass.openstreetmap.fr/api/interpreter', 'https://overpass-api.de/api/interpreter', 'https://overpass.private.coffee/api/interpreter', 'https://overpass.kumi.systems/api/interpreter'];
const PHOTON_URL = 'https://photon.komoot.io/api/';
const TTL_MS = 3600_000;
const MAX_CACHED = 400;
const AROUND_M = 15_000;

/** Words that name a kind of place -> the OSM tag filters that find it. */
const CATEGORY_WORDS = [
  { words: ['kingdom hall', 'kh'], filters: ['["amenity"="place_of_worship"]["religion"="jehovahs_witness"]', '["amenity"="place_of_worship"]["name"~"Kingdom Hall",i]'] },
  { words: ['church', 'chapel', 'cathedral', 'mosque', 'temple', 'iglesia', 'simbahan', 'worship', 'religious'], filters: ['["amenity"="place_of_worship"]'] },
  { words: ['school', 'elementary', 'high school', 'college', 'university', 'academy', 'campus', 'kindergarten', 'paaralan'], filters: ['["amenity"~"^(school|college|university|kindergarten)$"]'] },
  { words: ['hospital', 'clinic', 'health', 'health center', 'rhu', 'medical', 'doctor', 'ospital', 'pharmacy', 'drugstore', 'botika'], filters: ['["amenity"~"^(hospital|clinic|doctors|pharmacy)$"]', '["healthcare"]'] },
  { words: ['barangay hall', 'brgy hall', 'municipal', 'city hall', 'town hall', 'munisipyo', 'government', 'gov', 'office', 'courthouse', 'capitol'], filters: ['["office"="government"]', '["amenity"~"^(townhall|courthouse|community_centre)$"]'] },
  { words: ['police', 'pulis', 'fire station', 'fire', 'bumbero'], filters: ['["amenity"~"^(police|fire_station)$"]'] },
  { words: ['market', 'palengke', 'mall', 'supermarket', 'grocery', 'store', 'shop', 'tindahan', 'commercial'], filters: ['["amenity"="marketplace"]', '["shop"~"^(supermarket|mall|department_store|convenience|general)$"]'] },
  { words: ['park', 'plaza', 'garden', 'playground', 'stadium', 'gym', 'sports', 'oval', 'basketball'], filters: ['["leisure"~"^(park|garden|playground|stadium|sports_centre|pitch)$"]', '["place"="square"]'] },
  { words: ['bridge', 'tulay'], filters: ['["man_made"="bridge"]', '["bridge"]["highway"]'] },
  { words: ['river', 'creek', 'stream', 'ilog', 'lake'], filters: ['["waterway"~"^(river|stream|canal)$"]', '["natural"="water"]'] },
  { words: ['bank', 'atm'], filters: ['["amenity"~"^(bank|atm)$"]'] },
  { words: ['gas', 'gasoline', 'fuel', 'petron', 'shell', 'caltex', 'gas station'], filters: ['["amenity"="fuel"]'] },
  { words: ['hotel', 'inn', 'resort', 'lodge', 'lodging', 'hostel'], filters: ['["tourism"~"^(hotel|guest_house|hostel|motel|resort)$"]'] },
  { words: ['restaurant', 'eatery', 'food', 'cafe', 'carinderia', 'fast food'], filters: ['["amenity"~"^(restaurant|fast_food|cafe)$"]'] },
  { words: ['bus', 'terminal', 'station', 'jeepney'], filters: ['["amenity"="bus_station"]', '["public_transport"="station"]'] },
  { words: ['library', 'museum', 'attraction', 'landmark', 'monument', 'historic'], filters: ['["amenity"="library"]', '["tourism"~"^(museum|attraction|viewpoint)$"]', '["historic"]'] },
];
/** The result kinds (see landmarks.js classify) each category group above stands for, in the same order. */
const CATEGORY_CATS = [['kingdom_hall'], ['worship', 'kingdom_hall'], ['school'], ['health'], ['government'], ['police', 'fire'], ['market', 'shop'], ['park'], ['bridge'], ['water'], ['bank'], ['fuel'], ['landmark'], ['landmark'], ['transport'], ['landmark']];
const FILLER = new Set(['in', 'at', 'near', 'nearby', 'around', 'of', 'the', 'a', 'sa', 'malapit', 'brgy', 'barangay', 'brgy.', 'city', 'municipality', 'town', 'to']);
const PLACE_MARKERS = new Set(['in', 'at', 'near', 'sa', 'around', 'malapit']);

const escapeRegex = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const norm = (s) => String(s ?? '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9 ]+/g, ' ').replace(/\s+/g, ' ').trim();

/** What a search text means: { categories: [wordsGroup], text, place }. */
export function interpret(q) {
  let rest = ` ${norm(q)} `;
  const categories = [];
  // longest category words first, so "kingdom hall" wins over "hall" and "health center" over "health"
  const all = CATEGORY_WORDS.flatMap((c, i) => c.words.map((w) => ({ w, i }))).sort((a, b) => b.w.length - a.w.length);
  for (const { w, i } of all) {
    const needle = ` ${w} `;
    if (rest.includes(needle)) { if (!categories.includes(i)) categories.push(i); rest = rest.replace(needle, ' '); }
  }
  const tokens = rest.trim().split(' ').filter(Boolean);
  // "<something> in <place>": everything after the marker is the place
  const at = tokens.findIndex((t) => PLACE_MARKERS.has(t));
  let text = '', place = '';
  if (at >= 0) {
    text = tokens.slice(0, at).filter((t) => !FILLER.has(t)).join(' ');
    place = tokens.slice(at + 1).filter((t) => !FILLER.has(t)).join(' ');
  } else {
    text = tokens.filter((t) => !FILLER.has(t)).join(' ');
    // a category word plus a name ("church Roxas", "school Solano"): the name is a place
    if (categories.length && text) { place = text; text = ''; }
  }
  return { categories, text, place };
}

const NAMED_TAGS = '^(amenity|shop|tourism|leisure|office|historic|man_made|waterway|natural|healthcare|public_transport|place)$';

/** The Overpass query for an interpreted search. */
export function buildQuery({ categories, text, place }, lat, lng, radius = AROUND_M) {
  const has = Number.isFinite(lat) && Number.isFinite(lng);
  const around = has ? `(around:${Math.round(radius)},${lat},${lng})` : '';
  const head = place
    ? `area["name"~"${escapeRegex(place)}",i]["boundary"="administrative"]["admin_level"~"^(4|5|6|7|8|9|10)$"]->.a;`
    : '';
  const scope = place ? `(area.a)${has ? `(around:${Math.round(Math.max(radius, 60_000))},${lat},${lng})` : ''}` : around;
  const nameFilter = text ? `["name"~"${escapeRegex(text)}",i]` : '["name"]';
  const parts = [];
  if (categories.length) {
    for (const i of categories) for (const f of CATEGORY_WORDS[i].filters) parts.push(`nwr${nameFilter}${f}${scope};`);
  } else if (text) {
    parts.push(`nwr${nameFilter}[~"${NAMED_TAGS}"~"."]${scope};`);
    parts.push(`way["highway"]${nameFilter}${scope};`);
  } else if (place) {
    parts.push(`nwr["name"][~"${NAMED_TAGS}"~"."]${scope};`);
  }
  if (!parts.length) return null;
  return `[out:json][timeout:25];${head}(${parts.join('')});out center tags 120;`;
}

const SENTINEL = { cat: 'landmark', tier: 3 };

/** Photon (an OpenStreetMap geocoder) answers as GeoJSON; this turns its features into the shape of Overpass elements so one code path ranks and returns them. */
export function photonToElements(json) {
  const out = [];
  for (const f of json?.features ?? []) {
    const p = f.properties ?? {}, c = f.geometry?.coordinates;
    if (!p.name || !Array.isArray(c)) continue;
    const tags = { name: p.name, [p.osm_key]: p.osm_value };
    if (p.street) tags['addr:street'] = p.street;
    if (p.housenumber) tags['addr:housenumber'] = p.housenumber;
    if (p.district) tags['addr:suburb'] = p.district;
    if (p.city) tags['addr:city'] = p.city;
    if (p.county) tags['addr:municipality'] = p.county;
    if (p.state) tags['addr:province'] = p.state;
    out.push({ type: String(p.osm_type ?? 'N').toLowerCase().startsWith('w') ? 'way' : 'node', id: p.osm_id, lat: c[1], lon: c[0], tags });
  }
  return out;
}

export function toResults(elements, lat, lng) {
  const seen = new Set(), out = [];
  const has = Number.isFinite(lat) && Number.isFinite(lng);
  for (const el of elements ?? []) {
    const tags = el.tags;
    if (!tags?.name) continue;
    const plat = el.lat ?? el.center?.lat, plng = el.lon ?? el.center?.lon;
    if (typeof plat !== 'number' || typeof plng !== 'number') continue;
    const kind = tags.highway ? { cat: 'road', tier: 3 } : classify(tags) ?? SENTINEL;
    // the same named place mapped as a point and as a building (within 250 m of each other), or a road in many pieces: one result
    const nameKey = `${norm(tags.name)}|${kind.cat}`;
    if (kind.cat === 'road' ? seen.has(nameKey) : out.some((o) => o.nameKey === nameKey && distanceM(o.lat, o.lng, plat, plng) < 250)) continue;
    seen.add(nameKey);
    const r = { id: `${el.type[0]}${el.id}`, name: tags.name, cat: kind.cat, tier: kind.tier, lat: Math.round(plat * 1e6) / 1e6, lng: Math.round(plng * 1e6) / 1e6 };
    const address = addressOf(tags);
    if (address) r.address = address;
    const phone = tags.phone || tags['contact:phone'];
    if (phone) r.phone = String(phone).slice(0, 60);
    const site = tags.website || tags['contact:website'];
    if (site && /^https?:\/\//i.test(site)) r.website = String(site).slice(0, 200);
    if (tags.opening_hours) r.hours = String(tags.opening_hours).slice(0, 120);
    if (has) r.distance = Math.round(distanceM(lat, lng, plat, plng));
    Object.defineProperty(r, 'nameKey', { value: nameKey, enumerable: false });
    out.push(r);
  }
  return out;
}

function distanceM(a, b, c, d) {
  const R = Math.PI / 180, x = Math.sin((c - a) * R / 2), y = Math.sin((d - b) * R / 2);
  return 2 * 6371008.8 * Math.asin(Math.min(1, Math.sqrt(x * x + Math.cos(a * R) * Math.cos(c * R) * y * y)));
}

/** Relevance: an exact or starting name match first, then nearer places, then the rest. */
export function rank(results, text, categories = []) {
  const t = norm(text);
  const wanted = new Set(categories.flatMap((i) => CATEGORY_CATS[i] ?? []));
  const score = (r) => {
    const n = norm(r.name);
    // when a kind of place was asked for, places of another kind (a suburb named like the church) come after the ones of that kind
    return (wanted.size && !wanted.has(r.cat) ? 4e9 : 0) + (t && n === t ? 0 : t && n.startsWith(t) ? 1 : t && n.split(' ').some((w) => w.startsWith(t)) ? 2 : 3) * 1e9 + (r.distance ?? 5e8) + (r.cat === 'road' ? 1e8 : 0);
  };
  return results.map((r) => [score(r), r]).sort((a, b) => a[0] - b[0]).map((x) => x[1]);
}

export class PlaceSearchService {
  constructor({ fetchImpl = globalThis.fetch, now = () => Date.now(), urls = OVERPASS_URLS } = {}) {
    this.fetchImpl = fetchImpl; this.now = now; this.urls = urls; this.cache = new Map(); this.pending = new Map();
  }

  async ask(query) {
    let last;
    for (const url of this.urls) {
      try {
        const res = await this.fetchImpl(url, {
          method: 'POST',
          headers: { 'content-type': 'application/x-www-form-urlencoded', 'user-agent': 'GoPreach/1.0 (territory map search)' },
          body: `data=${encodeURIComponent(query)}`,
          signal: AbortSignal.timeout(30_000),
        });
        if (!res.ok) throw new Error(`Overpass answered ${res.status}`);
        return (await res.json()).elements ?? [];
      } catch (e) { last = e; }
    }
    throw last;
  }

  /** The backup: Photon, when every Overpass server is unreachable. It cannot filter by category, so it is asked the person's own words. */
  async askPhoton(q, lat, lng) {
    const u = new URL(PHOTON_URL);
    u.searchParams.set('q', q); u.searchParams.set('limit', '25'); u.searchParams.set('lang', 'en');
    if (Number.isFinite(lat) && Number.isFinite(lng)) { u.searchParams.set('lat', String(lat)); u.searchParams.set('lon', String(lng)); }
    const res = await this.fetchImpl(u.toString(), { headers: { 'user-agent': 'GoPreach/1.0 (territory map search)' }, signal: AbortSignal.timeout(15_000) });
    if (!res.ok) throw new Error(`Photon answered ${res.status}`);
    return photonToElements(await res.json());
  }

  async search(q, lat, lng) {
    const meaning = interpret(q);
    const query = buildQuery(meaning, lat, lng);
    if (!query) return { results: [], interpreted: meaning };
    // positions are rounded (about 1 km) so people standing close together share an answer
    const key = `${query.length}|${norm(q)}|${Number.isFinite(lat) ? lat.toFixed(2) : ''}|${Number.isFinite(lng) ? lng.toFixed(2) : ''}`;
    const hit = this.cache.get(key), now = this.now();
    if (hit && now - hit.at < TTL_MS) return { results: hit.results, interpreted: meaning };
    if (!this.pending.has(key)) {
      this.pending.set(key, this.ask(query).catch(async (err) => { try { return await this.askPhoton(q, lat, lng); } catch { throw err; } }).then((els) => {
        const results = rank(toResults(els, lat, lng), meaning.text || meaning.place, meaning.categories).slice(0, 40);
        this.cache.set(key, { at: this.now(), results });
        while (this.cache.size > MAX_CACHED) this.cache.delete(this.cache.keys().next().value);
        return results;
      }).finally(() => this.pending.delete(key)));
    }
    return { results: await this.pending.get(key), interpreted: meaning };
  }
}

export function mountPlaceSearch(app, auth, service = new PlaceSearchService(), limits = {}) {
  const limit = rateLimit({ windowMs: 60_000, max: 40, key: (req) => req.personId ?? req.ip, ...limits });
  app.get('/v1/map/search', auth, limit, async (req, res) => {
    const q = String(req.query.q ?? '').trim();
    if (q.length < 2 || q.length > 80) return res.status(400).json({ error: 'Type at least two letters to search' });
    const lat = req.query.lat === undefined ? NaN : Number(req.query.lat), lng = req.query.lng === undefined ? NaN : Number(req.query.lng);
    if ((Number.isFinite(lat) && Math.abs(lat) > 85) || (Number.isFinite(lng) && Math.abs(lng) > 180)) return res.status(400).json({ error: 'Bad position' });
    // without a position only a named place ("church in Roxas") can be searched: otherwise the search would cover the whole country
    if (!(Number.isFinite(lat) && Number.isFinite(lng)) && !interpret(q).place) return res.status(400).json({ error: 'Open the map around the place you want first, or say where: for example "church in Roxas"', results: [] });
    try {
      res.set('Cache-Control', 'private, max-age=600');
      res.json(await service.search(q, lat, lng));
    } catch (err) {
      console.error('place search failed', err?.message);
      // say what the map data service answered (a status, never anything private), so a refusal can be told from a slow answer
      const why = /^Overpass answered d+/.test(String(err?.message)) ? ` (${err.message})` : /timeout|abort/i.test(String(err?.message)) ? ' (the map data service took too long)' : '';
      res.status(502).json({ error: `Search is not available right now${why}. Try again in a moment.`, results: [] });
    }
  });
}
