//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.io.ssl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntFunction;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Invocable;

/**
 * A Connection that acts as an interceptor between an EndPoint providing SSL encrypted data
 * and another consumer of an EndPoint (typically an {@link Connection} like HttpConnection) that
 * wants unencrypted data.
 * <p>
 * The connector uses an {@link EndPoint} (typically SocketChannelEndPoint) as
 * it's source/sink of encrypted data.   It then provides an endpoint via {@link #getDecryptedEndPoint()} to
 * expose a source/sink of unencrypted data to another connection (eg HttpConnection).
 * <p>
 * The design of this class is based on a clear separation between the passive methods, which do not block nor schedule any
 * asynchronous callbacks, and active methods that do schedule asynchronous callbacks.
 * <p>
 * The passive methods are {@link DecryptedEndPoint#fill(ByteBuffer)} and {@link DecryptedEndPoint#flush(ByteBuffer...)}. They make best
 * effort attempts to progress the connection using only calls to the encrypted {@link EndPoint#fill(ByteBuffer)} and {@link EndPoint#flush(ByteBuffer...)}
 * methods.  They will never block nor schedule any readInterest or write callbacks.   If a fill/flush cannot progress either because
 * of network congestion or waiting for an SSL handshake message, then the fill/flush will simply return with zero bytes filled/flushed.
 * Specifically, if a flush cannot proceed because it needs to receive a handshake message, then the flush will attempt to fill bytes from the
 * encrypted endpoint, but if insufficient bytes are read it will NOT call {@link EndPoint#fillInterested(Callback)}.
 * <p>
 * It is only the active methods : {@link DecryptedEndPoint#fillInterested(Callback)} and
 * {@link DecryptedEndPoint#write(Callback, ByteBuffer...)} that may schedule callbacks by calling the encrypted
 * {@link EndPoint#fillInterested(Callback)} and {@link EndPoint#write(Callback, ByteBuffer...)}
 * methods.  For normal data handling, the decrypted fillInterest method will result in an encrypted fillInterest and a decrypted
 * write will result in an encrypted write. However, due to SSL handshaking requirements, it is also possible for a decrypted fill
 * to call the encrypted write and for the decrypted flush to call the encrypted fillInterested methods.
 * <p>
 * MOST IMPORTANTLY, the encrypted callbacks from the active methods (#onFillable() and WriteFlusher#completeWrite()) do no filling or flushing
 * themselves.  Instead they simple make the callbacks to the decrypted callbacks, so that the passive encrypted fill/flush will
 * be called again and make another best effort attempt to progress the connection.
 */
public class SslConnection extends AbstractConnection implements Connection.UpgradeTo
{
    private static final Logger LOG = Log.getLogger(SslConnection.class);
    private static final String TLS_1_3 = "TLSv1.3";

    private enum HandshakeState
    {
        INITIAL,
        HANDSHAKE,
        SUCCEEDED,
        FAILED
    }

    private enum FillState
    {
        IDLE, // Not Filling any data
        INTERESTED, // We have a pending read interest
        WAIT_FOR_FLUSH // Waiting for a flush to happen
    }

    private enum FlushState
    {
        IDLE, // Not flushing any data
        WRITING, // We have a pending write of encrypted data
        WAIT_FOR_FILL // Waiting for a fill to happen
    }

    private final List<SslHandshakeListener> handshakeListeners = new ArrayList<>();
    private final ByteBufferPool _bufferPool;
    private final SSLEngine _sslEngine;
    private final DecryptedEndPoint _decryptedEndPoint;
    private ByteBuffer _decryptedInput;
    private ByteBuffer _encryptedInput;
    private ByteBuffer _encryptedOutput;
    private final boolean _encryptedDirectBuffers;
    private final boolean _decryptedDirectBuffers;
    private boolean _renegotiationAllowed;
    private int _renegotiationLimit = -1;
    private boolean _closedOutbound;
    private boolean _requireCloseMessage;
    private FlushState _flushState = FlushState.IDLE;
    private FillState _fillState = FillState.IDLE;
    private AtomicReference<HandshakeState> _handshake = new AtomicReference<>(HandshakeState.INITIAL);
    private boolean _underflown;

    private abstract class RunnableTask implements Runnable, Invocable
    {
        private final String _operation;

        protected RunnableTask(String op)
        {
            _operation = op;
        }

        @Override
        public String toString()
        {
            return String.format("SSL:%s:%s:%s", SslConnection.this, _operation, getInvocationType());
        }
    }

    private final Runnable _runFillable = new RunnableTask("runFillable")
    {
        @Override
        public void run()
        {
            _decryptedEndPoint.getFillInterest().fillable();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return _decryptedEndPoint.getFillInterest().getCallbackInvocationType();
        }
    };

    private final Callback _sslReadCallback = new Callback()
    {
        @Override
        public void succeeded()
        {
            onFillable();
        }

        @Override
        public void failed(final Throwable x)
        {
            onFillInterestedFailed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return getDecryptedEndPoint().getFillInterest().getCallbackInvocationType();
        }

        @Override
        public String toString()
        {
            return String.format("SSLC.NBReadCB@%x{%s}", SslConnection.this.hashCode(), SslConnection.this);
        }
    };

