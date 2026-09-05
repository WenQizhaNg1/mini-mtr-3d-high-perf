# Mini MTR Night

Local adaptation of OSM Liberty and the earlier monkaCode dark palette, with
a restrained Mapbox Dark-inspired hierarchy. This is an original local palette,
not an official Mapbox style. Upstream attribution and licenses are preserved
in LICENSE.md and the style metadata.

- Blue-grey land, darker water, muted parks and narrow neutral roads.
- POIs, duplicate transit labels, railway hatching, road shields and direction
  arrows are hidden so the application's transit layers remain prominent.
- Road names appear from zoom 16 with wider spacing. Minor place labels appear
  from zoom 14–15; labels follow the application's global language state.
- Building footprints appear from zoom 15. Opaque, matte 3D buildings rise
  between zoom 16 and 17 using the original tileset heights.
- Local Martin tiles, fonts and sprite endpoints remain unchanged.

The Java API reads this file on each style request. Refresh the map or switch
back to nighttime to load edits. Martin's standalone style endpoint may require
`docker compose restart martin` after edits.
