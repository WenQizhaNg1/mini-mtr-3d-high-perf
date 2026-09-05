package dev.minimtr.model.entity;

import java.util.List;

public record Station(String code, String name, String nameEn, List<String> lineIds) {}
