package com.postalid.verification.ingest;

import com.postalid.verification.domain.CardStatusHistory;
import com.postalid.verification.domain.CardStatusHistoryRepository;
import com.postalid.verification.domain.Cardholder;
import com.postalid.verification.domain.CardholderDocument;
import com.postalid.verification.domain.CardholderDocumentRepository;
import com.postalid.verification.domain.CardholderRepository;
import com.postalid.verification.domain.DocumentType;
import com.postalid.verification.domain.DocumentTypeRepository;
import com.postalid.verification.domain.StorageKind;
import com.postalid.verification.storage.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writes one enrollment packet to the database and blob store, in one transaction.
 *
 * <p>This is a separate bean from {@link PacketIngestService} for a reason that is easy to get
 * wrong: {@code @Transactional} is applied by a proxy around the bean, so a method calling a
 * transactional method <em>on itself</em> gets no transaction at all. Keeping the per-packet unit
 * of work in its own bean means the loop in {@code PacketIngestService} genuinely crosses a proxy
 * boundary, and each packet genuinely commits or rolls back on its own.
 *
 * <p>Writes are idempotent: an existing record with the same {@code RecordID} is updated in place
 * rather than duplicated, so a corrected packet can simply be reloaded.
 */
@Component
public class PacketWriter {

    private static final Logger log = LoggerFactory.getLogger(PacketWriter.class);

    private final CardholderRepository cardholderRepository;
    private final CardholderDocumentRepository documentRepository;
    private final CardStatusHistoryRepository statusRepository;
    private final DocumentTypeRepository documentTypeRepository;
    private final DemographicsCsvParser demographicsParser;
    private final StatusDetailCsvParser statusParser;
    private final PacketFileScanner fileScanner;
    private final BlobStore blobStore;

    public PacketWriter(CardholderRepository cardholderRepository,
                        CardholderDocumentRepository documentRepository,
                        CardStatusHistoryRepository statusRepository,
                        DocumentTypeRepository documentTypeRepository,
                        DemographicsCsvParser demographicsParser,
                        StatusDetailCsvParser statusParser,
                        PacketFileScanner fileScanner,
                        BlobStore blobStore) {
        this.cardholderRepository = cardholderRepository;
        this.documentRepository = documentRepository;
        this.statusRepository = statusRepository;
        this.documentTypeRepository = documentTypeRepository;
        this.demographicsParser = demographicsParser;
        this.statusParser = statusParser;
        this.fileScanner = fileScanner;
        this.blobStore = blobStore;
    }

    /** How many of a packet's files were copied, and how many were only pointed at. */
    public record WriteResult(int documentsStored, int documentsReferenced) {
    }

    /**
     * Loads one packet.
     *
     * @param demographicsCsv the packet's demographics file; its directory holds the assets
     * @param sourceRoot      the ingest root, used to record each asset's location relatively
     */
    @Transactional
    public WriteResult write(Path demographicsCsv, Path sourceRoot, Set<String> knownCodes) throws IOException {
        Cardholder parsed = demographicsParser.parse(demographicsCsv)
                .orElseThrow(() -> new PacketIngestException("No data row in " + demographicsCsv.getFileName()));

        Cardholder cardholder = cardholderRepository.findByRecordId(parsed.getRecordId())
                .map(existing -> copyInto(parsed, existing))
                .orElse(parsed);
        cardholder = cardholderRepository.save(cardholder);

        String recordId = cardholder.getRecordId();
        Path packetDir = demographicsCsv.getParent();

        writeStatusHistory(packetDir, recordId, cardholder);
        WriteResult result = writeAssets(packetDir, sourceRoot, recordId, cardholder, knownCodes);

        log.info("Ingested packet {}: {} document(s) copied, {} referenced in place",
                recordId, result.documentsStored(), result.documentsReferenced());
        return result;
    }

