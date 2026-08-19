package com.postalid.verification.ingest;

import com.postalid.verification.domain.Cardholder;
import com.postalid.verification.verify.FieldNormalizer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Maps a {@code *_demographics.csv} row onto a {@link Cardholder}.
 *
 * <p>The column names are the export's own, abbreviated ones; the mapping to readable field names
 * lives here and nowhere else. Normalized name columns are computed with the same
 * {@link FieldNormalizer} the verifier uses, which is what guarantees a record ingested today can
 * be matched by input typed tomorrow.
 */
@Component
public class DemographicsCsvParser {

    private final FieldNormalizer normalizer;

    public DemographicsCsvParser(FieldNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * @return the single record in the file, or empty when it holds only a header
     */
    public Optional<Cardholder> parse(Path csvFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setIgnoreSurroundingSpaces(false)
                     .get()
                     .parse(reader)) {

            for (CSVRecord record : parser) {
                CsvRow row = new CsvRow(record);
                if (row.isBlank()) {
                    continue;
                }
                return Optional.of(toCardholder(row));
            }
        }
        return Optional.empty();
    }

    private Cardholder toCardholder(CsvRow row) {
        Cardholder c = new Cardholder();

        // Identity. RecordID keys the packet; PostalRefNo is the number printed on the card and
        // is stored in normalized form because it is the verification lookup key.
        c.setRecordId(row.required("RecordID"));
        c.setPostalRefNo(normalizer.normalizeToEmpty(row.required("PostalRefNo")));
        c.setRnum(row.text("Rnum"));
        c.setControlNo(row.text("ControlNo"));

        // Name
        c.setFirstName(row.required("Fname"));
        c.setMiddleName(row.text("Mname"));
        c.setLastName(row.required("Lname"));
        c.setSuffixName(row.text("Sname"));
        c.setFirstNameNorm(normalizer.normalizeToEmpty(c.getFirstName()));
        c.setMiddleNameNorm(normalizer.normalize(c.getMiddleName()));
        c.setLastNameNorm(normalizer.normalizeToEmpty(c.getLastName()));

        // Vitals
        c.setGender(row.text("Gen"));
        c.setDateOfBirth(row.requiredCompactDate("DOB"));
        c.setCivilStatus(row.text("MS"));
        c.setNationality(row.text("Nationality"));
        c.setHeight(row.text("HT"));
        c.setWeight(row.text("WT"));
        c.setComplexion(row.text("Complexion"));
        c.setHairColor(row.text("HairColor"));
        c.setEyeColor(row.text("EyeColor"));
        c.setDistinguishingFeatures(row.text("DisFeat"));

        // Place of birth
        c.setPobCity(row.text("POBCM"));
        c.setPobProvince(row.text("POBPS"));
        c.setPobCountry(row.text("POBCC"));

        // Parents
        c.setFatherFirstName(row.text("FFname"));
        c.setFatherMiddleName(row.text("FMname"));
        c.setFatherLastName(row.text("FLname"));
        c.setFatherSuffixName(row.text("FSname"));
        c.setMotherFirstName(row.text("MFname"));
        c.setMotherMiddleName(row.text("MMname"));
        c.setMotherLastName(row.text("MLname"));
        c.setMotherSuffixName(row.text("MSname"));

        // Home address: PC postal code, CC country, PS province, CM city/municipality,
        // BDL barangay, Sub subdivision, St street, HBL house/building/lot.
        c.setAddrPostalCode(row.text("AddPC"));
        c.setAddrCountry(row.text("AddCC"));
        c.setAddrProvince(row.text("AddPS"));
        c.setAddrCity(row.text("AddCM"));
        c.setAddrArea(row.text("AddArea"));
        c.setAddrBarangay(row.text("AddBDL"));
        c.setAddrSubdivision(row.text("AddSub"));
        c.setAddrStreet(row.text("AddSt"));
        c.setAddrHouseBldgLot(row.text("AddHBL"));

        // Work
        c.setCompanyName(row.text("CompanyName"));
        c.setOccupation(row.text("Occupation"));
        c.setWorkPostalCode(row.text("WorkPC"));
        c.setWorkCountry(row.text("WorkCC"));
        c.setWorkProvince(row.text("WorkPS"));
        c.setWorkCity(row.text("WorkCM"));
        c.setWorkArea(row.text("WorkArea"));
        c.setWorkBarangay(row.text("WorkBDL"));
        c.setWorkSubdivision(row.text("WorkSub"));
        c.setWorkStreet(row.text("WorkSt"));
        c.setWorkHouseBldgLot(row.text("WorkHBL"));

        // Contact
        c.setContactNo(row.text("ContactNo"));
        c.setMobileNo(row.text("MobileNo"));
        c.setEmail(row.text("Email"));

        // Government identifiers
        c.setTin(row.text("TIN"));
        c.setCrn(row.text("CRN"));
        c.setGsis(row.text("GSIS"));
        c.setPhilhealth(row.text("PhilHealth"));
        c.setHdmf(row.text("HDMF"));

        // Enrollment
        c.setBranch(row.text("BR"));
        c.setAreaCode(row.text("AC"));
        c.setEnrollmentDate(row.compactDate("ERD"));
        c.setStationId(row.text("StID"));
        c.setSequenceNo(row.text("SQNo"));
        c.setCardType(row.text("CardType"));
        c.setProcessType(row.text("ProcessType"));
        c.setEnrollReason(row.text("EnrollReason"));
        c.setEntryType(row.text("ET"));
        c.setMailOption(row.text("MailOption"));
        c.setIssuingPostOffice(row.text("IssuingPostOfc"));
        c.setApoCode(row.text("APOCode"));
        c.setCpoCode(row.text("CPOCode"));
        c.setSchedDate(row.usDate("SchedDate"));

        // Payment
        c.setOrNumber(row.text("ORNumber"));
        c.setOrDate(row.usDate("ORDate"));
        c.setOrBranch(row.text("ORBranch"));
        c.setOrAmount(row.decimal("ORAmount"));

        // Record state. DemoStatus is what verification gates on.
        c.setDemoStatus(row.text("DemoStatus"));
        c.setDeleted(row.flag("IsDeleted"));
        c.setDoneQa(row.text("DoneQA"));
        c.setQaTag(row.text("QATag"));
        c.setCapturedBy(row.text("CapturedBy"));
        c.setTimeStartCapture(row.text("TimeStartCapture"));
        c.setTimeEndCapture(row.text("TimeEndCapture"));

        return c;
    }
}
