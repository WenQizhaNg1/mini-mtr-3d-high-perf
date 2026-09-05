# Hong Kong MTR OSM data audit

Snapshot: `hk-mtr-2026-09-04.osm`

Audit date: 2026-09-04

## Verdict

The snapshot is suitable as the source for a normalized Hong Kong MTR network
and for city-scale map rendering. Its route geometry is structurally complete,
but it must not be consumed as one display-ready line per relation. A small
normalization layer is required for station codes, route selection, terminal
trimming, and coarse underground geometry.

## Structural checks

| Check | Result |
| --- | ---: |
| XML parsed successfully | Pass |
| Target MTR line refs present | 10 / 10 |
| `route=subway` relations | 54 |
| Relations with `from`, `to`, `colour`, and PTv2 tags | 54 / 54 |
| Unique travel ways referenced by routes | 848 |
| Adjacent travel-way endpoint gaps | 0 |
| Missing way node references | 0 |
| Missing relation member references | 0 |
| Unique stop-position nodes | 240 |
| Existing project station codes matched by stop name | 98 / 98 |

All 54 route relations have their unroled travel ways in a continuous order.
This makes relation-member order a substantially safer basis for route building
than the old proximity and Dijkstra repair strategy.

## Route coverage

| Line | Route relations | Notes |
| --- | ---: | --- |
| ISL | 2 | Full service in both directions |
| TWL | 2 | Full service in both directions |
| KTL | 7 | Full routes and several short-turn patterns |
| TKL | 6 | Po Lam, LOHAS Park, and LOHAS shuttle patterns |
| EAL | 23 | Lo Wu, Lok Ma Chau, Racecourse, and short-turn patterns |
| TML | 4 | Full line plus two Hung Hom short patterns |
| TCL | 4 | Full line plus Tsing Yi short patterns |
| AEL | 2 | Full service in both directions |
| DRL | 2 | Full service in both directions |
| SIL | 2 | Full service in both directions |

No matching `route_master=subway` relation is present. The project must group
route patterns by `ref` itself.

## Required normalization

### Station refs

Every existing project station can be matched by English station name, but nine
stop-position nodes have incomplete or inconsistent `ref` tags:

| Station | OSM issue | Affected stop nodes |
| --- | --- | ---: |
| AsiaWorld-Expo | Missing `ref=AWE` | 1 |
| East Tsim Sha Tsui | Missing `ref=ETS` | 2 |
| Tiu Keng Leng | Missing `ref=TIK` | 4 |
| Sung Wong Toi | Uses `SWT`; project/API code is `SUW` | 2 |

Normalization should prefer an explicit project override keyed by OSM object ID
or stable station identity. Name matching is acceptable for the initial import,
but should not become the permanent identifier.

One Tiu Keng Leng stop in relation `13352937` is not literally a node of that
relation's travel ways. It is only 0.55 m from the corresponding route node, so
snapping it to the route is safe.

### Route selection

Not every relation should automatically be treated as a currently active
full-service pattern. Relations `6582965` and `6582966` are Hung Hom short TML
patterns inherited from the former West Rail Line. Together they still reference
two `railway=disused` and two `railway=razed` tunnel ways. They should be excluded
from the initial active pattern set until their current operational meaning is
confirmed.

For the first implementation, use the 24 full end-to-end directional patterns:
two for each normal line, plus separate Po Lam/LOHAS Park patterns for TKL and
Lo Wu/Lok Ma Chau patterns for EAL. Add short turns and Racecourse variants only
after timetable behavior is defined.

The OSM EAL relations correctly distinguish normal routes from Racecourse
variants. The current application instead puts `RAC` in the single main EAL
station sequence, which makes every generated East Rail trip pass Racecourse.
The normalized model should preserve the OSM route-pattern distinction.

### Terminal trimming

Some route relations include turnback or approach track before the first stop or
after the final stop. Route geometry must be trimmed to the first and last stop
positions before it is used for train interpolation.

For example, the raw Po Lam TKL directional geometries measure 10.51 km and
9.76 km. After trimming terminal tails, they measure approximately 9.54 km and
9.56 km. The apparent directional mismatch is therefore not a broken route.

### Underground geometry detail

The referenced geometry contains 54 node-to-node segments of at least 500 m and
20 segments of at least 1 km. The longest is 2.601 km. These are predominantly
underground ways with sparse vertices.

This is acceptable at the project's city-scale zoom levels, but close zooms may
show visibly straight tunnel sections. Do not silently invent topology to fix
them. If more visual detail is needed, keep curated display geometry as a
separate derived layer while preserving OSM ways as the canonical topology.

## Recommended import rules

1. Preserve all raw OSM IDs, versions, timestamps, member order, roles, and tags.
2. Build route paths from relation member order; reject rather than bridge a real
   endpoint gap automatically.
3. Snap stops only within a small documented tolerance.
4. Trim paths to their first and last stop positions.
5. Keep physical track segments separate from route patterns.
6. Maintain a small reviewed override table for station codes and confirmed OSM
   defects.
7. Publish display geometry as MVT, but provide uncut route-pattern geometry for
   train interpolation.

## Visual validation

The structural and semantic audit passes. The normalized routes and stations
have also been overlaid on the MapLibre OSM basemap and checked at network
scale. Route-specific terminal tracks align correctly. Sparse underground OSM
segments remain intentionally visible as documented above.
