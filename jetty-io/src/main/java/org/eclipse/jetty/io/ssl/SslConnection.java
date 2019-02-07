//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
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
 *
 */
public class SslConnection extends AbstractConnection implements Connection.UpgradeTo
{
    private static final Logger LOG = Log.getLogger(SslConnection.class);
    private static final String TLS_1_3 = "TLSv1.3";

    private enum Handshake
    {
        INITIAL,
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
    private boolean _allowMissingCloseMessage = true;
    private FlushState _flushState = FlushState.IDLE;
    private FillState _fillState = FillState.IDLE;
    private AtomicReference<Handshake> _handshake = new AtomicReference<>(Handshake.INITIAL);
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
            return getDecryptedEndPoint().getFillInterest().getCallbackInvocationType();
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
     * @return The number of renegotions allowed for this connection.  When the limit
     * is 0 renegotiation will be denied. If the limit is less than 0 then no limit is applied.
     */
    public int getRenegotiationLimit()
    {
        return _renegotiationLimit;
    }

    /**
     * @param renegotiationLimit The number of renegotions allowed for this connection.
     *                           When the limit is 0 renegotiation will be denied. If the limit is less than 0 then no limit is applied.
     *                           Default -1.
     */
    public void setRenegotiationLimit(int renegotiationLimit)
    {
        _renegotiationLimit = renegotiationLimit;
    }

    public boolean isAllowMissingCloseMessage()
    {
        return _allowMissingCloseMessage;
    }

    public void setAllowMissingCloseMessage(boolean allowMissingCloseMessage)
    {
        this._allowMissingCloseMessage = allowMissingCloseMessage;
    }

    private void acquireEncryptedInput()
    {
        if (_encryptedInput == null)
            _encryptedInput = _bufferPool.acquire(_sslEngine.getSession().getPacketBufferSize(), _encryptedDirectBuffers);
    }