    private void writeStatusHistory(Path packetDir, String recordId, Cardholder cardholder) throws IOException {
        Path statusFile = packetDir.resolve(recordId + "__demostatusdtl.csv");
        if (!Files.isRegularFile(statusFile)) {
            log.warn("Packet {} has no status detail file", recordId);
            return;
        }

        // Replace wholesale: the source file is the authority on the trail, and its rows carry no
        // stable key of their own that would make a row-by-row merge meaningful.
        statusRepository.deleteByCardholderId(cardholder.getId());

        List<CardStatusHistory> history = statusParser.parse(statusFile);
        history.forEach(entry -> entry.setCardholder(cardholder));
        statusRepository.saveAll(history);
    }

    /**
     * Records every asset in the packet, copying only the ones the API can serve.
     *
     * <p>A photograph and signature are together well under a percent of an enrollment packet;
     * the certificates, receipts, forms and fingerprints are the rest, and no route can return
     * them. Copying those into the blob store would duplicate almost the entire archive into a
     * tier that never reads it, so they are recorded with their metadata, digest and location and
     * left where they are.
     *
     * <p>The digest is computed for every file regardless, which does mean reading the bytes. It
     * is the only way to later tell whether an archived certificate is still the one that was
     * captured, and it avoids the write amplification that copying would cost.
     */
    private WriteResult writeAssets(Path packetDir,
                                    Path sourceRoot,
                                    String recordId,
                                    Cardholder cardholder,
                                    Set<String> knownCodes) throws IOException {

        List<PacketFileScanner.PacketAsset> assets = fileScanner.scan(packetDir, recordId, knownCodes);
        if (assets.isEmpty()) {
            return new WriteResult(0, 0);
        }

        Map<String, DocumentType> typesByCode = documentTypeRepository.findAll().stream()
                .collect(Collectors.toMap(DocumentType::getCode, Function.identity()));

        Map<String, CardholderDocument> existing = new HashMap<>();
        documentRepository.findByCardholderId(cardholder.getId())
                .forEach(doc -> existing.put(doc.getDocumentType().getCode(), doc));

        int stored = 0;
        int referenced = 0;

        for (PacketFileScanner.PacketAsset asset : assets) {
            DocumentType type = typesByCode.get(asset.docCode());

            CardholderDocument document = Optional.ofNullable(existing.get(asset.docCode()))
                    .orElseGet(CardholderDocument::new);
            document.setCardholder(cardholder);
            document.setDocumentType(type);
            document.setOriginalFilename(asset.file().getFileName().toString());
            document.setSourcePath(relativeSourcePath(sourceRoot, asset.file()));
            document.setContentType(asset.contentType());
            document.setByteSize(Files.size(asset.file()));
            document.setSha256(BlobStore.sha256(asset.file()));
            document.setCapturedAt(Files.getLastModifiedTime(asset.file()).toInstant());

            if (type.isPubliclyRetrievable()) {
                document.setStorageKind(StorageKind.MANAGED);
                document.setStoragePath(
                        blobStore.store(asset.file(), recordId, asset.docCode(), asset.extension()));
                stored++;
            } else {
                document.setStorageKind(StorageKind.EXTERNAL);
                document.setStoragePath(null);
                referenced++;
            }

            documentRepository.save(document);
        }
        return new WriteResult(stored, referenced);
    }

