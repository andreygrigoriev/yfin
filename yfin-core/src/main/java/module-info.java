/** Java port of yfinance: Yahoo Finance market data client. */
module io.andreygrigoriev.yfin {
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires org.slf4j;
    requires static org.jspecify;

    exports io.andreygrigoriev.yfin;
    exports io.andreygrigoriev.yfin.model;
    exports io.andreygrigoriev.yfin.request;
    exports io.andreygrigoriev.yfin.spi;
    exports io.andreygrigoriev.yfin.exception;
}
