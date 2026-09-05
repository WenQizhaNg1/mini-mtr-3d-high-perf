-- Initial business-layer presets. Basemaps and data sources remain externally managed.
INSERT INTO app.style (code, name, basemap) VALUES
    ('mtr-light', '港铁 · 日间', 'positron'),
    ('mtr-dark', '港铁 · 夜间', 'osm-liberty-dark');

INSERT INTO app.layer (style_id, code, source, source_layer, type, position, minzoom, maxzoom, layout, paint, filter, enabled)
SELECT s.id, item->>'id', item->>'source', item->>'source-layer', item->>'type',
       ordinal::integer, coalesce((item->>'minzoom')::float8, 0), coalesce((item->>'maxzoom')::float8, 24),
       coalesce(item->'layout', '{}'::jsonb), coalesce(item->'paint', '{}'::jsonb), item->'filter', true
FROM app.style s
CROSS JOIN jsonb_array_elements($layers$
[
  {
    "id": "mtr-route-casing",
    "type": "line",
    "source": "mtr",
    "source-layer": "mtr_routes",
    "layout": {
      "line-cap": "round",
      "line-join": "round"
    },
    "paint": {
      "line-color": "#ffffff",
      "line-width": [
        "interpolate",
        [
          "exponential",
          1.4142135623730951
        ],
        [
          "zoom"
        ],
        0,
        0.08984375,
        11,
        4.065863991822647,
        14,
        16.099999999999998,
        18,
        73.6,
        22,
        294.4
      ]
    }
  },
  {
    "id": "mtr-routes",
    "type": "line",
    "source": "mtr",
    "source-layer": "mtr_routes",
    "layout": {
      "line-cap": "round",
      "line-join": "round"
    },
    "paint": {
      "line-color": [
        "coalesce",
        [
          "get",
          "colour"
        ],
        "#64748b"
      ],
      "line-width": [
        "interpolate",
        [
          "exponential",
          1.4142135623730951
        ],
        [
          "zoom"
        ],
        0,
        0.078125,
        11,
        3.5355339059327373,
        14,
        14,
        18,
        64,
        22,
        256
      ]
    }
  },
  {
    "id": "mtr-stations",
    "type": "circle",
    "source": "mtr",
    "source-layer": "mtr_stations",
    "paint": {
      "circle-color": "#ffffff",
      "circle-radius": [
        "interpolate",
        [
          "linear"
        ],
        [
          "zoom"
        ],
        9,
        [
          "case",
          [
            "get",
            "interchange"
          ],
          3,
          2
        ],
        11,
        [
          "case",
          [
            "get",
            "interchange"
          ],
          5,
          3.5
        ],
        14,
        [
          "case",
          [
            "get",
            "interchange"
          ],
          17,
          13
        ],
        18,
        [
          "case",
          [
            "get",
            "interchange"
          ],
          42,
          32
        ],
        22,
        [
          "case",
          [
            "get",
            "interchange"
          ],
          120,
          96
        ]
      ],
      "circle-stroke-color": "#111827",
      "circle-stroke-width": [
        "interpolate",
        [
          "linear"
        ],
        [
          "zoom"
        ],
        9,
        1.5,
        14,
        3,
        18,
        5,
        22,
        8
      ],
      "circle-pitch-alignment": "map",
      "circle-pitch-scale": "map"
    }
  },
  {
    "id": "mtr-trains",
    "type": "fill-extrusion",
    "source": "mtr-trains",
    "paint": {
      "fill-extrusion-color": [
        "case",
        [
          "boolean",
          [
            "feature-state",
            "selected"
          ],
          false
        ],
        "#facc15",
        [
          "interpolate",
          [
            "linear"
          ],
          0.22,
          0,
          [
            "get",
            "colour"
          ],
          1,
          "#ffffff"
        ]
      ],
      "fill-extrusion-height": [
        "get",
        "height"
      ],
      "fill-extrusion-opacity": 1,
      "fill-extrusion-vertical-gradient": true
    }
  },
  {
    "id": "mtr-station-labels",
    "type": "symbol",
    "source": "mtr",
    "source-layer": "mtr_stations",
    "minzoom": 10.5,
    "layout": {
      "text-field": [
        "case",
        [
          "==",
          [
            "global-state",
            "language"
          ],
          "en"
        ],
        [
          "get",
          "name_en"
        ],
        [
          "get",
          "name_zh"
        ]
      ],
      "text-font": [
        "Noto Sans CJK SC Bold"
      ],
      "text-size": [
        "interpolate",
        [
          "linear"
        ],
        [
          "zoom"
        ],
        10.5,
        12,
        14,
        16,
        18,
        18
      ],
      "text-offset": [
        "interpolate",
        [
          "linear"
        ],
        [
          "zoom"
        ],
        10.5,
        [
          "literal",
          [
            0,
            1.1
          ]
        ],
        14,
        [
          "literal",
          [
            0,
            1.6
          ]
        ],
        18,
        [
          "literal",
          [
            0,
            3
          ]
        ],
        22,
        [
          "literal",
          [
            0,
            7.5
          ]
        ]
      ],
      "text-anchor": "top",
      "text-optional": true,
      "text-padding": 3
    },
    "paint": {
      "text-color": "#343b43",
      "text-halo-color": "#ffffff",
      "text-halo-width": 1.5,
      "text-halo-blur": 0
    }
  }
]
$layers$::jsonb) WITH ORDINALITY AS config(item, ordinal)
WHERE s.code = 'mtr-light';

