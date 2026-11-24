package com.atsreviewer.backend.ratelimit;

import java.time.Duration;

public record RateLimitConsumption(boolean consumed, Duration retryAfter) {
}
