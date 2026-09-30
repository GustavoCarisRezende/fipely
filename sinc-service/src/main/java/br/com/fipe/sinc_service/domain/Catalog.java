package br.com.fipe.sinc_service.domain;

import java.time.Instant;
import java.time.LocalDate;

public final class Catalog {
    private Catalog() {}

    public record Period(long id, int code, LocalDate month) {}
    public record Brand(long id, int type, String code, String name, Instant syncedAt) {}
    public record Model(long id, long brandId, int code, String name, Instant syncedAt) {}
    public record Variant(long id, long modelId, String sourceValue, int year,
                          String fuelCode, Instant syncedAt) {}
}
