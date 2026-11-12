package com.resolum.intiva.poc;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
@Profile("agent")
class AgentController {
    private final JdbcTemplate db;
    private final LegacyClient legacy;
    private final GeminiPlanner ai;
    private final RestClient finances;
    private final String key;
    private RestClient savings;
    private RestClient communications;

    @org.springframework.beans.factory.annotation.Autowired
    void configureExtras(@Value("${intiva.savings}") String savingsUrl,
                         @Value("${intiva.communications}") String communicationsUrl){
        savings=LegacyClient.client(savingsUrl);
        communications=LegacyClient.client(communicationsUrl);
    }

    AgentController(JdbcTemplate db, LegacyClient legacy, GeminiPlanner ai,
                    @Value("${intiva.finances}") String url,@Value("${intiva.service-key}") String key) {
        this.db=db;this.legacy=legacy;this.ai=ai;this.finances=LegacyClient.client(url);this.key=key;
        if(key.length()<24) throw new IllegalArgumentException("Set INTIVA_SERVICE_KEY (at least 24 characters)");
    }
    record SuggestionRequest(String description,BigDecimal amount,String currency){}
    record Suggestion(String status,Long categoryId,String label){}
    record Generate(String month){}
    record Update(int version,BigDecimal proposedLimit,Long goalId,BigDecimal contribution,
                  LocalDate reminderDate,String reminderLabel) {
        Update(int version,BigDecimal proposedLimit){this(version,proposedLimit,null,null,null,null);}
    }
    record Goal(long id,String label){}
    record Approval(int version){}
    record Action(String id,String type,long categoryId,String label,BigDecimal currentLimit,BigDecimal projectedExpense,
                  BigDecimal proposedLimit,String currency,String status){}
    record View(UUID id,int version,String month,String status,String explanation,List<Action> actions){}
    record Plan(UUID id,long owner,String month,int version,String status,long categoryId,String label,
                BigDecimal projected,BigDecimal proposed,BigDecimal previous,String explanation,UUID eventId,
                Long goalId,String goalLabel,BigDecimal contribution,LocalDate reminderDate,String reminderLabel,
                boolean limitApplied,boolean contributionApplied,boolean reminderApplied){
        private String progress(boolean applied){
            return applied || status.equals("ACTIVE")?"APPLIED":switch(status){
                case "FAILED","APPLYING"->"PENDING";default->"PROPOSED";};
        }
        View view(){
            var actions=new ArrayList<Action>();
            actions.add(new Action("limit","SPENDING_LIMIT",categoryId,label,previous,projected,proposed,"PEN",progress(limitApplied)));
            if(goalId!=null)actions.add(new Action("contribution","PLANNED_CONTRIBUTION",goalId,goalLabel,null,null,
                contribution,"PEN",progress(contributionApplied)));
            if(reminderDate!=null)actions.add(new Action("reminder","PAYMENT_REMINDER",0,reminderDate+" · "+reminderLabel,
                null,null,null,"PEN",progress(reminderApplied)));
            return new View(id,version,month,status,explanation,actions);
        }
    }
    record Expense(YearMonth month,long category,BigDecimal amount){}
    record Insights(String period, int smallExpenseCount, BigDecimal smallExpenseTotal, String note){}

