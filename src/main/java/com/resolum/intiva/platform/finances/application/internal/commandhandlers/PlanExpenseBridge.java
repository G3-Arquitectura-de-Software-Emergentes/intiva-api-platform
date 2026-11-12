package com.resolum.intiva.platform.finances.application.internal.commandhandlers;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

/** Transitional outbox: no access from the new Finances service to the legacy database. */
@Component
@ConditionalOnProperty(name="intiva.plan-tracking.enabled",havingValue="true")
class PlanExpenseBridge {
    record Expense(long transactionId,long ownerId,String month,long categoryId,BigDecimal amount){}
    private final JdbcTemplate db;
    private final RestClient finances;
    private final String key;
    PlanExpenseBridge(JdbcTemplate db,@Value("${intiva.plan-tracking.finances-url}") String url,
                      @Value("${intiva.plan-tracking.service-key}") String key){
        this.db=db;this.key=key;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));factory.setReadTimeout(Duration.ofSeconds(5));
        finances=RestClient.builder().baseUrl(url).requestFactory(factory).build();
        db.execute("""
            create table if not exists plan_expense_outbox (
              transaction_id bigint primary key, owner_id bigint not null, month varchar(7) not null,
              category_id bigint not null, amount numeric(12,2) not null, sent_at timestamptz
            )
            """);
    }
    @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
    public void record(Expense e){
        db.update("insert into plan_expense_outbox(transaction_id,owner_id,month,category_id,amount) values(?,?,?,?,?) on conflict do nothing",
            e.transactionId(),e.ownerId(),e.month(),e.categoryId(),e.amount());
    }
    @Scheduled(fixedDelay=3000)
    @Transactional
    public void forward(){
        var pending=db.query("""
            select transaction_id,owner_id,month,category_id,amount from plan_expense_outbox where sent_at is null
            order by transaction_id limit 1 for update skip locked
            """,(rs,i)->new Expense(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getLong(4),rs.getBigDecimal(5)));
        for(var e:pending){
            finances.post().uri("/internal/registered-expenses").header("X-Intiva-Service-Key",key)
                .body(e).retrieve().toBodilessEntity();
            db.update("update plan_expense_outbox set sent_at=now() where transaction_id=?",e.transactionId());
        }
    }
}