    @Override
    public void onUpgradeTo(ByteBuffer buffer)
    {
        if (BufferUtil.hasContent(buffer))
        {
            acquireEncryptedInput();
            BufferUtil.append(_encryptedInput, buffer);
        }
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

    public class DecryptedEndPoint extends AbstractEndPoint
    {
        private final Callback _incompleteWriteCallback = new IncompleteWriteCallback();

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
        protected WriteFlusher getWriteFlusher()
        {
            return super.getWriteFlusher();
        }

        protected void onFillable()
        {
            try
            {
                // If we are handshaking, then wake up any waiting write as well as it may have been blocked on the read
                boolean waiting_for_fill;
                synchronized (_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("onFillable {}", SslConnection.this);

                    _fillState = FillState.IDLE;
                    waiting_for_fill = _flushState == FlushState.WAIT_FOR_FILL;
                }

                getFillInterest().fillable();

                if (waiting_for_fill)
                {
                    synchronized (_decryptedEndPoint)
                    {
                        waiting_for_fill = _flushState == FlushState.WAIT_FOR_FILL;
                    }
                    if (waiting_for_fill)
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
                switch (_flushState)
                {
                    case WAIT_FOR_FILL:
                        _flushState = FlushState.IDLE;
                        fail = true;
                        break;
                    default:
                        break;
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
                AbstractConnection a = (AbstractConnection)connection;
                if (a.getInputBufferSize() < _sslEngine.getSession().getApplicationBufferSize())
                    a.setInputBufferSize(_sslEngine.getSession().getApplicationBufferSize());
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
                                        if (_sslEngine.isInboundDone())
                                            // TODO this is probably a JVM bug, work around it by -1
                                            return -1;
                                        continue;
                                    }
                                    // handle in needsFillInterest
                                    return filled = 0;

                                default:
                                    throw new IllegalStateException("Unexpected HandshakeStatus " + status);
                            }

                            acquireEncryptedInput();

                            // can we use the passed buffer if it is big enough
                            ByteBuffer app_in;
                            if (_decryptedInput == null)
                            {
                                if (BufferUtil.space(buffer) > _sslEngine.getSession().getApplicationBufferSize())
                                    app_in = buffer;
                                else
                                    app_in = _decryptedInput = _bufferPool.acquire(_sslEngine.getSession().getApplicationBufferSize(), _decryptedDirectBuffers);
                            }
                            else
                            {
                                app_in = _decryptedInput;
                                BufferUtil.compact(_encryptedInput);
                            }

                            // Let's try reading some encrypted data... even if we have some already.
                            int net_filled = getEndPoint().fill(_encryptedInput);

                            if (LOG.isDebugEnabled())
                                LOG.debug("net filled={}", net_filled);

                            if (net_filled > 0 && _handshake.get() == Handshake.INITIAL && isOutboundDone())
                                throw new SSLHandshakeException("Closed during handshake");

                            // Let's unwrap even if we have no net data because in that
                            // case we want to fall through to the handshake handling
                            int pos = BufferUtil.flipToFill(app_in);
                            SSLEngineResult unwrapResult;
                            try
                            {
                                _underflown = false;
                                unwrapResult = _sslEngine.unwrap(_encryptedInput, app_in);
                            }
                            finally
                            {
                                BufferUtil.flipToFlush(app_in, pos);
                            }
                            if (LOG.isDebugEnabled())
                                LOG.debug("unwrap net_filled={} {} encryptedBuffer={} unwrapBuffer={} appBuffer={}",
                                        net_filled,
                                        unwrapResult.toString().replace('\n', ' '),
                                        BufferUtil.toSummaryString(_encryptedInput),
                                        BufferUtil.toDetailString(app_in),
                                        BufferUtil.toDetailString(buffer));

                            SSLEngineResult.Status unwrap = unwrapResult.getStatus();

                            // Extra check on unwrapResultStatus == OK with zero bytes consumed
                            // or produced is due to an SSL client on Android (see bug #454773).
                            if (unwrap == Status.OK && unwrapResult.bytesConsumed() == 0 && unwrapResult.bytesProduced() == 0)
                                unwrap = Status.BUFFER_UNDERFLOW;

                            switch (unwrap)
                            {
                                case CLOSED:
                                    return filled = -1;

                                case BUFFER_UNDERFLOW:
                                    if (net_filled > 0)
                                        continue; // try filling some more
                                    _underflown = true;
                                    if (net_filled < 0 && _sslEngine.getUseClientMode())
                                    {
                                        closeInbound();
                                        return filled = -1;
                                    }
                                    return filled = net_filled;

                                case OK:
                                {
                                    if (unwrapResult.getHandshakeStatus() == HandshakeStatus.FINISHED)
                                        handshakeSucceeded();

                                    if (isRenegotiating() && !allowRenegotiate())
                                        return filled = -1;

                                    // If bytes were produced, don't bother with the handshake status;
                                    // pass the decrypted data to the application, which will perform
                                    // another call to fill() or flush().
                                    if (unwrapResult.bytesProduced() > 0)
                                    {
                                        if (app_in == buffer)
                                            return filled = unwrapResult.bytesProduced();
                                        return filled = BufferUtil.append(buffer, _decryptedInput);
                                    }

                                    break;
                                }

                                default:
                                    throw new IllegalStateException("Unexpected unwrap result " + unwrap);
                            }
                        }
                    }
                    catch (Throwable x)
                    {
                        handshakeFailed(x);

                        if (_flushState == FlushState.WAIT_FOR_FILL)
                        {
                            _flushState = FlushState.IDLE;
                            getExecutor().execute(() -> _decryptedEndPoint.getWriteFlusher().onFail(x));
                        }

                        throw x;
                    }
                    finally
                    {
                        if (_encryptedInput != null && !_encryptedInput.hasRemaining())
                        {
                            _bufferPool.release(_encryptedInput);
                            _encryptedInput = null;
                        }

                        if (_decryptedInput != null && !_decryptedInput.hasRemaining())
                        {
                            _bufferPool.release(_decryptedInput);
                            _decryptedInput = null;
                        }

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
                if (LOG.isDebugEnabled())
                    LOG.debug(SslConnection.this.toString(), x);
                close(x);
                throw x;
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
                    {
                        LOG.debug(">needFillInterest uf={} {}", _underflown, SslConnection.this);
                        LOG.debug("ei={} di={}", BufferUtil.toDetailString(_encryptedInput), BufferUtil.toDetailString(_decryptedInput));
                    }

                    if (_fillState != FillState.IDLE)
                        return;

                    // Fillable if we have decrypted Input OR encrypted input that has not yet been underflown.
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
            if (_handshake.compareAndSet(Handshake.INITIAL, Handshake.SUCCEEDED))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("handshake succeeded {} {} {}/{}", SslConnection.this,
                            _sslEngine.getUseClientMode() ? "client" : "resumed server",
                            _sslEngine.getSession().getProtocol(), _sslEngine.getSession().getCipherSuite());
                notifyHandshakeSucceeded(_sslEngine);
            }
            else if (_handshake.get() == Handshake.SUCCEEDED)
            {
                if (_renegotiationLimit > 0)
                    _renegotiationLimit--;
            }
        }

        private void handshakeFailed(Throwable failure)
        {
            if (_handshake.compareAndSet(Handshake.INITIAL, Handshake.FAILED))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("handshake failed {} {}", SslConnection.this, failure);
                if (!(failure instanceof SSLHandshakeException))
                    failure = new SSLHandshakeException(failure.getMessage()).initCause(failure);
                notifyHandshakeFailed(_sslEngine, failure);
            }
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

        private void closeInbound() throws SSLException
        {
            HandshakeStatus handshakeStatus = _sslEngine.getHandshakeStatus();
            try
            {
                _sslEngine.closeInbound();
            }
            catch (SSLException x)
            {
                if (handshakeStatus == HandshakeStatus.NOT_HANDSHAKING && !isAllowMissingCloseMessage())
                    throw x;
                else
                    LOG.ignore(x);
            }
            catch (Throwable x)
            {
                LOG.ignore(x);
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
                            LOG.debug("flush b[{}]={}", i++, BufferUtil.toDetailString(b));
                    }

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
                                    if (_fillState == FillState.IDLE)
                                    {
                                        int filled = fill(BufferUtil.EMPTY_BUFFER);
                                        if (_sslEngine.getHandshakeStatus() != status)
                                            continue;
                                        if (filled < 0)
                                            throw new IOException("Broken pipe");
                                    }
                                    return result = false;

                                default:
                                    throw new IllegalStateException("Unexpected HandshakeStatus " + status);
                            }

                            if (_encryptedOutput == null)
                                _encryptedOutput = _bufferPool.acquire(_sslEngine.getSession().getPacketBufferSize(), _encryptedDirectBuffers);

                            // We call sslEngine.wrap to try to take bytes from appOut buffers and encrypt them into the _netOut buffer
                            BufferUtil.compact(_encryptedOutput);
                            int pos = BufferUtil.flipToFill(_encryptedOutput);
                            SSLEngineResult wrapResult;
                            try
                            {
                                wrapResult = _sslEngine.wrap(appOuts, _encryptedOutput);
                            }
                            finally
                            {
                                BufferUtil.flipToFlush(_encryptedOutput, pos);
                            }
                            if (LOG.isDebugEnabled())
                                LOG.debug("wrap {} {} ioDone={}/{}",
                                        wrapResult.toString().replace('\n', ' '),
                                        BufferUtil.toSummaryString(_encryptedOutput),
                                        _sslEngine.isInboundDone(),
                                        _sslEngine.isOutboundDone());

                            // Was all the data consumed?
                            boolean allConsumed = true;
                            for (ByteBuffer b : appOuts)
                                if (BufferUtil.hasContent(b))
                                    allConsumed = false;

                            // if we have net bytes, let's try to flush them
                            boolean flushed = true;
                            if (BufferUtil.hasContent(_encryptedOutput))
                                flushed = getEndPoint().flush(_encryptedOutput);

                            if (LOG.isDebugEnabled())
                                LOG.debug("net flushed={}, ac={}", flushed, allConsumed);

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
                                    if (allConsumed)
                                        return result = true;
                                    throw new IOException("Broken pipe");
                                }

