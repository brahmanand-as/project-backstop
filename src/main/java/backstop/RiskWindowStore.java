package backstop;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

interface RiskWindowStore {
    record Point(String type, Instant occurredAt) {}
    record Counts(long sales, long refunds, long chargebacks) {
        double refundRatio() { return sales == 0 ? 0 : (double) refunds / sales; }
        double chargebackRatio() { return sales == 0 ? 0 : (double) chargebacks / sales; }
    }
    Counts count(Collection<Point> events, Duration window);
    default Counts snapshot(String merchantId,long revision,Duration window,java.util.function.Supplier<Collection<Point>> events) {
        return count(events.get(),window);
    }
}
