/** Real-time Yahoo Finance price streaming over WebSocket for yfin. */
module io.andreygrigoriev.yfin.live {
    requires transitive io.andreygrigoriev.yfin;
    requires java.net.http;
    requires com.google.protobuf;
    requires com.fasterxml.jackson.databind;
    requires org.slf4j;
    requires static org.jspecify;

    exports io.andreygrigoriev.yfin.live;

    // generated protobuf messages are read reflectively by protobuf-java's descriptor-based APIs
    opens io.andreygrigoriev.yfin.live.internal.proto to
            com.google.protobuf;
}