                                case BUFFER_OVERFLOW:
                                    if (!flushed)
                                        return result = false;
                                    continue;

                                case OK:
                                    if (wrapResult.getHandshakeStatus() == HandshakeStatus.FINISHED)
                                        handshakeSucceeded();

                                    if (isRenegotiating() && !allowRenegotiate())
                                    {
                                        getEndPoint().shutdownOutput();
                                        if (allConsumed && BufferUtil.isEmpty(_encryptedOutput))
                                            return result = true;
                                        throw new IOException("Broken pipe");
                                    }

                                    if (!flushed)
                                        return result = false;
                                    if (allConsumed)
                                        return result = true;
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
                        handshakeFailed(x);
                        throw x;
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
                if (LOG.isDebugEnabled())
                    LOG.debug(SslConnection.this.toString(), x);
                close(x);
                throw x;
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
            try
            {
                boolean close;
                boolean flush = false;
                synchronized (_decryptedEndPoint)
                {
                    boolean ishut = getEndPoint().isInputShutdown();
                    boolean oshut = getEndPoint().isOutputShutdown();
                    if (LOG.isDebugEnabled())
                        LOG.debug("shutdownOutput: {} oshut={}, ishut={} {}", SslConnection.this, oshut, ishut);

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
                    flush(BufferUtil.EMPTY_BUFFER); // Send the TLS close message.
                if (close)
                    getEndPoint().close();
                else
                    ensureFillInterested();
            }
            catch (Throwable x)
            {
                LOG.ignore(x);
                getEndPoint().close();
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
                LOG.ignore(x);
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
            if (_handshake.get() == Handshake.INITIAL)
                return false;
            if (isTLS13())
                return false;
            if (_sslEngine.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING)
                return false;
            return true;
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
                synchronized (_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("IncompleteWriteCB succeeded {}", SslConnection.this);

                    releaseEncryptedOutputBuffer();
                    _flushState = FlushState.IDLE;
                    fillable = _fillState == FillState.WAIT_FOR_FLUSH;
                    if (fillable)
                        _fillState = FillState.IDLE;
                }

                if (fillable)
                    _decryptedEndPoint.getFillInterest().fillable();

                _decryptedEndPoint.getWriteFlusher().completeWrite();
            }

            @Override
            public void failed(final Throwable x)
            {
                boolean fail_fill_interest;
                synchronized (_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("IncompleteWriteCB failed {}", SslConnection.this, x);

                    BufferUtil.clear(_encryptedOutput);
                    releaseEncryptedOutputBuffer();

                    _flushState = FlushState.IDLE;
                    fail_fill_interest = _fillState == FillState.WAIT_FOR_FLUSH;
                    if (fail_fill_interest)
                        _fillState = FillState.IDLE;
                }

                getExecutor().execute(() ->
                {
                    if (fail_fill_interest)
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
