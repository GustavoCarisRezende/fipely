package br.com.fipe.sinc_service.controller;

import br.com.fipe.sinc_service.dto.SyncRequest;
import br.com.fipe.sinc_service.dto.SyncResult;
import br.com.fipe.sinc_service.repository.CatalogRepository;
import br.com.fipe.sinc_service.service.SyncService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@RequestMapping("/api/v1")
public class SyncController {
    private final SyncService sync;
    private final CatalogRepository repository;

    public SyncController(SyncService sync, CatalogRepository repository) {
        this.sync = sync;
        this.repository = repository;
    }

    @PostMapping("/sync/periods")
    public SyncResult period(@RequestBody SyncRequest request,
                             @RequestParam(defaultValue = "false") boolean refreshOldRecords) {
        return sync.sync(SyncService.Scope.PERIOD, request, refreshOldRecords);
    }

    @PostMapping("/sync/brands")
    public SyncResult brand(@RequestBody SyncRequest request,
                            @RequestParam(defaultValue = "false") boolean refreshOldRecords) {
        return sync.sync(SyncService.Scope.BRAND, request, refreshOldRecords);
    }

    @PostMapping("/sync/models")
    public SyncResult model(@RequestBody SyncRequest request,
                            @RequestParam(defaultValue = "false") boolean refreshOldRecords) {
        return sync.sync(SyncService.Scope.MODEL, request, refreshOldRecords);
    }

    @PostMapping("/sync/variants")
    public SyncResult variant(@RequestBody SyncRequest request,
                              @RequestParam(defaultValue = "false") boolean refreshOldRecords) {
        return sync.sync(SyncService.Scope.VARIANT, request, refreshOldRecords);
    }

    // History and reports are read-only. The flag is accepted consistently on every endpoint.
    @GetMapping("/history/models/{vehicleType}/{brandCode}/{modelCode}")
    public List<CatalogRepository.PriceHistory> modelHistory(@PathVariable int vehicleType,
            @PathVariable String brandCode, @PathVariable int modelCode,
            @RequestParam(defaultValue = "false") boolean refreshOldRecords) {
        return repository.history(vehicleType, brandCode, modelCode, null, null);
    }

    @GetMapping("/history/variants/{vehicleType}/{brandCode}/{modelCode}/{modelYear}/{fuelCode}")
    public List<CatalogRepository.PriceHistory> variantHistory(@PathVariable int vehicleType,
            @PathVariable String brandCode, @PathVariable int modelCode,
            @PathVariable int modelYear, @PathVariable String fuelCode,
            @RequestParam(defaultValue = "false") boolean refreshOldRecords) {
        return repository.history(vehicleType, brandCode, modelCode, modelYear, fuelCode);
    }

    @GetMapping("/reports/periods/absent")
    public List<LocalDate> absentPeriods(@RequestParam(defaultValue = "false") boolean refreshOldRecords) {
        return sync.absentPeriods();
    }

    @GetMapping("/reports/periods/incomplete")
    public List<CatalogRepository.IncompletePeriod> incompletePeriods(
            @RequestParam(defaultValue = "false") boolean refreshOldRecords) {
        return sync.incompletePeriods();
    }
}

@RestControllerAdvice
class ApiErrors {
    record ErrorMessage(String message) {}

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorMessage> invalid(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorMessage(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorMessage> upstream(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorMessage(e.getMessage()));
    }
}
