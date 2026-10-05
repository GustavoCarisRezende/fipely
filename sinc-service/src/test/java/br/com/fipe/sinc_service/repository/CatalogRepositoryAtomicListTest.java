package br.com.fipe.sinc_service.repository;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

class CatalogRepositoryAtomicListTest {
    @Test
    void listStampIsNotWrittenWhenPeriodLinkBatchFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(23L);
        when(jdbc.batchUpdate(anyString(), anyList())).thenThrow(new IllegalStateException("link failure"));
        CatalogRepository repository = new CatalogRepository(jdbc, mock(ObjectMapper.class));

        assertThrows(IllegalStateException.class, () -> repository.persistBrandResponse(7, 1,
                List.of(new CatalogRepository.BrandInput("1", "brand")), "[]", Instant.now()));

        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.contains("brand_list_responses"),
                any(Object[].class));
        assertTransactional("persistBrandResponse");
        assertTransactional("persistModelResponse");
        assertTransactional("persistYearResponse");
    }

    private static void assertTransactional(String method) {
        try {
            var methodRef = java.util.Arrays.stream(CatalogRepository.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(method)).findFirst().orElseThrow();
            org.junit.jupiter.api.Assertions.assertNotNull(methodRef.getAnnotation(Transactional.class));
        } catch (RuntimeException e) {
            throw e;
        }
    }
}
