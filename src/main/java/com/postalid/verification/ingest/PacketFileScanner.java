package com.postalid.verification.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the asset files belonging to one enrollment packet.
 *
 * <p>Every file in a packet is named {@code {RecordID}_{Suffix}.{ext}}, so the record id is the
 * only thing needed to gather them. The suffix is what identifies the kind of file, and it is
 * taken from the filename on disk rather than from the packet's own manifest: the sample manifest
 * lists assets as {@code .jpg} that actually ship as {@code .tiff}, so trusting it would mean
 * looking for files that are not there.
 */
@Component
public class PacketFileScanner {

    private static final Logger log = LoggerFactory.getLogger(PacketFileScanner.class);

    /** Suffixes that are packet metadata rather than captured assets. */
    private static final Set<String> NON_ASSET_SUFFIXES = Set.of("demographics", "_demostatusdtl", "demostatusdtl");

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "tif", "image/tiff",
            "tiff", "image/tiff",
            "bmp", "image/bmp",
            // Wavelet Scalar Quantization: a fingerprint format with no registered media type
            // and no browser support. Stored, never served.
            "wsq", "application/octet-stream");

    /** One asset file found next to a packet's demographics CSV. */
    public record PacketAsset(Path file, String docCode, String extension, String contentType) {
    }

    /**
     * @param packetDir  directory holding the packet
     * @param recordId   the packet's record id, which prefixes every filename
     * @param knownCodes document codes present in the {@code document_type} table; anything else
     *                   is skipped with a warning rather than failing the packet, so an unfamiliar
     *                   attachment does not block ingestion of a valid record
     */
    public List<PacketAsset> scan(Path packetDir, String recordId, Set<String> knownCodes) throws IOException {
        List<PacketAsset> assets = new ArrayList<>();
        String prefix = recordId + "_";

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(packetDir, prefix + "*")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String filename = file.getFileName().toString();
                String remainder = filename.substring(prefix.length());

                int dot = remainder.lastIndexOf('.');
                String suffix = dot >= 0 ? remainder.substring(0, dot) : remainder;
                String extension = dot >= 0 ? remainder.substring(dot + 1).toLowerCase(Locale.ROOT) : "";

                if (NON_ASSET_SUFFIXES.contains(suffix)) {
                    continue;
                }

                String docCode = resolveCode(suffix, knownCodes);
                if (docCode == null) {
                    log.warn("Skipping {}: document code '{}' is not registered in document_type",
                            filename, suffix);
                    continue;
                }

                assets.add(new PacketAsset(file, docCode, extension,
                        CONTENT_TYPES.getOrDefault(extension, "application/octet-stream")));
            }
        }
        return assets;
    }

    /** Matches the filename suffix to a registered code, case-insensitively. */
    private String resolveCode(String suffix, Set<String> knownCodes) {
        if (knownCodes.contains(suffix)) {
            return suffix;
        }
        for (String code : knownCodes) {
            if (code.equalsIgnoreCase(suffix)) {
                return code;
            }
        }
        return null;
    }
}
