package dev.minimtr.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchAccessTest {
    @Test void disabledByDefaultAndRequiresExactBearerToken() {
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> new WorkbenchAccess("").check(null)).getStatusCode().value());
        var access = new WorkbenchAccess("test-token");
        for (var header : new String[]{null, "Bearer wrong", "test-token"})
            assertEquals(401, assertThrows(ResponseStatusException.class, () -> access.check(header)).getStatusCode().value());
        assertDoesNotThrow(() -> access.check("Bearer test-token"));
    }
}