INSERT INTO app.layer (style_id, code, source, source_layer, type, position, minzoom, maxzoom, layout, paint, filter, enabled)
SELECT s.id, item->>'id', item->>'source', item->>'source-layer', item->>'type',
       ordinal::integer, coalesce((item->>'minzoom')::float8, 0), coalesce((item->>'maxzoom')::float8, 24),
       coalesce(item->'layout', '{}'::jsonb), coalesce(item->'paint', '{}'::jsonb), item->'filter', true
FROM app.style s
CROSS JOIN jsonb_array_elements($layers$
[
  {
    "id": "mtr-route-casing",
    "type": "line",
    "source": "mtr",
    "source-layer": "mtr_routes",
    "layout": {
      "line-cap": "round",
      "line-join": "round"
    },
    "paint": {
      "line-color": "#283341",
      "line-width": [
        "interpolate",
        [
          "exponential",
          1.4142135623730951
        ],
        [
          "zoom"
        ],
        0,
        0.08984375,
        11,
        4.065863991822647,
        14,
        16.099999999999998,
        18,
        73.6,
        22,
        294.4
      ]
    }
  },
  {
    "id": "mtr-routes",
    "type": "line",
    "source": "mtr",
    "source-layer": "mtr_routes",
    "layout": {
      "line-cap": "round",
      "line-join": "round"
    },
    "paint": {
      "line-color": [
        "coalesce",
        [
          "get",
          "colour"
        ],
        "#64748b"
      ],
      "line-width": [
        "interpolate",
        [
          "exponential",
          1.4142135623730951
        ],
        [
          "zoom"
        ],
        0,
        0.078125,
        11,
        3.5355339059327373,
        14,
        14,
        18,
        64,
        22,
        256
      ]
    }
  },
  {
    "id": "mtr-stations",
    "type": "circle",
    "source": "mtr",
    "source-layer": "mtr_stations",
    "paint": {
      "circle-color": "#ffffff",
      "circle-radius": [
        "interpolate",
        [
          "linear"
        ],
        [
          "zoom"
        ],
        9,
        [
          "case",
          [
            "get",
            "interchange"
          ],
          3,
          2
        ],
        11,
        [
          "case",
          [
            "get",
            "interchange"
          ],
          5,
          3.5
        ],
        14,
        [
          "case",
          [
            "get",
            "interchange"
          ],
          17,
          13
        ],
        18,
        [
          "case",
          [
            "get",
            "interchange"
          ],
          42,
          32
        ],
        22,
        [
          "case",
          [
            "get",
            "interchange"
          ],
          120,
          96
        ]
      ],
      "circle-stroke-color": "#111827",
      "circle-stroke-width": [
        "interpolate",
        [
          "linear"
        ],
        [
          "zoom"
        ],
        9,
        1.5,
        14,
        3,
        18,
        5,
        22,
        8
      ],
      "circle-pitch-alignment": "map",
      "circle-pitch-scale": "map"
    }
  },
  {
    "id": "mtr-trains",
    "type": "fill-extrusion",
    "source": "mtr-trains",
    "paint": {
      "fill-extrusion-color": [
        "case",
        [
          "boolean",
          [
            "feature-state",
            "selected"
          ],
          false
        ],
        "#facc15",
        [
          "interpolate",
          [
            "linear"
          ],
          0.22,
          0,
          [
            "get",
            "colour"
          ],
          1,
          "#ffffff"
        ]
      ],
      "fill-extrusion-height": [
        "get",
        "height"
      ],
      "fill-extrusion-opacity": 1,
      "fill-extrusion-vertical-gradient": true
    }
  },
  {
    "id": "mtr-station-labels",
    "type": "symbol",
    "source": "mtr",
    "source-layer": "mtr_stations",
    "minzoom": 10.5,
    "layout": {
      "text-field": [
        "case",
        [
          "==",
          [
            "global-state",
            "language"
          ],
          "en"
        ],
        [
          "get",
          "name_en"
        ],
        [
          "get",
          "name_zh"
        ]
      ],
      "text-font": [
        "Noto Sans CJK SC Bold"
      ],
      "text-size": [
        "interpolate",
        [
          "linear"
        ],
        [
          "zoom"
        ],
        10.5,
        12,
        14,
        16,
        18,
        18
      ],
      "text-offset": [
        "interpolate",
        [
          "linear"
        ],
        [
          "zoom"
        ],
        10.5,
        [
          "literal",
          [
            0,
            1.1
          ]
        ],
        14,
        [
          "literal",
          [
            0,
            1.6
          ]
        ],
        18,
        [
          "literal",
          [
            0,
            3
          ]
        ],
        22,
        [
          "literal",
          [
            0,
            7.5
          ]
        ]
      ],
      "text-anchor": "top",
      "text-optional": true,
      "text-padding": 3
    },
    "paint": {
      "text-color": "#f1f5f9",
      "text-halo-color": "#18222f",
      "text-halo-width": 1.5,
      "text-halo-blur": 0
    }
  }
]
$layers$::jsonb) WITH ORDINALITY AS config(item, ordinal)
WHERE s.code = 'mtr-dark';
