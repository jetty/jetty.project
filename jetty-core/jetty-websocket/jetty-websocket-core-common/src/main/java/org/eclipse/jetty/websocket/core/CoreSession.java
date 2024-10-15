//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.ReadPendingException;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.exception.WebSocketTimeoutException;

/**
 * Represents the outgoing Frames.
 */
public interface CoreSession extends OutgoingFrames, IncomingFrames, Configuration
{
    String getNegotiatedSubProtocol();

    /**
     * The negotiated WebSocket Extension Configurations for this session.
     *
     * @return the list of Negotiated Extension Configurations for this session.
     */
    List<ExtensionConfig> getNegotiatedExtensions();

    /**
     * The parameter map (from URI Query) for the active session.
     *
     * @return the immutable map of parameters
     */
    Map<String, List<String>> getParameterMap();

    /**
     * The active {@code Sec-WebSocket-Version} (protocol version) in use.
     *
     * @return the protocol version in use.
     */
    String getProtocolVersion();

    /**
     * The active connection's Request URI.
     * This is the URI of the upgrade request and is typically http: or https: rather than
     * the ws: or wss: scheme.
     *
     * @return the absolute URI (including Query string)
     */
    URI getRequestURI();

    /**
     * The active connection's Secure status indicator.
     *
     * @return true if connection is secure (similar in role to {@code HttpServletRequest.isSecure()})
     */
    boolean isSecure();

    /**
     * @return Client or Server behaviour
     */
    Behavior getBehavior();

    /**
     * @return the WebSocketComponents instance in use for this Connection.
     */
    WebSocketComponents getWebSocketComponents();

    /**
     * @return The shared ByteBufferPool
     */
    ByteBufferPool getByteBufferPool();

    /**
     * The Local Socket Address for the connection
     * <p>
     * Do not assume that this will return a {@link InetSocketAddress} in all cases.
     * Use of various proxies, and even UnixSockets can result a SocketAddress being returned
     * without supporting {@link InetSocketAddress}
     * </p>
     *
     * @return the SocketAddress for the local connection, or null if not supported by Session
     */
    SocketAddress getLocalAddress();

    /**
     * The Remote Socket Address for the connection
     * <p>
     * Do not assume that this will return a {@link InetSocketAddress} in all cases.
     * Use of various proxies, and even UnixSockets can result a SocketAddress being returned
     * without supporting {@link InetSocketAddress}
     * </p>
     *
     * @return the SocketAddress for the remote connection, or null if not supported by Session
     */
    SocketAddress getRemoteAddress();

    /**
     * @return True if the websocket is open inbound
     */
    boolean isInputOpen();

    /**
     * @return True if the websocket is open outbound
     */
    boolean isOutputOpen();

    boolean isClosed();

    void flush(Callback callback);

    /**
     * Initiate close handshake, no payload (no declared status code or reason phrase).
     *
     * @param callback the callback to track close frame sent (or failed).
     */
    void close(Callback callback);

    /**
     * Initiate close handshake with provided status code and optional reason phrase.
     *
     * @param statusCode the status code (should be a valid status code that can be sent).
     * @param reason optional reason phrase (will be truncated automatically by implementation to fit within limits of protocol).
     * @param callback the callback to track close frame sent (or failed).
     */
    void close(int statusCode, String reason, Callback callback);

    /**
     * Initiate close handshake with a provided {@link CloseStatus}.
     *
     * @param closeStatus the close status containing (statusCode, reason, and optional {@link Throwable} cause).
     * @param callback the callback to track close frame sent (or failed).
     */
    default void close(CloseStatus closeStatus, Callback callback)
    {
        if (this instanceof WebSocketCoreSession coreSession)
            coreSession.close(closeStatus, callback);
        else
            close(closeStatus.getCode(), closeStatus.getReason(), callback);
    }

