package com.postalid.verification.sandbox;

import com.postalid.verification.config.PostalIdProperties;
import com.postalid.verification.domain.CardholderRepository;
import com.postalid.verification.ingest.PacketIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * Loads the sandbox's synthetic cardholders at startup.
 *
 * <p>The sandbox exists to be handed to outside parties, so the one property it must never lose is
 * that it holds nothing real. Three guards enforce that, and each stops startup rather than
 * logging a warning:
 *
 * <ol>
 *   <li>The {@code ingest} profile may not be active alongside it, since that would load real
 *       packets from the ingest source directory.</li>
 *   <li>The database may hold no record the catalogue does not list. A sandbox pointed by
 *       mistake at a copy of production data refuses to serve it, since real records are not in
 *       the catalogue.</li>
 *   <li>The catalogue may not list the same reference number twice (checked by
 *       {@link SandboxCatalogue}).</li>
 * </ol>
 *
 * <p>The guard keys on catalogue membership, not on a reference-number prefix, so testers' own
 * expected values can be used as given. The trust that leaves is in the catalogue itself: every
 * row in it must be fictional.
 *
 * <p>Seeding runs as a startup runner, so readiness stays down until it finishes and
 * {@link com.postalid.verification.web.ReadinessGateFilter} answers early callers with 503 rather
 * than a misleading INVALID. Re-running is safe: ingest updates existing records in place.
 */
@Component
@Profile("sandbox")
public class SandboxSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SandboxSeeder.class);

    private final SandboxCatalogue catalogue;
    private final SyntheticPacketWriter packetWriter;
    private final PacketIngestService ingestService;
    private final CardholderRepository cardholderRepository;
    private final Environment environment;
    private final PostalIdProperties.Sandbox config;

    public SandboxSeeder(SandboxCatalogue catalogue,
                         SyntheticPacketWriter packetWriter,
                         PacketIngestService ingestService,
                         CardholderRepository cardholderRepository,
                         Environment environment,
                         PostalIdProperties properties) {
        this.catalogue = catalogue;
        this.packetWriter = packetWriter;
        this.ingestService = ingestService;
        this.cardholderRepository = cardholderRepository;
        this.environment = environment;
        this.config = properties.getSandbox();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (environment.acceptsProfiles(Profiles.of("ingest"))) {
            throw new IllegalStateException("The sandbox and ingest profiles cannot be active together: "
                    + "ingest loads real enrollment packets, and the sandbox must hold only synthetic data.");
        }
        List<SandboxCardholder> cardholders = catalogue.load();
        assertHoldsOnlyCatalogueRecords(cardholders.stream().map(SandboxCardholder::postalRefNo).toList());

        Path packetRoot = Path.of(config.getPacketDir()).toAbsolutePath().normalize();
        for (SandboxCardholder cardholder : cardholders) {
            packetWriter.write(packetRoot, cardholder);
        }

        PacketIngestService.IngestReport report = ingestService.ingestDirectory(packetRoot);
        if (!report.failures().isEmpty()) {
            // A partially loaded sandbox hands testers wrong answers for whatever is missing.
            throw new IllegalStateException("Sandbox seeding failed for " + report.failures().size()
                    + " packet(s): " + report.failures());
        }

        log.info("Sandbox ready: {} synthetic cardholder(s) loaded from classpath:{} via {}",
                report.packetsLoaded(), SandboxCatalogue.LOCATION, packetRoot);
    }

    /**
     * Refuses to continue if the database already holds a record the catalogue does not list.
     * Package-private so the guard can be tested without a database.
     */
    void assertHoldsOnlyCatalogueRecords(Collection<String> cataloguePrns) {
        long foreign = cardholderRepository.countByPostalRefNoNotIn(cataloguePrns);
        if (foreign > 0) {
            throw new IllegalStateException("Refusing to start the sandbox: the database holds " + foreign
                    + " record(s) that the sandbox catalogue does not list. Point the sandbox at its own "
                    + "empty database, or delete ./data/sandbox if a row was removed from the catalogue.");
        }
    }
}
