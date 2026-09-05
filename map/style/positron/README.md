# Positron — local Martin adaptation

Source: https://github.com/openmaptiles/positron-gl-style (master, retrieved 2026-09-05).
Upstream style blob: `7ef98e3f0ea0610094b73bbd481be4164b6c21ec`.

The original 50 layers, colours and layout are retained. Resource adaptations:

- OpenMapTiles source uses the existing local China MBTiles at `/osm/{z}/{x}/{y}`.
- Glyphs use Martin `/font/{fontstack}/{range}` and installed `Noto Sans CJK SC Regular`, replacing the upstream Metropolis/Noto font stacks.
- Original circle/star SVGs are served through `/sprite/positron`.
- No MapTiler key or external runtime resource is required.

Day style: `http://127.0.0.1:8081/style/positron`.
Night remains `/style/osm-liberty-dark`. After configuration changes run `docker compose restart martin`.

See LICENSE.md for the upstream design and code licences. The old local OSM Liberty resources are retained for reference and the night sprite source.
