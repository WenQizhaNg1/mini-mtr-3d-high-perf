package dev.minimtr.model.vo;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

public record TrainVo(String id, String lineId, String patternId, String colour,
        double lng, double lat, double bearing, String state, String previousStation,
        String nextStation, String destinationStation, double delaySeconds,
        Instant previousTime, Instant nextTime,
        @JsonInclude(JsonInclude.Include.NON_NULL) String estimate,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<MotionPoint> motion,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant observedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant validUntil) {
    public TrainVo(String id,String lineId,String patternId,String colour,double lng,double lat,double bearing,
            String state,String previousStation,String nextStation,String destinationStation,double delaySeconds,
            Instant previousTime,Instant nextTime,String estimate,List<MotionPoint> motion) {
        this(id,lineId,patternId,colour,lng,lat,bearing,state,previousStation,nextStation,destinationStation,
                delaySeconds,previousTime,nextTime,estimate,motion,null,null);
    }
    public record MotionPoint(double at, double lng, double lat, double bearing) {}
}
