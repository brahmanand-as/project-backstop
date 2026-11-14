package backstop;

import backstop.RiskWindowStore.Counts;
import backstop.RiskWindowStore.Point;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class Merchants {
    record Event(@NotBlank @Size(max=80) String eventId,
                 @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String merchantId,
                 @NotNull @Pattern(regexp="SALE|REFUND|CHARGEBACK") String type,
                 @NotNull @DecimalMin("0.01") @Digits(integer=17,fraction=2) BigDecimal amount,
                 @NotNull @Pattern(regexp="[A-Z]{3}") String currency,
                 @NotNull Instant occurredAt) {}
    record Decision(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max=500) String reason) {}

    private final MerchantService service;
    Merchants(MerchantService service) { this.service = service; }

    @PostMapping("/merchant-events")
    Object ingest(@Valid @RequestBody Event event) { return Map.of("duplicate",service.ingest(event)); }

    @GetMapping("/merchants")
    Object list(@RequestParam(defaultValue="false") boolean flagged,
                @RequestParam(required=false) @Pattern(regexp="ACTIVE|ON_HOLD") String status,
                @RequestParam(defaultValue="0") @Min(0) int page,
                @RequestParam(defaultValue="25") @Min(1) @Max(100) int size) {
        return service.list(flagged,status,page,size);
    }

    @GetMapping("/merchants/{id}")
    Object detail(@PathVariable String id) { return service.detail(id); }

    @PostMapping("/merchants/{id}/hold")
    Object hold(@PathVariable String id,@Valid @RequestBody Decision decision,Principal actor) {
        return service.change(id,decision,actor.getName(),true);
    }

    @PostMapping("/merchants/{id}/release")
    Object release(@PathVariable String id,@Valid @RequestBody Decision decision,Principal actor) {
        return service.change(id,decision,actor.getName(),false);
    }
}

@Service
class MerchantService {
    private final JdbcTemplate db;
    private final RiskWindowStore store;
    private final Application.Settings config;

    MerchantService(JdbcTemplate db,RiskWindowStore store,Application.Settings config) {
        this.db = db;
        this.store = store;
        this.config = config;
    }

    @Transactional
    public boolean ingest(Merchants.Event event) {
        var id = event.merchantId();
        if (db.queryForObject("SELECT COUNT(*) FROM merchant_event WHERE id=?", Long.class,event.eventId()) > 0) return true;
        var transaction = org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus();
        var savepoint = transaction.createSavepoint();
        try {
            insertIfAbsent("INSERT INTO merchant(id) VALUES(?)",id);
            insertIfAbsent("INSERT INTO merchant_feed(merchant_id) VALUES(?)",id);
            db.queryForObject("SELECT merchant_id FROM merchant_feed WHERE merchant_id=? FOR UPDATE",String.class,id);
            db.update("""
                INSERT INTO merchant_event(id,merchant_id,type,amount,currency,occurred_at)
                VALUES(?,?,?,?,?,?)
                """,event.eventId(),id,event.type(),event.amount(),event.currency(),java.sql.Timestamp.from(event.occurredAt()));
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            transaction.rollbackToSavepoint(savepoint);
            return true;
        } finally {
            transaction.releaseSavepoint(savepoint);
        }

        db.update("UPDATE merchant_feed SET revision=revision+1 WHERE merchant_id=?",id);
        var counts = store.count(points(id),config.window());
        if (counts.sales() >= config.minSales()) {
            flag(id,"REFUND_RATIO",counts.refundRatio(),config.refundLimit());
            flag(id,"CHARGEBACK_RATIO",counts.chargebackRatio(),config.chargebackLimit());
        }
        return false;
    }

    private void insertIfAbsent(String sql,String id) {
        try { db.update(sql,id); }
        catch (org.springframework.dao.DuplicateKeyException existing) { /* Another delivery already created the row. */ }
    }

    private Counts counts(String id) {
        var revision = db.queryForObject("SELECT revision FROM merchant_feed WHERE merchant_id=?",Long.class,id);
        return store.snapshot(id,revision,config.window(),() -> points(id));
    }

