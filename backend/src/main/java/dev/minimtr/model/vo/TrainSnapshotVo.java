package dev.minimtr.model.vo;

import java.time.Instant;
import java.util.List;

public record TrainSnapshotVo(Instant timestamp, List<TrainVo> trains) {}
