/**
 * Landmarks for the Territory Map (Android and website): named places from OpenStreetMap inside a box, through the public Overpass API.
 *
 * GET /v1/map/landmarks?s=<south>&w=<west>&n=<north>&e=<east>
 *   -> { landmarks: [{ id, name, cat, tier, lat, lng, address?, phone?, website?, hours? }], partial: boolean }
 *
 * One server asks Overpass, so the phones and the website never hit its rate limit one by one and never need its CORS rules. The world is cut into
 * fixed zoom-13 tiles (about 5 km): a request is answered from the tiles it covers, each fetched once, kept for a week and shared by everybody. A tile
 * Overpass could not answer is not kept (the next request asks again after a short pause) and the answer says `partial: true`.
 *
 * `tier` says how important a place is, so both maps show the same thing at the same zoom: 1 = from zoom 12, 2 = from zoom 14, 3 = from zoom 16.
 */
export const TILE_ZOOM = 13;
export const MAX_TILES_PER_REQUEST = 16;
const TTL_MS = 7 * 24 * 3600_000;
const RETRY_AFTER_FAIL_MS = 60_000;
const MAX_TILES_CACHED = 800;
const OVERPASS_URLS = ['https://overpass.openstreetmap.fr/api/interpreter', 'https://overpass-api.de/api/interpreter'];

const tileX = (lng) => Math.floor(((lng + 180) / 360) * 2 ** TILE_ZOOM);
const tileY = (lat) => {
  const r = (lat * Math.PI) / 180;
  return Math.floor(((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * 2 ** TILE_ZOOM);
};
const lngOf = (x) => (x / 2 ** TILE_ZOOM) * 360 - 180;
const latOf = (y) => (Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / 2 ** TILE_ZOOM))) * 180) / Math.PI;

/** The tile columns and rows a box covers. */
export function tilesFor(s, w, n, e) {
  // a box that ends exactly on a tile edge (what the clients send for whole tiles) must not count the next tile as well
  const EDGE = 1e-7;
  const x0 = tileX(w), x1 = Math.max(x0, tileX(e - EDGE)), y0 = tileY(n), y1 = Math.max(y0, tileY(s + EDGE));
  const tiles = [];
  for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) tiles.push({ x, y });
  return tiles;
}

const QUERY_BODY = [
  'nwr["name"]["amenity"~"^(place_of_worship|school|college|university|kindergarten|hospital|clinic|doctors|pharmacy|marketplace|townhall|community_centre|police|fire_station|fuel|bank|bus_station|library|courthouse)$"];',
  'nwr["name"]["office"="government"];',
  'nwr["name"]["shop"~"^(supermarket|mall|department_store|convenience)$"];',
  'nwr["name"]["leisure"~"^(park|garden|stadium|sports_centre|playground)$"];',
  'nwr["name"]["place"="square"];',
  'nwr["name"]["tourism"~"^(attraction|museum|viewpoint|hotel)$"];',
  'nwr["name"]["historic"];',
  'way["name"]["bridge"]["bridge"!="no"]["highway"];',
  'nwr["name"]["man_made"="bridge"];',
  'way["name"]["waterway"~"^(river|canal)$"];',
  'nwr["name"]["natural"="water"];',
].join('');

