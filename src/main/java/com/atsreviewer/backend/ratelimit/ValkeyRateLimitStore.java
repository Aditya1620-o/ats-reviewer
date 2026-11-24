package com.atsreviewer.backend.ratelimit;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import jakarta.annotation.PreDestroy;

@Component
public class ValkeyRateLimitStore implements RateLimitStore {
	private static final Duration BUCKET_EXPIRATION = Duration.ofDays(1);

	private final RedisClient redisClient;
	private volatile StatefulRedisConnection<byte[], byte[]> connection;
	private volatile ProxyManager<byte[]> proxyManager;

	public ValkeyRateLimitStore(
			@Value("${app.rate-limit.valkey-url:redis://localhost:6379}") String valkeyUrl) {
		this.redisClient = RedisClient.create(valkeyUrl);
	}

	@Override
	public RateLimitConsumption consume(String key, BucketConfiguration configuration) {
		try {
			Bucket bucket = proxyManager().getProxy(
					key.getBytes(StandardCharsets.UTF_8), () -> configuration);
			var probe = bucket.tryConsumeAndReturnRemaining(1);
			Duration retryAfter = probe.isConsumed()
					? Duration.ZERO
					: Duration.ofNanos(probe.getNanosToWaitForRefill());
			return new RateLimitConsumption(probe.isConsumed(), retryAfter);
		} catch (RedisException exception) {
			throw new RateLimitStoreUnavailableException(exception);
		}
	}

	private ProxyManager<byte[]> proxyManager() {
		ProxyManager<byte[]> current = proxyManager;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			if (proxyManager == null) {
				StatefulRedisConnection<byte[], byte[]> candidate =
						redisClient.connect(ByteArrayCodec.INSTANCE);
				try {
					proxyManager = Bucket4jLettuce.casBasedBuilder(candidate)
							.expirationAfterWrite(ExpirationAfterWriteStrategy
									.basedOnTimeForRefillingBucketUpToMax(BUCKET_EXPIRATION))
							.build();
					connection = candidate;
				} catch (RuntimeException exception) {
					candidate.close();
					throw exception;
				}
			}
			return proxyManager;
		}
	}

	@PreDestroy
	public void close() {
		StatefulRedisConnection<byte[], byte[]> currentConnection = connection;
		if (currentConnection != null) {
			currentConnection.close();
		}
		redisClient.shutdown();
	}
}
