package com.resolum.intiva.poc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Configuration
@Profile({"finances","communications"})
class PlanEvents {
    static final String EXCHANGE="intiva.events", KEY="plan.limit.applied", QUEUE="communications.plan-limit-applied";
    @Bean TopicExchange intivaEvents(){return new TopicExchange(EXCHANGE);}
    @Bean Queue planEventsQueue(){return QueueBuilder.durable(QUEUE).build();}
    @Bean Binding planEventsBinding(TopicExchange exchange,Queue queue){return BindingBuilder.bind(queue).to(exchange).with(KEY);}
}

@Component
@Profile("finances")
class LimitOutboxPublisher {
    private final JdbcTemplate db;
    private final RabbitTemplate broker;
    LimitOutboxPublisher(JdbcTemplate db,RabbitTemplate broker){this.db=db;this.broker=broker;}
    record Event(UUID id,String payload){}
    @Scheduled(fixedDelay=3000)
    @Transactional
    public void publish() throws Exception {
        var events=db.query("""
            select event_id,payload from limit_outbox where sent_at is null
            order by created_at limit 1 for update skip locked
            """,(rs,i)->new Event(rs.getObject(1,UUID.class),rs.getString(2)));
        for(var e:events){
            var correlation=new CorrelationData(e.id().toString());
            var message=MessageBuilder.withBody(e.payload().getBytes(StandardCharsets.UTF_8))
                    .setContentType("application/json").setMessageId(e.id().toString())
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
            broker.send(PlanEvents.EXCHANGE,PlanEvents.KEY,message,correlation);
            if(!correlation.getFuture().get(5,TimeUnit.SECONDS).isAck() || correlation.getReturned()!=null)
                throw new IllegalStateException("Limit event was not routed; outbox will retry");
            db.update("update limit_outbox set sent_at=now() where event_id=?",e.id());
        }
    }
}
