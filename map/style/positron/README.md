# Positron — local Martin adaptation

Source: https://github.com/openmaptiles/positron-gl-style (master, retrieved 2026-09-05).
Upstream style blob: `7ef98e3f0ea0610094b73bbd481be4164b6c21ec`.

The original basemap palette and layout are retained. Local adaptations:

- OpenMapTiles source uses the local Hong Kong MBTiles subset at `/osm/{z}/{x}/{y}`.
- Glyphs use Martin `/font/{fontstack}/{range}` and installed `Noto Sans CJK SC Regular`, replacing the upstream Metropolis/Noto font stacks.
- Original circle/star SVGs are served through `/sprite/positron`.
- The daytime building layer extrudes from zoom 13 using the tileset's `render_height` and `render_min_height` fields.
- Buildings use opaque cool grey with flat wall shading for a matte plaster appearance. Soft neutral white viewport lighting applies to all daytime extrusions, including trains.
- No MapTiler key or external runtime resource is required.

Day style: `http://127.0.0.1:8081/style/positron`.
Night remains `/style/osm-liberty-dark`. After configuration changes run `docker compose restart martin`.

See LICENSE.md for the upstream design and code licences. The old local OSM Liberty resources are retained for reference and the night sprite source.
