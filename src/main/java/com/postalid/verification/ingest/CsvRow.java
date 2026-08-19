package com.postalid.verification.ingest;

import org.apache.commons.csv.CSVRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Reads one CSV record, absorbing the quirks of the enrollment export.
 *
 * <p>Every field in the source is padded to a fixed width with spaces, so "absent" arrives as a
 * run of blanks rather than an empty string. Dates arrive in two different layouts depending on
 * the column. Values are trimmed and empties folded to {@code null} here, once, so nothing
 * downstream has to remember to do it.
 */
final class CsvRow {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MMDDYYYY = DateTimeFormatter.ofPattern("MMddyyyy");

    private final CSVRecord record;

    CsvRow(CSVRecord record) {
        this.record = record;
    }

    /** Trimmed value, or null when the column is absent, empty, or nothing but padding. */
    String text(String column) {
        if (!record.isMapped(column)) {
            return null;
        }
        String value = record.get(column);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    String required(String column) {
        String value = text(column);
        if (value == null) {
            throw new PacketIngestException("Required column '" + column + "' is empty");
        }
        return value;
    }

    /** Parses a {@code yyyyMMdd} column, e.g. DOB and ERD. */
    LocalDate compactDate(String column) {
        return parseDate(column, text(column), YYYYMMDD);
    }

    /** Parses an {@code MMddyyyy} column, e.g. ORDate and SchedDate. */
    LocalDate usDate(String column) {
        return parseDate(column, text(column), MMDDYYYY);
    }

    LocalDate requiredCompactDate(String column) {
        LocalDate parsed = compactDate(column);
        if (parsed == null) {
            throw new PacketIngestException("Required date column '" + column + "' is empty or unparseable");
        }
        return parsed;
    }

    BigDecimal decimal(String column) {
        String value = text(column);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    Long integer(String column) {
        String value = text(column);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The export writes booleans as "0"/"1". */
    boolean flag(String column) {
        String value = text(column);
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "Y".equalsIgnoreCase(value);
    }

    private static LocalDate parseDate(String column, String value, DateTimeFormatter formatter) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, formatter);
        } catch (DateTimeParseException e) {
            // Placeholder junk such as "00000000" is common in these exports; treat it as absent
            // rather than failing the whole packet over a non-essential column.
            return null;
        }
    }

    /** True when the row is the trailing blank line these exports usually end with. */
    boolean isBlank() {
        for (String value : record) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
