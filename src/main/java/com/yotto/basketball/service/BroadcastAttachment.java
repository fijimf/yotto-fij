package com.yotto.basketball.service;

/**
 * An email attachment held in memory. Uploaded bytes are read out of the request
 * eagerly (the request is gone by the time the async send loop runs) and reused for
 * every recipient of a broadcast.
 */
public record BroadcastAttachment(String filename, String contentType, byte[] data) {
}
