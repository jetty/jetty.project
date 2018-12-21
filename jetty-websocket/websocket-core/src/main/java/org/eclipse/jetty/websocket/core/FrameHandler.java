//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.client.UpgradeRequest;
import org.eclipse.jetty.websocket.core.server.Negotiation;

/**
 * Interface for local WebSocket Endpoint Frame handling.
 *
 * <p>
 * This is the receiver of Parsed Frames.  It is implemented by the Application (or Application API layer or Framework)
 * as the primary API to/from the Core websocket implementation.   The instance to be used for each websocket connection
 * is instantiated by the application, either:
 * </p>
 * <ul>
 * <li>On the server, the application layer must provide a {@link org.eclipse.jetty.websocket.core.server.WebSocketNegotiator} instance
 * to negotiate and accept websocket connections, which will return the FrameHandler instance to use from
 * {@link org.eclipse.jetty.websocket.core.server.WebSocketNegotiator#negotiate(Negotiation)}.</li>
 * <li>On the client, the application returns the FrameHandler instance to user from the {@link UpgradeRequest}
 * instance that it passes to the {@link org.eclipse.jetty.websocket.core.client.WebSocketCoreClient#connect(UpgradeRequest)} method/</li>
 * </ul>
 * <p>
 * Once instantiated the FrameHandler follows is used as follows:
 * </p>
 * <ul>
 * <li>The {@link #onOpen(CoreSession)} method is called when negotiation of the connection is completed. The passed {@link CoreSession} instance is used
 * to obtain information about the connection and to send frames</li>
 * <li>Every data and control frame received is passed to {@link #onFrame(Frame, Callback)}.</li>
 * <li>Received Control Frames that require a response (eg Ping, Close) are first passed to the {@link #onFrame(Frame, Callback)} to give the
 * Application an opportunity to send the response itself. If an appropriate response has not been sent when the callback passed is completed, then a
 * response will be generated.</li>
 * <li>If an error is detected or received, then {@link #onError(Throwable)} will be called to inform the application of the cause of the problem.
 * The connection will then be closed or aborted and the {@link #onClosed(CloseStatus)} method called.</li>
 * <li>The {@link #onClosed(CloseStatus)} method is always called once a websocket connection is terminated, either gracefully or not. The error code
 * will indicate the nature of the close.</li>
 * </ul>
 */
public interface FrameHandler extends IncomingFrames
{
    // TODO: have conversation about "throws Exception" vs "throws WebSocketException" vs "throws Throwable" in below signatures.

    /**
     * Connection is being opened.
     * <p>
     * FrameHandler can write during this call, but will not receive frames until
     * the onOpen() completes.
     * </p>
     *
     * @param coreSession the channel associated with this connection.
     * @throws Exception if unable to open. TODO: will close the connection (optionally choosing close status code based on WebSocketException type)?
     */
    void onOpen(CoreSession coreSession) throws Exception;

    /**
     * Receiver of all Frames.
     * This method will never be called in parallel for the same session and will be called
     * sequentially to satisfy all outstanding demand signaled by calls to
     * {@link CoreSession#demand(long)}.
     * Control and Data frames are passed to this method.
     * Control frames that require a response (eg PING and CLOSE) may be responded to by the
     * the handler, but if an appropriate response is not sent once the callback is succeeded,
     * then a response will be generated and sent.
     *
     * @param frame    the raw frame
     * @param callback the callback to indicate success in processing frame (or failure)
     */
    void onFrame(Frame frame, Callback callback);

    /**
     * This is the Close Handshake Complete event.
     * <p>
     * The connection is now closed, no reading or writing is possible anymore.
     * Implementations of FrameHandler can cleanup their resources for this connection now.
     * </p>
     *
     * @param closeStatus the close status received from remote, or in the case of abnormal closure from local.
     */
    void onClosed(CloseStatus closeStatus);

    /**
     * An error has occurred or been detected in websocket-core and being reported to FrameHandler.
     * A call to onError will be followed by a call to {@link #onClosed(CloseStatus)} giving the close status
     * derived from the error.
     *
     * @param cause the reason for the error
     * @throws Exception if unable to process the error.
     */
    void onError(Throwable cause) throws Exception;

    /**
     * Does the FrameHandler manage it's own demand?
     *
     * @return true iff the FrameHandler will manage its own flow control by calling {@link CoreSession#demand(long)} when it
     * is willing to receive new Frames.  Otherwise the demand will be managed by an automatic call to demand(1) after every
     * succeeded callback passed to {@link #onFrame(Frame, Callback)}.
     */
    default boolean isDemanding()
    {
        return false;
    }


