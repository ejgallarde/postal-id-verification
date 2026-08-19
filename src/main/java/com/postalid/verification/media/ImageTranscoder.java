package com.postalid.verification.media;

import com.postalid.verification.config.PostalIdProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Converts captured assets into something a browser can display.
 *
 * <p>An enrollment packet stores the cardholder photo as JPEG but the signature — and every
 * supporting scan — as LZW-compressed TIFF, which no browser renders. Stock ImageIO cannot read
 * those either; the TwelveMonkeys plugin on the classpath is what makes it possible, and
 * {@link #assertTiffSupportPresent()} fails fast at startup rather than at the first signature
 * request if that plugin ever goes missing.
 *
 * <p>Formats the browser already handles are passed through untouched. Re-encoding the JPEG
 * photograph as PNG would multiply its size for no benefit.
 */
@Component
public class ImageTranscoder {

    private static final Logger log = LoggerFactory.getLogger(ImageTranscoder.class);

    private static final String PNG = "image/png";
    private static final String JPEG = "image/jpeg";

    private final int maxPixels;

    public ImageTranscoder(PostalIdProperties properties) {
        this.maxPixels = properties.getMedia().getMaxImagePixels();
    }

    @PostConstruct
    void assertTiffSupportPresent() {
        // Disk caching buys nothing for the small images handled here and creates temp files
        // holding personal data.
        ImageIO.setUseCache(false);

        if (!ImageIO.getImageReadersByFormatName("tiff").hasNext()) {
            throw new IllegalStateException(
                    "No TIFF ImageReader registered. The com.twelvemonkeys.imageio:imageio-tiff "
                            + "dependency is required to read enrollment signatures and scans.");
        }
        log.info("TIFF decoding available; signature and document scans can be transcoded.");
    }

    /**
     * @param sourceContentType the type recorded at ingest, used to decide whether any work is needed
     * @return web-renderable bytes plus the content type they are actually in
     */
    public WebImage toWebSafe(byte[] source, String sourceContentType) throws IOException {
        if (JPEG.equals(sourceContentType) || PNG.equals(sourceContentType)) {
            return new WebImage(source, sourceContentType);
        }
        return new WebImage(transcodeToPng(source), PNG);
    }

    private byte[] transcodeToPng(byte[] source) throws IOException {
        BufferedImage decoded = decode(source);
        BufferedImage flattened = flattenOntoWhite(decoded);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(flattened, "png", out)) {
            throw new IOException("No PNG writer available");
        }
        return out.toByteArray();
    }

    private BufferedImage decode(byte[] source) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (input == null) {
                throw new IOException("Unreadable image stream");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("No ImageReader for this image format");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                assertWithinPixelBudget(reader.getWidth(0), reader.getHeight(0));
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Rejects absurd dimensions before allocating a raster for them. A TIFF header can claim a
     * size that would exhaust the heap long before anyone notices the file is malicious.
     */
    private void assertWithinPixelBudget(int width, int height) throws IOException {
        long pixels = (long) width * height;
        if (pixels > maxPixels) {
            throw new IOException("Image exceeds the configured pixel budget: " + width + "x" + height);
        }
    }

    /**
     * Composites onto an opaque white background.
     *
     * <p>Signatures arrive as bilevel TIFFs. Written straight to PNG they can come out as ink on
     * transparency, which renders as invisible against a dark page. Flattening onto white gives
     * the paper the signature was captured on.
     */
    private BufferedImage flattenOntoWhite(BufferedImage source) {
        BufferedImage target = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, target.getWidth(), target.getHeight());
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return target;
    }
}
