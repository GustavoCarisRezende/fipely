package br.com.fipe.sinc_service.dto;

/** Omitted referenceMonth selects the latest FIPE table; omitted includeVariants is false. */
public record CatalogSyncRequest(String referenceMonth, Integer vehicleType, Boolean includeVariants) {
    public boolean variantsRequested() {
        return Boolean.TRUE.equals(includeVariants);
    }
}
