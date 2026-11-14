package backstop;

import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="backstop.store",havingValue="memory",matchIfMissing=true)
class InMemoryRiskWindowStore implements RiskWindowStore {
    public Counts count(Collection<Point> events, Duration window) {
        var latest = events.stream().map(Point::occurredAt).max(Comparator.naturalOrder());
        if (latest.isEmpty()) return new Counts(0, 0, 0);
        var cutoff = latest.get().minus(window);
        long sales = 0, refunds = 0, chargebacks = 0;
        for (var event : events) {
            if (event.occurredAt().isBefore(cutoff)) continue;
            switch (event.type()) {
                case "SALE" -> sales++;
                case "REFUND" -> refunds++;
                case "CHARGEBACK" -> chargebacks++;
                default -> throw new IllegalArgumentException("Unknown event type");
            }
        }
        return new Counts(sales, refunds, chargebacks);
    }
}
