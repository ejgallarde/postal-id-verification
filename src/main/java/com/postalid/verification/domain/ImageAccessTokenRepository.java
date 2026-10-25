package com.postalid.verification.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ImageAccessTokenRepository extends JpaRepository<ImageAccessToken, Long> {

    Optional<ImageAccessToken> findByTokenHash(String tokenHash);

    /**
     * Spends one use, if the reference is still usable by this holder.
     *
     * <p>A single conditional UPDATE rather than read, decrement, save: under concurrent fetches
     * the read-modify-write version let every racing request see the same count and all succeed,
     * so the use limit was a suggestion. Here the database serialises the decrement.
     *
     * @return 1 when a use was spent, 0 when the reference is unknown, foreign, spent, expired or revoked
     */
    @Modifying
    @Query("""
            UPDATE ImageAccessToken t SET t.usesRemaining = t.usesRemaining - 1
            WHERE t.tokenHash = :tokenHash
              AND t.clientId = :holder
              AND t.revoked = false
              AND t.usesRemaining > 0
              AND t.expiresAt > :now""")
    int spendOneUse(@Param("tokenHash") String tokenHash,
                    @Param("holder") String holder,
                    @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM ImageAccessToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
