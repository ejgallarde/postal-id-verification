package com.postalid.verification.verify;

import com.postalid.verification.config.PostalIdProperties;
import com.postalid.verification.domain.CardStatusHistoryRepository;
import com.postalid.verification.domain.Cardholder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Works out a card's validity window.
 *
 * <p>The source data carries no expiry, so this derives one: the date the card was produced plus a
 * configured period. Production date comes from the status trail — the entry where the record
 * reached an issued status — because validity runs from when a card existed, not from when someone
 * applied. Where that entry is missing, the enrollment date stands in, and where neither exists the
 * window is reported as unknown rather than guessed.
 *
 * <p>Everything here is inference. It is labelled as such all the way out to the API response, so
 * that nobody mistakes a computed date for one the Postal Service issued.
 */
@Component
public class CardValidityResolver {

    private final CardStatusHistoryRepository statusRepository;
    private final PostalIdProperties.Verify config;
    private final Clock clock;

    @Autowired
    public CardValidityResolver(CardStatusHistoryRepository statusRepository,
                                PostalIdProperties properties) {
        this(statusRepository, properties, Clock.system(properties.getVerify().getTimeZone()));
    }

    /** Test seam: lets a fixed clock be injected so expiry assertions are deterministic. */
    CardValidityResolver(CardStatusHistoryRepository statusRepository,
                         PostalIdProperties properties,
                         Clock clock) {
        this.statusRepository = statusRepository;
        this.config = properties.getVerify();
        this.clock = clock;
    }

    public CardValidityWindow resolve(Cardholder cardholder) {
        LocalDate issuedOn = resolveIssueDate(cardholder);
        if (issuedOn == null) {
            return CardValidityWindow.unknown();
        }

        Period validity = config.getValidityPeriod();
        if (validity == null || validity.isZero() || validity.isNegative()) {
            return new CardValidityWindow(issuedOn, null, false, true);
        }

        LocalDate expiresOn = issuedOn.plus(validity);
        boolean expired = LocalDate.now(clock).isAfter(expiresOn);
        return new CardValidityWindow(issuedOn, expiresOn, expired, true);
    }

    /** Production date if the trail records one; otherwise the enrollment date. */
    private LocalDate resolveIssueDate(Cardholder cardholder) {
        Set<String> issuedStatuses = config.getIssuedStatuses().stream()
                .map(status -> status.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());

        LocalDateTime issuedAt = cardholder.getId() == null
                ? null
                : statusRepository.findIssuedAt(cardholder.getId(), issuedStatuses);

        if (issuedAt != null) {
            return issuedAt.toLocalDate();
        }
        return cardholder.getEnrollmentDate();
    }
}
