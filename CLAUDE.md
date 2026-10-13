# yfin

Java 25 port of yfinance 1.7.0 (reference commit in `NOTICE`). Multi-module Maven build, parent
`io.andreygrigoriev:yfin-parent`.

## Build commands

Always use the wrapper (`./mvnw`), never a bare `mvn`.

- `./mvnw verify`: unit tests, Spotless check (palantir-java-format), javadoc lint and the JPMS
  export smoke test. Must pass before any commit.
- `./mvnw spotless:apply`: fix formatting. Run it whenever `verify` fails on Spotless.
- `./mvnw -Plive verify`: adds the live ITs in `yfin-it` that hit Yahoo. They assert shape, never
  exact values.
- `./mvnw -Pnative test`: unit tests as a native image. It needs GraalVM 25. Locally:
  `JAVA_HOME=~/.sdkman/candidates/java/25-graal ./mvnw -Pnative test`. It must pass after changes
  to parsing, resources or dependencies.
- `./mvnw -Plive,native verify`: also builds and runs the `native-smoke` executable.
- `./mvnw -Prelease package -Dgpg.skip`: local release-build check.
- Fixture recording: `./mvnw -pl yfin-it -am install -DskipTests`, then
  `./mvnw -pl yfin-it exec:java -Dexec.args="<endpoint>/<case>"` (no args lists the cases).

## Layout

- `yfin-core`: module `io.andreygrigoriev.yfin`. It exports only `yfin`, `yfin.model`,
  `yfin.request`, `yfin.spi` and `yfin.exception`. `BuildSmokeTest` asserts this. Everything in
  `yfin.internal.*` (`http`, `endpoint.<name>`, `cache`, `repair`) is private.
- `yfin-live`: WebSocket streaming. It vendors `pricing.proto`, compiled by the ascopes protobuf
  plugin. It opens `internal.proto` only to protobuf.
- `yfin-tablesaw`, `yfin-dflib`: static adapters (`TablesawAdapter`, `DFLibAdapter`). These are
  automatic-module dependencies.
- `yfin-it`: not published and has no `module-info`. Holds the live ITs, `FixtureRecorder` and the
  `NativeSmoke` app.
- The test-jar of `yfin-core` holds `io.andreygrigoriev.yfin.testing.FakeTransport`, which other
  modules use.

## Conventions

- `YFinance` is the explicit client: thread-safe, `AutoCloseable`, no global state. `Ticker` is a
  stateless handle: each accessor makes its own request(s) and caches nothing. `Sector`, `Industry`
  and `Market` memoize one fetch per instance.
- Calls are blocking. Concurrency uses virtual threads, and every request goes through the
  client-wide `RequestLimiter`. `RetryPolicy` wraps the limiter, so a permit is held per attempt
  and not during backoff.
- Public models are immutable records with unmodifiable collections. Time-series values use `NaN`
  when missing, volume uses `0`, and scalar snapshot fields are `@Nullable` boxed types. Bars and
  events use `ZonedDateTime` in the exchange zone, statements and calendar dates use `LocalDate`.
- No data from Yahoo gives an empty list or `Optional.empty()`, not an exception. Use exceptions
  from the `YFinanceException` hierarchy only for real errors: unknown symbol, auth, transport,
  rate limit, malformed payload.
- Parse JSON with the Jackson 2.x tree model (`JsonNode`), via `YahooJson`, not databind on DTOs.
  This keeps native-image metadata empty. Keep lookup tables as Java constants, not runtime
  resources (`StatementKeys`, `ScreenerFields`).
- GraalVM: no classpath scanning, `Class.forName`, dynamic proxies or bytecode generation in
  library code. Anything reflective, or any resource, goes into
  `META-INF/native-image/io.andreygrigoriev/<artifactId>/reachability-metadata.json` in the same
  change.
- Every package that contains classes has a `package-info.java` with `@NullMarked` (JSpecify).
- Javadoc lint is `all,-missing`, and its warnings fail the build. Watch for broken `{@link}`
  references and bad HTML.

## Tests

- Unit tests never use the network. Use `FakeTransport` with fixtures from
  `yfin-core/src/test/resources/fixtures/<endpoint>/<case>.json`.
- Typical setup: `fake.onGet("").respond(404, "not found")` (cookie bootstrap),
  `fake.onGet("/v1/test/getcrumb").respond(200, "crumb1")`, then
  `YFinance.builder().transport(fake).cache(...).maxRetries(0).build()`.
- Each endpoint needs normal, empty and error fixtures. Fixtures are recorded from live Yahoo, not
  written by hand. Name synthetic ones `*-synthetic.json` and explain them in the test.
- `FixtureRecorder` saves bodies only and redacts crumbs (`REDACTED_CRUMB`). New cases go in
  `FixtureRecorder.CASES`.
- When porting yfinance behavior, read the pinned yfinance 1.7.0 source for exact URLs, params and
  parsing quirks. Record any intentional differences in the README's parity table.

## Housekeeping

- Never commit `.idea/` (it is git-ignored).
- The pom `url`/`scm` values are placeholders (`https://github.com/andreygrigoriev/yfin`) until
  the GitHub repository exists.
