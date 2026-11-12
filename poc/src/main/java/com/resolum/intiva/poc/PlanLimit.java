package com.resolum.intiva.poc;

import java.math.BigDecimal;
import java.util.UUID;

record PlanLimit(UUID eventId, UUID planId, int version, long ownerId, String month,
                 long categoryId, String categoryLabel, BigDecimal amount) {}
