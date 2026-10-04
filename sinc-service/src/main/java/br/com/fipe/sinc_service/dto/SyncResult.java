package br.com.fipe.sinc_service.dto;

public record SyncResult(long jobId, String referenceMonth, String scope, int vehicleTypes,
                         int brands, int models, int variants, int prices) {}
