package dev.minimtr.service;

import dev.minimtr.model.entity.TrainWindow;
import dev.minimtr.model.vo.TrainSnapshotVo;
import dev.minimtr.model.vo.TrainVo;
import dev.minimtr.repo.TrainRepo;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class TrainService {
    private final TrainRepo repo;
    private final ConcurrentMap<Long, RoutePath> paths = new ConcurrentHashMap<>();

    public TrainService(TrainRepo repo) {
        this.repo = repo;
    }

    public TrainSnapshotVo load(Instant at) {
        return new TrainSnapshotVo(at, repo.windowsAt(at).stream().map(window -> locate(window, at)).toList());
    }

    private TrainVo locate(TrainWindow window, Instant at) {
        boolean dwell = at.isBefore(window.departureAt());
        double fraction = window.fraction();
        if (!dwell) {
            double progress = (double) (at.toEpochMilli() - window.departureAt().toEpochMilli())
                    / (window.nextArrivalAt().toEpochMilli() - window.departureAt().toEpochMilli());
            fraction += progress * (window.nextFraction() - fraction);
        }
        var path = paths.computeIfAbsent(window.objectId(), id -> new RoutePath(repo.path(id)));
        var position = path.locate(fraction);
        return new TrainVo(window.id(), window.lineId(), window.patternId(), window.colour(),
                position.lng(), position.lat(), position.bearing(), dwell ? "dwell" : "running",
                window.station(), window.nextStation(), window.destinationStation(), 0,
                dwell ? window.arrivalAt() : window.departureAt(), window.nextArrivalAt(), null, null);
    }
}
