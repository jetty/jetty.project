//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.api;

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
 * HTTP/2 streams present concurrent for an HTTP/2 session.</p>
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
    int getId();

    /**
     * @return the session this stream is associated to
     */
    Session getSession();

    /**
     * <p>Sends the given HEADERS {@code frame}.</p>
     * <p>Typically used to send an HTTP response or to send the HTTP response trailers.</p>
     *
     * @param frame the HEADERS frame to send
     * @param callback the callback that gets notified when the frame has been sent
     */
    void headers(HeadersFrame frame, Callback callback);

    /**
     * <p>Sends the given PUSH_PROMISE {@code frame}.</p>
     *
     * @param frame the PUSH_PROMISE frame to send
     * @param promise the promise that gets notified of the pushed stream creation
     * @param listener the listener that gets notified of stream events
     */
    void push(PushPromiseFrame frame, Promise<Stream> promise, Listener listener);

    /**
     * <p>Sends the given DATA {@code frame}.</p>
     *
     * @param frame the DATA frame to send
     * @param callback the callback that gets notified when the frame has been sent
     */
    void data(DataFrame frame, Callback callback);

    /**
     * <p>Sends the given RST_STREAM {@code frame}.</p>
     *
     * @param frame the RST_FRAME to send
     * @param callback the callback that gets notified when the frame has been sent
     */
    void reset(ResetFrame frame, Callback callback);

    /**
     * @param key the attribute key
     * @return an arbitrary object associated with the given key to this stream
     * or null if no object can be found for the given key.
     * @see #setAttribute(String, Object)
     */
    Object getAttribute(String key);

    /**
     * @param key the attribute key
     * @param value an arbitrary object to associate with the given key to this stream
     * @see #getAttribute(String)
     * @see #removeAttribute(String)
     */
    void setAttribute(String key, Object value);

    /**
     * @param key the attribute key
     * @return the arbitrary object associated with the given key to this stream
     * @see #setAttribute(String, Object)
     */
    Object removeAttribute(String key);

    /**
     * @return whether this stream has been reset
     */
    boolean isReset();

    /**
     * @return whether this stream is closed, both locally and remotely.
     */
    boolean isClosed();

    /**
     * @return the stream idle timeout
     * @see #setIdleTimeout(long)
     */
    long getIdleTimeout();

    /**
     * @param idleTimeout the stream idle timeout
     * @see #getIdleTimeout()
     * @see Stream.Listener#onIdleTimeout(Stream, Throwable)
     */
    void setIdleTimeout(long idleTimeout);

    /**
     * <p>A {@link Stream.Listener} is the passive counterpart of a {@link Stream} and receives
     * events happening on an HTTP/2 stream.</p>
     *
     * @see Stream
     */
    interface Listener
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
        void onHeaders(Stream stream, HeadersFrame frame);

        /**
         * <p>Callback method invoked when a PUSH_PROMISE frame has been received.</p>
         *
         * @param stream the stream
         * @param frame the PUSH_PROMISE frame received
         * @return a Stream.Listener that will be notified of pushed stream events
         */
        Listener onPush(Stream stream, PushPromiseFrame frame);

        /**
         * <p>Callback method invoked when a DATA frame has been received.</p>
         *
         * @param stream the stream
         * @param frame the DATA frame received
         * @param callback the callback to complete when the bytes of the DATA frame have been consumed
         */
        void onData(Stream stream, DataFrame frame, Callback callback);

        /**
         * <p>Callback method invoked when a RST_STREAM frame has been received for this stream.</p>
         *
         * @param stream the stream
         * @param frame the RST_FRAME received
         * @param callback the callback to complete when the reset has been handled
         */
        default void onReset(Stream stream, ResetFrame frame, Callback callback)
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
        default void onReset(Stream stream, ResetFrame frame)
        {
        }

        /**
         * <p>Callback method invoked when the stream exceeds its idle timeout.</p>
         *
         * @param stream the stream
         * @param x the timeout failure
         * @see #getIdleTimeout()
         * @deprecated use {@link #onIdleTimeout(Stream, Throwable)} instead
         */
        @Deprecated
        default void onTimeout(Stream stream, Throwable x)
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
        default boolean onIdleTimeout(Stream stream, Throwable x)
        {
            onTimeout(stream, x);
            return true;
        }

        /**
         * <p>Callback method invoked when the stream failed.</p>
         *
         * @param stream the stream
         * @param error the error code
         * @param reason the error reason, or null
         * @param failure the failure
         * @param callback the callback to complete when the failure has been handled
         */
        default void onFailure(Stream stream, int error, String reason, Throwable failure, Callback callback)
        {
            onFailure(stream, error, reason, callback);
        }

        /**
         * <p>Callback method invoked when the stream failed.</p>
         *
         * @param stream the stream
         * @param error the error code
         * @param reason the error reason, or null
         * @param callback the callback to complete when the failure has been handled
         * @deprecated use {@link #onFailure(Stream, int, String, Throwable, Callback)} instead
         */
        @Deprecated
        default void onFailure(Stream stream, int error, String reason, Callback callback)
        {
            callback.succeeded();
        }

        /**
         * <p>Callback method invoked after the stream has been closed.</p>
         *
         * @param stream the stream
         */
        default void onClosed(Stream stream)
        {
        }

        /**
         * <p>Empty implementation of {@link Listener}</p>
         */
        class Adapter implements Listener
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
            public void onTimeout(Stream stream, Throwable x)
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
