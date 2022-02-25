//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.api;

import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

/**
 * <p>A {@link Stream} represents a bidirectional exchange of data on top of a {@link Session}.</p>
 * <p>Differently from socket streams, where the input and output streams are permanently associated
 * with the socket (and hence with the connection that the socket represents), there can be multiple
 * HTTP/2 streams present concurrently for an HTTP/2 session.</p>
 * <p>A {@link Stream} maps to an HTTP request/response cycle, and after the request/response cycle is
 * completed, the stream is closed and removed from the session.</p>
 * <p>Like {@link Session}, {@link Stream} is the active part and by calling its API applications
 * can generate events on the stream; conversely, {@link Stream.Listener} is the passive part, and
 * its callbacks are invoked when events happen on the stream.</p>
 *
 * @see Stream.Listener
 */
public interface Stream
{
    /**
     * @return the stream unique id
     */
    public int getId();

    /**
     * @return the session this stream is associated to
     */
    public Session getSession();

    /**
     * <p>Sends the given HEADERS {@code frame} representing an HTTP response.</p>
     *
     * @param frame the HEADERS frame to send
     * @return the CompletableFuture that gets notified when the frame has been sent
     */
    public default CompletableFuture<Stream> headers(HeadersFrame frame)
    {
        Promise.Completable<Stream> result = new Promise.Completable<>();
        headers(frame, Callback.from(() -> result.succeeded(this), result::failed));
        return result;
    }

    /**
     * <p>Sends the given HEADERS {@code frame}.</p>
     * <p>Typically used to send an HTTP response or to send the HTTP response trailers.</p>
     *
     * @param frame the HEADERS frame to send
     * @param callback the callback that gets notified when the frame has been sent
     */
    public void headers(HeadersFrame frame, Callback callback);

    /**
     * <p>Sends the given PUSH_PROMISE {@code frame}.</p>
     *
     * @param frame the PUSH_PROMISE frame to send
     * @param listener the listener that gets notified of stream events
     * @return the CompletableFuture that gets notified of the pushed stream creation
     */
    public default CompletableFuture<Stream> push(PushPromiseFrame frame, Listener listener)
    {
        Promise.Completable<Stream> result = new Promise.Completable<>();
        push(frame, result, listener);
        return result;
    }

    /**
     * <p>Sends the given PUSH_PROMISE {@code frame}.</p>
     *
     * @param frame the PUSH_PROMISE frame to send
     * @param promise the promise that gets notified of the pushed stream creation
     * @param listener the listener that gets notified of stream events
     */
    public void push(PushPromiseFrame frame, Promise<Stream> promise, Listener listener);

    /**
     * <p>Sends the given DATA {@code frame}.</p>
     *
     * @param frame the DATA frame to send
     * @return the CompletableFuture that gets notified when the frame has been sent
     */
    public default CompletableFuture<Stream> data(DataFrame frame)
    {
        Promise.Completable<Stream> result = new Promise.Completable<>();
        data(frame, Callback.from(() -> result.succeeded(this), result::failed));
        return result;
    }

    /**
     * <p>Sends the given DATA {@code frame}.</p>
     *
     * @param frame the DATA frame to send
     * @param callback the callback that gets notified when the frame has been sent
     */
    public void data(DataFrame frame, Callback callback);

    /**
     * <p>Sends the given RST_STREAM {@code frame}.</p>
     *
     * @param frame the RST_FRAME to send
     * @param callback the callback that gets notified when the frame has been sent
     */
    public void reset(ResetFrame frame, Callback callback);

    /**
     * @param key the attribute key
     * @return an arbitrary object associated with the given key to this stream
     * or null if no object can be found for the given key.
     * @see #setAttribute(String, Object)
     */
    public Object getAttribute(String key);

    /**
     * @param key the attribute key
     * @param value an arbitrary object to associate with the given key to this stream
     * @see #getAttribute(String)
     * @see #removeAttribute(String)
     */
    public void setAttribute(String key, Object value);

    /**
     * @param key the attribute key
     * @return the arbitrary object associated with the given key to this stream
     * @see #setAttribute(String, Object)
     */
    public Object removeAttribute(String key);

    /**
     * @return whether this stream has been reset
     */
    public boolean isReset();

    /**
     * @return whether this stream is closed, both locally and remotely.
     */
    public boolean isClosed();

    /**
     * @return the stream idle timeout
     * @see #setIdleTimeout(long)
     */
    public long getIdleTimeout();

    /**
     * @param idleTimeout the stream idle timeout
     * @see #getIdleTimeout()
     * @see Stream.Listener#onIdleTimeout(Stream, Throwable)
     */
    public void setIdleTimeout(long idleTimeout);

