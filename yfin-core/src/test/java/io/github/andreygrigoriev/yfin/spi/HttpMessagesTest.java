package io.github.andreygrigoriev.yfin.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpMessagesTest {

    private static final URI URI_ = URI.create("https://query1.finance.yahoo.com/v7/finance/quote?symbols=AAPL");

    @Test
    void getRequest() {
        HttpRequest request = HttpRequest.get(URI_);

        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri()).isEqualTo(URI_);
        assertThat(request.headers()).isEmpty();
        assertThat(request.body()).isNull();
        assertThat(request.bodyBytes()).isEmpty();
        assertThat(request.bodyAsString()).isEmpty();
    }

    @Test
    void postJsonRequest() {
        HttpRequest request = HttpRequest.postJson(URI_, "{\"a\":1}");

        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.header("content-type")).hasValue("application/json");
        assertThat(request.bodyAsString()).hasValue("{\"a\":1}");
        assertThat(request.toString()).contains("POST").contains("7 bytes");
    }

    @Test
    void methodIsUpperCasedAndValidated() {
        assertThat(new HttpRequest("get", URI_, Map.of(), null).method()).isEqualTo("GET");
        assertThatThrownBy(() -> new HttpRequest(" ", URI_, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpRequest("GET", URI.create("/relative"), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestIsImmutable() {
        var headers = new HashMap<String, List<String>>();
        var values = new ArrayList<>(List.of("a"));
        headers.put("X-Test", values);
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        HttpRequest request = new HttpRequest("POST", URI_, headers, body);

        headers.put("Other", List.of("b"));
        values.add("c");
        body[0] = 'z';
        request.body()[1] = 'z';

        assertThat(request.headers()).containsOnlyKeys("X-Test");
        assertThat(request.headers().get("x-test")).containsExactly("a");
        assertThat(request.bodyAsString()).hasValue("abc");
        assertThatThrownBy(() -> request.headers().put("y", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void withHeaderAppendsCaseInsensitively() {
        HttpRequest request = HttpRequest.get(URI_).withHeader("Accept", "a").withHeader("accept", "b");

        assertThat(request.headers().get("ACCEPT")).containsExactly("a", "b");
        assertThat(request.header("Accept")).hasValue("a");
    }

    @Test
    void headersDifferingOnlyInCaseAreMerged() {
        HttpRequest request =
                new HttpRequest("GET", URI_, Map.of("Cookie", List.of("a=1"), "cookie", List.of("b=2")), null);

        assertThat(request.headers()).hasSize(1);
        assertThat(request.headers().get("COOKIE")).containsExactlyInAnyOrder("a=1", "b=2");
    }

    @Test
    void requestEqualityUsesBodyContent() {
        HttpRequest a = HttpRequest.postJson(URI_, "{}");
        HttpRequest b = HttpRequest.postJson(URI_, "{}");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(HttpRequest.postJson(URI_, "[]"));
        assertThat(HttpRequest.get(URI_)).isEqualTo(HttpRequest.get(URI_));
    }

    @Test
    void response() {
        HttpResponse response = new HttpResponse(
                200, Map.of("Set-Cookie", List.of("A3=1", "B=2")), "ok".getBytes(StandardCharsets.UTF_8));

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.bodyAsString()).isEqualTo("ok");
        assertThat(response.header("set-cookie")).hasValue("A3=1");
        assertThat(response.headerValues("SET-COOKIE")).containsExactly("A3=1", "B=2");
        assertThat(response.headerValues("missing")).isEmpty();
        assertThat(response.header("missing")).isEmpty();
        assertThat(response)
                .isEqualTo(new HttpResponse(
                        200, Map.of("set-cookie", List.of("A3=1", "B=2")), "ok".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void responseStatusAndImmutability() {
        assertThat(HttpResponse.of(301, "").isSuccess()).isFalse();
        assertThat(HttpResponse.of(429, "x").isSuccess()).isFalse();
        assertThatThrownBy(() -> HttpResponse.of(42, "")).isInstanceOf(IllegalArgumentException.class);

        byte[] body = {1, 2};
        HttpResponse response = new HttpResponse(200, Map.of(), body);
        body[0] = 9;
        response.body()[1] = 9;
        assertThat(response.body()).containsExactly(1, 2);
    }

    @Test
    void noCacheStoresNothing() {
        Cache cache = Cache.none();

        cache.put("tz", "AAPL", "America/New_York");

        assertThat(cache.get("tz", "AAPL")).isEmpty();
        cache.invalidate("tz", "AAPL");
        assertThat(Cache.none()).isSameAs(cache);
    }
}
