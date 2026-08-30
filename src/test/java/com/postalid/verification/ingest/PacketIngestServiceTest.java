package com.postalid.verification.ingest;

import com.postalid.verification.domain.CardStatusHistory;
import com.postalid.verification.domain.CardStatusHistoryRepository;
import com.postalid.verification.domain.Cardholder;
import com.postalid.verification.domain.CardholderDocument;
import com.postalid.verification.domain.CardholderDocumentRepository;
import com.postalid.verification.domain.CardholderRepository;
import com.postalid.verification.domain.StorageKind;
import com.postalid.verification.storage.BlobStore;
import com.postalid.verification.testsupport.SyntheticPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deliberately <em>not</em> {@code @Transactional}.
 *
 * <p>Wrapping these tests in a transaction would supply the very thing the production path has to
 * provide for itself, and would hide a missing {@code @Transactional} on the code under test. The
 * cost is having to clean up between tests by hand, which is cheap next to shipping an ingest that
 * fails on the second run.
 */
@SpringBootTest
@ActiveProfiles("test")
class PacketIngestServiceTest {

    @TempDir
    Path packetDir;

    @Autowired PacketIngestService ingestService;
    @Autowired CardholderRepository cardholderRepository;
    @Autowired CardholderDocumentRepository documentRepository;
    @Autowired CardStatusHistoryRepository statusRepository;
    @Autowired BlobStore blobStore;

    @BeforeEach
    void writePacket() throws IOException {
        clearIngestedData();
        // Real packets arrive as one subfolder per enrollee, named for the record id.
        SyntheticPacket.write(packetDir.resolve(SyntheticPacket.RECORD_ID));
    }

    @AfterEach
    void cleanUp() {
        clearIngestedData();
    }

    /**
     * Clears both halves of the ingested state.
     *
     * <p>The blob store matters as much as the database here: it lives under {@code target/} and
     * survives between runs, so a test asserting that a file was <em>not</em> copied would happily
     * pass or fail on a leftover from an earlier build rather than on what this run actually did.
     */
    private void clearIngestedData() {
        documentRepository.deleteAllInBatch();
        statusRepository.deleteAllInBatch();
        cardholderRepository.deleteAllInBatch();
        clearBlobStore();
    }

    private void clearBlobStore() {
        Path root = blobStore.getRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(root)) {
            entries.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(root))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("a packet loads into demographics, documents and status history")
    void ingestsCompletePacket() throws IOException {
        PacketIngestService.IngestReport report = ingestService.ingestDirectory(packetDir);

        assertThat(report.packetsFound()).isEqualTo(1);
        assertThat(report.packetsLoaded()).isEqualTo(1);
        assertThat(report.failures()).isEmpty();

        Cardholder cardholder = cardholderRepository.findByRecordId(SyntheticPacket.RECORD_ID).orElseThrow();
        assertThat(cardholder.getPostalRefNo()).isEqualTo(SyntheticPacket.POSTAL_REF_NO);
        assertThat(cardholder.getDateOfBirth()).isEqualTo(LocalDate.parse(SyntheticPacket.DATE_OF_BIRTH));
        assertThat(cardholder.getDemoStatus()).isEqualTo("CDE");
        assertThat(cardholder.isDeleted()).isFalse();

        assertThat(statusRepository.countByCardholderId(cardholder.getId())).isEqualTo(3);
    }

    @Test
    @DisplayName("fixed-width padding is trimmed off every field")
    void trimsSourcePadding() throws IOException {
        ingestService.ingestDirectory(packetDir);

        Cardholder cardholder = cardholderRepository.findByRecordId(SyntheticPacket.RECORD_ID).orElseThrow();

        assertThat(cardholder.getFirstName()).isEqualTo(SyntheticPacket.FIRST_NAME);
        assertThat(cardholder.getLastName()).isEqualTo(SyntheticPacket.LAST_NAME);
        assertThat(cardholder.getMiddleName()).isEqualTo(SyntheticPacket.MIDDLE_NAME);
        assertThat(cardholder.getNationality()).isEqualTo("Filipino");
        assertThat(cardholder.getMailOption()).isEqualTo("HOME");
        assertThat(cardholder.getControlNo()).isEqualTo("001");
    }

    @Test
    @DisplayName("normalized name columns are written so the verifier can match against them")
    void writesNormalizedNames() throws IOException {
        ingestService.ingestDirectory(packetDir);

        Cardholder cardholder = cardholderRepository.findByRecordId(SyntheticPacket.RECORD_ID).orElseThrow();

        assertThat(cardholder.getFirstNameNorm()).isEqualTo("MARIA");
        assertThat(cardholder.getMiddleNameNorm()).isEqualTo("REYES");
        assertThat(cardholder.getLastNameNorm()).isEqualTo("SANTOS");
    }