    private Collection<Point> points(String id) {
        var latest = db.queryForObject("SELECT MAX(occurred_at) FROM merchant_event WHERE merchant_id=?",
            (rs,n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(),id);
        if (latest == null) return List.of();
        return db.query("SELECT type,occurred_at FROM merchant_event WHERE merchant_id=? AND occurred_at>=?",
            (rs,n) -> new Point(rs.getString(1),rs.getTimestamp(2).toInstant()),
            id,java.sql.Timestamp.from(latest.minus(config.window())));
    }

    private void flag(String id,String rule,double ratio,double threshold) {
        if (ratio <= threshold) return;
        if (db.update("UPDATE risk_flag SET ratio=?,last_seen_at=CURRENT_TIMESTAMP WHERE merchant_id=? AND rule=?",
            ratio,id,rule) != 0) return;
        db.update("INSERT INTO risk_flag VALUES(?,?,?,CURRENT_TIMESTAMP)",id,rule,ratio);
        var status = db.queryForObject("SELECT status FROM merchant WHERE id=? FOR UPDATE",String.class,id);
        audit(id,"system","FLAG_RAISED",status,status,rule);
    }

    record Flag(String rule,double ratio,OffsetDateTime lastSeenAt) {}
    record Audit(String actor,String action,String fromStatus,String toStatus,String reason,OffsetDateTime at) {}
    record View(String merchantId,String status,long version,long sales,long refunds,long chargebacks,
                double refundRatio,double chargebackRatio,double risk,double refundLimit,double chargebackLimit,List<Flag> openFlags,List<Audit> audit) {}
    record Control(String id,String status,long version) {}
    record Page(List<View> items,int page,int size,long total) {}

    private Control control(String id) {
        return db.query("SELECT id,status,version FROM merchant WHERE id=?",this::controlRow,id)
            .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Merchant not found"));
    }

    private Control controlRow(java.sql.ResultSet rs,int index) throws java.sql.SQLException {
        return new Control(rs.getString(1),rs.getString(2),rs.getLong(3));
    }

    private View view(Control merchant,List<Audit> history) {
        var c = counts(merchant.id());
        var flags = db.query("SELECT rule,ratio,last_seen_at FROM risk_flag WHERE merchant_id=? ORDER BY rule",
            (rs,n) -> new Flag(rs.getString(1),rs.getDouble(2),rs.getTimestamp(3).toInstant().atOffset(ZoneOffset.UTC)),merchant.id());
        var risk = Math.max(c.refundRatio()/config.refundLimit(),c.chargebackRatio()/config.chargebackLimit());
        return new View(merchant.id(),merchant.status(),merchant.version(),c.sales(),c.refunds(),c.chargebacks(),
            c.refundRatio(),c.chargebackRatio(),risk,config.refundLimit(),config.chargebackLimit(),flags,history);
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public View detail(String id) {
        var merchant = control(id);
        var history = db.query("""
            SELECT actor,action,from_status,to_status,reason,at FROM merchant_audit
            WHERE merchant_id=? ORDER BY id DESC LIMIT 20
            """,(rs,n) -> new Audit(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getTimestamp(6).toInstant().atOffset(ZoneOffset.UTC)),id);
        return view(merchant,history);
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page list(boolean flagged,String status,int page,int size) {
        var merchants = db.query("SELECT id,status,version FROM merchant",this::controlRow).stream()
            .filter(m -> status == null || status.equals(m.status()))
            .map(m -> view(m,List.of())).filter(m -> !flagged || !m.openFlags().isEmpty())
            .sorted(Comparator.comparingDouble(View::risk).reversed().thenComparing(View::merchantId)).toList();
        return new Page(merchants.stream().skip((long) page*size).limit(size).toList(),page,size,merchants.size());
    }

    @Transactional
    public Object change(String id,Merchants.Decision decision,String actor,boolean hold) {
        var reason = decision.reason().strip();
        if (reason.length() < 15) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Reason must be at least 15 characters");
        control(id);
        var from = hold ? "ACTIVE" : "ON_HOLD";
        var to = hold ? "ON_HOLD" : "ACTIVE";
        if (db.update("UPDATE merchant SET status=?,version=version+1 WHERE id=? AND status=? AND version=?",
            to,id,from,decision.version()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Merchant version or status changed");
        }
        audit(id,actor,hold ? "HOLD" : "RELEASE",from,to,reason);
        return Map.of("merchantId",id,"status",to,"version",decision.version()+1);
    }

    private void audit(String id,String actor,String action,String from,String to,String reason) {
        db.update("INSERT INTO merchant_audit(merchant_id,actor,action,from_status,to_status,reason) VALUES(?,?,?,?,?,?)",
            id,actor,action,from,to,reason);
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of(
            "code",HttpStatus.valueOf(e.getStatusCode().value()).name(),"message",e.getReason()));
    }

    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.web.method.annotation.HandlerMethodValidationException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> validation(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("code","BAD_REQUEST","message","Invalid request data"));
    }
}
