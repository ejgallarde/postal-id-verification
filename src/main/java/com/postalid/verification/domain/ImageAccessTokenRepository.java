package com.postalid.verification.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ImageAccessTokenRepository extends JpaRepository<ImageAccessToken, Long> {

    Optional<ImageAccessToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("DELETE FROM ImageAccessToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
