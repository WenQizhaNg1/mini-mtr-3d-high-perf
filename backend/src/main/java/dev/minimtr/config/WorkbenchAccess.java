package dev.minimtr.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class WorkbenchAccess {
    private final String token;
    public WorkbenchAccess(@Value("${workbench.token:}") String token) { this.token = token; }

    public void check(String authorization) {
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workbench is disabled");
        if (authorization == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid management token");
    }
}
