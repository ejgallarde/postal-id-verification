package com.postalid.verification.media;

import com.postalid.verification.config.PostalIdProperties;
import com.postalid.verification.testsupport.SyntheticPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the piece most likely to break silently.
 *
 * <p>Enrollment signatures are bilevel LZW TIFFs. Stock ImageIO cannot read them at all, so if the
 * TwelveMonkeys plugin ever falls off the classpath the symptom would be a broken image icon on
 * the result page rather than a failing build. These tests make it a failing build.
 */
class ImageTranscoderTest {

    @TempDir
    Path tempDir;

    private ImageTranscoder transcoder;

    @BeforeEach
    void setUp() {
        transcoder = new ImageTranscoder(new PostalIdProperties());
        transcoder.assertTiffSupportPresent();
    }

    @Test
    @DisplayName("a bilevel LZW TIFF signature becomes a PNG of the same dimensions")
    void transcodesBilevelTiffToPng() throws IOException {
        Path tiff = tempDir.resolve("signature.tiff");
        SyntheticPacket.writeSignatureTiff(tiff);

        WebImage result = transcoder.toWebSafe(Files.readAllBytes(tiff), "image/tiff");

        assertThat(result.contentType()).isEqualTo("image/png");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.data()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(SyntheticPacket.SIGNATURE_WIDTH);
        assertThat(decoded.getHeight()).isEqualTo(SyntheticPacket.SIGNATURE_HEIGHT);
    }

    @Test
    @DisplayName("the transcoded signature is ink on white, not a blank or transparent image")
    void transcodedSignatureHasVisibleInkOnWhite() throws IOException {
        Path tiff = tempDir.resolve("signature.tiff");
        SyntheticPacket.writeSignatureTiff(tiff);

        WebImage result = transcoder.toWebSafe(Files.readAllBytes(tiff), "image/tiff");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.data()));

        long dark = 0;
        long light = 0;
        for (int y = 0; y < decoded.getHeight(); y++) {
            for (int x = 0; x < decoded.getWidth(); x++) {
                int luminance = decoded.getRGB(x, y) & 0xFF;
                if (luminance < 64) {
                    dark++;
                } else if (luminance > 192) {
                    light++;
                }
            }
        }

        assertThat(dark).as("signature strokes should survive the conversion").isPositive();
        assertThat(light).as("background should be white, not transparent black").isPositive();

        // Fully opaque: a transparent signature renders as invisible against a dark page.
        assertThat(decoded.getColorModel().hasAlpha()).isFalse();
    }

    @Test
    @DisplayName("JPEG is passed through rather than re-encoded")
    void passesJpegThrough() throws IOException {
        BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        Path jpeg = tempDir.resolve("photo.jpg");
        try (var out = Files.newOutputStream(jpeg)) {
            ImageIO.write(image, "jpeg", out);
        }
        byte[] original = Files.readAllBytes(jpeg);

        WebImage result = transcoder.toWebSafe(original, "image/jpeg");

        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.data()).isSameAs(original);
    }

    @Test
    void rejectsBytesThatAreNotAnImage() {
        byte[] junk = "this is not an image".getBytes();

        assertThatThrownBy(() -> transcoder.toWebSafe(junk, "application/octet-stream"))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("an image beyond the pixel budget is refused rather than allocated")
    void enforcesPixelBudget() throws IOException {
        PostalIdProperties tiny = new PostalIdProperties();
        tiny.getMedia().setMaxImagePixels(100);
        ImageTranscoder strict = new ImageTranscoder(tiny);
        strict.assertTiffSupportPresent();

        Path tiff = tempDir.resolve("signature.tiff");
        SyntheticPacket.writeSignatureTiff(tiff);
        byte[] oversized = Files.readAllBytes(tiff);

        assertThatThrownBy(() -> strict.toWebSafe(oversized, "image/tiff"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("pixel budget");
    }
}