    interface Configuration
    {

        /**
         * Get the Idle Timeout
         *
         * @return the idle timeout
         */
        Duration getIdleTimeout();

        /**
         * Set the Idle Timeout.
         *
         * @param timeout the timeout duration
         */
        void setIdleTimeout(Duration timeout);

        boolean isAutoFragment();

        void setAutoFragment(boolean autoFragment);

        long getMaxFrameSize();

        void setMaxFrameSize(long maxFrameSize);

        int getOutputBufferSize();

        void setOutputBufferSize(int outputBufferSize);

        int getInputBufferSize();

        void setInputBufferSize(int inputBufferSize);

        long getMaxBinaryMessageSize();

        void setMaxBinaryMessageSize(long maxSize);

        long getMaxTextMessageSize();

        void setMaxTextMessageSize(long maxSize);
    }

    /**
     * Represents the outgoing Frames.
     */
    interface CoreSession extends OutgoingFrames, Configuration
    {
        /**
         * The negotiated WebSocket Sub-Protocol for this channel.
         *
         * @return the negotiated WebSocket Sub-Protocol for this channel.
         */
        String getNegotiatedSubProtocol();

        /**
         * The negotiated WebSocket Extension Configurations for this channel.
         *
         * @return the list of Negotiated Extension Configurations for this channel.
         */
        List<ExtensionConfig> getNegotiatedExtensions();

        /**
         * The parameter map (from URI Query) for the active channel.
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
         * Issue a harsh abort of the underlying connection.
         * <p>
         * This will terminate the connection, without sending a websocket close frame.
         * No WebSocket Protocol close handshake will be performed.
         * </p>
         * <p>
         * Once called, any read/write activity on the websocket from this point will be indeterminate.
         * This can result in the {@link #onError(Throwable)} event being called indicating any issue that arises.
         * </p>
         * <p>
         * Once the underlying connection has been determined to be closed, the {@link #onClosed(CloseStatus)} event will be called.
         * </p>
         */
        void abort();

        /**
         * @return Client or Server behaviour
         */
        Behavior getBehavior();

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
         * @return the SocketAddress for the local connection, or null if not supported by Channel
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
         * @return the SocketAddress for the remote connection, or null if not supported by Channel
         */
        SocketAddress getRemoteAddress();

        /**
         * @return True if the websocket is open outbound
         */
        boolean isOpen();

        /**
         * If using BatchMode.ON or BatchMode.AUTO, trigger a flush of enqueued / batched frames.
         *
         * @param callback the callback to track close frame sent (or failed)
         */
        void flush(Callback callback);

        /**
         * Initiate close handshake, no payload (no declared status code or reason phrase)
         *
         * @param callback the callback to track close frame sent (or failed)
         */
        void close(Callback callback);

        /**
         * Initiate close handshake with provide status code and optional reason phrase.
         *
         * @param statusCode the status code (should be a valid status code that can be sent)
         * @param reason     optional reason phrase (will be truncated automatically by implementation to fit within limits of protocol)
         * @param callback   the callback to track close frame sent (or failed)
         */
        void close(int statusCode, String reason, Callback callback);

        /**
         * Manage flow control by indicating demand for handling Frames.  A call to
         * {@link FrameHandler#onFrame(Frame, Callback)} will only be made if a
         * corresponding demand has been signaled.   It is an error to call this method
         * if {@link FrameHandler#isDemanding()} returns false.
         *
         * @param n The number of frames that can be handled (in sequential calls to
         *          {@link FrameHandler#onFrame(Frame, Callback)}).  May not be negative.
         */
        void demand(long n);