/** What a place is: its category and importance tier (see the top of the file), or null when it is not worth a pin. */
export function classify(tags) {
  const a = tags.amenity, name = tags.name ?? '';
  if (a === 'place_of_worship') {
    const kh = tags.religion === 'jehovahs_witness' || /kingdom hall/i.test(name);
    return kh ? { cat: 'kingdom_hall', tier: 1 } : { cat: 'worship', tier: 2 };
  }
  if (a === 'hospital') return { cat: 'health', tier: 1 };
  if (a === 'townhall' || a === 'courthouse') return { cat: 'government', tier: 1 };
  if (a === 'university' || a === 'college') return { cat: 'school', tier: 1 };
  if (a === 'bus_station') return { cat: 'transport', tier: 1 };
  if (a === 'school') return { cat: 'school', tier: 2 };
  if (a === 'kindergarten') return { cat: 'school', tier: 3 };
  if (a === 'clinic') return { cat: 'health', tier: 2 };
  if (a === 'doctors' || a === 'pharmacy') return { cat: 'health', tier: 3 };
  if (a === 'marketplace') return { cat: 'market', tier: 2 };
  if (a === 'police') return { cat: 'police', tier: 2 };
  if (a === 'fire_station') return { cat: 'fire', tier: 2 };
  if (a === 'fuel') return { cat: 'fuel', tier: 2 };
  if (a === 'bank') return { cat: 'bank', tier: 2 };
  if (a === 'community_centre') return { cat: 'government', tier: 2 };
  if (a === 'library') return { cat: 'landmark', tier: 3 };
  if (tags.office === 'government') return { cat: 'government', tier: /barangay|brgy/i.test(name) ? 2 : 1 };
  if (tags.shop === 'mall' || tags.shop === 'department_store') return { cat: 'market', tier: 1 };
  if (tags.shop === 'supermarket') return { cat: 'market', tier: 2 };
  if (tags.shop === 'convenience') return { cat: 'shop', tier: 3 };
  if (tags.leisure === 'stadium') return { cat: 'park', tier: 1 };
  if (tags.leisure === 'park' || tags.leisure === 'garden' || tags.leisure === 'sports_centre') return { cat: 'park', tier: 2 };
  if (tags.leisure === 'playground') return { cat: 'park', tier: 3 };
  if (tags.place === 'square') return { cat: 'park', tier: 2 };
  if (tags.tourism === 'hotel') return { cat: 'landmark', tier: 3 };
  if (tags.tourism) return { cat: 'landmark', tier: 2 };
  if (tags.historic) return { cat: 'landmark', tier: 2 };
  if (tags.man_made === 'bridge' || (tags.bridge && tags.bridge !== 'no')) return { cat: 'bridge', tier: 1 };
  if (tags.waterway) return { cat: 'water', tier: 1 };
  if (tags.natural === 'water') return { cat: 'water', tier: 2 };
  return null;
}

/** "Rizal St, Brgy. Poblacion, Bagabag" from the address tags that exist. */
export function addressOf(tags) {
  const street = [tags['addr:housenumber'], tags['addr:street']].filter(Boolean).join(' ');
  const parts = [street, tags['addr:barangay'] || tags['addr:suburb'] || tags['addr:neighbourhood'], tags['addr:city'] || tags['addr:municipality'] || tags['addr:town'], tags['addr:province']].filter(Boolean);
  return parts.length ? parts.join(', ') : undefined;
}

