# Market Calendars

## Overview
- Port yfinance 1.7.0 `Calendars` (`yfinance/calendars.py`, commit `3d9d2f0`): the market-wide
  earnings, IPO, economic-events and splits calendars. They are the last unported yfinance feature
  listed in the README parity table ("not ported in v1").
- Users get typed, immutable results (`List<EarningsEvent>`, `List<IpoEvent>`,
  `List<EconomicEvent>`, `List<SplitEvent>`) instead of four differently shaped DataFrames, plus
  Tablesaw/DFLib conversions for DataFrame-style use.
- Integrates as four `YFinance` methods taking immutable request values (`CalendarRequest`,
  `EarningsCalendarRequest`), the same shape as `Lookup`/`Search`/`Screener`. Stateless: every call
  makes its own request(s), nothing is cached.

## Context (from discovery)
- yfinance source: `Calendars._get_data` sends `POST {query1}/v1/finance/visualization?lang=en-US&region=US`
  with body `{sortType: "DESC", entityIdType, sortField, includeFields, size: min(limit,100), offset, query}`.
  `query` is an operator tree (`{"operator": "AND"|"OR"|"EQ"|"GTE"|"LTE"|"GTELT", "operands": [...]}`,
  operators upper-cased by `CalendarQuery`).
- Per type (`PREDEFINED_CALENDARS`):
  - `sp_earnings`: sort `intradaymarketcap`; fields `ticker, companyshortname, intradaymarketcap,
    eventname, startdatetime, startdatetimetype, epsestimate, epsactual, epssurprisepct`. The query is
    `AND[eq region us, OR[eq eventtype EAD, eq eventtype ERA], gte startdatetime start, lte startdatetime end]`,
    plus `gte intradaymarketcap marketCap` when a market cap is set, plus `OR[eq ticker X...]` built from
    `screen("MOST_ACTIVES", count=200)` (filtered by `marketCap >= cutoff`) when `filter_most_active`
    is set and `offset == 0`. The result is sorted client-side by start date, descending.
  - `ipo_info`: sort `startdatetime`; fields `ticker, companyshortname, exchange_short_name,
    filingdate, startdatetime, amendeddate, pricefrom, priceto, offerprice, currencyname, shares,
    dealtype`. The query is `OR[gtelt startdatetime s e, gtelt filingdate s e, gtelt amendeddate s e]`.
  - `economic_event`: sort `startdatetime`; fields `econ_release, country_code, startdatetime,
    period, after_release_actual, consensus_estimate, prior_release_actual,
    originally_reported_actual`. The query is `AND[gte startdatetime s, lte startdatetime e]`.
  - `splits`: sort `startdatetime`; fields `ticker, companyshortname, startdatetime, optionable,
    old_share_worth, share_worth`. Same date query as economic events.
  - In the `nan_cols` columns (EPS/surprise, IPO prices/shares, economic values), yfinance turns
    `0.0` into NaN.
  - Defaults: `start` is today, `end` is `start + 7 days`, `limit=12`, `offset=0`, and `size` is
    capped at 100.
- Files and components involved:
  - `yfin-core/.../internal/endpoint/earnings/EarningsDatesEndpoint.java`: already posts
    `sp_earnings` for one ticker. It maps columns by `id` and has the `text`/`number` cell readers.
  - `yfin-core/.../YFinance.java`: entry points (`lookup`, `screen`, `market`).
  - `yfin-core/.../internal/endpoint/screener/ScreenerEndpoint.java` and `Screener.predefined("most_actives").size(200)`
    are used for the most-active filter (`Screener.MAX_SIZE` = 250).
  - `yfin-core/.../request/HistoryRequest.java`: a precedent for request values in `yfin.request`.
  - `yfin-it/.../FixtureRecorder.java`: `endpointOf` maps `/v1/finance/visualization` to `"earnings"`.
    This has to change, because calendars use the same path.
  - `yfin-tablesaw/.../TablesawAdapter.java`, `yfin-dflib/.../DFLibAdapter.java`: list-to-table
    conversions (`earningsDatesToTable` etc.).
  - `README.md`: the parity table row for `Calendars`.
- Related patterns: Jackson tree model through `YahooJson` (it already maps `finance.error`
  envelopes to `YFDataException`); `@NullMarked` `package-info.java` per package; records with
  unmodifiable collections; NaN for missing doubles; `@Nullable` boxed scalars; `FakeTransport` with
  recorded fixtures (normal, empty and error per endpoint).
