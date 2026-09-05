package dev.minimtr.service;

import dev.minimtr.config.ServiceSettings;
import dev.minimtr.model.vo.ServiceDayVo;
import dev.minimtr.repo.ServiceDayRepo;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class ServiceDayService {
    private final ServiceDayRepo repo;
    private final ServiceSettings settings;

    public ServiceDayService(ServiceDayRepo repo, ServiceSettings settings) {
        this.repo = repo;
        this.settings = settings;
    }

    public ServiceDayVo load(Instant at) {
        var date = settings.serviceDateAt(at);
        var start = settings.startsAt(date);
        var end = start.plusSeconds(settings.serviceEndOffsetMinutes() * 60L);
        var state = repo.load(date);
        return new ServiceDayVo(date, !at.isBefore(start) && at.isBefore(end), start, end,
                new ServiceDayVo.Replay(state.replayStartsAt(), state.replayEndsAt()),
                new ServiceDayVo.Schedules(state.currentExists(), date.plusDays(1), state.nextExists()));
    }
}
