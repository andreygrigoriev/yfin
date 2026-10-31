package io.github.andreygrigoriev.yfin.internal.endpoint.quote;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.andreygrigoriev.yfin.internal.http.YahooJson;
import io.github.andreygrigoriev.yfin.model.Quote;
import java.time.Instant;

/** Maps one {@code /v7/finance/quote} entry to the public {@link Quote}. */
public final class QuoteMapper {

    private QuoteMapper() {}

    public static Quote map(String symbol, JsonNode q) {
        Long time = YahooJson.integral(q.path("regularMarketTime"));
        return new Quote(
                symbol,
                YahooJson.text(q.path("shortName")),
                YahooJson.text(q.path("longName")),
                YahooJson.text(q.path("quoteType")),
                YahooJson.text(q.path("currency")),
                YahooJson.text(q.path("marketState")),
                YahooJson.number(q.path("regularMarketPrice")),
                YahooJson.number(q.path("regularMarketChange")),
                YahooJson.number(q.path("regularMarketChangePercent")),
                YahooJson.number(q.path("regularMarketPreviousClose")),
                YahooJson.integral(q.path("regularMarketVolume")),
                YahooJson.integral(q.path("averageDailyVolume3Month")),
                YahooJson.number(q.path("marketCap")),
                YahooJson.number(q.path("fiftyDayAverage")),
                YahooJson.number(q.path("twoHundredDayAverage")),
                YahooJson.number(q.path("fiftyTwoWeekHigh")),
                YahooJson.number(q.path("fiftyTwoWeekLow")),
                YahooJson.number(q.path("regularMarketDayHigh")),
                YahooJson.number(q.path("regularMarketDayLow")),
                time == null ? null : Instant.ofEpochSecond(time),
                YahooJson.plainMap(q));
    }
}