- Name clashes to avoid: `model.Calendar` (per-ticker calendar) and `model.Split` (history event)
  already exist.
- Dependencies: no new libraries. No reflection or resources, so the native-image metadata stays
  unchanged.

## Development Approach
- **testing approach**: Regular (code first, then tests)
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
- run tests after each change (`./mvnw verify`; `spotless:apply` on format failures)
- maintain backward compatibility (`Ticker.earningsDates()` behavior must not change)

## Testing Strategy
- **unit tests**: required for every task. No network: `FakeTransport` plus fixtures under
  `yfin-core/src/test/resources/fixtures/calendar/`. Tests assert the request body (decoded JSON
  tree, compared structurally) and the parsed records.
- **fixtures**: recorded from live Yahoo via `FixtureRecorder` (normal + empty + error per
  calendar). Any hand-written case is named `*-synthetic.json` and explained in its test.
- **live ITs**: `yfin-it` `CalendarsIT` under `-Plive`, asserting shape only.
- **native**: `JAVA_HOME=~/.sdkman/candidates/java/25-graal ./mvnw -Pnative test` after the
  parsing tasks (the CLAUDE.md rule for parsing changes).
- no UI, so no e2e tests.

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview
- **Shared visualization layer** (`internal.endpoint.visualization`): `VisualizationTable` wraps
  the first document of a response. It provides a column-id → index map, row iteration and typed
  cell readers (`text`, `number`, `zeroAsNaN`, `instant`, `localDate`, `bool`). `VisualizationQuery`
  is a tiny internal operator-tree builder producing Jackson `ObjectNode`s. `VisualizationEndpoint`
  posts a body and returns the parsed table. `EarningsDatesEndpoint` is refactored onto it, and its
  existing tests guard the refactor.
- **Calendar endpoint** (`internal.endpoint.calendar.CalendarEndpoint`): one method per calendar.
  Each builds the yfinance body (same `entityIdType`, `sortField`, `includeFields`, query tree and
  upper-case operators) and maps rows to records by column id, never by label or position.
- **Most-active filter**: when `filterMostActive` is set and `offset == 0`, run the existing
  screener (`most_actives`, size 200) through the same client, keep symbols with
  `marketCap >= cutoff` (all of them when no cutoff is set), and append `OR[eq ticker ...]`. If no
  symbol survives, return an empty list without calling visualization. yfinance would send an
  empty `OR`; we answer "no data" instead. Screener errors propagate (yfinance logs them and
  continues).
- **Public API** (Option A, chosen):
  ```java
  List<EarningsEvent> e = yf.earningsCalendar();                       // today..+7d, limit 12, most-active on
  List<EarningsEvent> e2 = yf.earningsCalendar(EarningsCalendarRequest
          .between(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 23))
          .marketCap(1e9).filterMostActive(false).limit(50).offset(0));
  List<IpoEvent> ipos = yf.ipoCalendar(CalendarRequest.from(LocalDate.now()).limit(100));
  List<EconomicEvent> econ = yf.economicEventsCalendar();
  List<SplitEvent> splits = yf.splitsCalendar(CalendarRequest.between(a, b));
  ```
- **Key decisions**:
  - Stateless (no `force`, no per-instance result cache, no cached most-active list). This matches
    the `Ticker` convention and goes in the parity table.
  - The window is always complete: `between(start, end)` (rejects `end < start`), `from(start)`
    (end = start + 7), `nextWeek()` (today..today+7, system default zone like yfinance's
    `datetime.now()`). This replaces yfinance's "only start or only end" warning case, which could
    produce an inverted window.
  - `limit` is validated in `1..100` (yfinance silently caps it). `offset >= 0`.
  - `marketCap < 10_000_000` logs a WARN through SLF4J, as yfinance's `UserWarning` does.
  - The zero → NaN quirk is kept for the yfinance `nan_cols`.
  - Earnings results are sorted by start time descending (stable), like yfinance's `sort_values`.
    The other calendars keep Yahoo's order.

