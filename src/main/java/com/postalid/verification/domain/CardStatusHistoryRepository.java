package com.postalid.verification.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface CardStatusHistoryRepository extends JpaRepository<CardStatusHistory, Long> {

    List<CardStatusHistory> findByCardholderIdOrderByStatusTimestampAsc(Long cardholderId);

    long countByCardholderId(Long cardholderId);

    void deleteByCardholderId(Long cardholderId);

    /**
     * When the card reached an issued status — the date a physical card existed, which is what
     * validity runs from. Latest matching entry wins, so a reissue supersedes the original.
     *
     * @return null when the record has no issued entry in its trail
     */
    @Query("""
           SELECT MAX(h.statusTimestamp)
           FROM   CardStatusHistory h
           WHERE  h.cardholder.id = :cardholderId
             AND  UPPER(TRIM(h.status)) IN :issuedStatuses
           """)
    LocalDateTime findIssuedAt(@Param("cardholderId") Long cardholderId,
                               @Param("issuedStatuses") Collection<String> issuedStatuses);
}
