package io.amscotti.janus.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * {@code GET /key/list} response.
 */
public record KeyListResponse(@JsonProperty("keys") List<KeyListItem> keys) {}
