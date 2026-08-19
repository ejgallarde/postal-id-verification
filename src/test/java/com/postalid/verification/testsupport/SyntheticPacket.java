package com.postalid.verification.testsupport;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Builds a made-up enrollment packet on disk for tests.
 *
 * <p>Tests never touch the real {@code files/} directory. That packet is one identifiable
 * person's photograph, fingerprints and certificates, and a test suite is exactly the sort of
 * thing that ends up copied into a CI log or a shared work directory.
 *
 * <p>The images are generated rather than committed, which keeps binaries out of the repository
 * and lets the signature be written as a bilevel LZW TIFF — the awkward format the real packets
 * use, and the one the transcoder has to cope with.
 */
public final class SyntheticPacket {

    public static final String RECORD_ID = "9920260101TESTSTA0001";
    public static final String POSTAL_REF_NO = "T99260320001";
    public static final String FIRST_NAME = "MARIA";
    public static final String MIDDLE_NAME = "REYES";
    public static final String LAST_NAME = "SANTOS";
    public static final String DATE_OF_BIRTH = "1988-03-17";

    public static final int SIGNATURE_WIDTH = 792;
    public static final int SIGNATURE_HEIGHT = 200;
    public static final int PHOTO_WIDTH = 240;
    public static final int PHOTO_HEIGHT = 320;

    private static final String DEMOGRAPHICS_HEADER = String.join(",",
            "Rnum", "RecordID", "SSN", "BR", "AC", "ERD", "StID", "SQNo", "Fname", "Mname", "Lname",
            "Sname", "AddPC", "AddCC", "AddPS", "AddCM", "AddArea", "AddBDL", "AddSub", "AddSt",
            "AddHBL", "AddRFU", "Gen", "DOB", "POBCM", "POBPS", "POBCC", "MS", "FFname", "FMname",
            "FLname", "FSname", "MFname", "MMname", "MLname", "MSname", "FCLP", "FCRP", "FCLB",
            "FCRB", "FIEC", "HT", "WT", "DisFeat", "TIN", "CRN", "ET", "EnrollReason", "Nationality",
            "ContactNo", "MobileNo", "GSIS", "PhilHealth", "HDMF", "PostalRefNo", "Email",
            "CompanyName", "Occupation", "WorkPC", "WorkCC", "WorkPS", "WorkCM", "WorkArea",
            "WorkBDL", "WorkSub", "WorkSt", "WorkHBL", "WorkRFU", "MailOption", "SchedDate",
            "ORNumber", "ORDate", "ORBranch", "ORAmount", "APOCode", "CPOCode", "CardType",
            "ProcessType", "Complexion", "HairColor", "EyeColor", "ControlNo", "IsDeleted",
            "DemoStatus", "IssuingPostOfc", "fPhoto", "HldRem", "CapturedBy", "TimeStartCapture",
            "TimeEndCapture", "DoneQA", "QATag");

    private SyntheticPacket() {
    }

    /** Writes a complete packet whose record has reached the issued status. */
    public static void write(Path directory) throws IOException {
        write(directory, "CDE", false);
    }

    /**
     * @param demoStatus status to stamp on the record, e.g. {@code CDE} for issued or {@code FCD}
     *                   for a record that never got as far as a card
     * @param deleted    whether the record is flagged deleted
     */
    public static void write(Path directory, String demoStatus, boolean deleted) throws IOException {
        write(directory, RECORD_ID, POSTAL_REF_NO, demoStatus, deleted);
    }

    /**
     * Writes a packet under a different identity, for tests that need several in one batch.
     * Both identifiers must be unique: the schema enforces uniqueness on each.
     */
    public static void writeAs(Path directory, String recordId, String postalRefNo) throws IOException {
        write(directory, recordId, postalRefNo, "CDE", false);
    }

    private static void write(Path directory, String recordId, String postalRefNo,
                              String demoStatus, boolean deleted) throws IOException {
        Files.createDirectories(directory);
        writeDemographics(directory, recordId, postalRefNo, demoStatus, deleted);
        writeStatusDetail(directory, recordId, demoStatus);
        writePhoto(directory.resolve(recordId + "_Photo.jpg"));
        writeSignatureTiff(directory.resolve(recordId + "_Signature.tiff"));
        // Stand-in for a fingerprint: never decoded, only referenced, so the bytes are irrelevant.
        Files.write(directory.resolve(recordId + "_RT.wsq"), new byte[]{(byte) 0xFF, (byte) 0xA0, 0x01, 0x02});
    }

