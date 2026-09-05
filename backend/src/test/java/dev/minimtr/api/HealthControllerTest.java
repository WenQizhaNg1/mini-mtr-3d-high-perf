package dev.minimtr.api;

import dev.minimtr.repo.HealthRepo;
import dev.minimtr.model.vo.HealthVo;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

class HealthControllerTest {
    private final HealthRepo repo = mock(HealthRepo.class);
    private final HealthController controller = new HealthController(repo);

    @Test
    void checksDatabaseThroughJooq() {

        var response = controller.health();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(new HealthVo("ok", "up"), response.getBody());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        verify(repo).check();
    }

    @Test
    void databaseFailureReturns503WithoutConnectionDetails() {
        doThrow(new DataAccessResourceFailureException("internal connection details")).when(repo).check();

        var response = controller.health();

        assertEquals(503, response.getStatusCode().value());
        assertEquals(new HealthVo("error", "down"), response.getBody());
    }
}
