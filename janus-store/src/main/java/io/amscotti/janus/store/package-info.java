/**
 * Governance store: hashed virtual keys {@code sk-janus-<prefix>-<secret>},
 * per-key model scopes, rate limits, pricing, spend, and call records.
 *
 * <p>{@link io.amscotti.janus.store.CallStore} is the flat seam ({@code
 * extends} {@link io.amscotti.janus.store.KeyStore}, {@link
 * io.amscotti.janus.store.RateLimiter}, {@link
 * io.amscotti.janus.store.SpendLedger} plus the {@link
 * io.amscotti.janus.store.CallRecord} ledger). {@link
 * io.amscotti.janus.store.InMemoryCallStore} is the zero-dependency default;
 * {@link io.amscotti.janus.store.PostgresCallStore} is the JDBC implementation.
 * Both satisfy {@code AbstractCallStoreContractTest}.
 *
 * <p>Also here: {@link io.amscotti.janus.store.PriceTable} / {@link
 * io.amscotti.janus.store.CostCalculator} (USD-per-1K → integer micro-USD).
 * Pure core + JDK — no Micronaut, no Jackson, no {@code provider} / {@code
 * router} imports. JDBC / HikariCP stay in this module.
 */
package io.amscotti.janus.store;