/** Turns Overpass `elements` (out center tags) into landmarks. Rivers and ponds come as many pieces: one pin per name and tile. */
export function toLandmarks(elements) {
  const out = [], seenLine = new Set();
  for (const el of elements ?? []) {
    const tags = el.tags;
    if (!tags?.name) continue;
    const lat = el.lat ?? el.center?.lat, lng = el.lon ?? el.center?.lon;
    if (typeof lat !== 'number' || typeof lng !== 'number') continue;
    const kind = classify(tags);
    if (!kind) continue;
    if (kind.cat === 'water') {
      const key = `${tags.name}|${kind.tier}`;
      if (seenLine.has(key)) continue;
      seenLine.add(key);
    }
    const lm = { id: `${el.type[0]}${el.id}`, name: tags.name, cat: kind.cat, tier: kind.tier, lat: Math.round(lat * 1e6) / 1e6, lng: Math.round(lng * 1e6) / 1e6 };
    const address = addressOf(tags);
    if (address) lm.address = address;
    const phone = tags.phone || tags['contact:phone'];
    if (phone) lm.phone = String(phone).slice(0, 60);
    const website = tags.website || tags['contact:website'];
    if (website && /^https?:\/\//i.test(website)) lm.website = String(website).slice(0, 200);
    if (tags.opening_hours) lm.hours = String(tags.opening_hours).slice(0, 120);
    out.push(lm);
  }
  return out;
}

export class LandmarkService {
  constructor({ fetchImpl = globalThis.fetch, now = () => Date.now(), urls = OVERPASS_URLS } = {}) {
    this.fetchImpl = fetchImpl;
    this.now = now;
    this.urls = urls;
    this.tiles = new Map(); // "x/y" -> { at, landmarks }
    this.pending = new Map(); // "x/y" -> Promise
    this.failedAt = new Map(); // "x/y" -> time of the last failure
  }

  async fetchTile({ x, y }) {
    const s = latOf(y + 1), n = latOf(y), w = lngOf(x), e = lngOf(x + 1);
    const query = `[out:json][timeout:25];(${QUERY_BODY});out center tags 1500;`.replace('[out:json]', `[out:json][bbox:${s},${w},${n},${e}]`);
    let lastError;
    for (const url of this.urls) {
      try {
        const res = await this.fetchImpl(url, {
          method: 'POST',
          headers: { 'content-type': 'application/x-www-form-urlencoded', 'user-agent': 'GoPreach/1.0 (territory map landmarks)' },
          body: `data=${encodeURIComponent(query)}`,
          signal: AbortSignal.timeout(30_000),
        });
        if (!res.ok) throw new Error(`Overpass answered ${res.status}`);
        const json = await res.json();
        return toLandmarks(json.elements);
      } catch (err) {
        lastError = err;
      }
    }
    throw lastError;
  }

  /** The landmarks of one tile, or null when it could not be loaded. */
  async tile(t) {
    const key = `${t.x}/${t.y}`, now = this.now();
    const hit = this.tiles.get(key);
    if (hit && now - hit.at < TTL_MS) { this.tiles.delete(key); this.tiles.set(key, hit); return hit.landmarks; }
    if (now - (this.failedAt.get(key) ?? -Infinity) < RETRY_AFTER_FAIL_MS) return null;
    if (!this.pending.has(key)) {
      this.pending.set(key, this.fetchTile(t)
        .then((landmarks) => {
          this.tiles.set(key, { at: this.now(), landmarks });
          this.failedAt.delete(key);
          while (this.tiles.size > MAX_TILES_CACHED) this.tiles.delete(this.tiles.keys().next().value);
          return landmarks;
        })
        .catch((err) => { console.warn('landmark tile failed', key, err?.message); this.failedAt.set(key, this.now()); return null; })
        .finally(() => this.pending.delete(key)));
    }
    return this.pending.get(key);
  }

  async inBox(s, w, n, e) {
    const tiles = tilesFor(s, w, n, e);
    const lists = [];
    let partial = false;
    // two at a time: the public service allows few parallel requests per address
    for (let i = 0; i < tiles.length; i += 2) {
      const got = await Promise.all(tiles.slice(i, i + 2).map((t) => this.tile(t)));
      for (const g of got) g ? lists.push(g) : (partial = true);
    }
    const seen = new Set(), landmarks = [];
    for (const l of lists.flat()) {
      if (seen.has(l.id) || l.lat < s || l.lat > n || l.lng < w || l.lng > e) continue;
      seen.add(l.id);
      landmarks.push(l);
    }
    return { landmarks, partial };
  }
}

export function mountLandmarks(app, auth, service = new LandmarkService()) {
  app.get('/v1/map/landmarks', auth, async (req, res) => {
    const [s, w, n, e] = ['s', 'w', 'n', 'e'].map((k) => Number(req.query[k]));
    if (![s, w, n, e].every(Number.isFinite) || s >= n || w >= e || s < -85 || n > 85 || w < -180 || e > 180) {
      return res.status(400).json({ error: 'Give the box as s, w, n, e (south, west, north, east)' });
    }
    if (tilesFor(s, w, n, e).length > MAX_TILES_PER_REQUEST) return res.status(400).json({ error: 'That area is too large: zoom in' });
    try {
      res.set('Cache-Control', 'private, max-age=3600');
      res.json(await service.inBox(s, w, n, e));
    } catch (err) {
      console.error('landmarks failed', err);
      res.status(500).json({ error: 'Server error' });
    }
  });
}
