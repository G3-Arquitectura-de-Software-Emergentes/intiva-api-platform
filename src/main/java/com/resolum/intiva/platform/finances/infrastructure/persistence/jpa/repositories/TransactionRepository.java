package com.resolum.intiva.platform.finances.infrastructure.persistence.jpa.repositories;

import com.resolum.intiva.platform.finances.domain.model.aggregates.Transaction;
import com.resolum.intiva.platform.shared.domain.valueobjects.TransactionTypes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for managing Transaction entities in the database.
 * This interface extends JpaRepository, providing CRUD operations and custom query methods for Transaction entities.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByOwnerIdAndOwnerTypesAndTransactionType(Long ownerId,
            com.resolum.intiva.platform.shared.domain.valueobjects.OwnerTypes ownerTypes,
            TransactionTypes transactionType);

    /**
     * Finds a transaction list by its owner ID.
     * @param ownerId The ID of the owner of the transaction.
     * @return An Optional containing the Transaction if found, or empty if not found.
     */
    List<Transaction> findTransactionByOwnerId(Long ownerId);

    /**
     * Finds a transaction list by its owner ID and transaction type.
     * @param ownerId The ID of the owner of the transaction.
     * @param transactionType The type of the transaction (e.g., EXPENSE, INCOME).
     * @return An Optional containing the Transaction if found, or empty if not found.
     */
    List<Transaction> findTransactionByOwnerIdAndTransactionType(Long ownerId, TransactionTypes transactionType);

    /**
     * Finds the top 5 transactions by owner ID, ordered by creation date in descending order.
     * @param ownerId The ID of the owner of the transactions.
     * @return A list of the top 5 transactions for the specified owner, ordered by creation date.
     */
    List<Transaction> findTop5ByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    /**
     * Finds a transaction by its client operation ID.
     * @param clientOperationId The client operation ID of the transaction.
     * @return An Optional containing the Transaction if found, or empty if not found.
     */
    Optional<Transaction> findByClientOperationId(String clientOperationId);

    /**
     * Checks if a transaction exists with the given client operation ID.
     * @param clientOperationId The client operation ID to check for existence.
     * @return true if a transaction with the specified client operation ID exists, false otherwise.
     */
    boolean existsByClientOperationId(String clientOperationId);
}