    private static void writeDemographics(Path directory, String recordId, String postalRefNo,
                                          String demoStatus, boolean deleted) throws IOException {
        // Values are padded with trailing spaces, as the real export does, so tests exercise the
        // trimming the parser has to perform.
        String row = String.join(",",
                "9000001", recordId, "", "T9 ", "99", "20260101", "TESTSTA", "001",
                pad(FIRST_NAME, 60), pad(MIDDLE_NAME, 60), pad(LAST_NAME, 60), pad("", 10),
                "TS10001   ", "PHL", "TST", pad("Test City", 40), pad("", 20), pad("BARANGAY UNO", 30),
                pad("TEST SUBDIVISION", 40), pad("", 40), pad("BLK 1 LOT 1", 15), pad("", 40),
                "F", "19880317", pad("Test Birth City", 40), pad("TST", 30), "PHL", "1",
                pad("JOSE", 40), pad("CRUZ", 40), pad("SANTOS", 40), pad("", 10),
                pad("ANA", 40), pad("LIM", 40), pad("REYES", 40), pad("", 10),
                " ", " ", " ", " ", "  ", "165", "55   ", pad("", 70),
                pad("", 10), pad("", 12), " ", "I", pad("Filipino", 50),
                pad("", 45), "09170000001", pad("", 10), pad("", 12), pad("", 12),
                postalRefNo, pad("", 50), pad("", 60), pad("", 40),
                "TS10002   ", "PHL", "TST", pad("Test Work City", 40), pad("", 20), pad("BARANGAY DOS", 30),
                pad("", 40), pad("", 40), pad("", 15), pad("TEST-PO", 40),
                "HOME  ", "01152026", "0000000001", "01152026", pad("", 40), "650.00",
                "T99   ", "T99   ", "P ", "RUS", pad("", 20), pad("", 20), pad("", 20),
                "001                 ", deleted ? "1" : "0", demoStatus, "", "", "",
                "0000-00-0000|09:00:00|09:30:00", "", "", "0", "");

        Files.writeString(directory.resolve(recordId + "_demographics.csv"),
                DEMOGRAPHICS_HEADER + "\n" + row + "\n", StandardCharsets.UTF_8);
    }

    private static void writeStatusDetail(Path directory, String recordId, String finalStatus) throws IOException {
        String content = """
                ID,DemoFK,RecordID,Status,TimeStamp,ProcBy
                9000001,9000001,%1$s,REC       ,2026-01-01 09:00:00.000001,tester
                9000002,9000001,%1$s,AQC       ,2026-01-01 09:05:00.000002,tester
                9000003,9000001,%1$s,%2$-10s,2026-01-01 10:00:00.000003,tester
                """.formatted(recordId, finalStatus);

        Files.writeString(directory.resolve(recordId + "__demostatusdtl.csv"), content, StandardCharsets.UTF_8);
    }

    /** A recognisable JPEG: two flat colour bands, so a blank result is obvious. */
    private static void writePhoto(Path target) throws IOException {
        BufferedImage image = new BufferedImage(PHOTO_WIDTH, PHOTO_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(190, 200, 210));
        g.fillRect(0, 0, PHOTO_WIDTH, PHOTO_HEIGHT);
        g.setColor(new Color(60, 70, 90));
        g.fillOval(60, 60, 120, 140);
        g.dispose();

        try (OutputStream out = Files.newOutputStream(target)) {
            ImageIO.write(image, "jpeg", out);
        }
    }

    /**
     * A bilevel, LZW-compressed TIFF, matching what real packets hold for a signature.
     *
     * <p>This is the format stock ImageIO cannot read at all, which is the whole reason the
     * TwelveMonkeys plugin is a dependency. Generating it here means the transcoder is tested
     * against the awkward case rather than a convenient PNG.
     */
    public static void writeSignatureTiff(Path target) throws IOException {
        BufferedImage image = new BufferedImage(
                SIGNATURE_WIDTH, SIGNATURE_HEIGHT, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, SIGNATURE_WIDTH, SIGNATURE_HEIGHT);
        g.setColor(Color.BLACK);
        g.drawLine(40, 150, 300, 60);
        g.drawLine(300, 60, 500, 160);
        g.drawLine(500, 160, 740, 70);
        g.dispose();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("tiff");
        if (!writers.hasNext()) {
            throw new IOException("No TIFF ImageWriter available");
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(Files.newOutputStream(target))) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType("LZW");
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }
}
