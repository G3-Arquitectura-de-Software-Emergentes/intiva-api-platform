package com.resolum.intiva.poc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("finances")
class FinancesController {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final byte[] serviceKey;
    FinancesController(JdbcTemplate db,ObjectMapper json,@Value("${intiva.service-key}") String key) {
        this.db=db;this.json=json;
        if(key.length()<24) throw new IllegalArgumentException("Set INTIVA_SERVICE_KEY (at least 24 characters)");
        serviceKey=key.getBytes(StandardCharsets.UTF_8);
    }
    private void authenticate(String supplied) {
        if(!MessageDigest.isEqual(serviceKey,supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    @GetMapping("/internal/plan-limits")
    Map<String,BigDecimal> current(@RequestHeader("X-Intiva-Service-Key") String key,
            @RequestParam long ownerId,@RequestParam String month,@RequestParam long categoryId) {
        authenticate(key);
        var found=db.query("select amount from monthly_limits where owner_id=? and plan_month=? and category_id=?",
                (rs,i)->rs.getBigDecimal(1),ownerId,month,categoryId);
        return found.isEmpty()?Map.of():Map.of("amount",found.getFirst());
    }
    record Expense(long transactionId,long ownerId,String month,long categoryId,BigDecimal amount){}
    record BudgetAlert(String kind,java.util.UUID eventId,java.util.UUID planId,long ownerId,String message){}
    record Budget(java.util.UUID planId,String label,BigDecimal limit){}
    @PostMapping("/internal/registered-expenses")
    @Transactional
    public void track(@RequestHeader("X-Intiva-Service-Key") String key,@RequestBody Expense e) throws JsonProcessingException {
        authenticate(key);
        if(e.transactionId()<=0 || e.ownerId()<=0 || e.categoryId()<=0 || !AgentController.validAmount(e.amount()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try { YearMonth.parse(e.month()); } catch(RuntimeException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
        // Lock the active limit before recording its expense so concurrent deliveries see the committed total.
        var budgets=db.query("""
            select plan_id,category_label,amount from monthly_limits where owner_id=? and plan_month=? and category_id=? for update
            """,(rs,i)->new Budget(rs.getObject(1,java.util.UUID.class),rs.getString(2),rs.getBigDecimal(3)),
            e.ownerId(),e.month(),e.categoryId());
        int inserted=db.update("""
            insert into tracked_expenses(transaction_id,owner_id,month,category_id,amount) values(?,?,?,?,?)
            on conflict(transaction_id) do nothing
            """,e.transactionId(),e.ownerId(),e.month(),e.categoryId(),e.amount());
        if(inserted==0 || budgets.isEmpty() || budgets.getFirst().planId()==null)return;
        var budget=budgets.getFirst();
        var total=db.queryForObject("""
            select sum(amount) from tracked_expenses where owner_id=? and month=? and category_id=?
            """,BigDecimal.class,e.ownerId(),e.month(),e.categoryId());
        if(total.compareTo(budget.limit())<0)return;
        var id=java.util.UUID.nameUUIDFromBytes(("budget:"+budget.planId()).getBytes(StandardCharsets.UTF_8));
        var alert=new BudgetAlert("BUDGET_ALERT",id,budget.planId(),e.ownerId(),
            "Llegaste al límite de "+budget.label()+" (S/ "+total+"). Revisa tu plan.");
        db.update("insert into budget_alert_outbox(event_id,payload) values(?,?) on conflict(event_id) do nothing",
            id,json.writeValueAsString(alert));
    }
    @PostMapping("/internal/plan-limits")
    @Transactional
    public Map<String,String> apply(@RequestHeader("X-Intiva-Service-Key") String key,
                    @RequestBody PlanLimit cmd) throws JsonProcessingException {
        authenticate(key);
        if(cmd.eventId()==null || cmd.planId()==null || cmd.ownerId()<=0 || cmd.categoryId()<=0 ||
                cmd.version()<1 || cmd.month()==null || !cmd.month().matches("[0-9]{4}-[0-9]{2}") ||
                cmd.amount()==null || cmd.amount().signum()<=0 || cmd.amount().scale()>2 ||
                cmd.amount().compareTo(new BigDecimal("10000000"))>=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try { YearMonth.parse(cmd.month()); } catch(java.time.DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String payload=json.writeValueAsString(cmd);
        int inserted=db.update("""
            insert into limit_outbox(event_id,plan_id,plan_version,payload) values(?,?,?,?)
            on conflict (plan_id,plan_version) do nothing
            """,cmd.eventId(),cmd.planId(),cmd.version(),payload);
        if(inserted==0) {
            String original=db.queryForObject("select payload from limit_outbox where plan_id=? and plan_version=?",
                    String.class,cmd.planId(),cmd.version());
            if(!json.readTree(original).equals(json.readTree(payload)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval already used for different values");
            return Map.of("status","APPLIED");
        }
        db.update("""
            insert into monthly_limits(owner_id,plan_month,category_id,amount,plan_id,category_label) values(?,?,?,?,?,?)
            on conflict(owner_id,plan_month,category_id) do update set amount=excluded.amount,plan_id=excluded.plan_id,category_label=excluded.category_label
            """,cmd.ownerId(),cmd.month(),cmd.categoryId(),cmd.amount(),cmd.planId(),cmd.categoryLabel());
        return Map.of("status","APPLIED");
    }
}
