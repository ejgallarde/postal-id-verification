package com.postalid.verification.sandbox;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a sandbox cardholder out as an enrollment packet, in the same layout and column format
 * as a real export, so the sandbox loads through the ordinary ingest path rather than a shortcut.
 * A tester exercising the sandbox is therefore exercising the real parser, the real transcoder
 * and the real blob store.
 *
 * <p>The images are drawn, not sourced: a photo card stamped SANDBOX with the person's initials,
 * so a tester can see at a glance that the right image came back for the right record, and a
 * signature rendered from the name as a bilevel LZW TIFF — the awkward format real packets use,
 * and the one the transcoder exists to convert.
 */
@Component
@Profile("sandbox")
public class SyntheticPacketWriter {

    // Dimensions match a real capture: a 480x640 JPEG portrait, a 792x200 bilevel signature, and
    // A4 document scans at 150 dpi.
    static final int PHOTO_WIDTH = 480;
    static final int PHOTO_HEIGHT = 640;
    static final int SIGNATURE_WIDTH = 792;
    static final int SIGNATURE_HEIGHT = 200;
    static final int SCAN_WIDTH = 1236;
    static final int SCAN_HEIGHT = 1748;

    /**
     * The supporting-document scans a real packet carries, by asset code, with the heading drawn on
     * each specimen. None is ever servable; they exist so the sandbox stores, hashes and refuses
     * them exactly as it would a real packet's.
     */
    static final Map<String, String> SCANS = Map.of(
            "BC", "CERTIFICATE OF LIVE BIRTH",
            "MC", "CERTIFICATE OF MARRIAGE",
            "PC", "SUPPORTING DOCUMENT (CODE PC)",
            "OR", "OFFICIAL RECEIPT",
            "ORForm", "OFFICIAL RECEIPT FORM",
            "E6Form", "ENROLLMENT FORM (CODE E6FORM)");

    /** Real packets scan the enrollment form in black and white; everything else is in colour. */
    private static final String BILEVEL_SCAN = "E6Form";

    static final List<String> FINGERPRINTS = List.of("RT", "RI", "LT", "LI");

    /** Scans listed in the order a real late-submission manifest lists them. */
    private static final List<String> MANIFEST_ORDER = List.of("OR", "BC", "MC", "PC");

    /** The photo's drawing grid, scaled up 2x onto the full-size image. */
    private static final int GRID_WIDTH = 240;
    private static final int GRID_HEIGHT = 320;

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MMDDYYYY = DateTimeFormatter.ofPattern("MMddyyyy");
    private static final DateTimeFormatter STATUS_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** The export's column order. The parser reads by name, but keeping the order keeps the files recognisable. */
    private static final String[] DEMOGRAPHICS_HEADER = {
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
            "TimeEndCapture", "DoneQA", "QATag"};

    /**
     * Writes one packet into its own folder under {@code root}, named for the record id as real
     * drops are. Overwrites a previous run's files, so re-seeding is idempotent.
     *
     * @return the packet folder
     */
    public Path write(Path root, SandboxCardholder person) throws IOException {
        Path directory = root.resolve(person.recordId());
        Files.createDirectories(directory);

        String id = person.recordId();
        writeDemographics(directory, person);
        writeStatusDetail(directory, person);
        writePhoto(directory.resolve(id + "_Photo.jpg"), person);
        if (person.hasSignature()) {
            writeSignature(directory.resolve(id + "_Signature.tiff"), person);
        } else {
            Files.deleteIfExists(directory.resolve(id + "_Signature.tiff"));
        }
        for (Map.Entry<String, String> scan : SCANS.entrySet()) {
            writeScan(directory.resolve(id + "_" + scan.getKey() + ".tiff"), scan.getValue(), person,
                    BILEVEL_SCAN.equals(scan.getKey()));
        }
        // Stand-ins for the four fingerprints: real packets carry them, the API must never serve
        // them, and the sandbox should prove that by having them present. Never decoded.
        for (String finger : FINGERPRINTS) {
            Files.write(directory.resolve(id + "_" + finger + ".wsq"),
                    new byte[]{(byte) 0xFF, (byte) 0xA0, 0x01, 0x02});
        }
        writeManifest(directory, person);
        return directory;
    }

    /**
     * The late-submission manifest a real drop includes. Like the real one, it names every asset
     * with a {@code .jpg} extension even where the file on disk is a TIFF; the ingester is meant
     * to trust the disk, and the sandbox keeps that discrepancy so it stays exercised.
     */
    private void writeManifest(Path directory, SandboxCardholder p) throws IOException {
        String id = p.recordId();
        List<String> lines = new ArrayList<>(List.of(id + "_demographics.csv", id + "__demostatusdtl.csv"));
        MANIFEST_ORDER.forEach(code -> lines.add(id + "_" + code + ".jpg"));
        FINGERPRINTS.forEach(finger -> lines.add(id + "_" + finger + ".wsq"));
        lines.add(id + "_Photo.jpg");
        if (p.hasSignature()) {
            lines.add(id + "_Signature.jpg");
        }
        lines.add(id + "_E6Form.jpg");
        lines.add(id + "_ORForm.jpg");

        Files.write(directory.resolve("lateSub_" + p.enrolledOn().format(YYYYMMDD) + ".txt"), lines,
                StandardCharsets.UTF_8);
    }

