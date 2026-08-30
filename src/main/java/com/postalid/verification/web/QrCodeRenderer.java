package com.postalid.verification.web;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * Renders the QR shown on the comparison view.
 *
 * <p>The code encodes <strong>the verification, not the cardholder</strong>: a request id and the
 * time the check ran, which together locate the exact {@code verification_audit} row. Scanning it
 * gets a supervisor to the record of who checked what and when.
 *
 * <p>It deliberately carries no personal data. A QR holding a name or reference number would put
 * those details into every screenshot in a machine-readable form, which is the opposite of what the
 * rest of this system does.
 *
 * <p>Output is inline SVG rather than a PNG endpoint or a {@code data:} URI. That needs no extra
 * route, stays crisp at any zoom or print size, and — being markup rather than an image request —
 * sits outside the content security policy's image rules entirely.
 */
@Component
public class QrCodeRenderer {

    private static final Logger log = LoggerFactory.getLogger(QrCodeRenderer.class);

    /** Marks the payload as ours, so a scanner operator knows what they are looking at. */
    private static final String PAYLOAD_PREFIX = "PIDV:";

    /** Modules of white space around the code. Below 2 many scanners struggle to lock on. */
    private static final int QUIET_ZONE_MODULES = 2;

    /** Nominal module count; the writer enlarges this if the payload needs a bigger symbol. */
    private static final int REQUESTED_SIZE = 45;

    /**
     * @return an inline {@code <svg>} element, or an empty string if encoding fails — a missing QR
     *         should never take down a verification result that is otherwise correct
     */
    public String toSvg(String requestId, String verifiedAt) {
        String payload = PAYLOAD_PREFIX + requestId + " " + verifiedAt;
        try {
            return render(encode(payload));
        } catch (WriterException | IllegalArgumentException e) {
            log.warn("Could not encode verification QR for request {}: {}", requestId, e.getMessage());
            return "";
        }
    }

    private BitMatrix encode(String payload) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        hints.put(EncodeHintType.MARGIN, QUIET_ZONE_MODULES);

        return new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE,
                REQUESTED_SIZE, REQUESTED_SIZE, hints);
    }

    /**
     * Walks the matrix and emits one path, merging each horizontal run of dark modules into a
     * single sub-path. A 45-module symbol drawn as individual rectangles would be roughly two
     * thousand elements; merged runs bring that down by about an order of magnitude.
     */
    private String render(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();

        StringBuilder path = new StringBuilder();
        for (int y = 0; y < height; y++) {
            int runStart = -1;
            for (int x = 0; x <= width; x++) {
                boolean dark = x < width && matrix.get(x, y);
                if (dark && runStart < 0) {
                    runStart = x;
                } else if (!dark && runStart >= 0) {
                    path.append('M').append(runStart).append(' ').append(y)
                        .append('h').append(x - runStart).append("v1h-").append(x - runStart).append('z');
                    runStart = -1;
                }
            }
        }

        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + width + " " + height + "\""
                + " class=\"qr\" role=\"img\" aria-label=\"Verification reference code\""
                + " shape-rendering=\"crispEdges\">"
                + "<rect width=\"" + width + "\" height=\"" + height + "\" fill=\"#ffffff\"/>"
                + "<path d=\"" + path + "\" fill=\"#000000\"/>"
                + "</svg>";
    }
}