    /**
     * The asset's location relative to the ingest root, so a moved or remounted archive stays
     * resolvable. Falls back to the bare filename if the file somehow sits outside the root.
     */
    private String relativeSourcePath(Path sourceRoot, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(sourceRoot)) {
            return absolute.getFileName().toString();
        }
        // Forward slashes so a path recorded on Windows still reads correctly elsewhere.
        return sourceRoot.relativize(absolute).toString().replace('\\', '/');
    }

    /**
     * Copies parsed values onto a managed entity so a reload updates in place. The identity
     * columns are left alone: {@code recordId} is what matched the two, and the surrogate id
     * belongs to the existing row.
     */
    private Cardholder copyInto(Cardholder source, Cardholder target) {
        target.setPostalRefNo(source.getPostalRefNo());
        target.setRnum(source.getRnum());
        target.setControlNo(source.getControlNo());

        target.setFirstName(source.getFirstName());
        target.setMiddleName(source.getMiddleName());
        target.setLastName(source.getLastName());
        target.setSuffixName(source.getSuffixName());
        target.setFirstNameNorm(source.getFirstNameNorm());
        target.setMiddleNameNorm(source.getMiddleNameNorm());
        target.setLastNameNorm(source.getLastNameNorm());

        target.setGender(source.getGender());
        target.setDateOfBirth(source.getDateOfBirth());
        target.setCivilStatus(source.getCivilStatus());
        target.setNationality(source.getNationality());
        target.setHeight(source.getHeight());
        target.setWeight(source.getWeight());
        target.setComplexion(source.getComplexion());
        target.setHairColor(source.getHairColor());
        target.setEyeColor(source.getEyeColor());
        target.setDistinguishingFeatures(source.getDistinguishingFeatures());

        target.setPobCity(source.getPobCity());
        target.setPobProvince(source.getPobProvince());
        target.setPobCountry(source.getPobCountry());

        target.setFatherFirstName(source.getFatherFirstName());
        target.setFatherMiddleName(source.getFatherMiddleName());
        target.setFatherLastName(source.getFatherLastName());
        target.setFatherSuffixName(source.getFatherSuffixName());
        target.setMotherFirstName(source.getMotherFirstName());
        target.setMotherMiddleName(source.getMotherMiddleName());
        target.setMotherLastName(source.getMotherLastName());
        target.setMotherSuffixName(source.getMotherSuffixName());

        target.setAddrPostalCode(source.getAddrPostalCode());
        target.setAddrCountry(source.getAddrCountry());
        target.setAddrProvince(source.getAddrProvince());
        target.setAddrCity(source.getAddrCity());
        target.setAddrArea(source.getAddrArea());
        target.setAddrBarangay(source.getAddrBarangay());
        target.setAddrSubdivision(source.getAddrSubdivision());
        target.setAddrStreet(source.getAddrStreet());
        target.setAddrHouseBldgLot(source.getAddrHouseBldgLot());

        target.setCompanyName(source.getCompanyName());
        target.setOccupation(source.getOccupation());
        target.setWorkPostalCode(source.getWorkPostalCode());
        target.setWorkCountry(source.getWorkCountry());
        target.setWorkProvince(source.getWorkProvince());
        target.setWorkCity(source.getWorkCity());
        target.setWorkArea(source.getWorkArea());
        target.setWorkBarangay(source.getWorkBarangay());
        target.setWorkSubdivision(source.getWorkSubdivision());
        target.setWorkStreet(source.getWorkStreet());
        target.setWorkHouseBldgLot(source.getWorkHouseBldgLot());

        target.setContactNo(source.getContactNo());
        target.setMobileNo(source.getMobileNo());
        target.setEmail(source.getEmail());

        target.setTin(source.getTin());
        target.setCrn(source.getCrn());
        target.setGsis(source.getGsis());
        target.setPhilhealth(source.getPhilhealth());
        target.setHdmf(source.getHdmf());

        target.setBranch(source.getBranch());
        target.setAreaCode(source.getAreaCode());
        target.setEnrollmentDate(source.getEnrollmentDate());
        target.setStationId(source.getStationId());
        target.setSequenceNo(source.getSequenceNo());
        target.setCardType(source.getCardType());
        target.setProcessType(source.getProcessType());
        target.setEnrollReason(source.getEnrollReason());
        target.setEntryType(source.getEntryType());
        target.setMailOption(source.getMailOption());
        target.setIssuingPostOffice(source.getIssuingPostOffice());
        target.setApoCode(source.getApoCode());
        target.setCpoCode(source.getCpoCode());
        target.setSchedDate(source.getSchedDate());

        target.setOrNumber(source.getOrNumber());
        target.setOrDate(source.getOrDate());
        target.setOrBranch(source.getOrBranch());
        target.setOrAmount(source.getOrAmount());

        target.setDemoStatus(source.getDemoStatus());
        target.setDeleted(source.isDeleted());
        target.setDoneQa(source.getDoneQa());
        target.setQaTag(source.getQaTag());
        target.setCapturedBy(source.getCapturedBy());
        target.setTimeStartCapture(source.getTimeStartCapture());
        target.setTimeEndCapture(source.getTimeEndCapture());

        return target;
    }
}