    private void writeDemographics(Path directory, SandboxCardholder p) throws IOException {
        String seq = String.format("%04d", p.sequence());
        LocalDate enrolled = p.enrolledOn();

        // Fields the API never returns get fixed placeholders that are obviously not real.
        List<Object> row = new ArrayList<>(List.of(
                "91" + seq, p.recordId(), "", "SB", "99", enrolled.format(YYYYMMDD), "SANDBOX", seq,
                p.firstName(), p.middleName(), p.lastName(), p.suffix(),
                p.postalCode(), "PHL", p.province(), p.city(), "", p.barangay(),
                "SANDBOX VILLAGE", "", p.houseBldgLot(), "",
                p.gender(), p.dateOfBirth().format(YYYYMMDD), "Sandbox Birth City", "SBX", "PHL", "1",
                "SANDBOX", "", "FATHER", "", "SANDBOX", "", "MOTHER", "",
                "", "", "", "", "", "165", "55", "",
                "", "", "", "I", "Filipino",
                "", "09000000000", "", "", "",
                p.postalRefNo(), "", "", "",
                "", "", "", "", "", "", "", "", "", "",
                "HOME", enrolled.format(MMDDYYYY), "SBX" + seq, enrolled.format(MMDDYYYY), "", "0.00",
                "SBX", "SBX", "P", "RUS", "", "", "",
                "SBX" + seq, p.deleted() ? "1" : "0", p.status(), "SANDBOX POST OFFICE", "", "",
                "sandbox|09:00:00|09:30:00", "", "", "1", ""));

        try (Writer writer = Files.newBufferedWriter(directory.resolve(p.recordId() + "_demographics.csv"),
                StandardCharsets.UTF_8);
             CSVPrinter printer = CSVFormat.DEFAULT.builder()
                     .setHeader(DEMOGRAPHICS_HEADER)
                     .setRecordSeparator("\n")
                     .get()
                     .print(writer)) {
            printer.printRecord(row);
        }
    }

    /**
     * The status trail. Validity is derived from the entry where the record reached an issued
     * status, so its date is what makes a card ACTIVE or EXPIRED in the sandbox.
     */
    private void writeStatusDetail(Path directory, SandboxCardholder p) throws IOException {
        String enrolled = p.enrolledOn().format(STATUS_DATE);
        String finalDate = (p.issuedOn() != null ? p.issuedOn() : p.enrolledOn()).format(STATUS_DATE);
        String demoFk = "91" + String.format("%04d", p.sequence());

        String content = """
                ID,DemoFK,RecordID,Status,TimeStamp,ProcBy
                %1$s1,%1$s,%2$s,REC,%3$s 09:00:00.000001,sandbox
                %1$s2,%1$s,%2$s,AQC,%3$s 09:05:00.000002,sandbox
                %1$s3,%1$s,%2$s,%4$s,%5$s 10:00:00.000003,sandbox
                """.formatted(demoFk, p.recordId(), enrolled, p.status(), finalDate);

        Files.writeString(directory.resolve(p.recordId() + "__demostatusdtl.csv"), content, StandardCharsets.UTF_8);
    }

    /** A passport-proportioned card: tinted background, silhouette, initials, and a SANDBOX band. */
    private void writePhoto(Path target, SandboxCardholder p) throws IOException {
        BufferedImage image = new BufferedImage(PHOTO_WIDTH, PHOTO_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // Laid out on a 240x320 grid and drawn at twice that, the real capture size.
        g.scale(2, 2);

        // A different, muted background per person, so two records' photos never look alike.
        float hue = (p.sequence() * 0.137f) % 1f;
        g.setColor(Color.getHSBColor(hue, 0.18f, 0.92f));
        g.fillRect(0, 0, GRID_WIDTH, GRID_HEIGHT);

        g.setColor(new Color(70, 80, 100));
        g.fillOval(72, 56, 96, 112);
        g.fillRoundRect(40, 170, 160, 150, 80, 80);

        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
        drawCentred(g, initials(p), 128);

        g.setColor(new Color(200, 16, 46));
        g.fillRect(0, 262, GRID_WIDTH, 34);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        drawCentred(g, "SANDBOX", 286);
        g.dispose();

        try (OutputStream out = Files.newOutputStream(target)) {
            ImageIO.write(image, "jpeg", out);
        }
    }

    /** The name in an italic face on a bilevel canvas, saved as an LZW TIFF like a real capture. */
    private void writeSignature(Path target, SandboxCardholder p) throws IOException {
        BufferedImage image = new BufferedImage(SIGNATURE_WIDTH, SIGNATURE_HEIGHT, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, SIGNATURE_WIDTH, SIGNATURE_HEIGHT);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SERIF, Font.ITALIC, 64));

