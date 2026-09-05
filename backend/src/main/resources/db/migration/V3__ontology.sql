INSERT INTO app.ontology (code, name, geometry_types, description) VALUES
    ('station', '车站', ARRAY['Point', 'MultiPoint', 'Polygon', 'MultiPolygon'], '站级设施，几何存储于 spatial_object'),
    ('stop', '停靠点', ARRAY['Point', 'MultiPoint', 'Polygon', 'MultiPolygon'], '已知的具体停靠设施，不根据站级数据伪造站台'),
    ('route', '运行路径', ARRAY['LineString'], '有方向的运行方案路径，不代表物理轨道拓扑');
