package br.com.fipe.sinc_service.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Discovered total is provisional until discoveryComplete becomes true. */
public record SyncProgress(long jobId, Long parentJobId, String scope, LocalDate referenceMonth,
                           Integer vehicleType, String brandCode, Integer modelCode,
                           Integer modelYear, String fuelCode, boolean refreshOldRecords, boolean includeVariants,
                           String status, String phase, String currentBrand, String currentModel,
                           long brandsDiscovered, long modelsDiscovered, long vehiclesDiscovered,
                           long vehiclesProcessed, long vehiclesSynced, long vehiclesSkipped,
                           long remainingKnown, boolean discoveryComplete,
                           Instant startedAt, Instant updatedAt, Instant finishedAt,
                           String errorMessage, List<SyncProgress> children) {
}
