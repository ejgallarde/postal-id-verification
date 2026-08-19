package com.postalid.verification.ingest;

import com.postalid.verification.config.PostalIdProperties;
import com.postalid.verification.domain.DocumentType;
import com.postalid.verification.domain.DocumentTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads enrollment packets from a directory tree.
 *
 * <p>Packets arrive as one subfolder per enrollee, named for the record id, so the search is
 * recursive. It keys on finding a {@code *_demographics.csv} rather than on the folder's name:
 * the record id is read from inside the CSV, and every asset is located relative to the CSV's own
 * directory. A folder named something unexpected therefore still loads correctly, and a flat
 * directory of packets works just as well as a nested one.
 *
 * <p>This class owns the walk and the error policy; {@link PacketWriter} owns the transaction for
 * each packet. The split is deliberate — the loop stays outside any transaction so one malformed
 * packet rolls back alone while the rest of the batch lands.
 *
 * <p>Nothing here logs a name, a reference number or any other field value. A failed packet is
 * identified by its path and the reason, which is enough to investigate without spilling personal
 * data into the log.
 */
@Service
public class PacketIngestService {

    private static final Logger log = LoggerFactory.getLogger(PacketIngestService.class);
    private static final String DEMOGRAPHICS_SUFFIX = "_demographics.csv";

    private final PacketWriter packetWriter;
    private final DocumentTypeRepository documentTypeRepository;
    private final PostalIdProperties.Ingest config;

    public PacketIngestService(PacketWriter packetWriter,
                               DocumentTypeRepository documentTypeRepository,
                               PostalIdProperties properties) {
        this.packetWriter = packetWriter;
        this.documentTypeRepository = documentTypeRepository;
        this.config = properties.getIngest();
    }

    /** Outcome of an ingest run over a directory tree. */
    public record IngestReport(int packetsFound,
                               int packetsLoaded,
                               int documentsStored,
                               int documentsReferenced,
                               List<String> failures) {

        public int documentsTotal() {
            return documentsStored + documentsReferenced;
        }
    }

    public IngestReport ingestDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new PacketIngestException("Ingest source is not a directory: " + directory);
        }

        Path root = directory.toAbsolutePath().normalize();
        List<Path> packets = findPackets(root);

        Set<String> knownCodes = documentTypeRepository.findAll().stream()
                .map(DocumentType::getCode)
                .collect(Collectors.toSet());

        int loaded = 0;
        int stored = 0;
        int referenced = 0;
        List<String> failures = new ArrayList<>();

        for (Path packet : packets) {
            try {
                PacketWriter.WriteResult result = packetWriter.write(packet, root, knownCodes);
                stored += result.documentsStored();
                referenced += result.documentsReferenced();
                loaded++;
            } catch (Exception e) {
                String message = root.relativize(packet) + ": " + e.getMessage();
                failures.add(message);
                log.error("Packet ingest failed for {}", message);
                if (config.isFailFast()) {
                    throw new PacketIngestException("Ingest aborted on first failure: " + message, e);
                }
            }
        }

        log.info("Ingest complete: {} packet(s) found, {} loaded, {} document(s) copied, "
                        + "{} referenced in place, {} failure(s)",
                packets.size(), loaded, stored, referenced, failures.size());

        return new IngestReport(packets.size(), loaded, stored, referenced, failures);
    }

    /**
     * Finds every packet under the root.
     *
     * <p>Symbolic links are not followed. An archive of identity documents is exactly the place
     * where a stray link could walk somewhere it should not, and there is no legitimate reason for
     * a packet to be reachable only through one.
     */
    private List<Path> findPackets(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root, config.getMaxDepth())) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(DEMOGRAPHICS_SUFFIX))
                    // Stable order so a re-run processes packets the same way twice, which makes
                    // a partial batch easy to reason about.
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }
}