## Technical Details
- **Request values** (`yfin.request`, immutable, copying setters, `equals`/`hashCode`/`toString`
  like `Lookup`):
  - `CalendarRequest(LocalDate start, LocalDate end, int limit, int offset)`: `DEFAULT_LIMIT = 12`,
    `MAX_LIMIT = 100`, `DEFAULT_DAYS = 7`.
  - `EarningsCalendarRequest`: the same window and paging, plus `@Nullable Double marketCap` and
    `boolean filterMostActive` (default `true`). It is a separate class, not a subclass, to keep
    both final and simple.
  - Dates go on the wire as `yyyy-MM-dd` (yfinance `DATE_STR_FORMAT`).
- **Models** (`yfin.model`, records):
  - `EarningsEvent(String symbol, @Nullable String companyName, double marketCap, @Nullable String eventName,
    ZonedDateTime startTime, @Nullable String timing, double epsEstimate, double reportedEps, double surprisePercent)`.
    `startTime` is in `America/New_York` (the query is `region = us`). `timing` is Yahoo's
    `startdatetimetype` code (BMO/AMC/TAS/TNS), kept raw. `isReported()` works as in `EarningsDate`.
  - `IpoEvent(String symbol, @Nullable String companyName, @Nullable String exchange, @Nullable LocalDate filingDate,
    @Nullable LocalDate date, @Nullable LocalDate amendedDate, double priceFrom, double priceTo,
    double offerPrice, @Nullable String currency, double shares, @Nullable String dealType)`.
  - `EconomicEvent(String event, @Nullable String region, Instant time, @Nullable String period,
    double actual, double expected, double last, double revised)`. It uses `Instant` because these
    events aren't tied to an exchange; this is called out in the README.
  - `SplitEvent(String symbol, @Nullable String companyName, LocalDate payableDate, @Nullable Boolean optionable,
    double oldShareWorth, double newShareWorth)` with `ratio()` = `newShareWorth / oldShareWorth`
    (NaN when unknown), consistent with `Split.ratio`.
  - Rows without the key column (symbol / event name / start time) are skipped. A malformed date
    raises `YFDataException`.
  - Wire types confirmed from the Task 1 fixtures (`fixtures/calendar/`). Column ids equal the
    `includeFields` names; each column also has a `type` (`STRING`, `NUMBER`, `DATE`, `BOOLEAN`):
    - every `DATE` column (`startdatetime`, `filingdate`, `amendeddate`) is an ISO-8601 instant
      string such as `"2026-10-15T12:30:00.000Z"`. IPO and split dates are New York midnight
      (`04:00Z`/`05:00Z`), so `LocalDate`s are taken in `America/New_York`. Economic event times
      are `00:00Z` instants.
    - earnings: `intradaymarketcap`, `epsestimate`, `epsactual`, `epssurprisepct` are `NUMBER`.
      `eventname` can be `null`. A not-yet-reported row has `epsactual = null` and
      `epssurprisepct = 0.0` (the placeholder yfinance turns into NaN).
    - IPO: `pricefrom`, `priceto`, `offerprice`, `shares` are `NUMBER` and often `null`;
      `currencyname` is often `""` (map to `null`); `exchange_short_name`, `companyshortname` can
      be `null`; `dealtype` is e.g. `"Expected"`.
    - economic: `after_release_actual`, `consensus_estimate`, `prior_release_actual`,
      `originally_reported_actual` are `STRING` numbers (`"1.8"`, `"-1111.9"`) or `null`.
      yfinance does `astype("float64")` and then `0 → NaN`, so the readers parse numeric strings
      (unparseable → NaN) before the zero rule. `country_code` is a two-letter code (`EU` too),
      `period` is e.g. `"Sep"`, `"Q3"` or `null`.
    - splits: `optionable` is `BOOLEAN`; `old_share_worth` and `share_worth` are integer
      `NUMBER`s, and `share_worth` can be `0` (seen in old data), so `ratio()` gives `0` there.
  - The model field types above are unchanged by these findings. `VisualizationTable.number` and
    `zeroAsNaN` (Task 2) must accept numeric strings as well as numbers.
  - Error fixture: an unknown `entityIdType` makes Yahoo answer HTTP 500 (retried as transient,
    not recordable), so `calendar/error.json` uses an unknown query operator, which gives a 400
    `finance.error` envelope (`Bad Request`, "Unsupported operator").
  - The most-active screener response is recorded as `calendar/most-actives.json` (its own case).
    Its quotes are not the same snapshot the `earnings-week*` cases filtered with.
