package io.github.andreygrigoriev.yfin.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ExceptionsTest {

    @Test
    void hierarchyIsUnchecked() {
        var cause = new IOException("boom");
        assertThat(new YFRateLimitException("429")).isInstanceOf(YFinanceException.class);
        assertThat(new YFAuthException("crumb", cause))
                .isInstanceOf(YFinanceException.class)
                .hasCause(cause);
        assertThat(new YFTransportException("net", cause))
                .isInstanceOf(YFinanceException.class)
                .hasCause(cause);
        assertThat(new YFDataException("bad")).isInstanceOf(YFinanceException.class);
        assertThat(new YFSymbolNotFoundException("NOPE")).isInstanceOf(YFinanceException.class);
        assertThat(new YFinanceException("x")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void symbolNotFoundKeepsSymbol() {
        var e = new YFSymbolNotFoundException("NOPE");
        assertThat(e.symbol()).isEqualTo("NOPE");
        assertThat(e).hasMessage("symbol not found: NOPE");
        assertThat(new YFSymbolNotFoundException("X", "delisted").getMessage()).isEqualTo("delisted");
    }

    @Test
    void dataExceptionKeepsShortPayload() {
        var e = new YFDataException("unexpected shape", "{\"a\":1}");
        assertThat(e.payloadSnippet()).isEqualTo("{\"a\":1}");
        assertThat(e).hasMessage("unexpected shape [payload: {\"a\":1}]");
        assertThat(new YFDataException("no payload").payloadSnippet()).isNull();
        assertThat(new YFDataException("no payload")).hasMessage("no payload");
    }

    @Test
    void dataExceptionTruncatesPayloadTo2k() {
        String payload = "x".repeat(10_000);
        var e = new YFDataException("too big", payload, new IllegalStateException());

        assertThat(e.payloadSnippet())
                .hasSize(YFDataException.MAX_SNIPPET_LENGTH)
                .isEqualTo("x".repeat(2048));
        assertThat(e).hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void truncationDoesNotSplitSurrogatePairs() {
        String payload = "a".repeat(2047) + "😀" + "tail";
        assertThat(YFDataException.truncate(payload)).isEqualTo("a".repeat(2047));
    }

    @Test
    void dataExceptionFromBytes() {
        byte[] big = "é".repeat(5_000).getBytes(StandardCharsets.UTF_8);
        var e = new YFDataException("bytes", big, null);
        assertThat(e.payloadSnippet()).isEqualTo("é".repeat(2048));
        assertThat(new YFDataException("bytes", (byte[]) null, null).payloadSnippet())
                .isNull();
        assertThat(new YFDataException("bytes", "ok".getBytes(StandardCharsets.UTF_8), null).payloadSnippet())
                .isEqualTo("ok");
    }
}
