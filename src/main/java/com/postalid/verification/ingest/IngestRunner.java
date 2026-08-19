package com.postalid.verification.ingest;

import com.postalid.verification.config.PostalIdProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Runs a one-off ingest at startup when the {@code ingest} profile is active.
 *
 * <p>Gated behind a profile so a normally-serving instance never touches the source directory:
 *
 * <pre>mvn spring-boot:run -Dspring-boot.run.profiles=dev,ingest</pre>
 */
@Component
@Profile("ingest")
public class IngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestRunner.class);

    private final PacketIngestService ingestService;
    private final PostalIdProperties properties;

    public IngestRunner(PacketIngestService ingestService, PostalIdProperties properties) {
        this.ingestService = ingestService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path source = Path.of(properties.getIngest().getSourceDir()).toAbsolutePath().normalize();
        log.info("Ingest profile active; scanning {}", source);

        PacketIngestService.IngestReport report = ingestService.ingestDirectory(source);

        log.info("Ingest report: found={}, loaded={}, documents={} (copied={}, referenced={}), failures={}",
                report.packetsFound(), report.packetsLoaded(), report.documentsTotal(),
                report.documentsStored(), report.documentsReferenced(), report.failures().size());
        report.failures().forEach(failure -> log.warn("  failure: {}", failure));
    }
}
