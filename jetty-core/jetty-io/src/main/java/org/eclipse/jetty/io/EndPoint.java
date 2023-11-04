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

package org.eclipse.jetty.io;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * <p>EndPoint is the abstraction for an I/O channel that transports bytes.</p>
 *
 * <p>Asynchronous Methods</p>
 * <p>The asynchronous scheduling methods of {@link EndPoint}
 * has been influenced by NIO.2 Futures and Completion
 * handlers, but does not use those actual interfaces because they have
 * some inefficiencies.</p>
 * <p>This class will frequently be used in conjunction with some of the utility
 * implementations of {@link Callback}, such as {@link FutureCallback} and
 * {@link IteratingCallback}.</p>
 *
 * <p>Reads</p>
 * <p>A {@link FutureCallback} can be used to block until an endpoint is ready
 * to fill bytes - the notification will be emitted by the NIO subsystem:</p>
 * <pre>
 * FutureCallback callback = new FutureCallback();
 * endPoint.fillInterested(callback);
 *
 * // Blocks until read to fill bytes.
 * callback.get();
 *
 * // Now bytes can be filled in a ByteBuffer.
 * int filled = endPoint.fill(byteBuffer);
 * </pre>
 *
 * <p>Asynchronous Reads</p>
 * <p>A {@link Callback} can be used to read asynchronously in its own dispatched
 * thread:</p>
 * <pre>
 * endPoint.fillInterested(new Callback()
 * {
 *   public void onSucceeded()
 *   {
 *     executor.execute(() -&gt;
 *     {
 *       // Fill bytes in a different thread.
 *       int filled = endPoint.fill(byteBuffer);
 *     });
 *   }
 *   public void onFailed(Throwable failure)
 *   {
 *     endPoint.close();
 *   }
 * });
 * </pre>
 *
 * <p>Blocking Writes</p>
 * <p>The write contract is that the callback is completed when all the bytes
 * have been written or there is a failure.
 * Blocking writes look like this:</p>
 * <pre>
 * FutureCallback callback = new FutureCallback();
 * endpoint.write(callback, headerBuffer, contentBuffer);
 *
 * // Blocks until the write succeeds or fails.
 * future.get();
 * </pre>
 * <p>Note also that multiple buffers may be passed in {@link #write(Callback, ByteBuffer...)}
 * so that gather writes can be performed for efficiency.</p>
 */
public interface EndPoint extends Closeable
{
    /**
     * Marks an {@code EndPoint} that wraps another {@code EndPoint}.
     */
    interface Wrapper
    {
        /**
         * @return The wrapped {@code EndPoint}
         */
        EndPoint unwrap();
    }

    /**
     * @return The local InetSocketAddress to which this {@code EndPoint} is bound, or {@code null}
     * if this {@code EndPoint} is not bound to a Socket address.
     * @deprecated use {@link #getLocalSocketAddress()} instead
     */
    @Deprecated
    InetSocketAddress getLocalAddress();

    /**
     * @return the local SocketAddress to which this {@code EndPoint} is bound or {@code null}
     * if this {@code EndPoint} is not bound to a Socket address.
     */
    default SocketAddress getLocalSocketAddress()
    {
        return getLocalAddress();
    }

    /**
     * @return The remote InetSocketAddress to which this {@code EndPoint} is connected, or {@code null}
     * if this {@code EndPoint} is not connected to a Socket address.
     * @deprecated use {@link #getRemoteSocketAddress()} instead.
     */
    @Deprecated
    InetSocketAddress getRemoteAddress();

    /**
     * @return The remote SocketAddress to which this {@code EndPoint} is connected, or {@code null}
     * if this {@code EndPoint} is not connected to a Socket address.
     */
    default SocketAddress getRemoteSocketAddress()
    {
        return getRemoteAddress();
    }

    /**
     * @return whether this EndPoint is open
     */
    boolean isOpen();

    /**
     * @return the epoch time in milliseconds when this EndPoint was created
     */
    long getCreatedTimeStamp();

    /**
     * Shutdown the output.
     * <p>This call indicates that no more data will be sent on this endpoint that
     * that the remote end should read an EOF once all previously sent data has been
     * consumed. Shutdown may be done either at the TCP/IP level, as a protocol exchange (Eg
     * TLS close handshake) or both.
     * <p>
     * If the endpoint has {@link #isInputShutdown()} true, then this call has the same effect
     * as {@link #close()}.
     */
    void shutdownOutput();

    /**
     * Test if output is shutdown.
     * The output is shutdown by a call to {@link #shutdownOutput()}
     * or {@link #close()}.
     *
     * @return true if the output is shutdown or the endpoint is closed.
     */
    boolean isOutputShutdown();

    /**
     * Test if the input is shutdown.
     * The input is shutdown if an EOF has been read while doing
     * a {@link #fill(ByteBuffer)}.   Once the input is shutdown, all calls to
     * {@link #fill(ByteBuffer)} will  return -1, until such time as the
     * end point is close, when they will return {@link EofException}.
     *
     * @return True if the input is shutdown or the endpoint is closed.
     */
    boolean isInputShutdown();

    /**
     * Close any backing stream associated with the endpoint
     */
    @Override
    default void close()
    {
        close(null);
    }

    /**
     * Close any backing stream associated with the endpoint, passing a cause
     *
     * @param cause the reason for the close or null
     */
    void close(Throwable cause);

    /**
     * Fill the passed buffer with data from this endpoint.  The bytes are appended to any
     * data already in the buffer by writing from the buffers limit up to it's capacity.
     * The limit is updated to include the filled bytes.
     *
     * @param buffer The buffer to fill. The position and limit are modified during the fill. After the
     * operation, the position is unchanged and the limit is increased to reflect the new data filled.
     * @return an {@code int} value indicating the number of bytes
     * filled or -1 if EOF is read or the input is shutdown.
     * @throws IOException if the endpoint is closed.
     */
    default int fill(ByteBuffer buffer) throws IOException
    {
        throw new UnsupportedOperationException();
    }

    /**
     * Flush data from the passed header/buffer to this endpoint.  As many bytes as can be consumed
     * are taken from the header/buffer position up until the buffer limit.  The header/buffers position
     * is updated to indicate how many bytes have been consumed.
     *
     * @param buffer the buffers to flush
     * @return True IFF all the buffers have been consumed and the endpoint has flushed the data to its
     * destination (ie is not buffering any data).
     * @throws IOException If the endpoint is closed or output is shutdown.
     */
    default boolean flush(ByteBuffer... buffer) throws IOException
    {
        throw new UnsupportedOperationException();
    }

    /**
     * @return The underlying transport object (socket, channel, etc.)
     */
    Object getTransport();

    /**
     * Get the max idle time in ms.
     * <p>The max idle time is the time the endpoint can be idle before
     * extraordinary handling takes place.
     *
     * @return the max idle time in ms or if ms &lt;= 0 implies an infinite timeout
     */
    long getIdleTimeout();

    /**
     * Set the idle timeout.
     *
     * @param idleTimeout the idle timeout in MS. Timeout &lt;= 0 implies an infinite timeout
     */
    void setIdleTimeout(long idleTimeout);

    /**
     * <p>Requests callback methods to be invoked when a call to {@link #fill(ByteBuffer)} would return data or EOF.</p>
     *
     * @param callback the callback to call when an error occurs or we are readable.  The callback may implement the {@link Invocable} interface to
     * self declare its blocking status. Non-blocking callbacks may be called more efficiently without dispatch delays.
     * @throws ReadPendingException if another read operation is concurrent.
     */
    void fillInterested(Callback callback) throws ReadPendingException;

    /**
     * <p>Requests callback methods to be invoked when a call to {@link #fill(ByteBuffer)} would return data or EOF.</p>
     *
     * @param callback the callback to call when an error occurs or we are readable.  The callback may implement the {@link Invocable} interface to
     * self declare its blocking status. Non-blocking callbacks may be called more efficiently without dispatch delays.
     * @return true if set
     */
    boolean tryFillInterested(Callback callback);

    /**
     * @return whether {@link #fillInterested(Callback)} has been called, but {@link #fill(ByteBuffer)} has not yet
     * been called
     */
    boolean isFillInterested();

    /**
     * <p>Writes the given buffers via {@link #flush(ByteBuffer...)} and invokes callback methods when either
     * all the data has been flushed or an error occurs.</p>
     *
     * @param callback the callback to call when an error occurs or the write completed.   The callback may implement the {@link Invocable} interface to
     * self declare its blocking status. Non-blocking callbacks may be called more efficiently without dispatch delays.
     * @param buffers one or more {@link ByteBuffer}s that will be flushed.
     * @throws WritePendingException if another write operation is concurrent.
     */
    default void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
    {
        throw new UnsupportedOperationException();
    }

    /**
     * @return the {@link Connection} associated with this EndPoint
     * @see #setConnection(Connection)
     */
    Connection getConnection();

    /**
     * @param connection the {@link Connection} associated with this EndPoint
     * @see #getConnection()
     * @see #upgrade(Connection)
     */
    void setConnection(Connection connection);

    /**
     * <p>Callback method invoked when this EndPoint is opened.</p>
     *
     * @see #onClose(Throwable)
     */
    void onOpen();

    /**
     * <p>Callback method invoked when this {@link EndPoint} is closed.</p>
     *
     * @param cause The reason for the close, or null if a normal close.
     * @see #onOpen()
     */
    void onClose(Throwable cause);

    /**
     * <p>Upgrades this EndPoint from the current connection to the given new connection.</p>
     * <p>Closes the current connection, links this EndPoint to the new connection and
     * then opens the new connection.</p>
     * <p>If the current connection is an instance of {@link Connection.UpgradeFrom} then
     * a buffer of unconsumed bytes is requested.
     * If the buffer of unconsumed bytes is non-null and non-empty, then the new
     * connection is tested: if it is an instance of {@link Connection.UpgradeTo}, then
     * the unconsumed buffer is passed to the new connection; otherwise, an exception
     * is thrown since there are unconsumed bytes that cannot be consumed by the new
     * connection.</p>
     *
     * @param newConnection the connection to upgrade to
     */
    void upgrade(Connection newConnection);

    interface Securable extends EndPoint
    {
        /**
         * Get the SslSessionData of a secure end point.
         * @return A {@link SslSessionData} instance (with possibly null field values) if secure, else {@code null}.
         */
        SslSessionData getSslSessionData();

        default boolean isSecure()
        {
            return getSslSessionData() != null;
        }
    }

    /**
     * Bundle of SSLSession associated data that may be known from an {@link SSLSession} or determined otherwise in the
     * absence of an {@link SSLSession}.  All fields may be {@code null}, and typically it is the presence of a non
     * {@code null} instance that indicates a {@link Connection} and/or request is secure.
     * @param sslSession The {@link SSLSession} itself, if known, else {@code null}
     * @param sessionId The {@link SSLSession#getId()} rendered as a hex string, if known, else {@code null}
     * @param cipherSuite The {@link SSLSession#getCipherSuite()} if known, else {@code null}
     * @param peerCertificates The {@link SSLSession#getPeerCertificates()}s converted to {@link X509Certificate}, if known
     *                         else {@code null}.
     */
    record SslSessionData(SSLSession sslSession, String sessionId, String cipherSuite, X509Certificate[] peerCertificates)
    {
        public static final String ATTRIBUTE = "org.eclipse.jetty.io.Endpoint.SslSessionData";

        public static SslSessionData from(SslSessionData baseData, SSLSession sslSession, String sessionId, String cipherSuite, X509Certificate[] peerCertificates)
        {
            return (baseData == null)
                ? new SslSessionData(sslSession, sessionId, cipherSuite, peerCertificates)
                : new SslSessionData(
                    sslSession != null ? sslSession : baseData.sslSession,
                    sessionId != null ? sessionId : baseData.sessionId,
                    cipherSuite != null ? cipherSuite : baseData.cipherSuite,
                    peerCertificates != null ? peerCertificates : baseData.peerCertificates());
        }
    }
}
