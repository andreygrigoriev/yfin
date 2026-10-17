# Contributing to yfin

## Requirements

- JDK 25. Use Oracle GraalVM 25 if you want to run the native tests.
- Run Maven through the wrapper with `./mvnw`. You don't need a local Maven install or `protoc`:
  the protobuf plugin downloads `protoc` from Maven Central.

## Build and test

| Command | What it runs |
|---|---|
| `./mvnw verify` | unit tests for every module, the Spotless format check, javadoc lint and the JPMS export smoke test. CI (`ci.yml`) runs this on every push, plus a GraalVM job running `-Pnative test`. |
| `./mvnw -Plive verify` | the above plus the live integration suite (`yfin-it`, failsafe), run against Yahoo Finance |
| `./mvnw -Pnative test` | every module's unit tests compiled and run as a GraalVM native image |
| `./mvnw -Plive,native verify` | also builds the `native-smoke` consumer executable and runs it against Yahoo |
| `./mvnw spotless:apply` | reformats the sources |
| `./mvnw -Prelease package -Dgpg.skip` | checks the release build locally (sources and javadoc jars), without signing |

Unit tests never touch the network. All HTTP goes through the `Transport` SPI, and the tests plug in
`FakeTransport`.

## Formatting and style

- Spotless runs [palantir-java-format](https://github.com/palantir/palantir-java-format) and
  removes unused imports. `verify` fails on unformatted code, so run `./mvnw spotless:apply`
  before you commit.
- Javadoc lint (`doclint all,-missing`) runs on `verify`, and warnings fail the build. It checks
  syntax, HTML, references and accessibility. It does not flag missing tags, but you should still
  document public types and methods in exported packages.
- Every package has a `package-info.java` with `@NullMarked` (JSpecify). Mark nullable values
  with `@Nullable`.
- Public models are immutable records with unmodifiable collections. Missing time-series numbers
  are `Double.NaN`. Missing scalar snapshot fields are `null`.
- When Yahoo has no data, return an empty list or `Optional.empty()`. Throw only for real errors,
  using the `YFinanceException` hierarchy.
- Internal DTOs and mappers live in `…yfin.internal.*`, which is not exported. Public records never
  mirror Yahoo's raw JSON. Expose semi-structured parts through `raw()` instead.

## GraalVM native-image rules

Consumers compile yfin into native executables with no extra configuration, so library code must:

- not use classpath scanning, loading classes by name, runtime bytecode generation or JDK
  dynamic proxies;
- parse JSON with the Jackson tree model (`JsonNode`). If a class must be bound reflectively,
  register it in the module's
  `src/main/resources/META-INF/native-image/io.github.andreygrigoriev/<artifactId>/reachability-metadata.json`;
- register any resource it loads at runtime in the same metadata file.

`./mvnw -Pnative test` turns off the GraalVM reachability metadata repository, so it proves that
the metadata yfin ships is enough on its own. Run it before you open a PR that touches parsing,
resources or dependencies.

## Fixtures

Each endpoint test replays recorded Yahoo responses from
`yfin-core/src/test/resources/fixtures/<endpoint>/<case>.json`. Every endpoint needs at least:

- a **normal** case;
- an **empty or missing-fields** case, such as an ETF without statements or a symbol with no
  options;
- an **error** case: Yahoo's `{"finance":{"error":...}}` or `{"chart":{"error":...}}` payload, or
  the HTTP error body.

Record fixtures from live Yahoo responses. Don't write them by hand. Synthetic fixtures are only
for edge cases Yahoo no longer produces. Put `synthetic` in the file name and explain the case in
the test.

### Recording with `FixtureRecorder`

`FixtureRecorder` (in `yfin-it`) is a recording `Transport` decorator. It saves only response
bodies, never headers, so cookies never reach a fixture. It replaces every crumb with
`REDACTED_CRUMB`. It skips session bootstrap traffic and the transient 429/5xx responses that get
retried.

```sh
./mvnw -pl yfin-it -am install -DskipTests                       # once, after changing core
./mvnw -pl yfin-it exec:java                                     # list the built-in cases
./mvnw -pl yfin-it exec:java -Dexec.args="chart/aapl-1d-1mo"     # record one case
./mvnw -pl yfin-it exec:java -Dexec.args="all"                   # re-record everything
./mvnw -pl yfin-it exec:java -Dexec.args="--out /tmp/fx chart/aapl-1d-1mo"
```

To add a fixture for a new endpoint or case, add a `FixtureCase` to `FixtureRecorder.CASES`. Name
the endpoint directory it writes to. Record it, read the diff (look for anything account-specific),
and commit the fixture together with the test that uses it.

### Using fixtures in tests

`FakeTransport` comes from the `yfin-core` test-jar (`io.github.andreygrigoriev.yfin.testing`). Other
modules can use it by depending on the test-jar.

```java
FakeTransport fake = FakeTransport.create();
fake.onGet("").respond(404, "not found");                 // fc.yahoo.com cookie bootstrap
fake.onGet("/v1/test/getcrumb").respond(200, "crumb1");
fake.onGet("/v8/finance/chart/AAPL").query("interval", "1d").respondFixture("chart/aapl-1d-1mo.json");

YFinance yf = YFinance.builder().transport(fake).cache(Cache.none()).maxRetries(0).build();
```

A request that matches no rule fails with an `AssertionError` that lists the registered rules.
`fake.requests()` returns the requests sent, so tests can check them.

## Live integration suite

`yfin-it` holds `*IT` tests that call Yahoo for real. They run only with `-Plive`:

```sh
./mvnw -Plive verify
./mvnw -Plive verify -pl yfin-it -am -Dit.test=HistoryIT   # a single IT
```

- Check the shape of the data: non-empty, fields parse, values in sane ranges. Never assert exact
  values, because the data changes daily.
- The clients use `Cache.none()` (see `LiveClients`), so every run goes through the full
  cookie/crumb bootstrap.
- A GitHub Actions workflow (`nightly-live.yml`) runs the suite every night, on the JVM and, as
  `NativeSmokeIT` with `-Plive,native -Dnative.smoke.required=true`, as a native image (a missing
  `native-smoke` executable fails the test there instead of skipping it). A failure there usually
  means Yahoo changed an endpoint. Re-record the affected fixtures and update the parser.

Keep the live suite small. Yahoo rate-limits by IP, and a long suite can get your address blocked
for a while.

## Releasing

Maven Central publishing runs locally, so the signing key and the Central token never leave the
maintainer's machine. Pushing the tag afterwards makes `.github/workflows/release.yml` create the
GitHub release. That workflow needs no secrets.

One-time setup:

- A Central Portal user token in `~/.m2/settings.xml`, under a server with id `central`:
  `<server><id>central</id><username>…</username><password>…</password></server>`.
- A local GPG key, with its public key uploaded to `keyserver.ubuntu.com` and `keys.openpgp.org`.
  If you have several secret keys, select one with `-Dgpg.keyname=<KEYID>`.

Cutting release `X.Y.Z` (Central releases are permanent; a suffix such as `1.2.0-rc1` makes a
GitHub pre-release):

```sh
make release-dry-run          # builds and lists the Central bundle, no signing or upload
make release VERSION=X.Y.Z    # publishes X.Y.Z to Central, then pushes the vX.Y.Z tag
```

`make release` first checks the version format, a clean working tree, `master` in sync with
`origin/master`, that the tag doesn't exist yet, and the `central` server in `settings.xml`. It
then sets the version with `versions:set`, runs `./mvnw -Prelease clean deploy` and restores the
poms (the release version is never committed, and the poms are restored when the deploy fails
too). Only after `deploy` reports the release as published does it tag and push, and
`release.yml` then creates the GitHub release with the jars.

gpg-agent asks for the key passphrase, or you can set `MAVEN_GPG_PASSPHRASE` in the shell.

## Pull requests

- One focused change per PR, with tests: normal, empty and error paths.
- `./mvnw verify` must pass. If you touch parsing, metadata or dependencies, `./mvnw -Pnative test`
  must pass too.
- When you port behavior from yfinance, check it against the reference version in `NOTICE`. Point
  to the upstream source in the PR description.
- Don't commit IDE files (`.idea/`).
