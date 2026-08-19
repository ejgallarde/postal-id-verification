package com.postalid.verification.ingest;

import com.postalid.verification.domain.CardStatusHistory;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the {@code *__demostatusdtl.csv} status trail.
 *
 * <p>Timestamps come with microsecond precision and no zone, so they are kept as local date-times
 * exactly as written. Reading a zone into them would be inventing information the export does not
 * carry, and would make the stored values move if the server's timezone ever changed. The trail
 * is history: verification decisions read {@code DemoStatus} on the demographics row instead.
 */
@Component
public class StatusDetailCsvParser {

    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter();

    public List<CardStatusHistory> parse(Path csvFile) throws IOException {
        List<CardStatusHistory> history = new ArrayList<>();

        try (Reader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .get()
                     .parse(reader)) {

            for (CSVRecord record : parser) {
                CsvRow row = new CsvRow(record);
                if (row.isBlank()) {
                    continue;
                }
                String status = row.text("Status");
                if (status == null) {
                    continue;
                }

                CardStatusHistory entry = new CardStatusHistory();
                entry.setSourceId(row.integer("ID"));
                entry.setStatus(status);
                entry.setStatusTimestamp(parseTimestamp(row.text("TimeStamp")));
                entry.setProcessedBy(row.text("ProcBy"));
                history.add(entry);
            }
        }
        return history;
    }

    private static LocalDateTime parseTimestamp(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, TIMESTAMP);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
