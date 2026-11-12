package com.resolum.intiva.poc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

final class PlanExtras {
    private PlanExtras() {}
    record Contribution(UUID planId,long ownerId,long goalId,String month,BigDecimal amount) {}
    record Reminder(UUID planId,long ownerId,LocalDate date,String label) {}
    static void authenticate(String expected,String received){
        if(expected.length()<24 || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                received.getBytes(StandardCharsets.UTF_8))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    static void require(boolean valid){
        if(!valid)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
}

@RestController
@Profile("savings")
class PlannedContributionsController {
    private final JdbcTemplate db;
    private final String key;
    PlannedContributionsController(JdbcTemplate db,@Value("${intiva.service-key}") String key){this.db=db;this.key=key;}
    @PostMapping("/internal/planned-contributions")
    Map<String,String> apply(@RequestHeader("X-Intiva-Service-Key") String supplied,@RequestBody PlanExtras.Contribution c){
        PlanExtras.authenticate(key,supplied);
        PlanExtras.require(c.planId()!=null && c.ownerId()>0 && c.goalId()>0 && AgentController.validAmount(c.amount()));
        try{ YearMonth.parse(c.month()); } catch(RuntimeException ex){ PlanExtras.require(false); }
        int affected=db.update("""
            insert into planned_contributions(plan_id,owner_id,goal_id,plan_month,amount) values(?,?,?,?,?)
            on conflict(plan_id) do update set amount=excluded.amount
            where planned_contributions.owner_id=excluded.owner_id and planned_contributions.goal_id=excluded.goal_id
              and planned_contributions.plan_month=excluded.plan_month and planned_contributions.amount=excluded.amount
            """,c.planId(),c.ownerId(),c.goalId(),c.month(),c.amount());
        if(affected!=1)throw new ResponseStatusException(HttpStatus.CONFLICT);
        return Map.of("status","APPLIED");
    }
}

@RestController
@Profile("communications")
class PlanRemindersController {
    private final JdbcTemplate db;
    private final String key;
    PlanRemindersController(JdbcTemplate db,@Value("${intiva.service-key}") String key){this.db=db;this.key=key;}
    @PostMapping("/internal/plan-reminders")
    Map<String,String> apply(@RequestHeader("X-Intiva-Service-Key") String supplied,@RequestBody PlanExtras.Reminder r){
        PlanExtras.authenticate(key,supplied);
        PlanExtras.require(r.planId()!=null && r.ownerId()>0 && r.date()!=null && r.label()!=null
            && !r.label().isBlank() && r.label().length()<=120);
        int affected=db.update("""
            insert into plan_reminders(plan_id,owner_id,due_date,label) values(?,?,?,?)
            on conflict(plan_id) do update set label=excluded.label
            where plan_reminders.owner_id=excluded.owner_id and plan_reminders.due_date=excluded.due_date
              and plan_reminders.label=excluded.label
            """,r.planId(),r.ownerId(),r.date(),r.label());
        if(affected!=1)throw new ResponseStatusException(HttpStatus.CONFLICT);
        return Map.of("status","APPLIED");
    }
    @Scheduled(fixedDelay=60000)
    @Transactional
    public void sendDue(){
        db.update("""
            with due as (
                select plan_id,owner_id,label from plan_reminders
                where not delivered and due_date <= (now() at time zone 'America/Lima')::date
                order by due_date limit 50 for update skip locked
            ), delivered as (
                insert into plan_notices(event_id,plan_id,owner_id,message)
                select md5('reminder:' || plan_id::text)::uuid,plan_id,owner_id,'Recordatorio: ' || label from due
                on conflict(event_id) do nothing returning plan_id
            )
            update plan_reminders set delivered=true where plan_id in (select plan_id from due)
            """);
    }
}
