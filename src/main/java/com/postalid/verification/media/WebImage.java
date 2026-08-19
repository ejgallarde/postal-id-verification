package com.postalid.verification.media;

/** Image bytes in a format a browser will actually render, with the matching content type. */
public record WebImage(byte[] data, String contentType) {
}
