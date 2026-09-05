package dev.minimtr.model.entity;

public record MotionNode(double at, double distance, String station, boolean anchor) {
    public MotionNode(double at, double distance, String station) {
        this(at, distance, station, false);
    }
}
