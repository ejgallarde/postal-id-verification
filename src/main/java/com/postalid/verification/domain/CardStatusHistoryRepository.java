package com.postalid.verification.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CardStatusHistoryRepository extends JpaRepository<CardStatusHistory, Long> {

    List<CardStatusHistory> findByCardholderIdOrderByStatusTimestampAsc(Long cardholderId);

    long countByCardholderId(Long cardholderId);

    void deleteByCardholderId(Long cardholderId);
}