- **Flow**: `YFinance.xCalendar(req)`, then `ensureOpen()`, then `CalendarEndpoint` (builds the body
  with `VisualizationQuery`), then `VisualizationEndpoint.post` (`HttpRequest.postJson`, `YahooJson.read`
  for envelope errors), then `VisualizationTable`, then the records.
- **Errors**: a `finance.error` envelope or a missing `result` gives `YFDataException`. An empty
  `rows` array or no documents gives `List.of()`. Transport, auth and rate-limit errors come from
  the existing session/`RetryPolicy`.

## What Goes Where
- **Implementation Steps** (`[ ]` checkboxes): code, tests, fixtures, README/CLAUDE.md in this repo.
- **Post-Completion** (no checkboxes): nightly live run observation, releasing.

## Implementation Steps

### Task 1: Record calendar fixtures

**Files:**
- Modify: `yfin-it/src/main/java/io/andreygrigoriev/yfin/it/FixtureRecorder.java`
- ➕ Create: `yfin-it/src/main/java/io/andreygrigoriev/yfin/it/RawCalendars.java` (temporary)
- Modify: `yfin-it/src/test/java/io/andreygrigoriev/yfin/it/FixtureRecorderTest.java`
- Create: `yfin-core/src/test/resources/fixtures/calendar/*.json`

- [x] rename the `endpointOf` key for `/v1/finance/visualization` from `"earnings"` to `"visualization"`; change the existing AAPL earnings case to `Map.of("visualization", "earnings")` so its output path stays the same
- [x] since the API doesn't exist yet, add temporary recorder cases that post the yfinance bodies directly through the client's transport (or land this task together with Task 4 and record afterwards; pick whichever keeps the build green), covering: `earnings-week`, `earnings-week-mcap`, `ipo-week`, `economic-week`, `splits-week`, an empty window far in the past for each type (`*-empty`), and an error case (e.g. unknown `entityIdType` → `finance.error`)
- [x] record the screener response of the earnings case into `calendar/` as well (`Map.of("visualization", "calendar", "screener", "calendar")` gives `-1`/`-2` suffixes; or give the screener its own name)
- [x] inspect the fixtures and update the Technical Details models (⚠️ item) with the real column ids and types
- [x] update `FixtureRecorderTest` for the renamed endpoint key and the new cases
- [x] run `./mvnw verify` - must pass before task 2

### Task 2: Extract the shared visualization layer

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/visualization/VisualizationEndpoint.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/visualization/VisualizationTable.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/visualization/VisualizationQuery.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/visualization/package-info.java`
- Modify: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/earnings/EarningsDatesEndpoint.java`
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/internal/endpoint/visualization/VisualizationTableTest.java`
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/internal/endpoint/visualization/VisualizationQueryTest.java`

- [x] `VisualizationQuery`: `and(...)`, `or(...)`, `eq(field, value)`, `gte`, `lte`, `gtelt(field, from, to)` building `ObjectNode`s, with the operator case as a parameter. Earnings dates keep lower case (current wire format, unchanged); calendars use upper case like yfinance
- [x] `VisualizationEndpoint.post(ObjectNode body, @Nullable String symbol)`: adds `lang=en-US&region=US`, does a `postJson`, then `YahooJson.read` (the symbol keeps the `Not Found` mapping; calendars pass `null`) and returns the `VisualizationTable`
- [x] `VisualizationTable.of(JsonNode root)`: result/document checks (`YFDataException` on a missing result list or non-array rows, empty on no rows), `columnIndex(id)`, `rows()`, cell readers `text`, `number`, `zeroAsNaN`, `instant`, `localDate`, `bool`
- [x] refactor `EarningsDatesEndpoint` onto the three helpers with no behavior change (`body()` output byte-identical)
- [x] write `VisualizationQueryTest` (nesting, operator case) and `VisualizationTableTest` (column lookup by id, duplicate ids keep the first, missing/null/empty-string cells, zero-as-NaN, bad date gives `YFDataException`)
- [x] existing `EarningsDatesEndpointTest` passes unchanged
- [x] run `./mvnw verify` - must pass before task 3

