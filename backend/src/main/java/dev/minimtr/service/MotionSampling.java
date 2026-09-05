package dev.minimtr.service;

import dev.minimtr.model.entity.*;
import dev.minimtr.model.vo.TrainVo;
import java.time.Instant;
import java.util.*;

/** Pure sampling of a supplied schedule. Realtime observations never enter this calculation. */
public final class MotionSampling {
    private MotionSampling() {}
    public static double distance(List<MotionNode> nodes,double at) {
        if(at<=nodes.getFirst().at()) return nodes.getFirst().distance();
        for(int i=1;i<nodes.size();i++) {
            var a=nodes.get(i-1); var b=nodes.get(i);
            if(at<=b.at()) return a.distance()+(b.distance()-a.distance())*(at-a.at())/(b.at()-a.at());
        }
        return nodes.getLast().distance();
    }
    public static TrainVo sample(PlannedRun run,TransitNetwork.Path path,Instant time) {
        var nodes=run.nodes();
        double at=time.toEpochMilli(),d=distance(nodes,at);
        var a=nodes.getFirst();
        int index=1;
        while(index<nodes.size()-1 && nodes.get(index).at()<=at) a=nodes.get(index++);
        var b=nodes.get(index);
        var next=nodes.stream().filter(n -> n.distance()>d).findFirst().orElse(nodes.getLast());
        var position=path.geometry().locateMetric(d);
        double end=Math.min(at+3000,nodes.getLast().at());
        var times=new TreeSet<Double>(); times.add(at); times.add(end);
        nodes.stream().filter(n -> n.at()>at && n.at()<end).forEach(n -> times.add(n.at()));
        for(int i=1;i<nodes.size();i++) {
            var from=nodes.get(i-1); var to=nodes.get(i);
            if(to.at()<=at || from.at()>=end || to.distance()==from.distance()) continue;
            for(double vertex:path.geometry().metricVertices()) if(vertex>from.distance() && vertex<to.distance()) {
                double t=from.at()+(vertex-from.distance())/(to.distance()-from.distance())*(to.at()-from.at());
                if(t>at && t<end) times.add(t);
            }
        }
        var motion=times.stream().map(t -> {
            var p=path.geometry().locateMetric(distance(nodes,t));
            return new TrainVo.MotionPoint(t,p.lng(),p.lat(),p.bearing());
        }).toList();
        return new TrainVo(run.id(),path.lineId(),path.code(),path.colour(),position.lng(),position.lat(),position.bearing(),
                a.distance()==b.distance()?"dwell":"running",a.station(),next.station(),path.stops().getLast().code(),0,
                Instant.ofEpochMilli((long)a.at()),Instant.ofEpochMilli((long)next.at()),"simulated",motion);
    }
}
