package com.resolum.intiva.poc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named="INTIVA_TEST_DB",matches=".+")
class PlanPersistenceTest {
    private static final String KEY="local-test-service-secret-not-for-production";

    @Test
    void approvalPersistsOnceAndDeliveryCreatesOneNotice() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("INTIVA_TEST_DB"),
            System.getenv().getOrDefault("INTIVA_TEST_DB_USER","intiva_test"),
            System.getenv().getOrDefault("INTIVA_TEST_DB_PASSWORD",""));
        new ResourceDatabasePopulator(new ClassPathResource("agent.sql"),
            new ClassPathResource("finances.sql"),new ClassPathResource("communications.sql"),new ClassPathResource("savings.sql")).execute(ds);
        var db=new JdbcTemplate(ds);
        var json=new ObjectMapper().findAndRegisterModules();
        var finance=new FinancesController(db,json,KEY);
        var legacy=mock(LegacyClient.class);
        var model=mock(GeminiPlanner.class);
        var owner=System.nanoTime();
        when(legacy.owner("owner")).thenReturn(owner);
        when(legacy.owner("other")).thenReturn(owner+1);
        when(legacy.get("/api/v1/personal-expenses","owner")).thenReturn(json.readTree("""
            [{"month":"2026-11","categoryId":17,"amount":580}]
            """));
        when(legacy.categories(owner,"owner")).thenReturn(List.of(new LegacyClient.Category(17,"Comida")));
        when(model.propose(anyList())).thenReturn(new GeminiPlanner.Advice(17,new BigDecimal("520.00"),"Revisa el límite"));
        when(legacy.get("/api/v1/saving-goals?userId={id}","owner",owner)).thenReturn(json.readTree(
            "[{\"id\":44,\"ownerType\":\"INDIVIDUAL\",\"ownerId\":"+owner+",\"title\":\"Reserva\",\"currencyCode\":\"PEN\"}]"));
        var savings=new PlannedContributionsController(db,KEY);
        var reminders=new PlanRemindersController(db,KEY);
        var calls=new AtomicInteger();
        var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/internal/plan-limits", exchange -> {
            try {
                String key=exchange.getRequestHeaders().getFirst("X-Intiva-Service-Key");
                byte[] result;
                if(exchange.getRequestMethod().equals("GET")) result="{}".getBytes(StandardCharsets.UTF_8);
                else {
                    calls.incrementAndGet();
                    var cmd=json.readValue(exchange.getRequestBody(),PlanLimit.class);
                    result=json.writeValueAsBytes(finance.apply(key,cmd));
                    if(calls.get()==1) { exchange.sendResponseHeaders(503,-1);return; } // Lost confirmation after write.
                }
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);
            } catch(Exception e){exchange.sendResponseHeaders(500,-1);}
            finally{exchange.close();}
        });
        http.createContext("/internal/planned-contributions", exchange -> {
            try {
                var cmd=json.readValue(exchange.getRequestBody(),PlanExtras.Contribution.class);
                byte[] payload=json.writeValueAsBytes(savings.apply(exchange.getRequestHeaders().getFirst("X-Intiva-Service-Key"),cmd));
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,payload.length);exchange.getResponseBody().write(payload);
            } catch(Exception e){exchange.sendResponseHeaders(500,-1);}
            finally{exchange.close();}
        });
        http.createContext("/internal/plan-reminders", exchange -> {
            try {
                var cmd=json.readValue(exchange.getRequestBody(),PlanExtras.Reminder.class);
                byte[] payload=json.writeValueAsBytes(reminders.apply(exchange.getRequestHeaders().getFirst("X-Intiva-Service-Key"),cmd));
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,payload.length);exchange.getResponseBody().write(payload);
            } catch(Exception e){exchange.sendResponseHeaders(500,-1);}
            finally{exchange.close();}
        });
        http.start();
        try {
            var agent=new AgentController(db,legacy,model,"http://127.0.0.1:"+http.getAddress().getPort(),KEY);
            var base="http://127.0.0.1:"+http.getAddress().getPort();
            agent.configureExtras(base,base);
            var draft=agent.generate("owner",new AgentController.Generate("2026-12"));
            assertEquals("DRAFT",draft.status());
            assertEquals(0,db.queryForObject("select count(*) from monthly_limits where owner_id=?",Integer.class,owner));
            assertThrows(ResponseStatusException.class,()->agent.get("other",draft.id()));
            var edited=agent.edit("owner",draft.id(),new AgentController.Update(1,new BigDecimal("510.00"),44L,new BigDecimal("80.00"),java.time.LocalDate.parse("2026-12-15"),"Servicio"));
            assertEquals(2,edited.version());
            assertThrows(ResponseStatusException.class,()->agent.approve("owner",draft.id(),new AgentController.Approval(1)));
            assertEquals("FAILED",agent.approve("owner",draft.id(),new AgentController.Approval(2)).status());
            assertEquals("ACTIVE",agent.approve("owner",draft.id(),new AgentController.Approval(2)).status());
            assertEquals("ACTIVE",agent.approve("owner",draft.id(),new AgentController.Approval(2)).status());
            assertEquals(2,calls.get());
            assertEquals(3,agent.get("owner",draft.id()).actions().stream().filter(a->a.status().equals("APPLIED")).count());
            assertEquals(1,db.queryForObject("select count(*) from planned_contributions where plan_id=?",Integer.class,draft.id()));
            assertEquals(1,db.queryForObject("select count(*) from plan_reminders where plan_id=?",Integer.class,draft.id()));
            assertEquals(1,db.queryForObject("select count(*) from monthly_limits where owner_id=?",Integer.class,owner));
            assertEquals(new BigDecimal("510.00"),db.queryForObject("select amount from monthly_limits where owner_id=?",BigDecimal.class,owner));
            var payload=db.queryForObject("select payload from limit_outbox where plan_id=?",String.class,draft.id());
            var consumer=new CommunicationsController(db,json,legacy);
            var message=MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8)).build();
            consumer.consume(message);consumer.consume(message);
            assertEquals(1,consumer.list("owner").size());
            var expense=new FinancesController.Expense(owner,owner,"2026-12",17,new BigDecimal("510.00"));
            finance.track(KEY,expense);finance.track(KEY,expense);
            assertEquals(1,db.queryForObject("select count(*) from tracked_expenses where owner_id=?",Integer.class,owner));
            var alert=db.queryForObject("select payload from budget_alert_outbox where payload like ?",String.class,"%"+draft.id()+"%");
            var alertMessage=MessageBuilder.withBody(alert.getBytes(StandardCharsets.UTF_8)).build();
            consumer.consume(alertMessage);consumer.consume(alertMessage);
            assertEquals(2,consumer.list("owner").size());
            db.update("update plan_reminders set due_date=current_date-1 where plan_id=?",draft.id());
            reminders.sendDue();reminders.sendDue();
            assertEquals(3,consumer.list("owner").size());
            assertTrue(consumer.list("other").isEmpty());
        } finally {http.stop(0);}
    }
}