### Task 3: Add request values and models

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/request/CalendarRequest.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/request/EarningsCalendarRequest.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/EarningsEvent.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/IpoEvent.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/EconomicEvent.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/model/SplitEvent.java`
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/request/CalendarRequestTest.java`
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/model/CalendarModelsTest.java`

- [x] `CalendarRequest`: `between`, `from`, `nextWeek`, `limit`, `offset`, accessors, `equals`/`hashCode`/`toString`; javadoc examples and the yfinance mapping
- [x] `EarningsCalendarRequest`: the same, plus `marketCap(double)` (positive), `filterMostActive(boolean)`
- [x] the four model records with compact-constructor null checks; `EarningsEvent.isReported()`, `SplitEvent.ratio()`
- [x] write tests: defaults (limit 12, offset 0, +7 days), validation (`end < start`, limit 0/101, negative offset, non-positive market cap give IAE), copy semantics, equality
- [x] write model tests: `ratio()` with NaN/zero old worth, `isReported()`
- [x] run `./mvnw verify` - must pass before task 4

### Task 4: Implement IPO, economic-events and splits calendars

**Files:**
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/calendar/CalendarEndpoint.java`
- Create: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/calendar/package-info.java`
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/internal/endpoint/calendar/CalendarEndpointTest.java`

- [x] constants per type: `entityIdType`, `sortField`, `includeFields` copied from yfinance `PREDEFINED_CALENDARS`
- [x] `ipos(CalendarRequest)`: `OR[GTELT startdatetime, GTELT filingdate, GTELT amendeddate]`; maps rows to `IpoEvent` (zero → NaN for prices/shares)
- [x] `economicEvents(CalendarRequest)` and `splits(CalendarRequest)`: `AND[GTE, LTE startdatetime]`; map to `EconomicEvent` (zero → NaN for the four values) and `SplitEvent`
- [x] write body tests for each type: compare the parsed JSON tree with the expected yfinance body (`sortType`, `entityIdType`, `sortField`, `includeFields`, `size`, `offset`, `query`), and check the URL params `lang`/`region`
- [x] write parse tests per type against the normal fixtures (row count, first row fully asserted, NaN placeholders), the empty fixtures (`List.of()`), and the error fixture (`YFDataException`)
- [x] write edge tests: a row missing its key column is skipped; a synthetic fixture with reordered columns still maps by id (`*-synthetic.json`, explained in the test)
- [x] run `./mvnw verify` - must pass before task 5

### Task 5: Implement the earnings calendar with the most-active filter

**Files:**
- Modify: `yfin-core/src/main/java/io/andreygrigoriev/yfin/internal/endpoint/calendar/CalendarEndpoint.java`
- Modify: `yfin-core/src/test/java/io/andreygrigoriev/yfin/internal/endpoint/calendar/CalendarEndpointTest.java`

- [ ] `earnings(EarningsCalendarRequest)`: the base `AND[EQ region us, OR[EQ eventtype EAD, EQ eventtype ERA], GTE, LTE]`; append `GTE intradaymarketcap` when a cap is set; WARN log below 10M
- [ ] the most-active operand: when `filterMostActive && offset == 0`, call the screener (`most_actives`, size 200) through the injected `ScreenerEndpoint` (or a `Supplier<ScreenerResult>` for testability), filter by cap, append `OR[EQ ticker ...]`; an empty survivor list returns `List.of()` without a visualization call
- [ ] map to `EarningsEvent` in `America/New_York`, with zero → NaN for EPS/surprise; sort by `startTime` descending
- [ ] write tests: body without filters, with a cap, with most-active (screener fixture → expected OR operands, cap filtering), most-active skipped when `offset > 0`, `filterMostActive(false)` makes no screener request
- [ ] write tests: empty survivor list makes exactly one (screener) request and returns empty; a screener error propagates; parse plus descending sort from the fixture
- [ ] run `./mvnw verify` - must pass before task 6

### Task 6: Expose the calendars on YFinance

**Files:**
- Modify: `yfin-core/src/main/java/io/andreygrigoriev/yfin/YFinance.java`
- Create: `yfin-core/src/test/java/io/andreygrigoriev/yfin/CalendarsTest.java`

- [ ] add `earningsCalendar()`/`earningsCalendar(EarningsCalendarRequest)`, `ipoCalendar()`/`ipoCalendar(CalendarRequest)`, `economicEventsCalendar()`/`(CalendarRequest)`, `splitsCalendar()`/`(CalendarRequest)`, with `ensureOpen()`, null checks and javadoc (yfinance name, defaults, `@throws`)
- [ ] confirm `BuildSmokeTest` needs no change (the new types live in already-exported packages)
- [ ] write `CalendarsTest` end-to-end through `FakeTransport` (cookie bootstrap + crumb setup from CLAUDE.md): each calendar returns parsed records; the no-arg overloads send a 7-day window starting today
- [ ] write tests: a closed client throws `IllegalStateException`; a null request throws NPE
- [ ] run `./mvnw verify` - must pass before task 7