    /**
     * Issue a harsh abort of the underlying connection.
     * <p>
     * This will terminate the connection, without sending a websocket close frame.
     * No WebSocket Protocol close handshake will be performed.
     * </p>
     * <p>
     * Once called, any read/write activity on the websocket from this point will be indeterminate.
     * This can result in the {@link FrameHandler#onError(Throwable, Callback)} event being called indicating any issue that arises.
     * </p>
     * <p>
     * Once the underlying connection has been determined to be closed, the {@link FrameHandler#onClosed(CloseStatus, Callback)} event will be called.
     * </p>
     */
    void abort();

    /**
     * <p>Manages flow control by indicating demand for a WebSocket frame.</p>
     * <p>A call to {@link FrameHandler#onFrame(Frame, Callback)} will only
     * be made if there is demand.</p>
     * <p>If a previous demand has not been fulfilled this will throw {@link ReadPendingException}</p>
     *
     * {@link FrameHandler#onFrame(Frame, Callback)}.
     */
    void demand();

    /**
     * @return true if an extension has been negotiated which uses the RSV1 bit.
     */
    boolean isRsv1Used();

    /**
     * @return true if an extension has been negotiated which uses the RSV2 bit.
     */
    boolean isRsv2Used();

    /**
     * @return true if an extension has been negotiated which uses the RSV3 bit.
     */
    boolean isRsv3Used();

    /**
     * <p>Adds a listener for websocket idle timeouts.</p>
     * <p>The listener is a predicate function that should return {@code true} to indicate
     * that the timeout should be handled as a fatal failure or {@code false} to ignore
     * that specific timeout and for another timeout to occur after another idle period.</p>
     * <p>Listeners are processed in the same order they are added, and the first that
     * returns {@code true} stops the processing of subsequent listeners, which are
     * therefore not invoked.</p>
     *
     * @param onIdleTimeout the idle timeout listener as a predicate function
     */
    void addIdleTimeoutListener(Predicate<WebSocketTimeoutException> onIdleTimeout);

    class Empty extends ConfigurationCustomizer implements CoreSession
    {
        @Override
        public String getNegotiatedSubProtocol()
        {
            return null;
        }

        @Override
        public List<ExtensionConfig> getNegotiatedExtensions()
        {
            return null;
        }

        @Override
        public Map<String, List<String>> getParameterMap()
        {
            return null;
        }

        @Override
        public String getProtocolVersion()
        {
            return null;
        }

        @Override
        public URI getRequestURI()
        {
            return null;
        }

        @Override
        public boolean isSecure()
        {
            return false;
        }

        @Override
        public void abort()
        {
        }

        @Override
        public Behavior getBehavior()
        {
            return null;
        }

        @Override
        public WebSocketComponents getWebSocketComponents()
        {
            return null;
        }

        @Override
        public ByteBufferPool getByteBufferPool()
        {
            WebSocketComponents components = getWebSocketComponents();
            return components != null ? components.getByteBufferPool() : null;
        }

        @Override
        public SocketAddress getLocalAddress()
        {
            return null;
        }

        @Override
        public SocketAddress getRemoteAddress()
        {
            return null;
        }

        @Override
        public boolean isInputOpen()
        {
            return true;
        }

        @Override
        public boolean isOutputOpen()
        {
            return true;
        }

        @Override
        public boolean isClosed()
        {
            return false;
        }

        @Override
        public void flush(Callback callback)
        {
            callback.succeeded();
        }

        @Override
        public void close(Callback callback)
        {
            callback.succeeded();
        }

        @Override
        public void close(int statusCode, String reason, Callback callback)
        {
            callback.succeeded();
        }

        @Override
        public void close(CloseStatus closeStatus, Callback callback)
        {
            callback.succeeded();
        }

        @Override
        public void demand()
        {
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            callback.succeeded();
        }

        @Override
        public void sendFrame(Frame frame, Callback callback, boolean batch)
        {
            callback.succeeded();
        }

        @Override
        public boolean isRsv1Used()
        {
            return false;
        }

        @Override
        public boolean isRsv2Used()
        {
            return false;
        }

        @Override
        public boolean isRsv3Used()
        {
            return false;
        }

        @Override
        public void addIdleTimeoutListener(Predicate<WebSocketTimeoutException> onIdleTimeout)
        {
        }
    }
}
