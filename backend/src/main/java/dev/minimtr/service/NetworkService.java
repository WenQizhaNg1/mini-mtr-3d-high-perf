package dev.minimtr.service;

import dev.minimtr.config.ServiceSettings;
import dev.minimtr.model.vo.NetworkVo;
import dev.minimtr.repo.NetworkRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NetworkService {
    private final NetworkRepo repo;
    private final ServiceSettings settings;

    public NetworkService(NetworkRepo repo, ServiceSettings settings) {
        this.repo = repo;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public NetworkVo load() {
        var lines = repo.lines().stream().map(line -> {
            var config = settings.lines().stream().filter(c -> c.lineId().equals(line.code()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Missing MTR config: " + line.code()));
            return new NetworkVo.Line(line.code(), config.apiCode(), line.nameEn(), line.name(),
                    line.colour(), config.incidentAnchorStation());
        }).toList();
        var stations = repo.stations().stream().map(s -> new NetworkVo.Station(
                s.code(), s.nameEn(), s.name(), s.lineIds().size() > 1, s.lineIds())).toList();
        return new NetworkVo(new NetworkVo.Service(settings.serviceDayStart(),
                settings.serviceEndOffsetMinutes()), lines, stations);
    }
}