### Task 7: Add Tablesaw and DFLib conversions

**Files:**
- Modify: `yfin-tablesaw/src/main/java/io/andreygrigoriev/yfin/tablesaw/TablesawAdapter.java`
- Modify: `yfin-tablesaw/src/test/java/io/andreygrigoriev/yfin/tablesaw/TablesawAdapterTest.java`
- Modify: `yfin-dflib/src/main/java/io/andreygrigoriev/yfin/dflib/DFLibAdapter.java`
- Modify: `yfin-dflib/src/test/java/io/andreygrigoriev/yfin/dflib/DFLibAdapterTest.java`

- [ ] add `earningsCalendarToTable`, `ipoCalendarToTable`, `economicEventsToTable`, `splitsCalendarToTable` (column names follow yfinance's renamed DataFrame columns, e.g. `Symbol`, `Company`, `Marketcap`, `Event Start Date`, `Timing`, `EPS Estimate`, `Reported EPS`, `Surprise(%)`), matching the style of `earningsDatesToTable`
- [ ] add the DFLib equivalents in `DFLibAdapter` with the same column names
- [ ] write tests for both adapters: column names and types, values, NaN and null handling, empty list gives an empty table with columns
- [ ] run `./mvnw verify` - must pass before task 8

### Task 8: Live ITs and final recorder cases

**Files:**
- Create: `yfin-it/src/test/java/io/andreygrigoriev/yfin/it/CalendarsIT.java`
- Modify: `yfin-it/src/main/java/io/andreygrigoriev/yfin/it/FixtureRecorder.java`
- Modify: `yfin-it/src/test/java/io/andreygrigoriev/yfin/it/FixtureRecorderTest.java`

- [ ] replace the temporary Task 1 recorder cases with cases calling the public API (`yf.earningsCalendar(...)` etc.), keeping the same fixture names
- [ ] ➕ delete the temporary `yfin-it/.../RawCalendars.java` (Task 1 raw-body poster) and its two `FixtureRecorderTest` tests; the error case can stay raw (no public API sends an unknown operator) or be dropped
- [ ] `CalendarsIT` (`-Plive`): each calendar over a 14-day window returns records with non-null key fields; earnings sorted descending; `limit(5)` gives at most 5 rows. Assert shape only; IPO/splits may legitimately be empty
- [ ] update `FixtureRecorderTest` for the final cases
- [ ] run `./mvnw verify` and `./mvnw -Plive verify` - must pass before task 9

### Task 9: Verify acceptance criteria
- [ ] verify all four calendars, default windows, limit/offset, the market-cap and most-active filters are implemented
- [ ] verify edge cases: empty windows, error envelope, missing cells, zero placeholders, no most-active survivors
- [ ] run full test suite: `./mvnw verify`
- [ ] run native tests: `JAVA_HOME=~/.sdkman/candidates/java/25-graal ./mvnw -Pnative test`
- [ ] run live ITs: `./mvnw -Plive verify`
- [ ] verify coverage of `CalendarEndpoint`/`VisualizationTable` is comparable to the other endpoints

### Task 10: [Final] Update documentation
- [ ] README: a usage section for calendars (example from Solution Overview); move `Calendars` from "Not ported" to the mapping table (`Calendars.get_*_calendar` → `yf.*Calendar(...)`); add "different on purpose" rows: stateless (no `force`/cache), the window is always complete, `limit` validated not capped, empty result when no most-active symbol survives, screener errors propagate, `EconomicEvent.time` is an `Instant`, earnings times are in `America/New_York`
- [ ] CLAUDE.md: mention the shared `internal.endpoint.visualization` layer under Layout
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems - no checkboxes, informational only*

**Manual verification**:
- watch the next `nightly-live.yml` runs for `CalendarsIT` flakiness. Calendar contents vary by
  week, and IPO/splits weeks can be empty.
- spot-check one week's earnings calendar against finance.yahoo.com/calendar/earnings.

**External system updates**:
- none; release as a minor version (new public API, no breaking change).
