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
            insert into monthly_limits(owner_id,plan_month,category_id,amount) values(?,?,?,?)
            on conflict(owner_id,plan_month,category_id) do update set amount=excluded.amount
            """,cmd.ownerId(),cmd.month(),cmd.categoryId(),cmd.amount());
        return Map.of("status","APPLIED");
    }
}
