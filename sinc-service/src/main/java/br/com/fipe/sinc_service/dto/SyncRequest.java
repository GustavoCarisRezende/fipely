package br.com.fipe.sinc_service.dto;

/** For brand, model and variant sync, include all parent FIPE identifiers. */
public record SyncRequest(String referenceMonth, Integer vehicleType, String brandCode,
                          Integer modelCode, Integer modelYear, String fuelCode) {}
