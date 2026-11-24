package com.atsreviewer.backend.ratelimit;

import io.github.bucket4j.BucketConfiguration;

@FunctionalInterface
public interface RateLimitStore {
	RateLimitConsumption consume(String key, BucketConfiguration configuration);
}
