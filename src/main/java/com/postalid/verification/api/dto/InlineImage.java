package com.postalid.verification.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One cardholder image embedded in a VALID response, for clients that asked for
 * {@code include=images} rather than fetching each image separately.
 *
 * <p>{@code data} is serialised as standard base64, which is Jackson's default for a byte array.
 * That costs a third more bytes than the binary fetch, which is the price of the saved round trip.
 */
@Schema(name = "InlineImage", description = "An image embedded in the response as base64")
public record InlineImage(

        @Schema(description = "Media type of the decoded bytes. The photograph is image/jpeg; the "
                + "signature is converted from TIFF to image/png.", example = "image/jpeg")
        String contentType,

        @Schema(description = "The image bytes, standard base64 with padding",
                type = "string", format = "byte", example = "/9j/4AAQSkZJRgABAQAAAQABAAD...")
        byte[] data,

        @Schema(description = "Length of the decoded bytes", example = "18342")
        int byteLength) {

    public static InlineImage of(String contentType, byte[] data) {
        return new InlineImage(contentType, data, data.length);
    }
}
