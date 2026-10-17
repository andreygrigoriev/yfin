/** Real-time Yahoo Finance price streaming over WebSocket for yfin. */
module io.github.andreygrigoriev.yfin.live {
    requires transitive io.github.andreygrigoriev.yfin;
    requires java.net.http;
    requires com.google.protobuf;
    requires com.fasterxml.jackson.databind;
    requires org.slf4j;
    requires static org.jspecify;

    exports io.github.andreygrigoriev.yfin.live;

    // generated protobuf messages are read reflectively by protobuf-java's descriptor-based APIs
    opens io.github.andreygrigoriev.yfin.live.internal.proto to
            com.google.protobuf;
}
