package backstop;

import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(Application.Settings.class)
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }

    @ConfigurationProperties("backstop")
    record Settings(String demoPassword, Duration window, int minSales, double refundLimit, double chargebackLimit) {
        Settings {
            if (window == null || window.isZero() || window.isNegative() || minSales < 1 ||
                !(refundLimit > 0 && refundLimit <= 1) || !(chargebackLimit > 0 && chargebackLimit <= 1)) {
                throw new IllegalArgumentException("Invalid risk settings");
            }
        }
    }
}
