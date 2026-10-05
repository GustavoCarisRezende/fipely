package br.com.fipe.sinc_service.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import br.com.fipe.sinc_service.dto.SyncRequest;
import br.com.fipe.sinc_service.repository.CatalogRepository;
import br.com.fipe.sinc_service.service.SyncProgressService;
import br.com.fipe.sinc_service.service.SyncService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SyncControllerTest {
    @Test void postRespondsAcceptedWithJobId() {
        SyncService sync = mock(SyncService.class);
        when(sync.submit(SyncService.Scope.VARIANT,
                new SyncRequest("2026-07", 2, "80", 10378, 2023, "5"), false))
                .thenReturn(new SyncService.Accepted(123L, "queued"));
        SyncController controller = new SyncController(sync, mock(CatalogRepository.class), mock(SyncProgressService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        try {
            mvc.perform(post("/api/v1/sync/variants")
                            .contentType("application/json")
                            .content("{\"referenceMonth\":\"2026-07\",\"vehicleType\":2,\"brandCode\":\"80\",\"modelCode\":10378,\"modelYear\":2023,\"fuelCode\":\"5\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(content().json("{\"jobId\":123,\"status\":\"queued\"}"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
