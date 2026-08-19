package com.postalid.verification.storage;

import com.postalid.verification.config.PostalIdProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The blob store holds identity documents and biometrics, so path handling is a security control
 * rather than a convenience. These tests pin the containment guarantee.
 */
class BlobStoreTest {

    @TempDir
    Path tempDir;

    private BlobStore blobStore;

    @BeforeEach
    void setUp() {
        PostalIdProperties properties = new PostalIdProperties();
        properties.getStorage().setRoot(tempDir.resolve("blobs").toString());
        blobStore = new BlobStore(properties);
    }

    @Test
    void storesAndReadsBack() throws IOException {
        Path source = Files.writeString(tempDir.resolve("source.jpg"), "image-bytes");

        String relative = blobStore.store(source, "REC001", "Photo", "jpg");

        assertThat(relative).isEqualTo("REC001/Photo.jpg");
        assertThat(blobStore.exists(relative)).isTrue();
        assertThat(new String(blobStore.read(relative))).isEqualTo("image-bytes");
    }

    @Test
    void reStoringReplacesTheExistingFile() throws IOException {
        Path first = Files.writeString(tempDir.resolve("first.jpg"), "one");
        Path second = Files.writeString(tempDir.resolve("second.jpg"), "two");

        blobStore.store(first, "REC001", "Photo", "jpg");
        String relative = blobStore.store(second, "REC001", "Photo", "jpg");

        assertThat(new String(blobStore.read(relative))).isEqualTo("two");
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @ValueSource(strings = {
            "../outside.txt",
            "REC001/../../outside.txt",
            "REC001/../../../etc/passwd",
            "./../../secret.key"
    })
    @DisplayName("a stored path that climbs out of the root is refused")
    void rejectsTraversal(String relativePath) {
        assertThatThrownBy(() -> blobStore.read(relativePath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
    }

    @Test
    void rejectsBlankPath() {
        assertThatThrownBy(() -> blobStore.read("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("exists() answers false for a traversal attempt instead of throwing")
    void existsIsSafeForTraversal() {
        assertThat(blobStore.exists("../outside.txt")).isFalse();
    }

    @Test
    void digestsAreStableAndContentDependent() throws IOException {
        Path file = Files.writeString(tempDir.resolve("digest.txt"), "content");

        String fromFile = BlobStore.sha256(file);
        String fromBytes = BlobStore.sha256("content".getBytes());

        assertThat(fromFile).isEqualTo(fromBytes).hasSize(64);
        assertThat(BlobStore.sha256("other".getBytes())).isNotEqualTo(fromBytes);
    }
}
