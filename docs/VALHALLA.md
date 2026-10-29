# Self-hosted routing (Valhalla) for Driving and Walking Mode

The app and the website both ask a Valhalla server for the route. Out of the box they use the public FOSSGIS server, which is shared and fair-use with no availability guarantee. This guide puts routing on your own small server.

## What you need
- A VPS with 2 vCPU, 4 GB RAM and 20 GB disk (Ubuntu 22.04/24.04). The Philippines extract is small; building the tiles needs the RAM, serving needs far less.
- A domain or subdomain, e.g. `routing.sinaglahinv.com`, pointing at the VPS (an A record).
- Docker installed (`curl -fsSL https://get.docker.com | sh`).

## 1. Run Valhalla with the Philippines map
```bash
mkdir -p ~/valhalla/custom_files && cd ~/valhalla
wget -O custom_files/philippines-latest.osm.pbf https://download.geofabrik.de/asia/philippines-latest.osm.pbf
docker run -d --name valhalla --restart unless-stopped -p 127.0.0.1:8002:8002 \
  -v $PWD/custom_files:/custom_files \
  -e server_threads=2 -e use_tiles_ignore_pbf=True -e build_elevation=False \
  -e build_admins=True -e build_time_zones=True \
  ghcr.io/gis-ops/docker-valhalla/valhalla:latest
docker logs -f valhalla      # wait until it says the service is listening (tile build takes several minutes)
```
It listens on `127.0.0.1:8002` only; the next step puts HTTPS in front.

## 2. HTTPS with Caddy (automatic certificate)
```bash
apt install -y caddy
cat > /etc/caddy/Caddyfile <<'EOF'
routing.sinaglahinv.com {
    reverse_proxy 127.0.0.1:8002
}
EOF
systemctl reload caddy
```
Open ports 80 and 443 in the VPS firewall. Do not open 8002.

## 3. Check it
```bash
curl -s https://routing.sinaglahinv.com/status
curl -s -X POST https://routing.sinaglahinv.com/route -H 'Content-Type: application/json' \
  -d '{"locations":[{"lat":16.4023,"lon":120.5960},{"lat":16.4100,"lon":120.6000}],"costing":"auto"}' | head -c 300
```
The first prints the server version; the second must return a `trip` with a `summary`. Repeat with `"costing":"pedestrian"` for walking.

## 4. Point GoPreach at it
- **Android:** add to `local.properties`: `routingUrl=https://routing.sinaglahinv.com` and rebuild/release. If your server is down, the app falls back to the public server by itself (but not when your server says there is no route).
- **Website:** in `.env` set `GOPREACH_ROUTING_URL=https://routing.sinaglahinv.com`, then `php artisan config:clear`.

## 5. Keep it healthy
- Updating the map (every few months): download a fresh `.pbf` into `custom_files`, delete the old tiles folder in `custom_files`, `docker restart valhalla`.
- Watch it: a free uptime monitor on `https://routing.sinaglahinv.com/status`.
- Valhalla has no login. It is read-only routing on public map data, but if you see abuse add a Caddy rate limit or restrict by IP; keep the server updated (`apt upgrade`).
- Traffic and speed limits are not part of this: the ETA stays an estimate for normal driving.
