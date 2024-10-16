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
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * <p>EndPoint is the abstraction for I/O communication using bytes.</p>
 * <p>All the I/O methods are non-blocking; reads may return {@code 0}
 * bytes read, and flushes/writes may write {@code 0} bytes.</p>
 * <p>Applications are notified of read readiness by registering a
 * {@link Callback} via {@link #fillInterested(Callback)}, and then
 * using {@link #fill(ByteBuffer)} to read the available bytes.</p>
 * <p>Application may use {@link #flush(ByteBuffer...)} to transmit bytes;
 * if the flush does not transmit all the bytes, applications must
 * arrange to resume flushing when it will be possible to transmit more
 * bytes.
 * Alternatively, applications may use {@link #write(Callback, ByteBuffer...)}
 * and be notified via the {@link Callback} when the write completes
 * (i.e. all the buffers have been flushed), either successfully or
 * with a failure.</p>
 * <p>Connection-less reads are performed using {@link #receive(ByteBuffer)}.
 * Similarly, connection-less flushes are performed using
 * {@link #send(SocketAddress, ByteBuffer...)} and connection-less writes
 * using {@link #write(Callback, SocketAddress, ByteBuffer...)}.</p>
 * <p>While all the I/O methods are non-blocking, they can be easily
 * converted to blocking using either {@link org.eclipse.jetty.util.Blocker}
 * or {@link Callback.Completable}:</p>
 * <pre>{@code
 * EndPoint endPoint = ...;
 *
 * // Block until read ready with Blocker.
 * try (Blocker.Callback blocker = Blocker.callback())
 * {
 *     endPoint.fillInterested(blocker);
 *     blocker.block();
 * }
 *
 * // Block until write complete with Callback.Completable.
 * Callback.Completable completable = new Callback.Completable();
 * endPoint.write(completable, byteBuffer);
 * completable.get();
 * }</pre>
 */
public interface EndPoint extends Closeable
{
    /**
     * <p>Constant returned by {@link #receive(ByteBuffer)} to indicate the end-of-file.</p>
     */
    SocketAddress EOF = InetSocketAddress.createUnresolved("", 0);

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
     * <p>Shuts down the output.</p>
     * <p>This call indicates that no more data will be sent from this endpoint and
     * that the remote endpoint should read an EOF once all previously sent data has been
     * read. Shutdown may be done either at the TCP/IP level, as a protocol exchange
     * (for example, TLS close handshake) or both.</p>
     * <p>If the endpoint has {@link #isInputShutdown()} true, then this call has the
     * same effect as {@link #close()}.</p>
     */
    void shutdownOutput();

    /**
     * <p>Tests if output is shutdown.</p>
     * <p>The output is shutdown by a call to {@link #shutdownOutput()}
     * or {@link #close()}.</p>
     *
     * @return true if the output is shutdown or the endpoint is closed.
     */
    boolean isOutputShutdown();

    /**
     * <p>Tests if the input is shutdown.</p>
     * <p>The input is shutdown if an EOF has been read while doing
     * a {@link #fill(ByteBuffer)}.
     * Once the input is shutdown, all calls to
     * {@link #fill(ByteBuffer)} will  return -1, until such time as the
     * end point is close, when they will return {@link EofException}.</p>
     *
     * @return true if the input is shutdown or the endpoint is closed.
     */
    boolean isInputShutdown();

    /**
     * <p>Closes any backing stream associated with the endpoint.</p>
     */
    @Override
    default void close()
    {
        close(null);
    }

    /**
     * <p>Closes any backing stream associated with the endpoint, passing a
     * possibly {@code null} failure cause.</p>
     *
     * @param cause the reason for the close or null
     */
    void close(Throwable cause);

    /**
     * <p>Fills the passed buffer with data from this endpoint.</p>
     * <p>The bytes are appended to any data already in the buffer
     * by writing from the buffers limit up to its capacity.
     * The limit is updated to include the filled bytes.</p>
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
     * <p>Receives data into the given buffer from the returned address.</p>
     * <p>This method should be used to receive UDP data.</p>
     *
     * @param buffer the buffer to fill with data
     * @return the peer address that sent the data, or {@link #EOF}
     * @throws IOException if the receive fails
     */
    default SocketAddress receive(ByteBuffer buffer) throws IOException
    {
        int filled = fill(buffer);
        if (filled < 0)
            return EndPoint.EOF;
        if (filled == 0)
            return null;
        return getRemoteSocketAddress();
    }

    /**
     * <p>Flushes data from the passed header/buffer to this endpoint.</p>
     * <p>As many bytes as can be consumed are taken from the header/buffer
     * position up until the buffer limit.
     * The header/buffers position is updated to indicate how many bytes
     * have been consumed.</p>
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
     * <p>Sends to the given address the data in the given buffers.</p>
     * <p>This methods should be used to send UDP data.</p>
     *
     * @param address the peer address to send data to
     * @param buffers the buffers containing the data to send
     * @return true if all the buffers have been consumed
     * @throws IOException if the send fails
     * @see #write(Callback, SocketAddress, ByteBuffer...)
     */
    default boolean send(SocketAddress address, ByteBuffer... buffers) throws IOException
    {
        return flush(buffers);
    }

    /**
     * @return The underlying transport object (socket, channel, etc.)
     */
    Object getTransport();

    /**
     * <p>Returns the idle timeout in ms.</p>
     * <p>The idle timeout is the time the endpoint can be idle before
     * its close is initiated.</p>
     * <p>A timeout less than or equal to {@code 0} implies an infinite timeout.</p>
     *
     * @return the idle timeout in ms
     */
    long getIdleTimeout();

    /**
     * <p>Sets the idle timeout.</p>
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
     * <p>Writes to the given address the data contained in the given buffers, and invokes
     * the given callback when either all the data has been sent, or a failure occurs.</p>
     *
     * @param callback the callback to notify of the success or failure of the write operation
     * @param address the peer address to send data to
     * @param buffers the buffers containing the data to send
     * @throws WritePendingException if a previous write was initiated but was not yet completed
     * @see #send(SocketAddress, ByteBuffer...)
     */
    default void write(Callback callback, SocketAddress address, ByteBuffer... buffers) throws WritePendingException
    {
        write(callback, buffers);
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

    /**
     * <p>Returns the SslSessionData of a secure end point.</p>
     *
     * @return A {@link SslSessionData} instance (with possibly null field values) if secure, else {@code null}.
     */
    default SslSessionData getSslSessionData()
    {
        return null;
    }

    /**
     * @return whether this EndPoint represents a secure communication.
     */
    default boolean isSecure()
    {
        return getSslSessionData() != null;
    }

    /**
     * Interface representing bundle of SSLSession associated data.
     */
    interface SslSessionData
    {
        /**
         * The name at which an {@code SslSessionData} instance may be found
         * as a request {@link org.eclipse.jetty.util.Attributes attribute}.
         */
        String ATTRIBUTE = "org.eclipse.jetty.io.Endpoint.SslSessionData";

        /**
         * @return The {@link SSLSession} itself, if known, else {@code null}.
         */
        SSLSession sslSession();

        /**
         * @return The {@link SSLSession#getId()} rendered as a hex string, if known, else {@code null}.
         */
        String sslSessionId();

        /**
         * @return The {@link SSLSession#getCipherSuite()} if known, else {@code null}.
         */
        String cipherSuite();

        /**
         * @return The {@link SSLSession#getPeerCertificates()}s converted to {@link X509Certificate}, if known, else {@code null}.
         */
        X509Certificate[] peerCertificates();

        /**
         * Calculates the key size based on the cipher suite.
         * @return the key size.
         */
        default int keySize()
        {
            String cipherSuite = cipherSuite();
            return cipherSuite == null ? 0 : SslContextFactory.deduceKeyLength(cipherSuite);
        }

        static SslSessionData from(SSLSession sslSession, String sslSessionId, String cipherSuite, X509Certificate[] peerCertificates)
        {
            return new SslSessionData()
            {
                @Override
                public SSLSession sslSession()
                {
                    return sslSession;
                }

                @Override
                public String sslSessionId()
                {
                    return sslSessionId;
                }

                @Override
                public String cipherSuite()
                {
                    return cipherSuite;
                }

                @Override
                public X509Certificate[] peerCertificates()
                {
                    return peerCertificates;
                }
            };
        }

        static SslSessionData withCipherSuite(SslSessionData baseData, String cipherSuite)
        {
            return (baseData == null)
                ? from(null, null, cipherSuite, null)
                : from(
                    baseData.sslSession(),
                    baseData.sslSessionId(),
                    cipherSuite != null ? cipherSuite : baseData.cipherSuite(),
                    baseData.peerCertificates());
        }

        static SslSessionData withSslSessionId(SslSessionData baseData, String sslSessionId)
        {
            return (baseData == null)
                ? from(null, sslSessionId, null, null)
                : from(
                    baseData.sslSession(),
                    sslSessionId != null ? sslSessionId : baseData.sslSessionId(),
                    baseData.cipherSuite(),
                    baseData.peerCertificates());
        }
    }

    /**
     * <p>A communication conduit between two peers.</p>
     */
    interface Pipe
    {
        /**
         * @return the {@link EndPoint} of the local peer
         */
        EndPoint getLocalEndPoint();

        /**
         * @return the {@link EndPoint} of the remote peer
         */
        EndPoint getRemoteEndPoint();
    }
}
