# yfin

Java 25 port of [yfinance](https://github.com/ranaroussi/yfinance): a Yahoo Finance market data client.

## GraalVM native image

Applications using yfin can be compiled into native executables. You don't need extra
`native-image` flags, `--enable-url-protocols`, or your own reachability metadata. Each module
ships its metadata at `META-INF/native-image/io.andreygrigoriev/<artifactId>/reachability-metadata.json`.
Every module's unit tests also run as a native image (`./mvnw -Pnative test` on GraalVM 25). That
profile turns off the GraalVM reachability metadata repository, so the tests rely only on the
metadata yfin ships.

| Module | Native status |
|---|---|
| `yfin-core` | supported. JSON goes through the Jackson tree model, so no reflection metadata is needed. The JDK `HttpClient` over HTTPS works with no flags. |
| `yfin-live` | supported. Protobuf `PricingData` decoding and the JDK `WebSocket` (`wss://`) work. The metadata registers protobuf-java's reflective `ExtensionRegistry` lookup. |
| `yfin-tablesaw` | works (best effort). Tablesaw's `Table` runs a ClassGraph classpath scan in its static initializer; the shipped metadata lets that scan run. In a native image the scan finds no Tablesaw readers or writers, which the adapter does not use. Tablesaw's own CSV and other I/O is not covered. |
| `yfin-dflib` | works (best effort). The adapter's tests pass in a native image with no metadata. |

`yfin-it` builds a small consumer app (history, info, income statement, a 10 s live stream) as a
native executable from the plugin defaults alone. `./mvnw verify -Plive,native` builds it and runs
it against Yahoo.