        String signed = capitalise(p.firstName().split(" ")[0]) + " " + capitalise(p.lastName());
        FontMetrics metrics = g.getFontMetrics();
        int x = Math.max(20, (SIGNATURE_WIDTH - metrics.stringWidth(signed)) / 2);
        g.drawString(signed, x, 120);
        g.setStroke(new BasicStroke(3));
        g.drawLine(x, 140, Math.min(SIGNATURE_WIDTH - 20, x + metrics.stringWidth(signed)), 132);
        g.dispose();

        writeLzwTiff(image, target);
    }

    /**
     * An A4 page standing in for a supporting-document scan, at the size and in the format a real
     * one arrives: colour LZW TIFF, or black and white for the enrollment form.
     *
     * <p>Deliberately plain and unmistakably fake. It copies no official layout, carries no seal,
     * and is stamped SPECIMEN across the page: a convincing synthetic birth certificate would be a
     * forgery template, and nothing about testing needs one. These scans are never served anyway;
     * they exist so the sandbox records and refuses them as it would a real packet's.
     */
    private void writeScan(Path target, String title, SandboxCardholder p, boolean bilevel) throws IOException {
        BufferedImage page = new BufferedImage(SCAN_WIDTH, SCAN_HEIGHT,
                bilevel ? BufferedImage.TYPE_BYTE_BINARY : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                bilevel ? RenderingHints.VALUE_TEXT_ANTIALIAS_OFF : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, SCAN_WIDTH, SCAN_HEIGHT);

        Color ink = Color.BLACK;
        Color warn = bilevel ? Color.BLACK : new Color(200, 16, 46);

        // Watermark first, so the text sits on top of it.
        if (!bilevel) {
            Graphics2D mark = (Graphics2D) g.create();
            mark.setColor(new Color(235, 235, 235));
            mark.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 230));
            mark.rotate(Math.toRadians(-35), SCAN_WIDTH / 2.0, SCAN_HEIGHT / 2.0);
            drawCentred(mark, "SPECIMEN", SCAN_HEIGHT / 2 + 80, SCAN_WIDTH);
            mark.dispose();
        }

        g.setColor(warn);
        g.setStroke(new BasicStroke(6));
        g.drawRect(60, 60, SCAN_WIDTH - 120, SCAN_HEIGHT - 120);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
        drawCentred(g, "SANDBOX SPECIMEN  -  NOT A REAL DOCUMENT", 150, SCAN_WIDTH);

        g.setColor(ink);
        g.setFont(new Font(Font.SERIF, Font.BOLD, 52));
        drawCentred(g, title, 300, SCAN_WIDTH);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 34));
        String fullName = String.join(" ", p.firstName(), p.middleName(), p.lastName(), p.suffix())
                .replaceAll("\\s+", " ").trim();
        String[][] fields = {
                {"Name", fullName},
                {"Date of birth", p.dateOfBirth().toString()},
                {"Postal reference no.", p.postalRefNo()},
                {"Packet record id", p.recordId()},
                {"Enrolled", p.enrolledOn().toString()},
        };
        int y = 460;
        for (String[] field : fields) {
            g.drawString(field[0], 160, y);
            g.drawString(field[1], 560, y);
            g.drawLine(560, y + 12, SCAN_WIDTH - 160, y + 12);
            y += 90;
        }

        g.setColor(warn);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 26));
        drawCentred(g, "Synthetic test data generated by the PIVOT sandbox. Describes no real person.",
                SCAN_HEIGHT - 110, SCAN_WIDTH);
        g.dispose();

        writeLzwTiff(page, target);
    }

    private static void writeLzwTiff(BufferedImage image, Path target) throws IOException {
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

    private static void drawCentred(Graphics2D g, String text, int baseline) {
        drawCentred(g, text, baseline, GRID_WIDTH);
    }

    private static void drawCentred(Graphics2D g, String text, int baseline, int width) {
        int textWidth = g.getFontMetrics().stringWidth(text);
        g.drawString(text, (width - textWidth) / 2, baseline);
    }

    private static String initials(SandboxCardholder p) {
        return (p.firstName().substring(0, 1) + p.lastName().substring(0, 1)).toUpperCase(Locale.ROOT);
    }

    private static String capitalise(String word) {
        if (word.isEmpty()) {
            return word;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean upperNext = true;
        for (char c : lower.toCharArray()) {
            out.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = c == '-' || c == '\'' || c == ' ';
        }
        return out.toString();
    }
}
