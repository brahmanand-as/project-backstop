package backstop;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="backstop.store",havingValue="redis")
class RedisRiskWindowStore implements RiskWindowStore {
    private static final DefaultRedisScript<String> PROJECT = new DefaultRedisScript<>("""
        local existing = redis.call('GET', KEYS[1])
        if existing then return existing end
        local counts = { SALE=0, REFUND=0, CHARGEBACK=0 }
        for _, category in ipairs(ARGV) do
          counts[category] = counts[category] + 1
        end
        local result = counts.SALE .. ',' .. counts.REFUND .. ',' .. counts.CHARGEBACK
        redis.call('SET', KEYS[1], result, 'EX', 600)
        return result
        """,String.class);

    private final StringRedisTemplate redis;
    RedisRiskWindowStore(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public Counts count(Collection<Point> events, Duration window) {
        // Ingestion evaluates its uncommitted PostgreSQL snapshot without publishing it.
        return new InMemoryRiskWindowStore().count(events,window);
    }

    @Override
    public Counts snapshot(String merchantId,long revision,Duration window,Supplier<Collection<Point>> source) {
        var key = "backstop:projection:" + merchantId + ":" + revision + ":" + window;
        var cached = redis.opsForValue().get(key);
        if (cached != null) return decode(cached);
        var events = source.get();
        var latest = events.stream().map(Point::occurredAt).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        var cutoff = latest.minus(window);
        var types = events.stream().filter(e -> !e.occurredAt().isBefore(cutoff)).map(Point::type).toArray(String[]::new);
        return decode(Objects.requireNonNull(redis.execute(PROJECT,List.of(key),(Object[]) types)));
    }

    private Counts decode(String snapshot) {
        var counts = snapshot.split(",");
        return new Counts(Long.parseLong(counts[0]),Long.parseLong(counts[1]),Long.parseLong(counts[2]));
    }
}
