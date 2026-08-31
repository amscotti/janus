package io.amscotti.janus.core.model;

/** One streaming choice: a {@link Delta} and an optional {@code finishReason}. */
public record ChunkChoice(int index, Delta delta, String finishReason) {}
