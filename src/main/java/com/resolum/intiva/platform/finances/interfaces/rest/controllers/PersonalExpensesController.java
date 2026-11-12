package com.resolum.intiva.platform.finances.interfaces.rest.controllers;

import com.resolum.intiva.platform.finances.infrastructure.persistence.jpa.repositories.TransactionRepository;
import com.resolum.intiva.platform.iam.domain.model.queries.GetUserByEmailQuery;
import com.resolum.intiva.platform.iam.domain.model.valueobjects.Email;
import com.resolum.intiva.platform.iam.domain.services.UserQueryService;
import com.resolum.intiva.platform.shared.domain.valueobjects.OwnerTypes;
import com.resolum.intiva.platform.shared.domain.valueobjects.TransactionTypes;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/personal-expenses")
class PersonalExpensesController {
    private static final ZoneId LIMA=ZoneId.of("America/Lima");
    private final TransactionRepository transactions;
    private final UserQueryService users;
    PersonalExpensesController(TransactionRepository transactions,UserQueryService users) {
        this.transactions=transactions;this.users=users;
    }
    record Expense(String month,long categoryId,BigDecimal amount){}
    @GetMapping
    List<Expense> get(Principal principal) {
        if(principal==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        long owner=users.handle(new GetUserByEmailQuery(new Email(principal.getName())))
            .orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
        return transactions.findByOwnerIdAndOwnerTypesAndTransactionType(owner,OwnerTypes.INDIVIDUAL,TransactionTypes.EXPENSE)
            .stream().filter(t->"PEN".equalsIgnoreCase(t.getAmount().getCurrencyCode()))
            .map(t->new Expense(YearMonth.from((t.getOccurredAt()==null?t.getCreatedAt():t.getOccurredAt()).atZone(LIMA)).toString(),
                t.getCategoryId().getValue(),t.getAmount().getAmount())).toList();
    }
}
