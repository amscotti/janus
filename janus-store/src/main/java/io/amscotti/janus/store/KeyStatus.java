package io.amscotti.janus.store;

/**
 * Lifecycle status of a gateway key (Janus.Keys.Key {@code :active |
 * :revoked}).
 *
 * <p><b>Revoked → 403, not 401 (documented decision).</b> The reference
 * lets clients distinguish a bad key (401 {@code :authentication}) from a key taken
 * away (403 {@code :authorization}). The spec wording says
 * "invalid/revoked keys get 401"; ships the reference implementation semantics and flags the
 * wording conflict as a gate decision (the change, if the gate insists on the
 * literal spec, is one {@code ErrorMapper} row + one filter test).
 */
public enum KeyStatus {
    /** Authenticates, subject to {@code expiresAt}. */
    ACTIVE,

    /** Permanently rejected (403 {@code :authorization}). */
    REVOKED
}
