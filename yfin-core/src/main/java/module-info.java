/** Java port of yfinance: Yahoo Finance market data client. */
module io.github.andreygrigoriev.yfin {
    requires java.net.http;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires org.slf4j;
    requires static org.jspecify;

    exports io.github.andreygrigoriev.yfin;
    exports io.github.andreygrigoriev.yfin.model;
    exports io.github.andreygrigoriev.yfin.request;
    exports io.github.andreygrigoriev.yfin.spi;
    exports io.github.andreygrigoriev.yfin.exception;
}
