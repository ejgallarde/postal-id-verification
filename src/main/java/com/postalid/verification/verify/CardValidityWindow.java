package com.postalid.verification.verify;

import java.time.LocalDate;

/**
 * When a card was issued and when it lapses.
 *
 * <p>Both dates are derived. The enrollment export has no expiry column, so the window is the
 * issue date plus a configured period — which is why {@link #known()} exists at all: a record
 * whose status trail never recorded production has no issue date, and nothing honest can be said
 * about its expiry.
 */
public record CardValidityWindow(LocalDate issuedOn, LocalDate expiresOn, boolean expired, boolean known) {

    public static CardValidityWindow unknown() {
        return new CardValidityWindow(null, null, false, false);
    }
}
