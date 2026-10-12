# Transport spike: JDK HttpClient vs Yahoo throttling

Date: 2026-10-12. Environment: macOS, Corretto 25.0.4, one residential IP.
Reference: yfinance 1.7.0 (tag commit `3d9d2f0cacb662bff689874cd6113bae3a30a885`), which uses
`curl_cffi` with Chrome impersonation.

## Method

Throwaway single-file program (`spike/YahooSpike.java`, deleted after the spike):

- JDK `HttpClient`, Chrome 129 User-Agent, in-memory `CookieManager`, virtual-thread executor
- cookie + crumb flow: `GET https://fc.yahoo.com` (returns 404 but sets the `A3` cookie), then
  `GET https://query1.finance.yahoo.com/v1/test/getcrumb`
- 50 requests (alternating `/v8/finance/chart/{s}?range=1mo&interval=1d` and
  `/v10/finance/quoteSummary/{s}?modules=price,summaryDetail`, with crumb) across 10 symbols
  (AAPL, MSFT, GOOGL, AMZN, TSLA, NVDA, META, JPM, BTC-USD, SPY) at concurrency 1, 4, 8
- each run with HTTP/2 forced and HTTP/1.1 forced
- TLS variants: JDK defaults, and `SSLParameters` tuned towards Chrome's ClientHello

## Results

### Default JDK TLS: blocked

| Client (same IP, same minute) | getcrumb / chart |
|---|---|
| JDK `HttpClient`, default TLS, HTTP/2 | 429 `Too Many Requests` |
| JDK `HttpClient`, default TLS, HTTP/1.1 | 429 |
| curl 8.7.1 (LibreSSL), with or without full browser headers | 429 |
| `curl_cffi` 0.16.3, no impersonation | 429 |
| `curl_cffi` 0.16.3, `impersonate="chrome"` | 200 (20/20 chart requests, crumb not even needed) |

Yahoo returns 429 on the very first request, so this is not volume throttling: it is
TLS-fingerprint based blocking. User-Agent and other headers make no difference. The HTTP
version makes no difference either.

### Tuned JDK TLS: works

Restricting the cipher suites to Chrome's list (15 suites, Chrome order) is enough. Default
JDK 25 offers 31 suites, including `TLS_DHE_*`/`TLS_DHE_DSS_*` and CBC-SHA256/384 suites that no
browser sends.

| Variant | HTTP/2 | HTTP/1.1 |
|---|---|---|
| ciphers + named groups + signature schemes + protocols | 200 | 200 |
| ciphers only | 200 | 200 |
| named groups only (`x25519, secp256r1, secp384r1`) | 429 | 429 |
| signature schemes only | 429 | 429 |

Benchmark with tuned TLS (50 requests per run, all statuses 200, zero 429s):

| Variant | Version | Concurrency | Total | p50 | p95 | max |
|---|---|---|---|---|---|---|
| all tuned | HTTP/2 | 1 | 1713 ms | 12 ms | 183 ms | 255 ms |
| all tuned | HTTP/2 | 4 | 457 ms | 16 ms | 134 ms | 222 ms |
| all tuned | HTTP/2 | 8 | 186 ms | 28 ms | 56 ms | 57 ms |
| all tuned | HTTP/1.1 | 1 | 5548 ms | 29 ms | 389 ms | 982 ms |
| all tuned | HTTP/1.1 | 4 | 1539 ms | 65 ms | 169 ms | 1538 ms |
| all tuned | HTTP/1.1 | 8 | 530 ms | 35 ms | 231 ms | 379 ms |
| ciphers only | HTTP/2 | 1 | 2864 ms | 23 ms | 171 ms | 336 ms |
| ciphers only | HTTP/2 | 4 | 688 ms | 33 ms | 157 ms | 162 ms |
| ciphers only | HTTP/2 | 8 | 303 ms | 32 ms | 142 ms | 256 ms |
| ciphers only | HTTP/1.1 | 1 | 1944 ms | 14 ms | 139 ms | 181 ms |
| ciphers only | HTTP/1.1 | 4 | 543 ms | 12 ms | 200 ms | 304 ms |
| ciphers only | HTTP/1.1 | 8 | 662 ms | 72 ms | 208 ms | 310 ms |

A control run with default TLS directly after the tuned runs was still 429, so the result is
not a throttling window that happened to expire.

## Decision

**JDK transport OK, with Chrome-like TLS parameters.** No alternative transport module is
needed for v1.

- `JdkTransport` (Task 4) must configure `SSLParameters` explicitly: Chrome cipher suite list
  (required), plus named groups `x25519, secp256r1, secp384r1`, Chrome signature schemes and
  `TLSv1.3, TLSv1.2` (not required today, cheap to keep the ClientHello closer to Chrome).
- Prefer HTTP/2 (default `HttpClient` version): lower latency and one multiplexed connection;
  concurrency 8 caused no 429s, so `maxConcurrentRequests` default 8 stays.
- Send a current Chrome User-Agent by default.
- Cipher list used:
  `TLS_AES_128_GCM_SHA256, TLS_AES_256_GCM_SHA384, TLS_CHACHA20_POLY1305_SHA256,
  TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
  TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384, TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
  TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256, TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
  TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA, TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
  TLS_RSA_WITH_AES_128_GCM_SHA256, TLS_RSA_WITH_AES_256_GCM_SHA384,
  TLS_RSA_WITH_AES_128_CBC_SHA, TLS_RSA_WITH_AES_256_CBC_SHA`

## Risks

- This is fingerprint matching, and Yahoo can tighten it (e.g. JA4 extension sets, GREASE,
  the `X25519MLKEM768` group that current Chrome sends and JDK 25 does not). The `Transport` SPI
  keeps the door open for an alternative module (e.g. libcurl-impersonate over FFM) without
  touching core. The nightly live suite (Task 25) is the early-warning signal.
- Single IP and a single run; results from other networks or regions may differ.
- `SSLParameters` tuning is plain JSSE configuration and needs no native-image metadata.
