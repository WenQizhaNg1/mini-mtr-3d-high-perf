package dev.minimtr.model.vo;

import java.util.List;

public record NetworkVo(Service service, List<Line> lines, List<Station> stations) {
    public record Service(String serviceDayStart, int serviceEndOffsetMinutes) {}
    public record Line(String id, String apiCode, String nameEn, String nameZh,
            String colour, String incidentAnchorStation) {}
    public record Station(String code, String nameEn, String nameZh,
            boolean interchange, List<String> lineIds) {}
}