    @PostMapping("/category-suggestions")
    Suggestion suggest(@RequestHeader("Authorization") String token,@RequestBody SuggestionRequest req){
        long owner=legacy.owner(token);
        if(req.description()==null || req.description().isBlank() || req.description().length()>180 ||
            !validAmount(req.amount()) || !"PEN".equals(req.currency())) throw invalid("Ingresa una descripciÃ³n y un monto en soles.");
        var categories=legacy.categories(owner,token);
        long id=ai.classify(req.description(),categories);
        return categories.stream().filter(c->c.id()==id).findFirst()
            .map(c->new Suggestion("SUGGESTED",c.id(),c.label())).orElse(new Suggestion("UNAVAILABLE",null,null));
    }
    @GetMapping("/financial-insights")
    Insights insights(@RequestHeader("Authorization") String token,@RequestParam String month) {
        legacy.owner(token); var period=month(month);
        var all=history(token).stream().filter(e->e.month().equals(period)).toList();
        if(all.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"No hay gastos en soles para ese mes.");
        var small=all.stream().filter(e->e.amount().compareTo(new BigDecimal("10.00"))<=0).toList();
        return new Insights(month,small.size(),small.stream().map(Expense::amount).reduce(BigDecimal.ZERO,BigDecimal::add),
            "El resumen considera gastos de hasta S/ 10. Revisa si se repiten antes de ajustar una meta.");
    }
    @PostMapping("/monthly-plans")
    @ResponseStatus(HttpStatus.CREATED)
    View generate(@RequestHeader("Authorization") String token,@RequestBody Generate req){
        long owner=legacy.owner(token); var month=month(req.month());
        var history=history(token).stream().filter(e->e.month().isBefore(month) &&
            !e.month().isBefore(month.minusMonths(3))).toList();
        var categories=legacy.categories(owner,token);
        var totals=new HashMap<Long,BigDecimal>();
        for(var e:history) totals.merge(e.category(),e.amount(),BigDecimal::add);
        long periods=history.stream().map(Expense::month).distinct().count();
        var projections=categories.stream().filter(c->totals.containsKey(c.id())).limit(40).map(c->new GeminiPlanner.Projection(
            c.id(),c.label(),totals.get(c.id()).divide(BigDecimal.valueOf(periods),2,RoundingMode.HALF_UP),
            currentLimit(owner,month.toString(),c.id()))).toList();
        if(projections.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "Registra gastos con categorÃ­a en los Ãºltimos tres meses antes de generar un plan.");
        var advice=ai.propose(projections);
        var selected=projections.stream().filter(x->x.categoryId()==advice.categoryId()).findFirst().orElseThrow();
        var id=UUID.randomUUID();
        db.update("""
            insert into monthly_plans(id,owner_id,month,version,status,category_id,label,projected,proposed,previous,explanation,event_id)
            values(?,?,?,1,'DRAFT',?,?,?,?,?,?,?)
            """,id,owner,req.month(),selected.categoryId(),selected.label(),selected.expected(),advice.proposedLimit(),
            selected.currentLimit(),advice.explanation(),UUID.randomUUID());
        return load(id,owner).view();
    }
    @GetMapping("/monthly-plans/{id}")
    View get(@RequestHeader("Authorization") String token,@PathVariable UUID id){return load(id,legacy.owner(token)).view();}
    @GetMapping("/planning-goals")
    List<Goal> goals(@RequestHeader("Authorization") String token){
        return goalsFor(legacy.owner(token),token);
    }
    private List<Goal> goalsFor(long owner,String token){
        var result=new ArrayList<Goal>();
        for(var g:legacy.get("/api/v1/saving-goals?userId={id}",token,owner))
            if(g.path("actorUserId").asLong()==owner && "INDIVIDUAL".equalsIgnoreCase(g.path("ownerType").asText()) &&
                "PEN".equalsIgnoreCase(g.path("currencyCode").asText()) && "INPROGRESS".equals(g.path("status").asText()) &&
                g.path("id").asLong()>0){
                String title=g.path("title").asText();
                if(!title.isBlank()) result.add(new Goal(g.path("id").asLong(),title.substring(0,Math.min(200,title.length()))));
            }
        return result;
    }
    @PatchMapping("/monthly-plans/{id}")
    View edit(@RequestHeader("Authorization") String token,@PathVariable UUID id,@RequestBody Update req){
        long owner=legacy.owner(token); var plan=load(id,owner);
        if(!validAmount(req.proposedLimit()))throw invalid("El lÃ­mite debe ser positivo y tener hasta dos decimales.");
        if((req.goalId()==null)!=(req.contribution()==null) || req.contribution()!=null && !validAmount(req.contribution()))
            throw invalid("Elige una meta y un aporte válido.");
        String goalLabel=req.goalId()==null?null:goalsFor(owner,token).stream()
            .filter(g->g.id()==req.goalId()).findFirst().orElseThrow(()->invalid("Meta no disponible.")).label();
        String label=req.reminderLabel()==null?null:req.reminderLabel().strip();
        if((req.reminderDate()==null)!=(label==null) || label!=null &&
            (label.isBlank() || label.length()>120 || !YearMonth.from(req.reminderDate()).toString().equals(plan.month())))
            throw invalid("Indica un recordatorio dentro del mes del plan.");
        int changed=db.update("""
            update monthly_plans set proposed=?,goal_id=?,goal_label=?,contribution=?,reminder_date=?,reminder_label=?,version=version+1
            where id=? and owner_id=? and version=? and status='DRAFT'
            """,req.proposedLimit(),req.goalId(),goalLabel,req.contribution(),req.reminderDate(),label,id,owner,req.version());
        if(changed==0) throw conflict();
        return load(id,owner).view();
    }
    @PostMapping("/monthly-plans/{id}/approve")
    View approve(@RequestHeader("Authorization") String token,@PathVariable UUID id,@RequestBody Approval req){
        long owner=legacy.owner(token); var plan=load(id,owner);
        if(plan.version()!=req.version()) throw conflict();
        if(plan.status().equals("ACTIVE")) return plan.view();
        db.update("""
            update monthly_plans set status='APPLYING' where id=? and owner_id=? and version=? and status in ('DRAFT','FAILED')
            """,id,owner,req.version());
        plan=load(id,owner);
        if(plan.version()!=req.version()) throw conflict();
        if(plan.status().equals("ACTIVE")) return plan.view();
        if(!plan.status().equals("APPLYING")) throw conflict();
        try {
            if(!plan.limitApplied()){
                apply(finances,"/internal/plan-limits",new PlanLimit(plan.eventId(),plan.id(),plan.version(),owner,plan.month(),
                    plan.categoryId(),plan.label(),plan.proposed()));
                db.update("update monthly_plans set limit_applied=true where id=?",id);
            }
            if(plan.goalId()!=null && !plan.contributionApplied()){
                apply(savings,"/internal/planned-contributions",new PlanExtras.Contribution(id,owner,plan.goalId(),plan.month(),plan.contribution()));
                db.update("update monthly_plans set contribution_applied=true where id=?",id);
            }
            if(plan.reminderDate()!=null && !plan.reminderApplied()){
                apply(communications,"/internal/plan-reminders",new PlanExtras.Reminder(id,owner,plan.reminderDate(),plan.reminderLabel()));
                db.update("update monthly_plans set reminder_applied=true where id=?",id);
            }
        } catch(RuntimeException e) {
            db.update("update monthly_plans set status='FAILED' where id=? and owner_id=? and version=? and status='APPLYING'",
                id,owner,req.version());
            return load(id,owner).view();
        }
        db.update("update monthly_plans set status='ACTIVE' where id=? and owner_id=? and version=? and status in ('APPLYING','FAILED')",
            id,owner,req.version());
        return load(id,owner).view();
    }
    private void apply(RestClient target,String path,Object command){
        var response=target.post().uri(path).header("X-Intiva-Service-Key",key)
            .body(command).retrieve().body(JsonNode.class);
        if(response==null || !"APPLIED".equals(response.path("status").asText()))
            throw new IllegalStateException("Action was not confirmed");
    }
    private BigDecimal currentLimit(long owner,String month,long category) {
        try {
            var result=finances.get().uri("/internal/plan-limits?ownerId={o}&month={m}&categoryId={c}",owner,month,category)
                .header("X-Intiva-Service-Key",key).retrieve().body(JsonNode.class);
            if(result==null || !result.path("amount").isNumber())return null;
            return result.path("amount").decimalValue();
        } catch(RuntimeException ex){
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"No se pudo consultar el lÃ­mite vigente en Finanzas.");
        }
    }
    private List<Expense> history(String token) {
        var result=new ArrayList<Expense>();
        var response=legacy.get("/api/v1/personal-expenses",token);
        for(var tx:response) {
            try {
                var period=YearMonth.parse(tx.path("month").asText());
                var amount=tx.path("amount").decimalValue();
                if(amount.signum()>0) result.add(new Expense(period,tx.path("categoryId").asLong(),amount));
            }catch(DateTimeException e){/* Ignore incomplete historical records. */}
        }
        return result;
    }
    private Plan load(UUID id,long owner){
        return db.query("""
            select * from monthly_plans where id=? and owner_id=?
            """,(rs,i)->new Plan(rs.getObject("id",UUID.class),owner,rs.getString("month"),rs.getInt("version"),
                rs.getString("status"),rs.getLong("category_id"),rs.getString("label"),rs.getBigDecimal("projected"),
                rs.getBigDecimal("proposed"),rs.getBigDecimal("previous"),rs.getString("explanation"),
                rs.getObject("event_id",UUID.class),rs.getObject("goal_id",Long.class),rs.getString("goal_label"),
                rs.getBigDecimal("contribution"),rs.getObject("reminder_date",LocalDate.class),rs.getString("reminder_label"),
                rs.getBoolean("limit_applied"),rs.getBoolean("contribution_applied"),rs.getBoolean("reminder_applied")),id,owner)
            .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    static boolean validAmount(BigDecimal v){return v!=null && v.signum()>0 && v.scale()<=2 && v.compareTo(new BigDecimal("10000000"))<0;}
    private static YearMonth month(String v){
        try { if(v!=null && v.matches("[0-9]{4}-[0-9]{2}"))return YearMonth.parse(v); }
        catch(DateTimeException ignored){}
        throw invalid("Indica el mes con formato AAAA-MM.");
    }
    private static ResponseStatusException invalid(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"El plan cambiÃ³. Consulta la versiÃ³n vigente.");}
}
