package com.postalid.verification.sandbox;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the sandbox's made-up cardholders from {@code classpath:sandbox/cardholders.csv}.
 *
 * <p>Kept as a plain CSV, one person per row, so adding a scenario or correcting a name is an
 * edit to a data file rather than to code. A catalogue listing the same reference number twice
 * stops startup.
 *
 * <p>Reference numbers are free-form so that testers' expected values can be used as given.
 * Most use the {@code SBX} prefix by convention, which makes them recognisable at a glance, but
 * nothing enforces it: what keeps real data out is {@link SandboxSeeder}'s refusal to run against
 * any record the catalogue does not list. Every row here must therefore be fictional.
 */
@Component
@Profile("sandbox")
public class SandboxCatalogue {

    static final String LOCATION = "sandbox/cardholders.csv";

    public List<SandboxCardholder> load() throws IOException {
        try (Reader reader = new InputStreamReader(
                new ClassPathResource(LOCATION).getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setTrim(true)
                     .get()
                     .parse(reader)) {

            List<SandboxCardholder> cardholders = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int sequence = 0;

            for (CSVRecord row : parser) {
                sequence++;
                String prn = row.get("postalRefNo").toUpperCase(Locale.ROOT);
                if (!seen.add(prn)) {
                    throw new IllegalStateException("Sandbox catalogue lists " + prn + " more than once");
                }

                cardholders.add(new SandboxCardholder(
                        sequence,
                        prn,
                        row.get("firstName"),
                        row.get("middleName"),
                        row.get("lastName"),
                        row.get("suffix"),
                        LocalDate.parse(row.get("dateOfBirth")),
                        row.get("gender"),
                        row.get("status"),
                        Boolean.parseBoolean(row.get("deleted")),
                        LocalDate.parse(row.get("enrolledOn")),
                        row.get("issuedOn").isEmpty() ? null : LocalDate.parse(row.get("issuedOn")),
                        Boolean.parseBoolean(row.get("signature")),
                        row.get("houseBldgLot"),
                        row.get("barangay"),
                        row.get("city"),
                        row.get("province"),
                        row.get("postalCode"),
                        row.get("scenario")));
            }
            return cardholders;
        }
    }
}
