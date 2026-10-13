package io.andreygrigoriev.yfin.live;

import io.andreygrigoriev.yfin.exception.YFDataException;
import io.andreygrigoriev.yfin.exception.YFTransportException;

/**
 * Receives the events of a {@link LiveStream}. Callbacks of one stream run one at a time, in
 * arrival order, on a virtual thread owned by the stream; they never run concurrently with each
 * other. A slow callback delays later ones but not the network reads.
 */
@FunctionalInterface
public interface LiveListener {

    /** A pricing update arrived. An exception thrown here is passed to {@link #onError}. */
    void onTick(PriceTick tick);

    /**
     * Something went wrong without closing the stream: a frame that cannot be decoded ({@link
     * YFDataException}), a dropped connection or failed reconnect attempt ({@link
     * YFTransportException}; the stream keeps reconnecting), or an exception thrown by {@link
     * #onTick}. Default: ignore (the stream logs these at debug/warn level).
     */
    default void onError(Throwable error) {}
}
