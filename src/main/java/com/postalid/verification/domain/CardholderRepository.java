package com.postalid.verification.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CardholderRepository extends JpaRepository<Cardholder, Long> {

    /**
     * The verification lookup. Derived-query parameter binding keeps the PRN out of any
     * concatenated SQL string.
     */
    Optional<Cardholder> findByPostalRefNo(String postalRefNo);

    Optional<Cardholder> findByRecordId(String recordId);
}
