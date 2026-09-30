package br.com.fipe.sinc_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Payloads observed in the FIPE vehicle endpoints. Keep their distinct JSON types. */
public final class FipeResponses {
    private FipeResponses() {}

    public record Period(@JsonProperty("Codigo") int code,
                         @JsonProperty("Mes") String month) {}

    public record Option(@JsonProperty("Label") String label,
                         @JsonProperty("Value") String value) {}

    public record ModelOption(@JsonProperty("Label") String label,
                              @JsonProperty("Value") int value) {}

    public record Models(@JsonProperty("Modelos") List<ModelOption> models,
                         @JsonProperty("Anos") List<Option> years) {}

    public record Price(@JsonProperty("Valor") String value,
                        @JsonProperty("Marca") String brand,
                        @JsonProperty("Modelo") String model,
                        @JsonProperty("AnoModelo") int modelYear,
                        @JsonProperty("Combustivel") String fuel,
                        @JsonProperty("CodigoFipe") String fipeCode,
                        @JsonProperty("MesReferencia") String referenceMonth,
                        @JsonProperty("Autenticacao") String authentication,
                        @JsonProperty("TipoVeiculo") int vehicleType,
                        @JsonProperty("SiglaCombustivel") String fuelAbbreviation,
                        @JsonProperty("DataConsulta") String consultationDate) {}
}
