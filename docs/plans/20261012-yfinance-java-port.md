# yfinance Java Port (yfin)

## Overview
- Java 25 port of [yfinance](https://github.com/ranaroussi/yfinance), published to Maven Central as an open-source library
- solves: no maintained, idiomatic Java library offering yfinance's coverage of Yahoo Finance data (history, fundamentals, options, news, search, screener, sectors, live streaming)
- target: **full parity with yfinance in v1**; all modules released together under one version
- **GraalVM native-image compatible**: core and live modules ship reachability metadata so consuming apps build native executables with no extra configuration (adapters: best effort, depending on Tablesaw/DFLib native support)
- idiomatic Java API: explicit client instance, blocking calls (virtual threads for concurrency), immutable typed records, optional DataFrame adapters
- greenfield: repo is an empty Maven skeleton

## Context (from discovery)
- files/components involved: `pom.xml` (single-module skeleton, `io.andreygrigoriev:yfin:1.0-SNAPSHOT`, Java 25), `.gitignore`, empty `.mvn/` (no wrapper yet), empty `src/`
- related patterns found: none, greenfield
- reference implementation: yfinance Python source. Pin the latest yfinance release at project start and record its version/commit in `NOTICE`; consult its source for exact endpoint URLs, query params, module lists and parsing quirks
- dependencies identified:
  - core: Jackson (databind + jsr310), slf4j-api 2.x, JSpecify annotations
  - live: protobuf-java
  - adapters: Tablesaw, DFLib
  - test: JUnit 5, AssertJ, slf4j-simple

## Development Approach
- **testing approach**: Regular (code first, then tests in the same task)
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - tests are not optional - they are a required part of the checklist
  - write unit tests for new functions/methods
  - write unit tests for modified functions/methods
  - add new test cases for new code paths
  - update existing test cases if behavior changes
  - tests cover both success and error scenarios
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change: `rtk ./mvnw verify`
- maintain backward compatibility (within the plan: don't break public API introduced by earlier tasks without updating its tests)
- **CRITICAL: GraalVM native-image compatibility** - consumers may compile apps using this library into native executables. Every task must respect these rules:
  - no runtime classpath scanning, dynamic class loading by name, runtime bytecode generation or JDK dynamic proxies in library code
  - every class Jackson instantiates or reads reflectively (internal DTOs, public records it serializes) must be registered in the module's `reachability-metadata.json` in the same task that adds it (or parsing is done via the `JsonNode` tree model, which needs no metadata)
  - resources loaded at runtime (e.g. bundled key lists) must be registered in metadata
  - prefer explicit wiring over `ServiceLoader`; if used, register providers
  - Task 26 adds native test runs to CI; from then on `-Pnative` must pass for every later change

## Testing Strategy
- **unit tests**: required for every task (see Development Approach above)
  - JUnit 5 + AssertJ
  - `FakeTransport` (test-only, in `yfin-core` test sources, shared via test-jar) replays recorded JSON fixtures matched by endpoint path + relevant query params
  - fixtures live at `src/test/resources/fixtures/<endpoint>/<case>.json`; every endpoint needs at least: a normal case, an empty/missing-fields case, an error-payload case (Yahoo `{"finance":{"error":...}}` / `{"chart":{"error":...}}`)
  - fixtures are recorded with `FixtureRecorder` (yfin-it), never hand-invented except for synthetic edge cases
- **live integration tests**: `yfin-it` module, failsafe plugin, `-Plive` profile; assert shape (non-empty, fields parse, sane ranges), never exact values; nightly GitHub Actions
- **price repair**: port yfinance repair test cases and their CSV data
- **e2e/UI**: not applicable (library)

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

**Modules** (Maven multi-module, parent `io.andreygrigoriev:yfin-parent`):

| Module | Purpose | Extra deps | Published |
|---|---|---|---|
| `yfin-core` | client, Ticker, all endpoints, models | Jackson, slf4j-api, JSpecify | yes |
| `yfin-live` | WebSocket streaming | protobuf-java | yes |
| `yfin-tablesaw` | records → Tablesaw `Table` | tablesaw-core | yes |
| `yfin-dflib` | records → DFLib `DataFrame` | dflib | yes |
| `yfin-it` | live integration suite + FixtureRecorder | — | no |

**Core packages** (JPMS `module io.andreygrigoriev.yfin`):

| Package | Exported | Contents |
|---|---|---|
| `io.andreygrigoriev.yfin` | ✅ | `YFinance` (builder, `AutoCloseable`), `Ticker`, `Tickers`, `Search`, `Lookup`, `Screener`, `Sector`, `Industry`, `Market` |
| `…yfin.model` | ✅ | immutable records |
| `…yfin.request` | ✅ | `HistoryRequest`, `Interval`, `Period`, `Frequency`, `EquityQuery`, `FundQuery` |
| `…yfin.spi` | ✅ | `Transport`, `HttpRequest`/`HttpResponse` value types, `Cache` |
| `…yfin.exception` | ✅ | exception hierarchy |
| `…yfin.internal.http` | ❌ | `JdkTransport`, `YahooSession`, `RetryPolicy`, `RequestLimiter` |
| `…yfin.internal.endpoint` | ❌ | one class per Yahoo endpoint + internal DTOs + mappers |
| `…yfin.internal.cache` | ❌ | `FileCache`, `NoCache` |
| `…yfin.internal.repair` | ❌ | price repair |

**Key design decisions**
- JDK `HttpClient` behind `Transport` SPI. If Yahoo throttles the JDK TLS fingerprint (Task 1 spike), an alternative transport module is added without touching core
- explicit client, blocking only; no global state
- typed records + `raw()` escape hatch for semi-structured data
- internal DTOs absorb Yahoo JSON shape; public records never mirror raw JSON
- strict bulk download: throws on any failure, with partial results attached
- SLF4J for logging; no binding shipped
- native-image friendly: reflection-free where practical (Jackson tree model or explicitly registered DTOs), metadata shipped at `META-INF/native-image/io.andreygrigoriev/<artifactId>/reachability-metadata.json`, verified by native JUnit runs (GraalVM `native-maven-plugin`)

## Technical Details

**Request flow**
`Ticker.history(req)` → `ChartEndpoint` builds URL/params → `YahooSession` adds cookie + crumb → `RequestLimiter` (semaphore) → `Transport.send` → `RetryPolicy` (429/5xx backoff) → Jackson → internal DTO → mapper → optional `PriceRepair` → immutable `PriceHistory`

**Data model conventions**
- prices `double`; missing values in time series = `Double.NaN`; volume `long` (missing = 0, documented)
- scalar snapshot fields: boxed `Double`/`Long`/`String` with JSpecify `@Nullable`
- time: `ZonedDateTime` in exchange zone for bars/events; `LocalDate` for statement periods and calendar dates
- collections: unmodifiable (`List.copyOf`, `Collections.unmodifiableSortedMap`)

```java
record PriceHistory(String symbol, ZoneId exchangeZone, Interval interval, String currency,
                    List<Bar> bars, List<Dividend> dividends, List<Split> splits,
                    List<CapitalGain> capitalGains) {}
record Bar(ZonedDateTime time, double open, double high, double low,
           double close, double adjClose, long volume) {}
record Dividend(ZonedDateTime time, double amount) {}
record Split(ZonedDateTime time, int numerator, int denominator) { double ratio() }
record CapitalGain(ZonedDateTime time, double amount) {}
```

`FinancialStatement`: `periods()` (newest first), `get(LineItem)` / `get(String)` → `SortedMap<LocalDate, Double>`, `lineItems()`. `LineItem` constants use Yahoo key names (e.g. `TotalRevenue`).

`TickerInfo`: ~40 typed accessors + `Map<String, Object> raw()`.

**Session / auth**
1. basic: `GET https://fc.yahoo.com` (sets `A3` cookie) → `GET https://query1.finance.yahoo.com/v1/test/getcrumb`
2. fallback csrf: consent flow via `guce.yahoo.com` (EU), then crumb
3. cookie + crumb persisted in `Cache`; crumb fetch guarded by a lock (single-flight); on 401 or "Invalid Crumb" body → invalidate, refetch once, retry once

**Rate limiting**: `maxConcurrentRequests` semaphore (default 8); retry on 429/5xx with exponential backoff + full jitter (base 1s, `maxRetries` default 2); exhausted → `YFRateLimitException` (429) / `YFTransportException` (5xx)

**Cache**: `spi.Cache` (`get/put/invalidate` by namespace+key, string values). Default `FileCache` at `~/.cache/yfin` (or `$XDG_CACHE_HOME/yfin`), namespaces: `tz`, `cookie`, `isin`. `Cache.none()` disables. Writes are atomic (temp file + move).

**Exceptions** (all unchecked):
```
YFinanceException (RuntimeException)
├── YFRateLimitException
├── YFAuthException
├── YFSymbolNotFoundException
├── YFDataException            (includes truncated raw payload snippet)
├── YFTransportException
└── YFDownloadException        failures(): Map<String, YFinanceException>, partialResults(): Map<String, PriceHistory>
```

**Builder config**: `connectTimeout`, `requestTimeout`, `proxy(ProxySelector)`, `userAgent`, `maxRetries`, `maxConcurrentRequests`, `cache(Cache)` / `cacheDir(Path)`, `transport(Transport)`, `repair(boolean)` default.

**Yahoo endpoints** (verify against pinned yfinance source):
- chart: `/v8/finance/chart/{symbol}`
- quoteSummary: `/v10/finance/quoteSummary/{symbol}?modules=…`
- quote: `/v7/finance/quote`
- fundamentals timeseries: `/ws/fundamentals-timeseries/v1/finance/timeseries/{symbol}`
- options: `/v7/finance/options/{symbol}`
- search: `/v1/finance/search`; lookup: `/v1/finance/lookup`
- screener: `POST /v1/finance/screener`, predefined: `/v1/finance/screener/predefined/saved`
- sectors/industries: `/v1/finance/sectors/{key}`, `/v1/finance/industries/{key}`
- market: market summary + market time endpoints
- news, earnings dates (visualization), ISIN lookup: per the pinned yfinance implementation

**Live streaming** (`yfin-live`): `wss://streamer.finance.yahoo.com/?version=2`; send `{"subscribe":[…]}` / `{"unsubscribe":[…]}`; inbound `{"message":"<base64 PricingData>"}`; JDK `java.net.http.WebSocket`; vendored `pricing.proto`; callbacks on virtual-thread executor; auto-reconnect with backoff + resubscribe; resubscribe heartbeat every 15s.

## What Goes Where
- **Implementation Steps** (`[ ]` checkboxes): code, tests, build config, CI workflows, docs in this repo
- **Post-Completion** (no checkboxes): Sonatype namespace verification, publishing existing GPG key + GitHub secrets, first release, manual smoke tests

## Implementation Steps

### Task 1: Spike: JDK HttpClient vs Yahoo throttling

**Files:**
- Create: `spike/YahooSpike.java` (single-file `java` source program, deleted after the spike)
- Create: `docs/notes/20261012-transport-spike.md`

- [x] write single-file program: JDK `HttpClient` with Chrome-like User-Agent; run cookie (`fc.yahoo.com`) + crumb flow
- [x] issue ~50 chart and quoteSummary requests across 10 symbols at concurrency 1, 4, 8; record status codes, 429 count, latency
- [x] repeat with HTTP/2 forced and HTTP/1.1 forced; note differences
- [x] document results and decision (JDK transport OK / needs alternative transport module) in `docs/notes/20261012-transport-spike.md`; if an alternative is needed add a ➕ task to this plan before Task 3 (result: JDK transport OK with Chrome-like TLS cipher suites; no alternative module needed, ➕ item added to Task 4)
- [x] delete `spike/` (keep the notes)
- [x] no automated tests for the spike (throwaway code); the findings note is the deliverable (skipped - not automatable)

### Task 2: Multi-module Maven build skeleton

**Files:**
- Modify: `pom.xml` (becomes parent `yfin-parent`, packaging `pom`)
- Create: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`
- Create: `yfin-core/pom.xml`, `yfin-core/src/main/java/module-info.java`
- Create: `yfin-live/pom.xml`, `yfin-tablesaw/pom.xml`, `yfin-dflib/pom.xml`, `yfin-it/pom.xml`
- Create: `LICENSE` (Apache 2.0), `NOTICE`
- Modify: `.gitignore`
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/BuildSmokeTest.java`

- [x] generate Maven wrapper (`mvn wrapper:wrapper`, latest Maven 3.9.x)
- [x] parent pom: `dependencyManagement` for Jackson BOM, slf4j, JSpecify, protobuf, tablesaw, dflib, JUnit BOM, AssertJ; `pluginManagement` for compiler (release 25), surefire, failsafe, jar, source, javadoc
- [x] add `maven-enforcer-plugin` (requireJavaVersion 25, requireMavenVersion, dependencyConvergence, banDuplicatePomDependencyVersions)
- [x] add Spotless with palantir-java-format (`spotless:check` bound to `verify`)
- [x] create module poms; `yfin-it` with `maven.deploy.skip`/`skipPublishing`; `-Plive` profile enabling failsafe in `yfin-it`
- [x] `module-info.java` for yfin-core (requires Jackson, slf4j, `static org.jspecify`; exports placeholders for public packages; each exported package holds a package-private `Placeholder` class until its first real type lands, delete it then)
- [x] `LICENSE` Apache 2.0; `NOTICE` crediting yfinance (pinned version/commit) for ported logic, vendored `.proto`, ported test data
- [x] write smoke test asserting module descriptor exports only public packages
- [x] run `rtk ./mvnw verify` - must pass before next task

### Task 3: Exceptions, SPI types and test FakeTransport

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/exception/*.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/spi/{Transport,HttpRequest,HttpResponse,Cache}.java`
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/testing/FakeTransport.java`
- Modify: `yfin-core/pom.xml` (test-jar goal so other modules can reuse FakeTransport)
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/testing/FakeTransportTest.java`

- [x] exception hierarchy per Technical Details; `YFDataException` keeps truncated (≤2KB) payload snippet (`YFDownloadException` deferred to the download task: it needs `PriceHistory`)
- [x] `Transport` interface: `HttpResponse send(HttpRequest)`; `HttpRequest` record (method, URI, headers, optional body); `HttpResponse` record (status, headers, body bytes)
- [x] `Cache` interface: `Optional<String> get(ns, key)`, `put(ns, key, value)`, `invalidate(ns, key)`, static `none()` (`none()` backed by `internal.cache.NoCache`, created here)
- [x] `FakeTransport`: rule-based matching (method + path + query predicate) → fixture file or inline body/status; records received requests; unmatched request fails loudly
- [x] write tests for FakeTransport matching, missing-fixture failure, request recording
- [x] run `rtk ./mvnw verify` - must pass before next task

### Task 4: JdkTransport

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/http/JdkTransport.java`
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/internal/http/JdkTransportTest.java`

- [x] implement over `java.net.http.HttpClient` (virtual-thread executor, timeouts, proxy, redirect policy NORMAL, gzip decoding)
- [x] ➕ configure Chrome-like `SSLParameters` (cipher suite list required; named groups, signature schemes, TLSv1.3/1.2) and default Chrome User-Agent: default JDK TLS is blocked with 429 by Yahoo (see `docs/notes/20261012-transport-spike.md`); test asserts the configured cipher list (⚠️ named groups/signature schemes not set: `HttpClient` drops them when copying `SSLParameters`; `TLS_RSA_*` suites are filtered out as JDK 24+ disables them)
- [x] own a `CookieManager` (in-memory cookie store) exposed to session for persistence
- [x] map `IOException`/`HttpTimeoutException`/interrupts to `YFTransportException` (restore interrupt flag)
- [x] write tests against a local `com.sun.net.httpserver.HttpServer`: GET/POST, headers, gzip body, cookies set/sent
- [x] write tests for timeout and connection-refused error mapping
- [x] run `rtk ./mvnw verify` - must pass before next task

### Task 5: RetryPolicy and RequestLimiter

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/http/{RetryPolicy,RequestLimiter}.java`
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/internal/http/{RetryPolicyTest,RequestLimiterTest}.java`

- [x] `RetryPolicy`: retry on 429/5xx/transport error, exponential backoff + full jitter, injectable sleeper/clock for tests; honor `Retry-After` header if present
- [x] after exhaustion: 429 → `YFRateLimitException`, else `YFTransportException`
- [x] `RequestLimiter`: fair semaphore with `maxConcurrentRequests`; interruptible acquire
- [x] write tests: success after N retries, exhaustion mapping, Retry-After honored, 4xx (non-429) not retried
- [x] write tests: limiter never exceeds permit count under 100 virtual threads
- [x] run `rtk ./mvnw verify` - must pass before next task

### Task 6: Cache implementations

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/cache/{FileCache,NoCache}.java`
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/internal/cache/FileCacheTest.java`

- [x] `FileCache`: one JSON file per namespace under cache dir, in-memory map loaded lazily, atomic write (temp + `ATOMIC_MOVE`), thread-safe
- [x] default dir resolution: `$XDG_CACHE_HOME/yfin` else `~/.cache/yfin`; create on first write; unreadable/corrupt file → log warn + start empty
- [x] `NoCache` singleton behind `Cache.none()` (done in Task 3, needed by `Cache.none()`)
- [x] write tests: put/get/invalidate, persistence across instances (`@TempDir`), corrupt file recovery
- [x] write tests: concurrent puts from many threads don't corrupt file
- [x] run `rtk ./mvnw verify` - must pass before next task

### Task 7: YahooSession (cookie/crumb auth)

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/http/YahooSession.java`
- ➕ Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/http/YahooJson.java` (shared `ObjectMapper` + error-envelope detection, with `YahooJsonTest`)
- Create: `yfin-core/src/test/resources/fixtures/session/*`
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/internal/http/YahooSessionTest.java`

- [x] basic strategy: `fc.yahoo.com` → cookie, `getcrumb` → crumb; fallback to csrf/consent (`guce.yahoo.com`) strategy when basic fails; remember the working strategy
- [x] single-flight crumb acquisition (lock); persist cookie + crumb in cache namespace `cookie`; load on start
- [x] `execute(HttpRequest)`: append `crumb` param, go through limiter + retry; on 401 or "Invalid Crumb" body invalidate + refetch once + retry once, then `YFAuthException`
- [x] JSON helper: parse body with shared `ObjectMapper`, detect Yahoo error envelopes (`finance.error`, `chart.error`, `quoteSummary.error`) → `YFSymbolNotFoundException` for not-found codes, else `YFDataException`
- [x] write tests: happy path, cached crumb reused (no fetch), invalid crumb refresh path, consent fallback, auth failure
- [x] write tests: 50 concurrent calls trigger exactly one crumb fetch
- [x] run `rtk ./mvnw verify` - must pass before next task

### Task 8: YFinance client and builder

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/YFinance.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/Ticker.java` (shell, methods added in later tasks)
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/YFinanceTest.java`
- ➕ Delete: `yfin-core/src/main/java/io/andreygrigoriev/yfin/Placeholder.java` (package now has real types)

- [x] builder with all config options from Technical Details; validate inputs (positive timeouts/concurrency, non-negative retries)
- [x] `create()` shortcut with defaults; `close()` shuts down transport/executor, idempotent; calls after close throw `IllegalStateException`
- [x] `ticker(String)` normalizes symbol (trim, upper-case) and rejects blank
- [x] internal accessor for session/mapper used by endpoints (package-private or via internal package)
- [x] write tests: builder defaults, validation errors, custom transport wiring, close semantics
- [x] run `rtk ./mvnw verify` - must pass before next task

### Task 9: Price history (chart endpoint)

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/request/{HistoryRequest,Interval,Period}.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/{PriceHistory,Bar,Dividend,Split,CapitalGain}.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/chart/{ChartEndpoint,ChartDto,ChartMapper}.java`
- Modify: `yfin-core/src/main/java/io/andreygrigoriev/yfin/Ticker.java`
- Create: `yfin-core/src/test/resources/fixtures/chart/*.json`
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/internal/endpoint/chart/ChartMapperTest.java`
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/TickerHistoryTest.java`
- ➕ Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/{HistoryMetadata,Actions}.java` (typed chart metadata + `raw()`; return type of `Ticker.actions()`)
- ➕ Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/chart/{ChartQuery,package-info}.java` (request resolved to `range` or `period1/period2`)
- ➕ Modify: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/http/YahooJson.java` (`toPlain(JsonNode)` for `raw()` maps, with test)
- ➕ Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/request/HistoryRequestTest.java`
- ➕ Delete: `model/Placeholder.java`, `request/Placeholder.java`
- ⚠️ `PriceHistory` has an extra last component `HistoryMetadata metadata`; `Split` numerator/denominator are `double` (Yahoo sends floats). `ChartDto` is read from the JSON tree (no reflection, no native metadata needed)
- ⚠️ Yahoo's chart endpoint currently returns no `capitalGains` events (probed funds and ETFs), so `schd-1wk-capital-gains.json` is a recorded SCHD response with one synthetic capital-gains event added; `partiallyMissingPricesBecomeNaN` and malformed payloads are inline synthetic JSON. Other chart fixtures were recorded live (curl_cffi, yfinance 1.7.0 parameters)

- [x] `HistoryRequest` builder: period or start/end, interval, prepost, autoAdjust, backAdjust, actions, repair, keepNa; validate intraday range limits like yfinance (e.g. 1m ≤ 8 days per request, ≤ 30 days back)
- [x] `ChartEndpoint`: build params (`period1/period2` or `range`, `interval`, `includePrePost`, `events=div,splits,capitalGains`)
- [x] `ChartMapper`: timestamps → `ZonedDateTime` in `meta.exchangeTimezoneName`; nulls → NaN; drop all-NaN rows unless keepNa; auto-adjust (scale OHLC by adjclose/close) and back-adjust semantics matching yfinance; dividends/splits/capital gains from `events`
- [x] cache symbol → timezone in `tz` namespace (used for start/end date → epoch conversion)
- [x] `Ticker.history(HistoryRequest)`, `dividends()`, `splits()`, `capitalGains()`, `actions()`, `historyMetadata()`
- [x] write mapper tests with fixtures: daily equity, intraday with prepost, ETF with capital gains, crypto (24/7, UTC), symbol with splits+dividends, nulls in arrays
- [x] write tests for error cases: delisted symbol → `YFSymbolNotFoundException`, invalid interval/range combination → `IllegalArgumentException`, malformed payload → `YFDataException`
- [x] run `rtk ./mvnw verify` - must pass before next task

### Task 10: quoteSummary infrastructure + info and fast_info

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/quotesummary/{QuoteSummaryEndpoint,QuoteSummaryModule}.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/quote/QuoteEndpoint.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/{TickerInfo,FastInfo}.java`
- Modify: `yfin-core/src/main/java/io/andreygrigoriev/yfin/Ticker.java`
- Create: fixtures under `fixtures/quoteSummary/`, `fixtures/quote/`
- Create: tests `QuoteSummaryEndpointTest`, `TickerInfoTest`, `FastInfoTest`
- ➕ Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/quotesummary/InfoMapper.java` (yfinance `info` merge/flatten), `internal/endpoint/Uris.java` (shared URI building, `ChartEndpoint` now uses it), package-infos, `QuoteEndpointTest`
- ➕ Create: chart fixtures `aapl-1d-1y.json`, `aapl-1h-5d-prepost.json`, `btc-usd-1d-1y.json` (fast_info sources); all Task 10 fixtures recorded live 2026-10-12 (curl_cffi, yfinance 1.7.0 parameters)
- ⚠️ `TickerInfo` is a record `(symbol, raw)` with typed accessor methods reading `raw`, not a 40-component record. `trailingPegRatio` (yfinance fetches it from fundamentals-timeseries) is not added yet: that endpoint arrives with the financials task
- ⚠️ `FastInfo` is a lazy final class over a public `FastInfo.Source` (year of daily bars, 5d hourly bars, `/v7` quote, clock). `shares` comes from the `/v7` quote `sharesOutstanding` (yfinance uses the fundamentals-timeseries share count); fallbacks for `previousClose`/`regularMarketPreviousClose`/`marketCap` use the quote (yfinance's `_retired_info` fallback is always empty). `FastInfoTest` lives in package `io.andreygrigoriev.yfin` to use the package-private clock hook `Ticker.fastInfo(Supplier<Instant>)`

- [x] `QuoteSummaryEndpoint`: fetch arbitrary module set, return `JsonNode` per module; unwrap Yahoo `{raw, fmt}` value objects generically
- [x] `TickerInfo`: flatten modules the way yfinance `info` does (merge + `/v7/quote` extras); ~40 typed accessors (sector, industry, marketCap, trailingPE, forwardPE, dividendYield, beta, currency, exchange, quoteType, longName, …); `raw()` immutable map
- [x] `FastInfo`: lazily computed from history metadata + quote (lastPrice, previousClose, open, dayHigh/Low, yearHigh/Low, fiftyDayAverage, twoHundredDayAverage, marketCap, shares, currency, timezone, exchange, lastVolume, tenDayAverageVolume, threeMonthAverageVolume, yearChange)
- [x] `Ticker.info()`, `Ticker.fastInfo()`
- [x] write tests: equity, ETF, mutual fund, crypto, index fixtures; missing modules → nulls not exceptions
- [x] write tests: not-found symbol and error envelope mapping
- [x] run `rtk ./mvnw verify` - must pass before next task

### Task 11: Holders

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/{MajorHolders,Holder,InsiderTransaction,InsiderPurchases,InsiderRosterEntry}.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/quotesummary/HoldersMapper.java`
- Modify: `Ticker.java`
- Create: fixtures + `HoldersMapperTest`
- ➕ Fixtures under `fixtures/quoteSummary/holders/` (`aapl`, `sap-de` partial data, `spy` and `btc-usd` 404 "No fundamentals data found"), recorded live 2026-10-12 (curl_cffi, yfinance 1.7.0 module list)
- ⚠️ Yahoo answers ETFs/crypto/indices with 404 `Not Found` "No fundamentals data found for symbol"; `QuoteSummaryEndpoint.fetch` now returns an empty map for that (unknown symbols still say "Quote not found" → `YFSymbolNotFoundException`)
- ⚠️ `majorDirectHolders` is not mapped: yfinance requests it but leaves its parser commented out, and Yahoo sends an empty list. Each accessor requests only its own module (yfinance requests all seven at once); `majorHolders()`/`insiderPurchases()` return `Optional`

- [x] map modules `institutionOwnership`, `fundOwnership`, `majorDirectHolders`, `majorHoldersBreakdown`, `insiderTransactions`, `insiderHolders`, `netSharePurchaseActivity`
- [x] `Ticker.majorHolders()`, `institutionalHolders()`, `mutualFundHolders()`, `insiderTransactions()`, `insiderPurchases()`, `insiderRosterHolders()`
- [x] write tests for each accessor with fixtures (equity with full data)
- [x] write tests for symbol without holder data (empty lists, no exception)
- [x] run `rtk ./mvnw verify` - must pass before next task

### Task 12: Analyst data, recommendations, calendar, sustainability

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/{Recommendation,UpgradeDowngrade,PriceTargets,EarningsEstimate,RevenueEstimate,EarningsHistoryEntry,EpsTrend,EpsRevisions,GrowthEstimate,Calendar,Sustainability}.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/quotesummary/{AnalystMapper,CalendarMapper,SustainabilityMapper}.java`
- Modify: `Ticker.java`
- Create: fixtures + mapper tests
- ➕ Create: `internal/endpoint/quotesummary/ModuleNodes.java` (typed node reads shared with `HoldersMapper`), tests `AnalystMapperTest`, `CalendarMapperTest`, `SustainabilityMapperTest`
- ➕ Fixtures under `fixtures/quoteSummary/analysis/` (`aapl` with `upgradeDowngradeHistory` trimmed to the newest 20 of 971 entries, `sap-de`, `spy`/`btc-usd` 404, `aapl-esg` 404), recorded live 2026-10-12 (curl_cffi, yfinance 1.7.0 parameters)
- ⚠️ Yahoo no longer sends `esgScores`: requested with other modules it is omitted, requested alone it gets 404 "No fundamentals data found" (AAPL, MSFT, XOM, TSLA probed). `msft-esg-synthetic.json` is a synthetic payload in the historical shape
- ⚠️ `recommendationsSummary()` is an alias of `recommendations()`, as in yfinance. Empty `upgradeDowngradeHistory` gives an empty list (yfinance raises). Estimate records also carry the period `endDate`; calendar dates are UTC (yfinance uses local time). `growthEstimates()` fetches its four modules in one request

- [x] recommendations (`recommendationTrend`), recommendations summary, upgrades/downgrades (`upgradeDowngradeHistory`)
- [x] analyst price targets, earnings estimate, revenue estimate, earnings history, EPS trend, EPS revisions, growth estimates (`earningsTrend`, `earningsHistory`, `financialData`, `industryTrend` etc. per yfinance)
- [x] calendar (`calendarEvents`: earnings dates, ex-dividend, dividend date, estimate ranges)
- [x] sustainability (`esgScores`) as typed core fields + raw map
- [x] write tests per accessor with fixtures
- [x] write tests for missing modules (ETF, crypto) → empty/null results
- [x] run `rtk ./mvnw verify` - must pass before next task

### Task 13: Financial statements (timeseries endpoint)

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/{FinancialStatement,LineItem}.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/request/Frequency.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/timeseries/{TimeseriesEndpoint,StatementKeys,TimeseriesMapper}.java`
- Modify: `Ticker.java`
- Create: fixtures under `fixtures/timeseries/` + `TimeseriesMapperTest`, `FinancialStatementTest`

- [ ] port yfinance's key lists for income, balance sheet, cash flow (`const.py` fundamentals keys) into `StatementKeys`
- [ ] `TimeseriesEndpoint`: request `annual*`/`quarterly*`/`trailing*` types, chunked to avoid URL length limits
- [ ] `FinancialStatement` matrix with `periods()`, `get(LineItem|String)`, `lineItems()`; `LineItem` constants for common keys
- [ ] `Ticker.incomeStatement(Frequency)`, `balanceSheet(Frequency)`, `cashFlow(Frequency)` with `ANNUAL`, `QUARTERLY`, `TRAILING` (TTM where yfinance supports it)
- [ ] write tests: annual + quarterly + TTM for an equity fixture; values and period ordering
- [ ] write tests: bank (different line items), symbol with no fundamentals → empty statement
- [ ] run `rtk ./mvnw verify` - must pass before next task

### Task 14: Earnings and earnings dates

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/{EarningsDate}.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/earnings/EarningsDatesEndpoint.java`
- Modify: `Ticker.java`
- Create: fixtures + `EarningsDatesEndpointTest`

- [ ] `Ticker.earningsDates(int limit)`: port yfinance implementation (visualization endpoint), fields: date (ZonedDateTime), EPS estimate, reported EPS, surprise %
- [ ] any remaining yfinance earnings accessors not covered by Task 12/13 (check pinned source; deprecated ones skipped and listed in README parity table)
- [ ] write tests with fixtures: past + future dates, missing estimates
- [ ] write tests for empty result
- [ ] run `rtk ./mvnw verify` - must pass before next task

### Task 15: Options chains

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/{OptionChain,OptionContract}.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/options/{OptionsEndpoint,OptionsMapper}.java`
- Modify: `Ticker.java`
- Create: fixtures + `OptionsMapperTest`

- [ ] `Ticker.optionExpirations()` → `List<LocalDate>`; `Ticker.optionChain(LocalDate)` → calls, puts, underlying quote
- [ ] `OptionContract` fields: contractSymbol, strike, lastPrice, bid, ask, change, percentChange, volume, openInterest, impliedVolatility, inTheMoney, lastTradeDate, contractSize, currency
- [ ] unknown expiration → `IllegalArgumentException` listing valid dates (yfinance behavior)
- [ ] write tests: chain parsing, expirations, missing bid/ask → NaN
- [ ] write tests: invalid expiration error, symbol without options → empty expirations
- [ ] run `rtk ./mvnw verify` - must pass before next task

### Task 16: News, shares, ISIN

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/{NewsItem,SharesCount}.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/{news/NewsEndpoint,shares/SharesEndpoint,isin/IsinEndpoint}.java`
- Modify: `Ticker.java`
- Create: fixtures + tests per endpoint

- [ ] `Ticker.news(int count, NewsTab tab)` per pinned yfinance (news/press releases/all)
- [ ] `Ticker.sharesFull(LocalDate start, LocalDate end)` → `List<SharesCount>` (timeseries `shares_out`)
- [ ] `Ticker.isin()` (cached in `isin` namespace; `null` for non-equities, like yfinance's `-`)
- [ ] write tests per endpoint with fixtures (happy path)
- [ ] write tests: empty news, no shares data, ISIN cache hit skips request
- [ ] run `rtk ./mvnw verify` - must pass before next task

### Task 17: Bulk download and Tickers (strict)

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/Tickers.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/exception/YFDownloadException.java` (if not already in Task 3)
- Modify: `YFinance.java` (`download`, `tickers`)
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/DownloadTest.java`

- [ ] `YFinance.download(Collection<String>, HistoryRequest)` → `Map<String, PriceHistory>` (insertion order = input order, deduplicated)
- [ ] fan out on virtual threads (bounded by `RequestLimiter`); wait for all; any failure → `YFDownloadException` with `failures()`, `partialResults()`, each failure added as suppressed
- [ ] `Tickers`: `yf.tickers(...)` → `Map<String, Ticker>` access + `history(HistoryRequest)` delegating to download
- [ ] write tests: all succeed, one fails (exception contents), all fail, duplicate symbols
- [ ] write tests: concurrency bound respected
- [ ] run `rtk ./mvnw verify` - must pass before next task

### Task 18: Search and Lookup

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/{Search,Lookup}.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/{SearchResult,SearchQuote,LookupResult}.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/search/{SearchEndpoint,LookupEndpoint}.java`
- Create: fixtures + tests

- [ ] `yf.search(query)` with options (maxResults, newsCount, listsCount, fuzzy, includeResearch, …) → quotes, news, lists, research, nav
- [ ] `yf.lookup(query)` with type filter (all, stock, mutualfund, etf, index, future, currency, cryptocurrency) and count
- [ ] write tests per endpoint with fixtures
- [ ] write tests: empty results, blank query → `IllegalArgumentException`
- [ ] run `rtk ./mvnw verify` - must pass before next task

### Task 19: Screener and EquityQuery/FundQuery

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/request/{EquityQuery,FundQuery,ScreenerQuery,SortField}.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/{Screener}.java`, `model/ScreenerResult.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/screener/ScreenerEndpoint.java`
- Create: fixtures + `ScreenerQueryTest`, `ScreenerEndpointTest`

- [ ] query DSL: `and`, `or`, `eq`, `gt`, `lt`, `gte`, `lte`, `btwn`, `is-in`; validate field names and values against yfinance's allowed field/value maps (port `const.py` screener tables)
- [ ] serialize to Yahoo JSON body; `Screener.screen(query, offset, size, sortField, sortAsc)`
- [ ] predefined screeners (`day_gainers`, `most_actives`, …) by name via predefined endpoint
- [ ] write tests: JSON serialization of nested queries, invalid field/operator → `IllegalArgumentException`
- [ ] write tests: custom + predefined screen responses from fixtures
- [ ] run `rtk ./mvnw verify` - must pass before next task

### Task 20: Sector, Industry, Market

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/{Sector,Industry,Market}.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/{SectorOverview,IndustryOverview,MarketStatus,MarketSummaryItem,…}.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/domain/{SectorEndpoint,IndustryEndpoint,MarketEndpoint}.java`
- Create: fixtures + tests

- [ ] `Sector`: overview, topCompanies, topEtfs, topMutualFunds, industries, researchReports
- [ ] `Industry`: overview, topCompanies, topPerformingCompanies, topGrowthCompanies, sectorKey/name
- [ ] `Market` (region, e.g. `US`): status (open/close times, timezone) and summary
- [ ] write tests per class with fixtures
- [ ] write tests: unknown sector/industry key → `YFSymbolNotFoundException`
- [ ] run `rtk ./mvnw verify` - must pass before next task

### Task 21: Price repair

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/repair/{PriceRepair,UnitMixupRepair,ZeroesRepair,SplitRepair,DividendRepair,...}.java`
- Modify: `internal/endpoint/chart/ChartMapper.java` / `Ticker.java` (apply when `repair=true`)
- Create: `yfin-core/src/test/resources/repair/*.csv` (ported from yfinance tests/data)
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/internal/repair/*Test.java`

- [ ] port yfinance repair pipeline in its order: 100× unit mix-ups, zero/missing price fill (incl. reconstruct from finer interval), bad stock splits, bad dividends (and dividend-adjust consistency), capital-gains double counting
- [ ] repaired bars flagged (`Bar.repaired()` boolean or separate `PriceHistory.repairedIndices()`, decide once and document)
- [ ] reconstruction requests finer-interval history via the same session (respects limiter)
- [ ] port yfinance repair tests with their CSV inputs/expected outputs
- [ ] write tests: repair disabled leaves data untouched; already-clean data unchanged
- [ ] run `rtk ./mvnw verify` - must pass before next task

### Task 22: Live streaming (yfin-live)

**Files:**
- Create: `yfin-live/src/main/proto/pricing.proto` (vendored)
- Modify: `yfin-live/pom.xml` (protobuf-maven-plugin)
- Create: `yfin-live/src/main/java/module-info.java`
- Create: `yfin-live/src/main/java/io/andreygrigoriev/yfin/live/{YFinanceLive,LiveStream,PriceTick}.java`
- Create: `yfin-live/src/main/java/io/andreygrigoriev/yfin/live/internal/{PricingDecoder,ReconnectPolicy}.java`
- Create: `yfin-live/src/test/java/io/andreygrigoriev/yfin/live/*Test.java`

- [ ] vendor `pricing.proto` from pinned yfinance; generate Java via protobuf-maven-plugin into an internal (non-exported) package
- [ ] `PricingDecoder`: base64 → protobuf → `PriceTick` record (id, price, time, currency, exchange, quoteType, marketHours, changePercent, dayVolume, dayHigh, dayLow, change, …)
- [ ] `LiveStream` over `java.net.http.WebSocket`: subscribe/unsubscribe, partial-message buffering, callbacks on virtual-thread executor, 15s heartbeat resubscribe, reconnect with backoff + resubscribe, `close()`
- [ ] `YFinanceLive.connect(YFinance)` reuses client's proxy/user-agent config
- [ ] write tests: decoder with recorded base64 samples; subscribe message JSON
- [ ] write tests against a local WebSocket test server (minimal JDK-based or test-scope dependency): reconnect resubscribes, callbacks receive ticks, errors routed to `onError`
- [ ] run `rtk ./mvnw verify` - must pass before next task

### Task 23: Tablesaw adapter

**Files:**
- Create: `yfin-tablesaw/src/main/java/module-info.java`
- Create: `yfin-tablesaw/src/main/java/io/andreygrigoriev/yfin/tablesaw/TablesawAdapter.java`
- Create: `yfin-tablesaw/src/test/java/io/andreygrigoriev/yfin/tablesaw/TablesawAdapterTest.java`

- [ ] `toTable(PriceHistory)`: columns Date, Open, High, Low, Close, Adj Close, Volume (+ Dividends, Stock Splits, Capital Gains when present), yfinance naming
- [ ] `toTable(FinancialStatement)`: rows = line items, columns = periods
- [ ] `toTable` for other list-shaped results where useful (holders, option chain, earnings dates)
- [ ] write tests: column names/types/order, NaN preserved, empty input → empty table
- [ ] run `rtk ./mvnw verify` - must pass before next task

### Task 24: DFLib adapter

**Files:**
- Create: `yfin-dflib/src/main/java/module-info.java`
- Create: `yfin-dflib/src/main/java/io/andreygrigoriev/yfin/dflib/DFLibAdapter.java`
- Create: `yfin-dflib/src/test/java/io/andreygrigoriev/yfin/dflib/DFLibAdapterTest.java`

- [ ] same conversions as Task 23 targeting DFLib `DataFrame`
- [ ] write tests mirroring Task 23 cases
- [ ] run `rtk ./mvnw verify` - must pass before next task

### Task 25: Live integration suite and FixtureRecorder (yfin-it)

**Files:**
- Create: `yfin-it/src/test/java/io/andreygrigoriev/yfin/it/*IT.java`
- Create: `yfin-it/src/main/java/io/andreygrigoriev/yfin/it/FixtureRecorder.java`
- Create: `.github/workflows/nightly-live.yml`

- [ ] `FixtureRecorder`: recording `Transport` decorator that writes responses to `yfin-core/src/test/resources/fixtures/<endpoint>/<case>.json`; CLI entry listing which cases to record; strips cookies/crumb from saved data
- [ ] one IT class per area (history, info, holders, analyst, statements, options, news, search, screener, sector/industry/market, download, live stream ~30s); assert shape only
- [ ] nightly workflow runs `./mvnw verify -Plive`; on failure opens/updates a GitHub issue
- [ ] run `rtk ./mvnw verify -Plive` locally once - must pass before next task

### Task 26: GraalVM native-image verification

**Files:**
- Modify: `pom.xml` (`native` profile with `org.graalvm.buildtools:native-maven-plugin`, `test` goal)
- Create/Modify: `yfin-core/src/main/resources/META-INF/native-image/io.andreygrigoriev/yfin-core/reachability-metadata.json`
- Create/Modify: `yfin-live/src/main/resources/META-INF/native-image/io.andreygrigoriev/yfin-live/reachability-metadata.json`
- Create: `yfin-it/src/test/java/io/andreygrigoriev/yfin/it/NativeSmokeIT.java`
- Create: `yfin-it/native-smoke/` (minimal consumer app: history + info + statements + 10s live stream)

- [ ] add `native` profile running unit tests as native image (`./mvnw -Pnative test` on GraalVM 25)
- [ ] run native tests for `yfin-core` and `yfin-live`; fill gaps in reachability metadata found by failures (use the native-image tracing agent on the JVM test run to discover missing entries, then review and trim the generated file by hand)
- [ ] verify protobuf generated classes work in native image (register descriptors/reflection as needed)
- [ ] verify JDK `HttpClient` TLS + `WebSocket` work in native image (enable `--enable-url-protocols=https`; `--enable-http` not needed for https only — document required flags, ideally none beyond defaults via `native-image.properties`)
- [ ] build `native-smoke` consumer app as native executable with no extra config; run it against live Yahoo in `-Plive -Pnative`
- [ ] adapters: run native tests on a best-effort basis; document status per adapter in README
- [ ] run `rtk ./mvnw verify` and `rtk ./mvnw -Pnative test` - must pass before next task

### Task 27: CI and release pipeline

**Files:**
- Modify: `pom.xml` (release profile: source, javadoc, gpg, central-publishing-maven-plugin; SCM, developers, license metadata)
- Create: `.github/workflows/ci.yml`, `.github/workflows/release.yml`

- [ ] `ci.yml`: on push/PR, Java 25 (Temurin), `./mvnw verify` (unit tests + spotless + enforcer + javadoc lint); second job on GraalVM 25 (`graalvm/setup-graalvm`) running `./mvnw -Pnative test`
- [ ] `release.yml`: on `v*` tag, set version from tag, `./mvnw -Prelease deploy` to Central Portal, publish GitHub release
- [ ] parent pom metadata required by Central (name, description, url, licenses, developers, scm)
- [ ] verify `./mvnw -Prelease package -Dgpg.skip` builds sources + javadoc jars for all published modules
- [ ] run `rtk ./mvnw verify` - must pass before next task

### Task 28: Verify acceptance criteria
- [ ] walk the pinned yfinance public API (`Ticker`, `Tickers`, `download`, `Search`, `Lookup`, `Screener`, `EquityQuery`, `FundQuery`, `Sector`, `Industry`, `Market`, `WebSocket`) and confirm each has a Java counterpart or a documented reason for omission (deprecated upstream)
- [ ] verify edge cases: delisted symbols, crypto/24h markets, non-US exchanges/timezones, empty data, rate-limit path
- [ ] verify only intended packages are exported from each JPMS module
- [ ] run full test suite: `rtk ./mvnw verify`
- [ ] run live suite: `rtk ./mvnw verify -Plive`
- [ ] run native tests: `rtk ./mvnw -Pnative test`; build and run `native-smoke` executable
- [ ] verify every endpoint has normal, empty and error fixtures

### Task 29: [Final] Update documentation
- [ ] `README.md`: install (Maven/Gradle coordinates per module), quickstart, examples per area, yfinance → yfin API mapping/parity table, rate-limit guidance, logging setup, GraalVM native-image notes (supported modules, any required flags), Yahoo ToS disclaimer (not affiliated; personal/research use)
- [ ] `CONTRIBUTING.md`: fixture recording workflow, live suite, formatting
- [ ] create `CLAUDE.md` with build commands and project conventions discovered during implementation
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems - no checkboxes, informational only*

**External setup**
- verify `io.andreygrigoriev` namespace on Sonatype Central Portal (DNS TXT record for andreygrigoriev.io, or switch groupId to `io.github.<user>`)
- GPG signing key already exists: ensure its public key is published to a keyserver (keys.openpgp.org / keyserver.ubuntu.com) and export the private key for CI
- add GitHub secrets: `CENTRAL_USERNAME`, `CENTRAL_TOKEN` (Central Portal user token), `GPG_PRIVATE_KEY` (armored export of the existing key), `GPG_PASSPHRASE`
- create GitHub repository and push

**Manual verification**
- smoke test the published artifacts from a fresh Maven and Gradle project (classpath and module path)
- run a multi-hour live streaming session to check reconnect behavior
- bulk download of ~500 symbols to tune default concurrency/retry against real throttling

**Ongoing**
- track upstream yfinance releases for endpoint changes; nightly live suite is the early-warning signal