        class Empty implements CoreSession
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
            public ByteBufferPool getByteBufferPool()
            {
                return null;
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
            public boolean isOpen()
            {
                return false;
            }

            @Override
            public Duration getIdleTimeout()
            {
                return Duration.ZERO;
            }

            @Override
            public void setIdleTimeout(Duration timeout)
            {
            }

            @Override
            public void flush(Callback callback)
            {
            }

            @Override
            public void close(Callback callback)
            {
            }

            @Override
            public void close(int statusCode, String reason, Callback callback)
            {
            }

            @Override
            public void demand(long n)
            {
            }

            @Override
            public boolean isAutoFragment()
            {
                return false;
            }

            @Override
            public void setAutoFragment(boolean autoFragment)
            {
            }

            @Override
            public long getMaxFrameSize()
            {
                return 0;
            }

            @Override
            public void setMaxFrameSize(long maxFrameSize)
            {
            }

            @Override
            public int getOutputBufferSize()
            {
                return 0;
            }

            @Override
            public void setOutputBufferSize(int outputBufferSize)
            {
            }

            @Override
            public int getInputBufferSize()
            {
                return 0;
            }

            @Override
            public void setInputBufferSize(int inputBufferSize)
            {
            }

            @Override
            public void sendFrame(Frame frame, Callback callback, boolean batch)
            {
            }

            @Override
            public long getMaxBinaryMessageSize()
            {
                return 0;
            }

            @Override
            public void setMaxBinaryMessageSize(long maxSize)
            {
            }

            @Override
            public long getMaxTextMessageSize()
            {
                return 0;
            }

            @Override
            public void setMaxTextMessageSize(long maxSize)
            {
            }
        }
    }

    interface Customizer
    {
        void customize(CoreSession session);
    }

    class ConfigurationCustomizer implements Customizer, Configuration
    {
        private Duration timeout;
        private Boolean autoFragment;
        private Long maxFrameSize;
        private Integer outputBufferSize;
        private Integer inputBufferSize;
        private Long maxBinaryMessageSize;
        private Long maxTextMessageSize;

        @Override
        public Duration getIdleTimeout()
        {
            return timeout;
        }

        @Override
        public void setIdleTimeout(Duration timeout)
        {
            this.timeout = timeout;
        }

        @Override
        public boolean isAutoFragment()
        {
            return autoFragment==null?WebSocketConstants.DEFAULT_AUTO_FRAGMENT:autoFragment;
        }

        @Override
        public void setAutoFragment(boolean autoFragment)
        {
            this.autoFragment = autoFragment;
        }

        @Override
        public long getMaxFrameSize()
        {
            return maxFrameSize==null?WebSocketConstants.DEFAULT_MAX_FRAME_SIZE:maxFrameSize;
        }

        @Override
        public void setMaxFrameSize(long maxFrameSize)
        {
            this.maxFrameSize = maxFrameSize;
        }

        @Override
        public int getOutputBufferSize()
        {
            return outputBufferSize==null?WebSocketConstants.DEFAULT_OUTPUT_BUFFER_SIZE:outputBufferSize;
        }

        @Override
        public void setOutputBufferSize(int outputBufferSize)
        {
            this.outputBufferSize = outputBufferSize;
        }

        @Override
        public int getInputBufferSize()
        {
            return inputBufferSize==null?WebSocketConstants.DEFAULT_INPUT_BUFFER_SIZE:inputBufferSize;
        }

        @Override
        public void setInputBufferSize(int inputBufferSize)
        {
            this.inputBufferSize = inputBufferSize;
        }

        @Override
        public long getMaxBinaryMessageSize()
        {
            return maxBinaryMessageSize==null?WebSocketConstants.DEFAULT_MAX_BINARY_MESSAGE_SIZE:maxBinaryMessageSize;
        }

        @Override
        public void setMaxBinaryMessageSize(long maxBinaryMessageSize)
        {
            this.maxBinaryMessageSize = maxBinaryMessageSize;
        }

        @Override
        public long getMaxTextMessageSize()
        {
            return maxTextMessageSize==null?WebSocketConstants.DEFAULT_MAX_TEXT_MESSAGE_SIZE:maxTextMessageSize;
        }

        @Override
        public void setMaxTextMessageSize(long maxTextMessageSize)
        {
            this.maxTextMessageSize = maxTextMessageSize;
        }

        @Override
        public void customize(CoreSession session)
        {
            if (timeout!=null)
                session.setIdleTimeout(timeout);
            if (autoFragment!=null)
                session.setAutoFragment(autoFragment);
            if (maxFrameSize!=null)
                session.setMaxFrameSize(maxFrameSize);
            if (inputBufferSize!=null)
                session.setInputBufferSize(inputBufferSize);
            if (outputBufferSize!=null)
                session.setOutputBufferSize(outputBufferSize);
            if (maxBinaryMessageSize!=null)
                session.setMaxBinaryMessageSize(maxBinaryMessageSize);
            if (maxTextMessageSize!=null)
                session.setMaxTextMessageSize(maxTextMessageSize);
        }
    }

}
