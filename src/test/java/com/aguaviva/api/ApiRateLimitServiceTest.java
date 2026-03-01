package com.aguaviva.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ApiRateLimitServiceTest {

    @Test
    void deveConverterJanelaEmSegundos() {
        assertEquals(15, ApiRateLimitService.parseWindowSeconds("15s"));
        assertEquals(900, ApiRateLimitService.parseWindowSeconds("15m"));
        assertEquals(7200, ApiRateLimitService.parseWindowSeconds("2h"));
        assertEquals(86400, ApiRateLimitService.parseWindowSeconds("1d"));
    }

    @Test
    void deveFalharComFormatoDeJanelaInvalido() {
        assertThrows(IllegalArgumentException.class, () -> ApiRateLimitService.parseWindowSeconds("abc"));
        assertThrows(IllegalArgumentException.class, () -> ApiRateLimitService.parseWindowSeconds("10x"));
        assertThrows(IllegalArgumentException.class, () -> ApiRateLimitService.parseWindowSeconds("0m"));
    }
}
