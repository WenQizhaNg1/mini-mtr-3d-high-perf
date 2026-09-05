package dev.minimtr.service;

import dev.minimtr.model.dto.TransitConfig;
import dev.minimtr.repo.DatasetRepo;
import dev.minimtr.repo.TransitRepo;
import java.util.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import static dev.minimtr.service.DatasetParser.require;

@Service
public class TransitMappingService {
    private final DatasetRepo datasets;
    private final TransitRepo repo;
    public TransitMappingService(DatasetRepo datasets,TransitRepo repo) { this.datasets=datasets; this.repo=repo; }

    public void rebuild(long operator,TransitConfig.Mapping mapping) {
        var routes = features(mapping.routesDataset());
        var stations = features(mapping.stationsDataset());
        var stationIds = new LinkedHashMap<String,Long>();
        var stationCodes = new HashSet<String>();
        var lineIds = new LinkedHashMap<String,Long>();
        var lineColours = new HashMap<String,String>();
        var routeIds = new ArrayList<Long>();
        var codes = new HashSet<String>();
        repo.clearBindings(operator);
        for (var entry : stations.entrySet()) {
            var feature = entry.getValue();
            require("Point".equals(feature.path("geometry").path("type").asText()), "Station " + entry.getKey() + " must be a Point");
            String code = field(feature,mapping.stationFields(),"code",entry.getKey());
            require(stationCodes.add(code),"Duplicate station code: " + code);
            String name = field(feature,mapping.stationFields(),"name",code);
            stationIds.put(entry.getKey(),repo.station(operator,object(feature),code,name,
                    field(feature,mapping.stationFields(),"nameEn",name)));
        }
        for (var pattern : mapping.patterns()) {
            require(pattern!=null,"Pattern cannot be null");
            require(pattern.code()!=null && !pattern.code().isBlank() && codes.add(pattern.code()),"Duplicate or missing pattern code");
            var feature = routes.get(pattern.featureKey());
            require(feature!=null,"Pattern " + pattern.code() + ": missing source feature " + pattern.featureKey());
            var path = path(feature,pattern.reversed(),pattern.code());
            String line = field(feature,mapping.routeFields(),"line",null);
            String colour = field(feature,mapping.routeFields(),"colour","#168b92");
            require(colour.matches("#[0-9a-fA-F]{6}"),"Invalid colour for line " + line);
            require(!lineColours.containsKey(line) || lineColours.get(line).equalsIgnoreCase(colour),"Inconsistent colour for line " + line);
            lineColours.put(line,colour);
            long lineId = lineIds.computeIfAbsent(line,key -> repo.line(operator,line,field(feature,mapping.routeFields(),"lineName",line),colour));
            long routeId = repo.route(object(feature),lineId,pattern);
            routeIds.add(routeId);
            require(pattern.stops()!=null && pattern.stops().size()>=2,"Pattern " + pattern.code() + " needs at least two stops");
            double previous = -1;
            for (int i=0;i<pattern.stops().size();i++) {
                var stop = pattern.stops().get(i);
                require(stop!=null,"Missing stop in pattern "+pattern.code());
                var station = stations.get(stop.stationKey());
                require(station!=null,"Pattern " + pattern.code() + " stop " + i + ": unknown station " + stop.stationKey());
                var point = station.path("geometry").path("coordinates");
                RoutePath.Projection projection;
                if (stop.fraction()!=null) {
                    require(Double.isFinite(stop.fraction()) && stop.fraction()>=0 && stop.fraction()<=1,"Invalid stop fraction");
                    projection = new RoutePath.Projection(stop.fraction(),path.metricDistance(stop.fraction()),0);
                } else {
                    double after = previous;
                    var candidates = path.project(point.get(0).asDouble(),point.get(1).asDouble()).stream()
                            .filter(p -> p.distanceMeters()>after).toList();
                    require(!candidates.isEmpty(),"Pattern " + pattern.code() + " stop " + i + ": stops run against path direction");
                    require(candidates.size()==1,"Pattern " + pattern.code() + " stop " + i + ": ambiguous location; select a path fraction");
                    projection=candidates.getFirst();
                    require(projection.offsetMeters()<=mapping.snapToleranceMeters(),"Pattern " + pattern.code() + " stop " + i
                            + ": station is " + Math.round(projection.offsetMeters()) + " m from path");
                }
                require(projection.distanceMeters()>previous,"Pattern " + pattern.code() + " stop " + i + ": non-increasing distance");
                previous=projection.distanceMeters();
                repo.stop(routeId,stationIds.get(stop.stationKey()),lineId,i,projection);
            }
        }
        for (var entry : stations.entrySet()) {
            String memberships = field(entry.getValue(),mapping.stationFields(),"lines","");
            for (String line : memberships.split(",")) if (lineIds.containsKey(line.trim()))
                repo.membership(lineIds.get(line.trim()),stationIds.get(entry.getKey()));
        }
        repo.prune(operator,routeIds,stationIds.values(),lineIds.values());
    }
    private Map<String,JsonNode> features(String code) {
        require(code!=null && datasets.find(code,true)!=null,"Unknown dataset: " + code);
        var result=new LinkedHashMap<String,JsonNode>();
        for (var feature:datasets.features(code)) result.put(feature.path("id").asText(),feature);
        return result;
    }
    private RoutePath path(JsonNode feature,boolean reversed,String code) {
        try {
            var geometry=new GeoJsonReader().read(feature.path("geometry").toString());
            require(geometry instanceof LineString,"Pattern " + code + ": provide a connected LineString");
            if(reversed) geometry=geometry.reverse();
            return new RoutePath(new WKBWriter().write(geometry));
        } catch(org.locationtech.jts.io.ParseException | IllegalArgumentException error) {
            throw DatasetParser.bad("Pattern " + code + ": " + error.getMessage());
        }
    }
    private long object(JsonNode feature) { return Long.parseLong(feature.path("objectId").asText()); }
    private String field(JsonNode feature,Map<String,String> mapping,String target,String fallback) {
        String source=mapping.get(target);
        if(source==null || source.isBlank()) {
            require(fallback!=null,"Map the " + target + " field");
            return fallback;
        }
        var value=feature.path("properties").get(source);
        require(value!=null && !value.isNull() && !value.asText().isBlank(),"Feature " + feature.path("id").asText() + ": missing field " + source);
        return value.asText();
    }
}