    public SslConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, SSLEngine sslEngine)
    {
        this(byteBufferPool, executor, endPoint, sslEngine, false, false);
    }

    public SslConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, SSLEngine sslEngine,
                         boolean useDirectBuffersForEncryption, boolean useDirectBuffersForDecryption)
    {
        // This connection does not execute calls to onFillable(), so they will be called by the selector thread.
        // onFillable() does not block and will only wakeup another thread to do the actual reading and handling.
        super(endPoint, executor);
        this._bufferPool = byteBufferPool;
        this._sslEngine = sslEngine;
        this._decryptedEndPoint = newDecryptedEndPoint();
        this._encryptedDirectBuffers = useDirectBuffersForEncryption;
        this._decryptedDirectBuffers = useDirectBuffersForDecryption;
    }

    public void addHandshakeListener(SslHandshakeListener listener)
    {
        handshakeListeners.add(listener);
    }

    public boolean removeHandshakeListener(SslHandshakeListener listener)
    {
        return handshakeListeners.remove(listener);
    }

    protected DecryptedEndPoint newDecryptedEndPoint()
    {
        return new DecryptedEndPoint();
    }

    public SSLEngine getSSLEngine()
    {
        return _sslEngine;
    }

    public DecryptedEndPoint getDecryptedEndPoint()
    {
        return _decryptedEndPoint;
    }

    public boolean isRenegotiationAllowed()
    {
        return _renegotiationAllowed;
    }

    public void setRenegotiationAllowed(boolean renegotiationAllowed)
    {
        _renegotiationAllowed = renegotiationAllowed;
    }

    /**
     * @return The number of renegotiations allowed for this connection.  When the limit
     * is 0 renegotiation will be denied. If the limit is less than 0 then no limit is applied.
     */
    public int getRenegotiationLimit()
    {
        return _renegotiationLimit;
    }

    /**
     * @param renegotiationLimit The number of renegotiations allowed for this connection.
     * When the limit is 0 renegotiation will be denied. If the limit is less than 0 then no limit is applied.
     * Default -1.
     */
    public void setRenegotiationLimit(int renegotiationLimit)
    {
        _renegotiationLimit = renegotiationLimit;
    }

    /**
     * @return whether is not required that peers send the TLS {@code close_notify} message
     * @deprecated use inverted {@link #isRequireCloseMessage()} instead
     */
    @Deprecated
    public boolean isAllowMissingCloseMessage()
    {
        return !isRequireCloseMessage();
    }

    /**
     * @param allowMissingCloseMessage whether is not required that peers send the TLS {@code close_notify} message
     * @deprecated use inverted {@link #setRequireCloseMessage(boolean)} instead
     */
    @Deprecated
    public void setAllowMissingCloseMessage(boolean allowMissingCloseMessage)
    {
        setRequireCloseMessage(!allowMissingCloseMessage);
    }

    /**
     * @return whether peers must send the TLS {@code close_notify} message
     */
    public boolean isRequireCloseMessage()
    {
        return _requireCloseMessage;
    }

    /**
     * <p>Sets whether it is required that a peer send the TLS {@code close_notify} message
     * to indicate the will to close the connection, otherwise it may be interpreted as a
     * truncation attack.</p>
     * <p>This option is only useful on clients, since typically servers cannot accept
     * connection-delimited content that may be truncated.</p>
     *
     * @param requireCloseMessage whether peers must send the TLS {@code close_notify} message
     */
    public void setRequireCloseMessage(boolean requireCloseMessage)
    {
        _requireCloseMessage = requireCloseMessage;
    }

    private boolean isHandshakeInitial()
    {
        return _handshake.get() == HandshakeState.INITIAL;
    }

    private boolean isHandshakeSucceeded()
    {
        return _handshake.get() == HandshakeState.SUCCEEDED;
    }

    private boolean isHandshakeComplete()
    {
        HandshakeState state = _handshake.get();
        return state == HandshakeState.SUCCEEDED || state == HandshakeState.FAILED;
    }

    private int getApplicationBufferSize()
    {
        return getBufferSize(SSLSession::getApplicationBufferSize);
    }

    private int getPacketBufferSize()
    {
        return getBufferSize(SSLSession::getPacketBufferSize);
    }

    private int getBufferSize(ToIntFunction<SSLSession> bufferSizeFn)
    {
        SSLSession hsSession = _sslEngine.getHandshakeSession();
        SSLSession session = _sslEngine.getSession();
        int size = bufferSizeFn.applyAsInt(session);
        if (hsSession == null || hsSession == session)
            return size;
        int hsSize = bufferSizeFn.applyAsInt(hsSession);
        return Math.max(hsSize, size);
    }

    private void acquireEncryptedInput()
    {
        if (_encryptedInput == null)
            _encryptedInput = _bufferPool.acquire(getPacketBufferSize(), _encryptedDirectBuffers);
    }

    private void acquireEncryptedOutput()
    {
        if (_encryptedOutput == null)
            _encryptedOutput = _bufferPool.acquire(getPacketBufferSize(), _encryptedDirectBuffers);
    }

    @Override
    public void onUpgradeTo(ByteBuffer buffer)
    {
        acquireEncryptedInput();
        BufferUtil.append(_encryptedInput, buffer);
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        getDecryptedEndPoint().getConnection().onOpen();
    }

    @Override
    public void onClose()
    {
        _decryptedEndPoint.getConnection().onClose();
        super.onClose();
    }

    @Override
    public void close()
    {
        getDecryptedEndPoint().getConnection().close();
    }

    @Override
    public boolean onIdleExpired()
    {
        return getDecryptedEndPoint().getConnection().onIdleExpired();
    }

    @Override
    public void onFillable()
    {
        // onFillable means that there are encrypted bytes ready to be filled.
        // however we do not fill them here on this callback, but instead wakeup
        // the decrypted readInterest and/or writeFlusher so that they will attempt
        // to do the fill and/or flush again and these calls will do the actually
        // filling.

        if (LOG.isDebugEnabled())
            LOG.debug(">c.onFillable {}", SslConnection.this);

        // We have received a close handshake, close the end point to send FIN.
        if (_decryptedEndPoint.isInputShutdown())
            _decryptedEndPoint.close();

        _decryptedEndPoint.onFillable();

        if (LOG.isDebugEnabled())
            LOG.debug("<c.onFillable {}", SslConnection.this);
    }

    @Override
    public void onFillInterestedFailed(Throwable cause)
    {
        _decryptedEndPoint.onFillableFail(cause == null ? new IOException() : cause);
    }

    protected SSLEngineResult wrap(SSLEngine sslEngine, ByteBuffer[] input, ByteBuffer output) throws SSLException
    {
        return sslEngine.wrap(input, output);
    }

    protected SSLEngineResult unwrap(SSLEngine sslEngine, ByteBuffer input, ByteBuffer output) throws SSLException
    {
        return sslEngine.unwrap(input, output);
    }

    @Override
    public String toConnectionString()
    {
        ByteBuffer b = _encryptedInput;
        int ei = b == null ? -1 : b.remaining();
        b = _encryptedOutput;
        int eo = b == null ? -1 : b.remaining();
        b = _decryptedInput;
        int di = b == null ? -1 : b.remaining();

        Connection connection = _decryptedEndPoint.getConnection();
        return String.format("%s@%x{%s,eio=%d/%d,di=%d,fill=%s,flush=%s}~>%s=>%s",
            getClass().getSimpleName(),
            hashCode(),
            _sslEngine.getHandshakeStatus(),
            ei, eo, di,
            _fillState, _flushState,
            _decryptedEndPoint.toEndPointString(),
            connection instanceof AbstractConnection ? ((AbstractConnection)connection).toConnectionString() : connection);
    }

    private void releaseEncryptedInputBuffer()
    {
        if (_encryptedInput != null && !_encryptedInput.hasRemaining())
        {
            _bufferPool.release(_encryptedInput);
            _encryptedInput = null;
        }
    }

    protected void releaseDecryptedInputBuffer()
    {
        if (_decryptedInput != null && !_decryptedInput.hasRemaining())
        {
            _bufferPool.release(_decryptedInput);
            _decryptedInput = null;
        }
    }

    private void releaseEncryptedOutputBuffer()
    {
        if (!Thread.holdsLock(_decryptedEndPoint))
            throw new IllegalStateException();
        if (_encryptedOutput != null && !_encryptedOutput.hasRemaining())
        {
            _bufferPool.release(_encryptedOutput);
            _encryptedOutput = null;
        }
    }

    protected int networkFill(ByteBuffer input) throws IOException
    {
        return getEndPoint().fill(input);
    }

    protected boolean networkFlush(ByteBuffer output) throws IOException
    {
        return getEndPoint().flush(output);
    }

    public class DecryptedEndPoint extends AbstractEndPoint
    {
        private final Callback _incompleteWriteCallback = new IncompleteWriteCallback();
        private Throwable _failure;

        public DecryptedEndPoint()
        {
            // Disable idle timeout checking: no scheduler and -1 timeout for this instance.
            super(null);
            super.setIdleTimeout(-1);
        }

        @Override
        public long getIdleTimeout()
        {
            return getEndPoint().getIdleTimeout();
        }

        @Override
        public void setIdleTimeout(long idleTimeout)
        {
            getEndPoint().setIdleTimeout(idleTimeout);
        }

        @Override
        public boolean isOpen()
        {
            return getEndPoint().isOpen();
        }

        @Override
        public InetSocketAddress getLocalAddress()
        {
            return getEndPoint().getLocalAddress();
        }

        @Override
        public InetSocketAddress getRemoteAddress()
        {
            return getEndPoint().getRemoteAddress();
        }

        @Override
        public WriteFlusher getWriteFlusher()
        {
            return super.getWriteFlusher();
        }

        protected void onFillable()
        {
            try
            {
                // If we are handshaking, then wake up any waiting write as well as it may have been blocked on the read
                boolean waitingForFill;
                synchronized (_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("onFillable {}", SslConnection.this);

                    _fillState = FillState.IDLE;
                    waitingForFill = _flushState == FlushState.WAIT_FOR_FILL;
                }

                getFillInterest().fillable();

                if (waitingForFill)
                {
                    synchronized (_decryptedEndPoint)
                    {
                        waitingForFill = _flushState == FlushState.WAIT_FOR_FILL;
                    }
                    if (waitingForFill)
                        fill(BufferUtil.EMPTY_BUFFER);
                }
            }
            catch (Throwable e)
            {
                close(e);
            }
        }

        protected void onFillableFail(Throwable failure)
        {
            // If we are handshaking, then wake up any waiting write as well as it may have been blocked on the read
            boolean fail = false;
            synchronized (_decryptedEndPoint)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("onFillableFail {}", SslConnection.this, failure);

                _fillState = FillState.IDLE;
                if (_flushState == FlushState.WAIT_FOR_FILL)
                {
                    _flushState = FlushState.IDLE;
                    fail = true;
                }
            }

            // wake up whoever is doing the fill
            getFillInterest().onFail(failure);

            // Try to complete the write
            if (fail)
            {
                if (!getWriteFlusher().onFail(failure))
                    close(failure);
            }
        }

        @Override
        public void setConnection(Connection connection)
        {
            if (connection instanceof AbstractConnection)
            {
                // This is an optimization to avoid that upper layer connections use small
                // buffers and we need to copy decrypted data rather than decrypting in place.
                AbstractConnection c = (AbstractConnection)connection;
                int appBufferSize = getApplicationBufferSize();
                if (c.getInputBufferSize() < appBufferSize)
                    c.setInputBufferSize(appBufferSize);
            }
            super.setConnection(connection);
        }

        public SslConnection getSslConnection()
        {
            return SslConnection.this;
        }

        @Override
        public int fill(ByteBuffer buffer) throws IOException
        {
            try
            {
                synchronized (_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug(">fill {}", SslConnection.this);

                    int filled = -2;
                    try
                    {
                        if (_fillState != FillState.IDLE)
                            return filled = 0;

                        // Do we already have some decrypted data?
                        if (BufferUtil.hasContent(_decryptedInput))
                            return filled = BufferUtil.append(buffer, _decryptedInput);

                        // loop filling and unwrapping until we have something
                        while (true)
                        {
                            HandshakeStatus status = _sslEngine.getHandshakeStatus();
                            if (LOG.isDebugEnabled())
                                LOG.debug("fill {}", status);
                            switch (status)
                            {
                                case NEED_UNWRAP:
                                case NOT_HANDSHAKING:
                                    break;

                                case NEED_TASK:
                                    _sslEngine.getDelegatedTask().run();
                                    continue;

                                case NEED_WRAP:
                                    if (_flushState == FlushState.IDLE && flush(BufferUtil.EMPTY_BUFFER))
                                    {
                                        Throwable failure = _failure;
                                        if (failure != null)
                                            rethrow(failure);
                                        if (_sslEngine.isInboundDone())
                                            return filled = -1;
                                        continue;
                                    }
                                    // Handle in needsFillInterest().
                                    return filled = 0;

                                default:
                                    throw new IllegalStateException("Unexpected HandshakeStatus " + status);
                            }

                            acquireEncryptedInput();

                            // can we use the passed buffer if it is big enough
                            ByteBuffer appIn;
                            int appBufferSize = getApplicationBufferSize();
                            if (_decryptedInput == null)
                            {
                                if (BufferUtil.space(buffer) > appBufferSize)
                                    appIn = buffer;
                                else
                                    appIn = _decryptedInput = _bufferPool.acquire(appBufferSize, _decryptedDirectBuffers);
                            }
                            else
                            {
                                appIn = _decryptedInput;
                                BufferUtil.compact(_encryptedInput);
                            }

                            // Let's try reading some encrypted data... even if we have some already.
                            int netFilled = networkFill(_encryptedInput);
                            if (LOG.isDebugEnabled())
                                LOG.debug("net filled={}", netFilled);

                            // Workaround for Java 11 behavior.
                            if (netFilled < 0 && isHandshakeInitial() && BufferUtil.isEmpty(_encryptedInput))
                                closeInbound();

                            if (netFilled > 0 && !isHandshakeComplete() && isOutboundDone())
                                throw new SSLHandshakeException("Closed during handshake");

                            if (_handshake.compareAndSet(HandshakeState.INITIAL, HandshakeState.HANDSHAKE))
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("fill starting handshake {}", SslConnection.this);
                            }

                            // Let's unwrap even if we have no net data because in that
                            // case we want to fall through to the handshake handling
                            int pos = BufferUtil.flipToFill(appIn);
                            SSLEngineResult unwrapResult;
                            try
                            {
                                _underflown = false;
                                unwrapResult = unwrap(_sslEngine, _encryptedInput, appIn);
                            }
                            finally
                            {
                                BufferUtil.flipToFlush(appIn, pos);
                            }
                            if (LOG.isDebugEnabled())
                                LOG.debug("unwrap net_filled={} {} encryptedBuffer={} unwrapBuffer={} appBuffer={}",
                                    netFilled,
                                    StringUtil.replace(unwrapResult.toString(), '\n', ' '),
                                    BufferUtil.toSummaryString(_encryptedInput),
                                    BufferUtil.toDetailString(appIn),
                                    BufferUtil.toDetailString(buffer));

                            SSLEngineResult.Status unwrap = unwrapResult.getStatus();

                            // Extra check on unwrapResultStatus == OK with zero bytes consumed
                            // or produced is due to an SSL client on Android (see bug #454773).
                            if (unwrap == Status.OK && unwrapResult.bytesConsumed() == 0 && unwrapResult.bytesProduced() == 0)
                                unwrap = Status.BUFFER_UNDERFLOW;

                            switch (unwrap)
                            {
                                case CLOSED:
                                    Throwable failure = _failure;
                                    if (failure != null)
                                        rethrow(failure);
                                    return filled = -1;

                                case BUFFER_UNDERFLOW:
                                    if (netFilled > 0)
                                        continue; // try filling some more
                                    _underflown = true;
                                    if (netFilled < 0 && _sslEngine.getUseClientMode())
                                    {
                                        Throwable closeFailure = closeInbound();
                                        if (_flushState == FlushState.WAIT_FOR_FILL)
                                        {
                                            Throwable handshakeFailure = new SSLHandshakeException("Abruptly closed by peer");
                                            if (closeFailure != null)
                                                handshakeFailure.initCause(closeFailure);
                                            throw handshakeFailure;
                                        }
                                        return filled = -1;
                                    }
                                    return filled = netFilled;

                                case BUFFER_OVERFLOW:
                                    // It's possible that SSLSession.applicationBufferSize has been expanded
                                    // by the SSLEngine implementation. Unwrapping a large encrypted buffer
                                    // causes BUFFER_OVERFLOW because the (old) applicationBufferSize is
                                    // too small. Release the decrypted input buffer so it will be re-acquired
                                    // with the larger capacity.
                                    // See also system property "jsse.SSLEngine.acceptLargeFragments".
                                    if (BufferUtil.isEmpty(_decryptedInput) && appBufferSize < getApplicationBufferSize())
                                    {
                                        releaseDecryptedInputBuffer();
                                        continue;
                                    }
                                    throw new IllegalStateException("Unexpected unwrap result " + unwrap);

                                case OK:
                                    if (unwrapResult.getHandshakeStatus() == HandshakeStatus.FINISHED)
                                        handshakeSucceeded();

                                    if (isRenegotiating() && !allowRenegotiate())
                                        return filled = -1;

                                    // If bytes were produced, don't bother with the handshake status;
                                    // pass the decrypted data to the application, which will perform
                                    // another call to fill() or flush().
                                    if (unwrapResult.bytesProduced() > 0)
                                    {
                                        if (appIn == buffer)
                                            return filled = unwrapResult.bytesProduced();
                                        return filled = BufferUtil.append(buffer, _decryptedInput);
                                    }

                                    break;

                                default:
                                    throw new IllegalStateException("Unexpected unwrap result " + unwrap);
                            }
                        }
                    }
                    catch (Throwable x)
                    {
                        Throwable f = handleException(x, "fill");
                        Throwable failure = handshakeFailed(f);
                        if (_flushState == FlushState.WAIT_FOR_FILL)
                        {
                            _flushState = FlushState.IDLE;
                            getExecutor().execute(() -> _decryptedEndPoint.getWriteFlusher().onFail(failure));
                        }
                        throw failure;
                    }
                    finally
                    {
                        releaseEncryptedInputBuffer();
                        releaseDecryptedInputBuffer();

                        if (_flushState == FlushState.WAIT_FOR_FILL)
                        {
                            _flushState = FlushState.IDLE;
                            getExecutor().execute(() -> _decryptedEndPoint.getWriteFlusher().completeWrite());
                        }

                        if (LOG.isDebugEnabled())
                            LOG.debug("<fill f={} uf={} {}", filled, _underflown, SslConnection.this);
                    }
                }
            }
            catch (Throwable x)
            {
                close(x);
                rethrow(x);
                // Never reached.
                throw new AssertionError();
            }
        }

        @Override
        protected void needsFillInterest()
        {
            try
            {
                boolean fillable;
                ByteBuffer write = null;
                boolean interest = false;
                synchronized (_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug(">needFillInterest s={}/{} uf={} ei={} di={} {}",
                            _flushState,
                            _fillState,
                            _underflown,
                            BufferUtil.toDetailString(_encryptedInput),
                            BufferUtil.toDetailString(_decryptedInput),
                            SslConnection.this);

                    if (_fillState != FillState.IDLE)
                        return;

                    // Fillable if we have decrypted input OR enough encrypted input.
                    fillable = BufferUtil.hasContent(_decryptedInput) || (BufferUtil.hasContent(_encryptedInput) && !_underflown);

                    HandshakeStatus status = _sslEngine.getHandshakeStatus();
                    switch (status)
                    {
                        case NEED_TASK:
                            // Pretend we are fillable
                            fillable = true;
                            break;

                        case NEED_UNWRAP:
                        case NOT_HANDSHAKING:
                            if (!fillable)
                            {
                                interest = true;
                                _fillState = FillState.INTERESTED;
                                if (_flushState == FlushState.IDLE && BufferUtil.hasContent(_encryptedOutput))
                                {
                                    _flushState = FlushState.WRITING;
                                    write = _encryptedOutput;
                                }
                            }
                            break;

                        case NEED_WRAP:
                            if (!fillable)
                            {
                                _fillState = FillState.WAIT_FOR_FLUSH;
                                if (_flushState == FlushState.IDLE)
                                {
                                    _flushState = FlushState.WRITING;
                                    write = BufferUtil.hasContent(_encryptedOutput) ? _encryptedOutput : BufferUtil.EMPTY_BUFFER;
                                }
                            }
                            break;

                        default:
                            throw new IllegalStateException("Unexpected HandshakeStatus " + status);
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("<needFillInterest s={}/{} f={} i={} w={}", _flushState, _fillState, fillable, interest, BufferUtil.toDetailString(write));
                }

                if (write != null)
                    getEndPoint().write(_incompleteWriteCallback, write);
                else if (fillable)
                    getExecutor().execute(_runFillable);
                else if (interest)
                    ensureFillInterested();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug(SslConnection.this.toString(), x);
                close(x);
                throw x;
            }
        }

        private void handshakeSucceeded() throws SSLException
        {
            if (_handshake.compareAndSet(HandshakeState.HANDSHAKE, HandshakeState.SUCCEEDED))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("handshake succeeded {} {} {}/{}", SslConnection.this,
                        _sslEngine.getUseClientMode() ? "client" : "resumed server",
                        _sslEngine.getSession().getProtocol(), _sslEngine.getSession().getCipherSuite());
                notifyHandshakeSucceeded(_sslEngine);
            }
            else if (isHandshakeSucceeded())
            {
                if (_renegotiationLimit > 0)
                    _renegotiationLimit--;
            }
        }

        private Throwable handshakeFailed(Throwable failure)
        {
            if (_handshake.compareAndSet(HandshakeState.HANDSHAKE, HandshakeState.FAILED))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("handshake failed {} {}", SslConnection.this, failure);
                if (!(failure instanceof SSLHandshakeException))
                    failure = new SSLHandshakeException(failure.getMessage()).initCause(failure);
                notifyHandshakeFailed(_sslEngine, failure);
            }
            return failure;
        }

        private void terminateInput()
        {
            try
            {
                _sslEngine.closeInbound();
            }
            catch (Throwable x)
            {
                LOG.ignore(x);
            }
        }

        private Throwable closeInbound() throws SSLException
        {
            HandshakeStatus handshakeStatus = _sslEngine.getHandshakeStatus();
            try
            {
                _sslEngine.closeInbound();
                return null;
            }
            catch (SSLException x)
            {
                if (handshakeStatus == HandshakeStatus.NOT_HANDSHAKING && isRequireCloseMessage())
                    throw x;
                LOG.ignore(x);
                return x;
            }
            catch (Throwable x)
            {
                LOG.ignore(x);
                return x;
            }
        }

        @Override
        public boolean flush(ByteBuffer... appOuts) throws IOException
        {
            try
            {
                synchronized (_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug(">flush {}", SslConnection.this);
                        int i = 0;
                        for (ByteBuffer b : appOuts)
                        {
                            LOG.debug("flush b[{}]={}", i++, BufferUtil.toDetailString(b));
                        }
                    }

                    // finish of any previous flushes
                    if (BufferUtil.hasContent(_encryptedOutput) && !networkFlush(_encryptedOutput))
                        return false;

                    boolean isEmpty = BufferUtil.isEmpty(appOuts);

                    Boolean result = null;
                    try
                    {
                        if (_flushState != FlushState.IDLE)
                            return result = false;

                        // Keep going while we can make progress or until we are done
                        while (true)
                        {
                            HandshakeStatus status = _sslEngine.getHandshakeStatus();
                            if (LOG.isDebugEnabled())
                                LOG.debug("flush {}", status);
                            switch (status)
                            {
                                case NEED_WRAP:
                                case NOT_HANDSHAKING:
                                    break;

                                case NEED_TASK:
                                    _sslEngine.getDelegatedTask().run();
                                    continue;

                                case NEED_UNWRAP:
                                    // Workaround for Java 11 behavior.
                                    if (isHandshakeInitial() && isOutboundDone())
                                        break;
                                    if (_fillState == FillState.IDLE)
                                    {
                                        int filled = fill(BufferUtil.EMPTY_BUFFER);
                                        if (_sslEngine.getHandshakeStatus() != status)
                                            continue;
                                        if (filled < 0)
                                            throw new IOException("Broken pipe");
                                    }
                                    return result = isEmpty;

                                default:
                                    throw new IllegalStateException("Unexpected HandshakeStatus " + status);
                            }

                            int packetBufferSize = getPacketBufferSize();
                            acquireEncryptedOutput();

                            if (_handshake.compareAndSet(HandshakeState.INITIAL, HandshakeState.HANDSHAKE))
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("flush starting handshake {}", SslConnection.this);
                            }

                            // We call sslEngine.wrap to try to take bytes from appOuts
                            // buffers and encrypt them into the _encryptedOutput buffer.
                            BufferUtil.compact(_encryptedOutput);
                            int pos = BufferUtil.flipToFill(_encryptedOutput);
                            SSLEngineResult wrapResult;
                            try
                            {
                                wrapResult = wrap(_sslEngine, appOuts, _encryptedOutput);
                            }
                            finally
                            {
                                BufferUtil.flipToFlush(_encryptedOutput, pos);
                            }
                            if (LOG.isDebugEnabled())
                                LOG.debug("wrap {} {} ioDone={}/{}",
                                    StringUtil.replace(wrapResult.toString(), '\n', ' '),
                                    BufferUtil.toSummaryString(_encryptedOutput),
                                    _sslEngine.isInboundDone(),
                                    _sslEngine.isOutboundDone());

                            // Was all the data consumed?
                            isEmpty = BufferUtil.isEmpty(appOuts);

                            // if we have net bytes, let's try to flush them
                            boolean flushed = true;
                            if (BufferUtil.hasContent(_encryptedOutput))
                                flushed = networkFlush(_encryptedOutput);

                            if (LOG.isDebugEnabled())
                                LOG.debug("net flushed={}, ac={}", flushed, isEmpty);

                            // Now deal with the results returned from the wrap
                            Status wrap = wrapResult.getStatus();
                            switch (wrap)
                            {
                                case CLOSED:
                                {
                                    // TODO: do we need to remember the CLOSED state or SSLEngine
                                    // TODO: will produce CLOSED again if wrap() is called again?
                                    if (!flushed)
                                        return result = false;
                                    getEndPoint().shutdownOutput();
                                    if (isEmpty)
                                        return result = true;
                                    throw new IOException("Broken pipe");
                                }

                                case BUFFER_OVERFLOW:
                                    if (!flushed)
                                        return result = false;
                                    // It's possible that SSLSession.packetBufferSize has been expanded
                                    // by the SSLEngine implementation. Wrapping a large application buffer
                                    // causes BUFFER_OVERFLOW because the (old) packetBufferSize is
                                    // too small. Release the encrypted output buffer so that it will
                                    // be re-acquired with the larger capacity.
                                    // See also system property "jsse.SSLEngine.acceptLargeFragments".
                                    if (packetBufferSize < getPacketBufferSize())
                                    {
                                        releaseEncryptedOutputBuffer();
                                        continue;
                                    }
                                    throw new IllegalStateException("Unexpected wrap result " + wrap);

                                case OK:
                                    if (wrapResult.getHandshakeStatus() == HandshakeStatus.FINISHED)
                                        handshakeSucceeded();

                                    if (isRenegotiating() && !allowRenegotiate())
                                    {
                                        getEndPoint().shutdownOutput();
                                        if (isEmpty && BufferUtil.isEmpty(_encryptedOutput))
                                            return result = true;
                                        throw new IOException("Broken pipe");
                                    }

                                    if (!flushed)
                                        return result = false;

                                    if (isEmpty)
                                    {
                                        if (wrapResult.getHandshakeStatus() != HandshakeStatus.NEED_WRAP ||
                                            wrapResult.bytesProduced() == 0)
                                            return result = true;
                                    }
                                    break;

                                default:
                                    throw new IllegalStateException("Unexpected wrap result " + wrap);
                            }

                            if (getEndPoint().isOutputShutdown())
                                return false;
                        }
                    }
                    catch (Throwable x)
                    {
                        Throwable failure = handleException(x, "flush");
                        throw handshakeFailed(failure);
                    }
                    finally
                    {
                        releaseEncryptedOutputBuffer();
                        if (LOG.isDebugEnabled())
                            LOG.debug("<flush {} {}", result, SslConnection.this);
                    }
                }
            }
            catch (Throwable x)
            {
                close(x);
                rethrow(x);
                // Never reached.
                throw new AssertionError();
            }
        }

        @Override
        protected void onIncompleteFlush()
        {
            try
            {
                boolean fillInterest = false;
                ByteBuffer write = null;
                synchronized (_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug(">onIncompleteFlush {} {}", SslConnection.this, BufferUtil.toDetailString(_encryptedOutput));

                    if (_flushState != FlushState.IDLE)
                        return;

                    while (true)
                    {
                        HandshakeStatus status = _sslEngine.getHandshakeStatus();
                        switch (status)
                        {
                            case NEED_TASK:
                            case NEED_WRAP:
                            case NOT_HANDSHAKING:
                                // write what we have or an empty buffer to reschedule a call to flush
                                write = BufferUtil.hasContent(_encryptedOutput) ? _encryptedOutput : BufferUtil.EMPTY_BUFFER;
                                _flushState = FlushState.WRITING;
                                break;

                            case NEED_UNWRAP:
                                // If we have something to write, then write it and ignore the needed unwrap for now.
                                if (BufferUtil.hasContent(_encryptedOutput))
                                {
                                    write = _encryptedOutput;
                                    _flushState = FlushState.WRITING;
                                    break;
                                }

                                if (_fillState != FillState.IDLE)
                                {
                                    // Wait for a fill that is happening anyway
                                    _flushState = FlushState.WAIT_FOR_FILL;
                                    break;
                                }

                                // Try filling ourselves
                                try
                                {
                                    int filled = fill(BufferUtil.EMPTY_BUFFER);
                                    // If this changed the status, let's try again
                                    if (_sslEngine.getHandshakeStatus() != status)
                                        continue;
                                    if (filled < 0)
                                        throw new IOException("Broken pipe");
                                }
                                catch (IOException e)
                                {
                                    LOG.debug(e);
                                    close(e);
                                    write = BufferUtil.EMPTY_BUFFER;
                                    _flushState = FlushState.WRITING;
                                    break;
                                }

                                // Make sure we are fill interested.
                                fillInterest = true;
                                _fillState = FillState.INTERESTED;
                                _flushState = FlushState.WAIT_FOR_FILL;
                                break;

                            default:
                                throw new IllegalStateException("Unexpected HandshakeStatus " + status);
                        }
                        break;
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("<onIncompleteFlush s={}/{} fi={} w={}", _flushState, _fillState, fillInterest, BufferUtil.toDetailString(write));
                }

                if (write != null)
                    getEndPoint().write(_incompleteWriteCallback, write);
                else if (fillInterest)
                    ensureFillInterested();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug(SslConnection.this.toString(), x);
                close(x);
                throw x;
            }
        }

        @Override
        public void doShutdownOutput()
        {
            EndPoint endPoint = getEndPoint();
            try
            {
                boolean close;
                boolean flush = false;
                synchronized (_decryptedEndPoint)
                {
                    boolean ishut = endPoint.isInputShutdown();
                    boolean oshut = endPoint.isOutputShutdown();
                    if (LOG.isDebugEnabled())
                        LOG.debug("shutdownOutput: {} oshut={}, ishut={}", SslConnection.this, oshut, ishut);

                    closeOutbound();

                    if (!_closedOutbound)
                    {
                        _closedOutbound = true;
                        // Flush only once.
                        flush = !oshut;
                    }

                    close = ishut;
                }

                if (flush)
                {
                    if (!flush(BufferUtil.EMPTY_BUFFER) && !close)
                    {
                        // If we still can't flush, but we are not closing the endpoint,
                        // let's just flush the encrypted output in the background.
                        ByteBuffer write = null;
                        synchronized (_decryptedEndPoint)
                        {
                            if (BufferUtil.hasContent(_encryptedOutput))
                            {
                                write = _encryptedOutput;
                                _flushState = FlushState.WRITING;
                            }
                        }
                        if (write != null)
                        {
                            endPoint.write(Callback.from(() ->
                            {
                                synchronized (_decryptedEndPoint)
                                {
                                    _flushState = FlushState.IDLE;
                                    releaseEncryptedOutputBuffer();
                                }
                            }, t -> endPoint.close()), write);
                        }
                    }
                }

                if (close)
                    endPoint.close();
                else
                    ensureFillInterested();
            }
            catch (Throwable x)
            {
                LOG.ignore(x);
                endPoint.close();
            }
        }

        private void closeOutbound()
        {
            try
            {
                _sslEngine.closeOutbound();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug(x);
            }
        }

        private void ensureFillInterested()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ensureFillInterested {}", SslConnection.this);
            SslConnection.this.tryFillInterested(_sslReadCallback);
        }

        @Override
        public boolean isOutputShutdown()
        {
            return isOutboundDone() || getEndPoint().isOutputShutdown();
        }

        private boolean isOutboundDone()
        {
            try
            {
                return _sslEngine.isOutboundDone();
            }
            catch (Throwable x)
            {
                LOG.ignore(x);
                return true;
            }
        }

        @Override
        public void doClose()
        {
            // First send the TLS Close Alert, then the FIN.
            doShutdownOutput();
            getEndPoint().close();
            super.doClose();
        }

        @Override
        public Object getTransport()
        {
            return getEndPoint();
        }

        @Override
        public boolean isInputShutdown()
        {
            return BufferUtil.isEmpty(_decryptedInput) && (getEndPoint().isInputShutdown() || isInboundDone());
        }

        private boolean isInboundDone()
        {
            try
            {
                return _sslEngine.isInboundDone();
            }
            catch (Throwable x)
            {
                LOG.ignore(x);
                return true;
            }
        }

        private void notifyHandshakeSucceeded(SSLEngine sslEngine) throws SSLException
        {
            SslHandshakeListener.Event event = null;
            for (SslHandshakeListener listener : handshakeListeners)
            {
                if (event == null)
                    event = new SslHandshakeListener.Event(sslEngine);
                try
                {
                    listener.handshakeSucceeded(event);
                }
                catch (SSLException x)
                {
                    throw x;
                }
                catch (Throwable x)
                {
                    LOG.info("Exception while notifying listener " + listener, x);
                }
            }
        }

        private void notifyHandshakeFailed(SSLEngine sslEngine, Throwable failure)
        {
            SslHandshakeListener.Event event = null;
            for (SslHandshakeListener listener : handshakeListeners)
            {
                if (event == null)
                    event = new SslHandshakeListener.Event(sslEngine);
                try
                {
                    listener.handshakeFailed(event, failure);
                }
                catch (Throwable x)
                {
                    LOG.info("Exception while notifying listener " + listener, x);
                }
            }
        }

        private boolean isRenegotiating()
        {
            if (!isHandshakeComplete())
                return false;
            if (isTLS13())
                return false;
            return _sslEngine.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING;
        }

        private boolean allowRenegotiate()
        {
            if (!isRenegotiationAllowed())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Renegotiation denied {}", SslConnection.this);
                terminateInput();
                return false;
            }

            if (getRenegotiationLimit() == 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Renegotiation limit exceeded {}", SslConnection.this);
                terminateInput();
                return false;
            }

            return true;
        }

        private boolean isTLS13()
        {
            String protocol = _sslEngine.getSession().getProtocol();
            return TLS_1_3.equals(protocol);
        }

        private Throwable handleException(Throwable x, String context)
        {
            synchronized (_decryptedEndPoint)
            {
                if (_failure == null)
                {
                    _failure = x;
                    if (LOG.isDebugEnabled())
                        LOG.debug(this + " stored " + context + " exception", x);
                }
                else if (x != _failure)
                {
                    _failure.addSuppressed(x);
                    if (LOG.isDebugEnabled())
                        LOG.debug(this + " suppressed " + context + " exception", x);
                }
                return _failure;
            }
        }

        private void rethrow(Throwable x) throws IOException
        {
            if (x instanceof RuntimeException)
                throw (RuntimeException)x;
            if (x instanceof Error)
                throw (Error)x;
            if (x instanceof IOException)
                throw (IOException)x;
            throw new IOException(x);
        }

        @Override
        public String toString()
        {
            return super.toEndPointString();
        }

        private final class IncompleteWriteCallback implements Callback, Invocable
        {
            @Override
            public void succeeded()
            {
                boolean fillable;
                boolean interested;
                synchronized (_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("IncompleteWriteCB succeeded {}", SslConnection.this);
                    releaseEncryptedOutputBuffer();
                    _flushState = FlushState.IDLE;

                    interested = _fillState == FillState.INTERESTED;
                    fillable = _fillState == FillState.WAIT_FOR_FLUSH;
                    if (fillable)
                        _fillState = FillState.IDLE;
                }

                if (interested)
                    ensureFillInterested();
                else if (fillable)
                    _decryptedEndPoint.getFillInterest().fillable();

                _decryptedEndPoint.getWriteFlusher().completeWrite();
            }

            @Override
            public void failed(final Throwable x)
            {
                boolean failFillInterest;
                synchronized (_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("IncompleteWriteCB failed {}", SslConnection.this, x);

                    BufferUtil.clear(_encryptedOutput);
                    releaseEncryptedOutputBuffer();

                    _flushState = FlushState.IDLE;
                    failFillInterest = _fillState == FillState.WAIT_FOR_FLUSH ||
                        _fillState == FillState.INTERESTED;
                    if (failFillInterest)
                        _fillState = FillState.IDLE;
                }

                getExecutor().execute(() ->
                {
                    if (failFillInterest)
                        _decryptedEndPoint.getFillInterest().onFail(x);
                    _decryptedEndPoint.getWriteFlusher().onFail(x);
                });
            }

            @Override
            public InvocationType getInvocationType()
            {
                return _decryptedEndPoint.getWriteFlusher().getCallbackInvocationType();
            }

            @Override
            public String toString()
            {
                return String.format("SSL@%h.DEP.writeCallback", SslConnection.this);
            }
        }
    }
}
