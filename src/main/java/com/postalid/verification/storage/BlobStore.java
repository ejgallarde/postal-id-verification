package com.postalid.verification.storage;

import com.postalid.verification.config.PostalIdProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Filesystem store for captured enrollment assets.
 *
 * <p>The database holds a relative path; this class is the only thing that turns one into a real
 * file, and it refuses any path that escapes the configured root. That check matters because the
 * store holds identity documents and biometrics: a traversal bug here would read arbitrary files
 * off the host.
 */
@Component
public class BlobStore {

    private final Path root;

    public BlobStore(PostalIdProperties properties) {
        this.root = Path.of(properties.getStorage().getRoot()).toAbsolutePath().normalize();
    }

    public Path getRoot() {
        return root;
    }

    /**
     * Copies a source file into the store.
     *
     * @return the stored path, relative to the root, for persisting alongside the metadata.
     */
    public String store(Path source, String recordId, String docCode, String extension) throws IOException {
        String relative = recordId + "/" + docCode + (extension.isEmpty() ? "" : "." + extension);
        Path destination = resolveWithinRoot(relative);
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        return relative;
    }

    public byte[] read(String relativePath) throws IOException {
        return Files.readAllBytes(resolveWithinRoot(relativePath));
    }

    public boolean exists(String relativePath) {
        try {
            return Files.isRegularFile(resolveWithinRoot(relativePath));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Resolves a stored relative path against the root, rejecting anything that would land
     * outside it.
     */
    Path resolveWithinRoot(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Blank storage path");
        }
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Storage path escapes the blob store root");
        }
        return resolved;
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new UncheckedIOException(new IOException("SHA-256 unavailable", e));
        }
    }

    public static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