    @Test
    @DisplayName("status timestamps round-trip verbatim, unshifted by the server's timezone")
    void statusTimestampsAreNotShifted() throws IOException {
        ingestService.ingestDirectory(packetDir);

        Cardholder cardholder = cardholderRepository.findByRecordId(SyntheticPacket.RECORD_ID).orElseThrow();
        List<CardStatusHistory> trail =
                statusRepository.findByCardholderIdOrderByStatusTimestampAsc(cardholder.getId());

        // The fixture writes 09:00:00, 09:05:00 and 10:00:00 as bare wall-clock times. They carry
        // no zone, so they must come back exactly as written whatever the server's offset is.
        // A UTC conversion applied to them would move every one by that offset.
        assertThat(trail).extracting(CardStatusHistory::getStatusTimestamp)
                .containsExactly(
                        LocalDateTime.of(2026, 1, 1, 9, 0, 0, 1000),
                        LocalDateTime.of(2026, 1, 1, 9, 5, 0, 2000),
                        LocalDateTime.of(2026, 1, 1, 10, 0, 0, 3000));
    }

    @Test
    @DisplayName("the two date layouts in the export are both parsed")
    void parsesBothDateFormats() throws IOException {
        ingestService.ingestDirectory(packetDir);

        Cardholder cardholder = cardholderRepository.findByRecordId(SyntheticPacket.RECORD_ID).orElseThrow();

        // DOB and ERD are yyyyMMdd; ORDate and SchedDate are MMddyyyy.
        assertThat(cardholder.getDateOfBirth()).isEqualTo(LocalDate.of(1988, 3, 17));
        assertThat(cardholder.getEnrollmentDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(cardholder.getOrDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(cardholder.getSchedDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(cardholder.getOrAmount()).isEqualByComparingTo(new BigDecimal("650.00"));
    }

    @Test
    @DisplayName("assets are recorded with a digest and mapped to the record")
    void mapsDocumentsToRecord() throws IOException {
        ingestService.ingestDirectory(packetDir);

        Cardholder cardholder = cardholderRepository.findByRecordId(SyntheticPacket.RECORD_ID).orElseThrow();
        List<CardholderDocument> documents = documentRepository.findByCardholderId(cardholder.getId());

        assertThat(documents).hasSize(3);
        assertThat(documents).extracting(doc -> doc.getDocumentType().getCode())
                .containsExactlyInAnyOrder("Photo", "Signature", "RT");

        // Metadata, digest and a location are recorded for every file, copied or not.
        for (CardholderDocument document : documents) {
            assertThat(document.getSha256()).hasSize(64);
            assertThat(document.getByteSize()).isPositive();
            assertThat(document.getSourcePath()).isNotBlank();
        }
    }

    @Test
    @DisplayName("only the servable images are copied; everything else is referenced in place")
    void copiesOnlyServableImages() throws IOException {
        PacketIngestService.IngestReport report = ingestService.ingestDirectory(packetDir);

        assertThat(report.documentsStored()).as("photo and signature").isEqualTo(2);
        assertThat(report.documentsReferenced()).as("fingerprint").isEqualTo(1);
        assertThat(report.documentsTotal()).isEqualTo(3);

        Cardholder cardholder = cardholderRepository.findByRecordId(SyntheticPacket.RECORD_ID).orElseThrow();

        for (CardholderDocument document : documentRepository.findByCardholderId(cardholder.getId())) {
            if (document.getDocumentType().isPubliclyRetrievable()) {
                assertThat(document.getStorageKind()).isEqualTo(StorageKind.MANAGED);
                assertThat(document.getStoragePath()).isNotNull();
                assertThat(blobStore.exists(document.getStoragePath())).isTrue();
                assertThat(document.isServable()).isTrue();
            } else {
                assertThat(document.getStorageKind()).isEqualTo(StorageKind.EXTERNAL);
                assertThat(document.getStoragePath()).isNull();
                assertThat(document.isServable()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("a referenced document's bytes are never copied into the blob store")
    void doesNotCopyExternalDocumentBytes() throws IOException {
        ingestService.ingestDirectory(packetDir);

        Cardholder cardholder = cardholderRepository.findByRecordId(SyntheticPacket.RECORD_ID).orElseThrow();
        CardholderDocument fingerprint = documentRepository
                .findByCardholderIdAndDocumentTypeCode(cardholder.getId(), "RT").orElseThrow();

        assertThat(fingerprint.getStorageKind()).isEqualTo(StorageKind.EXTERNAL);
        assertThat(blobStore.exists(SyntheticPacket.RECORD_ID + "/RT.wsq")).isFalse();
    }

    @Test
    @DisplayName("the source pointer is relative to the ingest root, so a moved archive resolves")
    void recordsSourcePathRelativeToRoot() throws IOException {
        ingestService.ingestDirectory(packetDir);

        Cardholder cardholder = cardholderRepository.findByRecordId(SyntheticPacket.RECORD_ID).orElseThrow();
        CardholderDocument fingerprint = documentRepository
                .findByCardholderIdAndDocumentTypeCode(cardholder.getId(), "RT").orElseThrow();

        assertThat(fingerprint.getSourcePath())
                .isEqualTo(SyntheticPacket.RECORD_ID + "/" + SyntheticPacket.RECORD_ID + "_RT.wsq");
        // Relative, not absolute: the archive root can change without invalidating the pointer.
        assertThat(Path.of(fingerprint.getSourcePath()).isAbsolute()).isFalse();
    }

    @Test
    @DisplayName("packets are found in nested per-enrollee subfolders")
    void findsPacketsInNestedSubfolders(@TempDir Path nested) throws IOException {
        // A batch level above the per-enrollee folders, which is the shape quarterly drops take.
        SyntheticPacket.write(nested.resolve("batch-2026-Q1").resolve(SyntheticPacket.RECORD_ID));

        PacketIngestService.IngestReport report = ingestService.ingestDirectory(nested);

        assertThat(report.packetsFound()).isEqualTo(1);
        assertThat(report.packetsLoaded()).isEqualTo(1);
        assertThat(cardholderRepository.findByRecordId(SyntheticPacket.RECORD_ID)).isPresent();
    }

    @Test
    @DisplayName("many packets in one drop each load independently")
    void loadsManyPacketsInOneRun(@TempDir Path batch) throws IOException {
        SyntheticPacket.write(batch.resolve(SyntheticPacket.RECORD_ID));
        SyntheticPacket.writeAs(batch.resolve("9920260101TESTSTA0002"),
                "9920260101TESTSTA0002", "T99260320002");
        SyntheticPacket.writeAs(batch.resolve("9920260101TESTSTA0003"),
                "9920260101TESTSTA0003", "T99260320003");

        PacketIngestService.IngestReport report = ingestService.ingestDirectory(batch);

        assertThat(report.packetsFound()).isEqualTo(3);
        assertThat(report.packetsLoaded()).isEqualTo(3);
        assertThat(report.failures()).isEmpty();
        assertThat(cardholderRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("one unreadable packet is skipped and the rest of the batch still lands")
    void oneBadPacketDoesNotStopTheBatch(@TempDir Path batch) throws IOException {
        SyntheticPacket.write(batch.resolve(SyntheticPacket.RECORD_ID));
        SyntheticPacket.writeAs(batch.resolve("9920260101TESTSTA0002"),
                "9920260101TESTSTA0002", "T99260320002");

        // A third packet whose demographics file is a header with no data row.
        Path broken = batch.resolve("9920260101TESTSTA0009");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("9920260101TESTSTA0009_demographics.csv"), "Rnum,RecordID\n");

        PacketIngestService.IngestReport report = ingestService.ingestDirectory(batch);

        assertThat(report.packetsFound()).isEqualTo(3);
        assertThat(report.packetsLoaded()).isEqualTo(2);
        assertThat(report.failures()).hasSize(1);
        assertThat(cardholderRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("the extension comes from the file on disk, not from the packet manifest")
    void takesExtensionFromDisk() throws IOException {
        ingestService.ingestDirectory(packetDir);

        Cardholder cardholder = cardholderRepository.findByRecordId(SyntheticPacket.RECORD_ID).orElseThrow();

        CardholderDocument signature = documentRepository
                .findByCardholderIdAndDocumentTypeCode(cardholder.getId(), "Signature").orElseThrow();
        assertThat(signature.getContentType()).isEqualTo("image/tiff");
        assertThat(signature.getStoragePath()).endsWith(".tiff");

        CardholderDocument photo = documentRepository
                .findByCardholderIdAndDocumentTypeCode(cardholder.getId(), "Photo").orElseThrow();
        assertThat(photo.getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("only the photo and signature are marked retrievable; the fingerprint is not")
    void restrictsRetrievableDocuments() throws IOException {
        ingestService.ingestDirectory(packetDir);

        Cardholder cardholder = cardholderRepository.findByRecordId(SyntheticPacket.RECORD_ID).orElseThrow();

        assertThat(documentRepository
                .findByCardholderIdAndDocumentTypePubliclyRetrievableTrue(cardholder.getId()))
                .extracting(doc -> doc.getDocumentType().getCode())
                .containsExactlyInAnyOrder("Photo", "Signature");
    }

    @Test
    @DisplayName("re-running over the same packet updates in place rather than duplicating")
    void ingestIsIdempotent() throws IOException {
        ingestService.ingestDirectory(packetDir);
        ingestService.ingestDirectory(packetDir);

        assertThat(cardholderRepository.findAll())
                .filteredOn(c -> SyntheticPacket.RECORD_ID.equals(c.getRecordId()))
                .hasSize(1);

        Cardholder cardholder = cardholderRepository.findByRecordId(SyntheticPacket.RECORD_ID).orElseThrow();
        assertThat(documentRepository.findByCardholderId(cardholder.getId())).hasSize(3);
        assertThat(statusRepository.countByCardholderId(cardholder.getId())).isEqualTo(3);
    }

    @Test
    @DisplayName("an empty directory is a no-op, not a failure")
    void handlesEmptyDirectory(@TempDir Path empty) throws IOException {
        PacketIngestService.IngestReport report = ingestService.ingestDirectory(empty);

        assertThat(report.packetsFound()).isZero();
        assertThat(report.failures()).isEmpty();
    }
}
