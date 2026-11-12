package com.resolum.intiva.poc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("communications")
class CommunicationsController {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final LegacyClient legacy;
    CommunicationsController(JdbcTemplate db,ObjectMapper json,LegacyClient legacy){
        this.db=db;this.json=json;this.legacy=legacy;
    }
    @RabbitListener(queues=PlanEvents.QUEUE)
    public void consume(Message message){
        try {
            var node=json.readTree(message.getBody());
            if("BUDGET_ALERT".equals(node.path("kind").asText())){
                var alert=json.treeToValue(node,FinancesController.BudgetAlert.class);
                addNotice(alert.eventId(),alert.planId(),alert.ownerId(),alert.message());
            } else {
                var event=json.treeToValue(node,PlanLimit.class);
                addNotice(event.eventId(),event.planId(),event.ownerId(),
                    "Límite de "+event.categoryLabel()+" aplicado: S/ "+event.amount());
            }
        } catch(java.io.IOException e){throw new AmqpRejectAndDontRequeueException("Invalid plan event",e);}
    }
    private void addNotice(UUID event,UUID plan,long owner,String message){
        db.update("""
            insert into plan_notices(event_id,plan_id,owner_id,message)
            values(?,?,?,?) on conflict (event_id) do nothing
            """,event,plan,owner,message);
    }
    record Notice(UUID id,UUID planId,String message){}
    @GetMapping("/api/v1/plan-notifications")
    List<Notice> list(@RequestHeader("Authorization") String bearer) {
        return db.query("select event_id,plan_id,message from plan_notices where owner_id=? order by created_at desc limit 30",
            (rs,i)->new Notice(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3)),legacy.owner(bearer));
    }
}
