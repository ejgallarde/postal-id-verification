package com.postalid.verification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One Postal ID enrollment record, as captured in a {@code *_demographics.csv} packet.
 *
 * <p>Two identifiers matter and are easy to confuse. {@code recordId} is the internal packet
 * key and the filename prefix of every captured asset. {@code postalRefNo} is the reference
 * number printed on the physical card, and is the value a verifying party actually types in.
 */
@Entity
@Table(name = "cardholder")
public class Cardholder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_id", nullable = false, unique = true, length = 32)
    private String recordId;

    @Column(name = "postal_ref_no", nullable = false, unique = true, length = 32)
    private String postalRefNo;

    @Column(name = "rnum", length = 32)
    private String rnum;

    @Column(name = "control_no", length = 32)
    private String controlNo;

    // --- Name -----------------------------------------------------------------

    @Column(name = "first_name", nullable = false, length = 120)
    private String firstName;

    @Column(name = "middle_name", length = 120)
    private String middleName;

    @Column(name = "last_name", nullable = false, length = 120)
    private String lastName;

    @Column(name = "suffix_name", length = 32)
    private String suffixName;

    /**
     * Comparison forms produced by {@code FieldNormalizer} at ingest time. Matching reads these
     * directly so it never has to transform stored values at query time, and so the ingester and
     * the verifier cannot drift apart on what "the same name" means.
     */
    @Column(name = "first_name_norm", nullable = false, length = 120)
    private String firstNameNorm;

    @Column(name = "middle_name_norm", length = 120)
    private String middleNameNorm;

    @Column(name = "last_name_norm", nullable = false, length = 120)
    private String lastNameNorm;

    // --- Vitals ---------------------------------------------------------------

    @Column(name = "gender", length = 8)
    private String gender;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "civil_status", length = 8)
    private String civilStatus;

    @Column(name = "nationality", length = 64)
    private String nationality;

    @Column(name = "height", length = 16)
    private String height;

    @Column(name = "weight", length = 16)
    private String weight;

    @Column(name = "complexion", length = 32)
    private String complexion;

    @Column(name = "hair_color", length = 32)
    private String hairColor;

    @Column(name = "eye_color", length = 32)
    private String eyeColor;

    @Column(name = "distinguishing_features", length = 255)
    private String distinguishingFeatures;

    // --- Place of birth -------------------------------------------------------

    @Column(name = "pob_city", length = 160)
    private String pobCity;

    @Column(name = "pob_province", length = 64)
    private String pobProvince;

    @Column(name = "pob_country", length = 8)
    private String pobCountry;

    // --- Parents --------------------------------------------------------------

    @Column(name = "father_first_name", length = 120)
    private String fatherFirstName;

    @Column(name = "father_middle_name", length = 120)
    private String fatherMiddleName;

    @Column(name = "father_last_name", length = 120)
    private String fatherLastName;

    @Column(name = "father_suffix_name", length = 32)
    private String fatherSuffixName;

    @Column(name = "mother_first_name", length = 120)
    private String motherFirstName;

    @Column(name = "mother_middle_name", length = 120)
    private String motherMiddleName;

    @Column(name = "mother_last_name", length = 120)
    private String motherLastName;

    @Column(name = "mother_suffix_name", length = 32)
    private String motherSuffixName;

    // --- Home address ---------------------------------------------------------

    @Column(name = "addr_postal_code", length = 16)
    private String addrPostalCode;

    @Column(name = "addr_country", length = 8)
    private String addrCountry;

    @Column(name = "addr_province", length = 64)
    private String addrProvince;

    @Column(name = "addr_city", length = 160)
    private String addrCity;

    @Column(name = "addr_area", length = 255)
    private String addrArea;

    @Column(name = "addr_barangay", length = 64)
    private String addrBarangay;

    @Column(name = "addr_subdivision", length = 64)
    private String addrSubdivision;

    @Column(name = "addr_street", length = 64)
    private String addrStreet;

    @Column(name = "addr_house_bldg_lot", length = 64)
    private String addrHouseBldgLot;

    // --- Work -----------------------------------------------------------------

    @Column(name = "company_name", length = 255)
    private String companyName;

    @Column(name = "occupation", length = 160)
    private String occupation;

    @Column(name = "work_postal_code", length = 16)
    private String workPostalCode;

    @Column(name = "work_country", length = 8)
    private String workCountry;

    @Column(name = "work_province", length = 64)
    private String workProvince;

    @Column(name = "work_city", length = 160)
    private String workCity;

    @Column(name = "work_area", length = 255)
    private String workArea;

    @Column(name = "work_barangay", length = 64)
    private String workBarangay;

    @Column(name = "work_subdivision", length = 64)
    private String workSubdivision;

    @Column(name = "work_street", length = 64)
    private String workStreet;

    @Column(name = "work_house_bldg_lot", length = 64)
    private String workHouseBldgLot;

    // --- Contact --------------------------------------------------------------

    @Column(name = "contact_no", length = 64)
    private String contactNo;

    @Column(name = "mobile_no", length = 64)
    private String mobileNo;

    @Column(name = "email", length = 120)
    private String email;

    // --- Government identifiers ----------------------------------------------

    @Column(name = "tin", length = 32)
    private String tin;

    @Column(name = "crn", length = 32)
    private String crn;

    @Column(name = "gsis", length = 32)
    private String gsis;

    @Column(name = "philhealth", length = 32)
    private String philhealth;

    @Column(name = "hdmf", length = 32)
    private String hdmf;

    // --- Enrollment -----------------------------------------------------------

    @Column(name = "branch", length = 16)
    private String branch;

    @Column(name = "area_code", length = 16)
    private String areaCode;

    @Column(name = "enrollment_date")
    private LocalDate enrollmentDate;

    @Column(name = "station_id", length = 32)
    private String stationId;

    @Column(name = "sequence_no", length = 16)
    private String sequenceNo;

    @Column(name = "card_type", length = 8)
    private String cardType;

    @Column(name = "process_type", length = 8)
    private String processType;

    @Column(name = "enroll_reason", length = 64)
    private String enrollReason;

    @Column(name = "entry_type", length = 8)
    private String entryType;

    @Column(name = "mail_option", length = 16)
    private String mailOption;

    @Column(name = "issuing_post_office", length = 64)
    private String issuingPostOffice;

    @Column(name = "apo_code", length = 16)
    private String apoCode;

    @Column(name = "cpo_code", length = 16)
    private String cpoCode;

    @Column(name = "sched_date")
    private LocalDate schedDate;

    // --- Payment --------------------------------------------------------------

    @Column(name = "or_number", length = 32)
    private String orNumber;

    @Column(name = "or_date")
    private LocalDate orDate;

    @Column(name = "or_branch", length = 64)
    private String orBranch;

    @Column(name = "or_amount", precision = 12, scale = 2)
    private BigDecimal orAmount;

    // --- Record state ---------------------------------------------------------

    @Column(name = "demo_status", length = 8)
    private String demoStatus;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "done_qa", length = 8)
    private String doneQa;

    @Column(name = "qa_tag", length = 32)
    private String qaTag;

    @Column(name = "captured_by", length = 64)
    private String capturedBy;

    @Column(name = "time_start_capture", length = 32)
    private String timeStartCapture;

    @Column(name = "time_end_capture", length = 32)
    private String timeEndCapture;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "cardholder")
    private List<CardholderDocument> documents = new ArrayList<>();

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // --- Accessors ------------------------------------------------------------

    public Long getId() { return id; }

    public String getRecordId() { return recordId; }
    public void setRecordId(String recordId) { this.recordId = recordId; }

    public String getPostalRefNo() { return postalRefNo; }
    public void setPostalRefNo(String postalRefNo) { this.postalRefNo = postalRefNo; }

    public String getRnum() { return rnum; }
    public void setRnum(String rnum) { this.rnum = rnum; }

    public String getControlNo() { return controlNo; }
    public void setControlNo(String controlNo) { this.controlNo = controlNo; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getMiddleName() { return middleName; }
    public void setMiddleName(String middleName) { this.middleName = middleName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getSuffixName() { return suffixName; }
    public void setSuffixName(String suffixName) { this.suffixName = suffixName; }

    public String getFirstNameNorm() { return firstNameNorm; }
    public void setFirstNameNorm(String firstNameNorm) { this.firstNameNorm = firstNameNorm; }

    public String getMiddleNameNorm() { return middleNameNorm; }
    public void setMiddleNameNorm(String middleNameNorm) { this.middleNameNorm = middleNameNorm; }

    public String getLastNameNorm() { return lastNameNorm; }
    public void setLastNameNorm(String lastNameNorm) { this.lastNameNorm = lastNameNorm; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public String getCivilStatus() { return civilStatus; }
    public void setCivilStatus(String civilStatus) { this.civilStatus = civilStatus; }

    public String getNationality() { return nationality; }
    public void setNationality(String nationality) { this.nationality = nationality; }

    public String getHeight() { return height; }
    public void setHeight(String height) { this.height = height; }

    public String getWeight() { return weight; }
    public void setWeight(String weight) { this.weight = weight; }

    public String getComplexion() { return complexion; }
    public void setComplexion(String complexion) { this.complexion = complexion; }

    public String getHairColor() { return hairColor; }
    public void setHairColor(String hairColor) { this.hairColor = hairColor; }

    public String getEyeColor() { return eyeColor; }
    public void setEyeColor(String eyeColor) { this.eyeColor = eyeColor; }

    public String getDistinguishingFeatures() { return distinguishingFeatures; }
    public void setDistinguishingFeatures(String v) { this.distinguishingFeatures = v; }

    public String getPobCity() { return pobCity; }
    public void setPobCity(String pobCity) { this.pobCity = pobCity; }

    public String getPobProvince() { return pobProvince; }
    public void setPobProvince(String pobProvince) { this.pobProvince = pobProvince; }

    public String getPobCountry() { return pobCountry; }
    public void setPobCountry(String pobCountry) { this.pobCountry = pobCountry; }

    public String getFatherFirstName() { return fatherFirstName; }
    public void setFatherFirstName(String v) { this.fatherFirstName = v; }

    public String getFatherMiddleName() { return fatherMiddleName; }
    public void setFatherMiddleName(String v) { this.fatherMiddleName = v; }

    public String getFatherLastName() { return fatherLastName; }
    public void setFatherLastName(String v) { this.fatherLastName = v; }

    public String getFatherSuffixName() { return fatherSuffixName; }
    public void setFatherSuffixName(String v) { this.fatherSuffixName = v; }

    public String getMotherFirstName() { return motherFirstName; }
    public void setMotherFirstName(String v) { this.motherFirstName = v; }

    public String getMotherMiddleName() { return motherMiddleName; }
    public void setMotherMiddleName(String v) { this.motherMiddleName = v; }

    public String getMotherLastName() { return motherLastName; }
    public void setMotherLastName(String v) { this.motherLastName = v; }

    public String getMotherSuffixName() { return motherSuffixName; }
    public void setMotherSuffixName(String v) { this.motherSuffixName = v; }

    public String getAddrPostalCode() { return addrPostalCode; }
    public void setAddrPostalCode(String v) { this.addrPostalCode = v; }

    public String getAddrCountry() { return addrCountry; }
    public void setAddrCountry(String v) { this.addrCountry = v; }

    public String getAddrProvince() { return addrProvince; }
    public void setAddrProvince(String v) { this.addrProvince = v; }

    public String getAddrCity() { return addrCity; }
    public void setAddrCity(String v) { this.addrCity = v; }

    public String getAddrArea() { return addrArea; }
    public void setAddrArea(String v) { this.addrArea = v; }

    public String getAddrBarangay() { return addrBarangay; }
    public void setAddrBarangay(String v) { this.addrBarangay = v; }

    public String getAddrSubdivision() { return addrSubdivision; }
    public void setAddrSubdivision(String v) { this.addrSubdivision = v; }

    public String getAddrStreet() { return addrStreet; }
    public void setAddrStreet(String v) { this.addrStreet = v; }

    public String getAddrHouseBldgLot() { return addrHouseBldgLot; }
    public void setAddrHouseBldgLot(String v) { this.addrHouseBldgLot = v; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String v) { this.companyName = v; }

    public String getOccupation() { return occupation; }
    public void setOccupation(String v) { this.occupation = v; }

    public String getWorkPostalCode() { return workPostalCode; }
    public void setWorkPostalCode(String v) { this.workPostalCode = v; }

    public String getWorkCountry() { return workCountry; }
    public void setWorkCountry(String v) { this.workCountry = v; }

    public String getWorkProvince() { return workProvince; }
    public void setWorkProvince(String v) { this.workProvince = v; }

    public String getWorkCity() { return workCity; }
    public void setWorkCity(String v) { this.workCity = v; }

    public String getWorkArea() { return workArea; }
    public void setWorkArea(String v) { this.workArea = v; }

    public String getWorkBarangay() { return workBarangay; }
    public void setWorkBarangay(String v) { this.workBarangay = v; }

    public String getWorkSubdivision() { return workSubdivision; }
    public void setWorkSubdivision(String v) { this.workSubdivision = v; }

    public String getWorkStreet() { return workStreet; }
    public void setWorkStreet(String v) { this.workStreet = v; }

    public String getWorkHouseBldgLot() { return workHouseBldgLot; }
    public void setWorkHouseBldgLot(String v) { this.workHouseBldgLot = v; }

    public String getContactNo() { return contactNo; }
    public void setContactNo(String v) { this.contactNo = v; }

    public String getMobileNo() { return mobileNo; }
    public void setMobileNo(String v) { this.mobileNo = v; }

    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }

    public String getTin() { return tin; }
    public void setTin(String v) { this.tin = v; }

    public String getCrn() { return crn; }
    public void setCrn(String v) { this.crn = v; }

    public String getGsis() { return gsis; }
    public void setGsis(String v) { this.gsis = v; }

    public String getPhilhealth() { return philhealth; }
    public void setPhilhealth(String v) { this.philhealth = v; }

    public String getHdmf() { return hdmf; }
    public void setHdmf(String v) { this.hdmf = v; }

    public String getBranch() { return branch; }
    public void setBranch(String v) { this.branch = v; }

    public String getAreaCode() { return areaCode; }
    public void setAreaCode(String v) { this.areaCode = v; }

    public LocalDate getEnrollmentDate() { return enrollmentDate; }
    public void setEnrollmentDate(LocalDate v) { this.enrollmentDate = v; }

    public String getStationId() { return stationId; }
    public void setStationId(String v) { this.stationId = v; }

    public String getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(String v) { this.sequenceNo = v; }

    public String getCardType() { return cardType; }
    public void setCardType(String v) { this.cardType = v; }

    public String getProcessType() { return processType; }
    public void setProcessType(String v) { this.processType = v; }

    public String getEnrollReason() { return enrollReason; }
    public void setEnrollReason(String v) { this.enrollReason = v; }

    public String getEntryType() { return entryType; }
    public void setEntryType(String v) { this.entryType = v; }

    public String getMailOption() { return mailOption; }
    public void setMailOption(String v) { this.mailOption = v; }

    public String getIssuingPostOffice() { return issuingPostOffice; }
    public void setIssuingPostOffice(String v) { this.issuingPostOffice = v; }

    public String getApoCode() { return apoCode; }
    public void setApoCode(String v) { this.apoCode = v; }

    public String getCpoCode() { return cpoCode; }
    public void setCpoCode(String v) { this.cpoCode = v; }

    public LocalDate getSchedDate() { return schedDate; }
    public void setSchedDate(LocalDate v) { this.schedDate = v; }

    public String getOrNumber() { return orNumber; }
    public void setOrNumber(String v) { this.orNumber = v; }

    public LocalDate getOrDate() { return orDate; }
    public void setOrDate(LocalDate v) { this.orDate = v; }

    public String getOrBranch() { return orBranch; }
    public void setOrBranch(String v) { this.orBranch = v; }

    public BigDecimal getOrAmount() { return orAmount; }
    public void setOrAmount(BigDecimal v) { this.orAmount = v; }

    public String getDemoStatus() { return demoStatus; }
    public void setDemoStatus(String v) { this.demoStatus = v; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean v) { this.deleted = v; }

    public String getDoneQa() { return doneQa; }
    public void setDoneQa(String v) { this.doneQa = v; }

    public String getQaTag() { return qaTag; }
    public void setQaTag(String v) { this.qaTag = v; }

    public String getCapturedBy() { return capturedBy; }
    public void setCapturedBy(String v) { this.capturedBy = v; }

    public String getTimeStartCapture() { return timeStartCapture; }
    public void setTimeStartCapture(String v) { this.timeStartCapture = v; }

    public String getTimeEndCapture() { return timeEndCapture; }
    public void setTimeEndCapture(String v) { this.timeEndCapture = v; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public List<CardholderDocument> getDocuments() { return documents; }
}
