package com.postalid.verification.sandbox;

import com.postalid.verification.config.PostalIdProperties;
import com.postalid.verification.domain.CardholderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The guards that keep real enrollment data out of a sandbox. These are the controls that matter
 * most in this package: a sandbox is handed to outsiders, so the failure they prevent is a
 * disclosure, not a bug.
 */
class SandboxSeederGuardTest {

    private static final List<String> CATALOGUE = List.of("SBX000000001", "N41260423504");

    private static SandboxSeeder seederWith(CardholderRepository repository, MockEnvironment environment) {
        PostalIdProperties properties = new PostalIdProperties();
        return new SandboxSeeder(null, null, null, repository, environment, properties);
    }

    @Test
    @DisplayName("refuses to start against a database holding any record the catalogue does not list")
    void refusesRecordsOutsideTheCatalogue() {
        CardholderRepository repository = mock(CardholderRepository.class);
        when(repository.countByPostalRefNoNotIn(CATALOGUE)).thenReturn(1L);

        assertThatThrownBy(() -> seederWith(repository, new MockEnvironment()).assertHoldsOnlyCatalogueRecords(CATALOGUE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not list");
    }

    @Test
    @DisplayName("starts against an empty database or one holding only catalogue records")
    void acceptsCatalogueOnlyDatabase() {
        CardholderRepository repository = mock(CardholderRepository.class);
        when(repository.countByPostalRefNoNotIn(CATALOGUE)).thenReturn(0L);

        assertThatCode(() -> seederWith(repository, new MockEnvironment()).assertHoldsOnlyCatalogueRecords(CATALOGUE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses to run alongside the ingest profile, which would load real packets")
    void refusesIngestProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("sandbox", "ingest");

        assertThatThrownBy(() -> seederWith(mock(CardholderRepository.class), environment).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be active together");
    }
}