    /**
     * <p>Demands {@code n} more {@code DATA} frames for this stream.</p>
     *
     * @param n the increment of the demand, must be greater than zero
     * @see Listener#onDataDemanded(Stream, DataFrame, Callback)
     */
    public void demand(long n);

    /**
     * <p>A {@link Stream.Listener} is the passive counterpart of a {@link Stream} and receives
     * events happening on an HTTP/2 stream.</p>
     * <p>HTTP/2 data is flow controlled - this means that only a finite number of data events
     * are delivered, until the flow control window is exhausted.</p>
     * <p>Applications control the delivery of data events by requesting them via
     * {@link Stream#demand(long)}; the first event is always delivered, while subsequent
     * events must be explicitly demanded.</p>
     * <p>Applications control the HTTP/2 flow control by completing the callback associated
     * with data events - this allows the implementation to recycle the data buffer and
     * eventually to enlarge the flow control window so that the sender can send more data.</p>
     *
     * @see Stream
     */
    public interface Listener
    {
        /**
         * <p>Callback method invoked when a stream is created locally by
         * {@link Session#newStream(HeadersFrame, Promise, Listener)}.</p>
         *
         * @param stream the newly created stream
         */
        public default void onNewStream(Stream stream)
        {
        }

        /**
         * <p>Callback method invoked when a HEADERS frame representing the HTTP response has been received.</p>
         *
         * @param stream the stream
         * @param frame the HEADERS frame received
         */
        public void onHeaders(Stream stream, HeadersFrame frame);

        /**
         * <p>Callback method invoked when a PUSH_PROMISE frame has been received.</p>
         *
         * @param stream the pushed stream
         * @param frame the PUSH_PROMISE frame received
         * @return a Stream.Listener that will be notified of pushed stream events
         */
        public Listener onPush(Stream stream, PushPromiseFrame frame);

        /**
         * <p>Callback method invoked before notifying the first DATA frame.</p>
         * <p>The default implementation initializes the demand for DATA frames.</p>
         *
         * @param stream the stream
         */
        public default void onBeforeData(Stream stream)
        {
            stream.demand(1);
        }

        /**
         * <p>Callback method invoked when a DATA frame has been received.</p>
         *
         * @param stream the stream
         * @param frame the DATA frame received
         * @param callback the callback to complete when the bytes of the DATA frame have been consumed
         * @see #onDataDemanded(Stream, DataFrame, Callback)
         */
        public default void onData(Stream stream, DataFrame frame, Callback callback)
        {
            callback.succeeded();
        }

        /**
         * <p>Callback method invoked when a DATA frame has been demanded.</p>
         * <p>Implementations of this method must arrange to call (within the
         * method or otherwise asynchronously) {@link #demand(long)}.</p>
         *
         * @param stream the stream
         * @param frame the DATA frame received
         * @param callback the callback to complete when the bytes of the DATA frame have been consumed
         */
        public default void onDataDemanded(Stream stream, DataFrame frame, Callback callback)
        {
            onData(stream, frame, callback);
            stream.demand(1);
        }

        /**
         * <p>Callback method invoked when a RST_STREAM frame has been received for this stream.</p>
         *
         * @param stream the stream
         * @param frame the RST_FRAME received
         * @param callback the callback to complete when the reset has been handled
         */
        public default void onReset(Stream stream, ResetFrame frame, Callback callback)
        {
            try
            {
                onReset(stream, frame);
                callback.succeeded();
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }

        /**
         * <p>Callback method invoked when a RST_STREAM frame has been received for this stream.</p>
         *
         * @param stream the stream
         * @param frame the RST_FRAME received
         * @see Session.Listener#onReset(Session, ResetFrame)
         */
        public default void onReset(Stream stream, ResetFrame frame)
        {
        }

        /**
         * <p>Callback method invoked when the stream exceeds its idle timeout.</p>
         *
         * @param stream the stream
         * @param x the timeout failure
         * @return true to reset the stream, false to ignore the idle timeout
         * @see #getIdleTimeout()
         */
        public boolean onIdleTimeout(Stream stream, Throwable x);

        /**
         * <p>Callback method invoked when the stream failed.</p>
         *
         * @param stream the stream
         * @param error the error code
         * @param reason the error reason, or null
         * @param failure the failure
         * @param callback the callback to complete when the failure has been handled
         */
        public default void onFailure(Stream stream, int error, String reason, Throwable failure, Callback callback)
        {
            callback.succeeded();
        }

        /**
         * <p>Callback method invoked after the stream has been closed.</p>
         *
         * @param stream the stream
         */
        public default void onClosed(Stream stream)
        {
        }

        /**
         * <p>Empty implementation of {@link Listener}</p>
         */
        public static class Adapter implements Listener
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
            }

            @Override
            public Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                return null;
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
            }

            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
            }

            @Override
            public boolean onIdleTimeout(Stream stream, Throwable x)
            {
                return true;
            }
        }
    }
}
