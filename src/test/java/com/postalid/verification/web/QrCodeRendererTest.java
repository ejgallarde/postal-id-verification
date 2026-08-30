package com.postalid.verification.web;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the QR is actually scannable, not merely present.
 *
 * <p>Asserting that an {@code <svg>} element appears on the page would pass just as happily for a
 * corrupt symbol. These tests parse the emitted SVG back into a bitmap and decode it, so what is
 * verified is what a phone camera would read.
 */
class QrCodeRendererTest {

    private static final Pattern RUN =
            Pattern.compile("M(\\d+) (\\d+)h(\\d+)v1h-\\d+z");
    private static final Pattern VIEW_BOX =
            Pattern.compile("viewBox=\"0 0 (\\d+) (\\d+)\"");

    private final QrCodeRenderer renderer = new QrCodeRenderer();

    @Test
    @DisplayName("the rendered QR decodes back to the verification reference")
    void roundTripsThroughAScanner() throws Exception {
        String requestId = "7038dc89-3bf6-4b9e-b423-309586eef8d1";
        String verifiedAt = "2026-08-30 12:06 UTC";

        String decoded = decode(renderer.toSvg(requestId, verifiedAt));

        assertThat(decoded)
                .startsWith("PIDV:")
                .contains(requestId)
                .contains(verifiedAt);
    }

    @Test
    @DisplayName("the payload carries the verification only, never the cardholder")
    void carriesNoPersonalData() throws Exception {
        String decoded = decode(renderer.toSvg("abc-123", "2026-08-30 12:06 UTC"));

        // Whatever else changes, a scanned screenshot must not yield somebody's identity.
        assertThat(decoded)
                .doesNotContain("SANTOS")
                .doesNotContain("MARIA")
                .doesNotContain("1988")
                .doesNotContain("T99260320001");
        assertThat(decoded).isEqualTo("PIDV:abc-123 2026-08-30 12:06 UTC");
    }

    @Test
    @DisplayName("a rendering failure yields an empty string, never a broken result page")
    void failsSoftly() {
        // A payload beyond any QR version cannot be encoded; the page must still render.
        String oversized = "x".repeat(10_000);

        assertThat(renderer.toSvg(oversized, oversized)).isEmpty();
    }

    @Test
    void emitsWellFormedSvg() {
        String svg = renderer.toSvg("abc-123", "2026-08-30 12:06 UTC");

        assertThat(svg)
                .startsWith("<svg")
                .endsWith("</svg>")
                .contains("role=\"img\"")
                .contains("aria-label=\"Verification reference code\"")
                .contains("shape-rendering=\"crispEdges\"");
    }

    // --- Turning the emitted SVG back into something decodable --------------

    private String decode(String svg) throws NotFoundException, com.google.zxing.ChecksumException,
            com.google.zxing.FormatException {

        Matcher box = VIEW_BOX.matcher(svg);
        assertThat(box.find()).as("svg must declare a viewBox").isTrue();
        int width = Integer.parseInt(box.group(1));
        int height = Integer.parseInt(box.group(2));

        boolean[][] dark = new boolean[height][width];
        Matcher runs = RUN.matcher(svg);
        while (runs.find()) {
            int x = Integer.parseInt(runs.group(1));
            int y = Integer.parseInt(runs.group(2));
            int length = Integer.parseInt(runs.group(3));
            for (int i = 0; i < length; i++) {
                dark[y][x + i] = true;
            }
        }

        // Scale up: a decoder needs more than one pixel per module to lock on.
        int scale = 4;
        LuminanceSource source = new ArraySource(dark, width, height, scale);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }

    /** Minimal luminance source over the parsed module grid. */
    private static final class ArraySource extends LuminanceSource {

        private final byte[] pixels;

        ArraySource(boolean[][] dark, int modulesWide, int modulesHigh, int scale) {
            super(modulesWide * scale, modulesHigh * scale);
            this.pixels = new byte[getWidth() * getHeight()];
            for (int y = 0; y < getHeight(); y++) {
                for (int x = 0; x < getWidth(); x++) {
                    boolean on = dark[y / scale][x / scale];
                    pixels[y * getWidth() + x] = (byte) (on ? 0x00 : 0xFF);
                }
            }
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            byte[] target = (row != null && row.length >= getWidth()) ? row : new byte[getWidth()];
            System.arraycopy(pixels, y * getWidth(), target, 0, getWidth());
            return target;
        }

        @Override
        public byte[] getMatrix() {
            return pixels;
        }
    }
}
