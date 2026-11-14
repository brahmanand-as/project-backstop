package backstop;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class MerchantApiTest {
    @Autowired MockMvc api;
    static final Instant TIME = Instant.parse("2026-11-14T00:00:00Z");
    static final String REASON = "{\"version\":0,\"reason\":\"Review unusual refund activity\"}";

    static String auth(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user+":test-only").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    String event(String id,String merchant,String type,Instant timestamp) {
        return """
            {"eventId":"%s","merchantId":"%s","type":"%s","amount":100,"currency":"INR","occurredAt":"%s"}
            """.formatted(id,merchant,type,timestamp);
    }

    void ingest(String body,boolean duplicate) throws Exception {
        api.perform(post("/api/merchant-events").header("Authorization",auth("ingest")).contentType("application/json")
            .content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(duplicate));
    }

    void events(String merchant,String type,int quantity) throws Exception {
        for (int i=0;i<quantity;i++) ingest(event(UUID.randomUUID().toString(),merchant,type,TIME),false);
    }

    @Test void flagsAndReplay() throws Exception {
        events("bad","SALE",100);
        events("bad","REFUND",16);
        var replay = event("replay","bad","REFUND",TIME);
        ingest(replay,false);
        ingest(replay,true);
        ingest(event("old","bad","REFUND",TIME.minusSeconds(86401)),false);
        api.perform(get("/api/merchants/bad").header("Authorization",auth("analyst1")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sales").value(100))
            .andExpect(jsonPath("$.refunds").value(17)).andExpect(jsonPath("$.openFlags",hasSize(1)))
            .andExpect(jsonPath("$.openFlags[0].rule").value("REFUND_RATIO"))
            .andExpect(jsonPath("$.audit",hasSize(1)));
        events("small","SALE",19);
        events("small","REFUND",10);
        api.perform(get("/api/merchants/small").header("Authorization",auth("analyst1")))
            .andExpect(jsonPath("$.openFlags",hasSize(0)));
    }

    int hold(String merchant,String user) throws Exception {
        return api.perform(post("/api/merchants/"+merchant+"/hold").header("Authorization",auth(user))
            .contentType("application/json").content(REASON)).andReturn().getResponse().getStatus();
    }

    @Test void exactlyOneConcurrentHold() throws Exception {
        events("race","SALE",1);
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { barrier.await(5,TimeUnit.SECONDS); return hold("race","analyst1"); });
            var two = pool.submit(() -> { barrier.await(5,TimeUnit.SECONDS); return hold("race","analyst2"); });
            assertEquals(java.util.Set.of(200,409),java.util.Set.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS)));
        } finally { pool.shutdownNow(); }
        api.perform(get("/api/merchants/race").header("Authorization",auth("analyst1")))
            .andExpect(jsonPath("$.status").value("ON_HOLD")).andExpect(jsonPath("$.audit",hasSize(1)))
            .andExpect(jsonPath("$.audit[0].action").value("HOLD"));
    }

    @Test void ingestionLeavesControlVersionAndRolesAreEnforced() throws Exception {
        events("version","SALE",1);
        api.perform(get("/api/merchants/version").header("Authorization",auth("analyst1")))
            .andExpect(jsonPath("$.version").value(0));
        events("version","REFUND",1);
        assertEquals(200,hold("version","analyst1"));
        api.perform(post("/api/merchants/version/release").header("Authorization",auth("analyst1")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        for (String user : new String[]{"analyst1","supervisor1"}) {
            api.perform(post("/api/merchant-events").header("Authorization",auth(user))).andExpect(status().isForbidden());
        }
        api.perform(get("/api/merchants")).andExpect(status().isUnauthorized());
    }
}
