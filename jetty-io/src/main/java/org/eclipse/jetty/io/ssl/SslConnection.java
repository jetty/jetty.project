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
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
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
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * A Connection that acts as an interceptor between an EndPoint providing SSL encrypted data
 * and another consumer of an EndPoint (typically an {@link Connection} like HttpConnection) that
 * wants unencrypted data.
 * <p>
 * The connector uses an {@link EndPoint} (typically {@link SelectChannelEndPoint}) as
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
public class SslConnection extends AbstractConnection
{
    private static final Logger LOG = Log.getLogger(SslConnection.class);

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
    private final Runnable _runCompletWrite = new Runnable()
    {
        @Override
        public void run()
        {
            _decryptedEndPoint.getWriteFlusher().completeWrite();
        }
    };
    private final Runnable _runFillable = new Runnable()
    {
        @Override
        public void run()
        {
            _decryptedEndPoint.getFillInterest().fillable();
        }
    };
    private final Callback _nonBlockingReadCallback = new Callback.NonBlocking()
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
        public String toString()
        {
            return String.format("SSLC.NBReadCB@%x{%s}", SslConnection.this.hashCode(),SslConnection.this);
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
     * When the limit is 0 renegotiation will be denied. If the limit is less than 0 then no limit is applied.
     * Default -1.
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
            LOG.debug("onFillable enter {}", _decryptedEndPoint);

        // We have received a close handshake, close the end point to send FIN.
        if (_decryptedEndPoint.isInputShutdown())
            _decryptedEndPoint.close();

        // wake up whoever is doing the fill or the flush so they can
        // do all the filling, unwrapping, wrapping and flushing
        _decryptedEndPoint.getFillInterest().fillable();

        // If we are handshaking, then wake up any waiting write as well as it may have been blocked on the read
        boolean runComplete = false;
        synchronized(_decryptedEndPoint)
        {
            if (_decryptedEndPoint._flushRequiresFillToProgress)
            {
                _decryptedEndPoint._flushRequiresFillToProgress = false;
                runComplete = true;
            }
        }
        if (runComplete)
            _runCompletWrite.run();

        if (LOG.isDebugEnabled())
            LOG.debug("onFillable exit {}", _decryptedEndPoint);
    }

    @Override
    public void onFillInterestedFailed(Throwable cause)
    {
        // this means that the fill interest in encrypted bytes has failed.
        // However we do not handle that here on this callback, but instead wakeup
        // the decrypted readInterest and/or writeFlusher so that they will attempt
        // to do the fill and/or flush again and these calls will do the actually
        // handle the cause.
        _decryptedEndPoint.getFillInterest().onFail(cause);

        boolean failFlusher = false;
        synchronized(_decryptedEndPoint)
        {
            if (_decryptedEndPoint._flushRequiresFillToProgress)
            {
                _decryptedEndPoint._flushRequiresFillToProgress = false;
                failFlusher = true;
            }
        }
        if (failFlusher)
            _decryptedEndPoint.getWriteFlusher().onFail(cause);
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
    public String toString()
    {
        ByteBuffer b = _encryptedInput;
        int ei=b==null?-1:b.remaining();
        b = _encryptedOutput;
        int eo=b==null?-1:b.remaining();
        b = _decryptedInput;
        int di=b==null?-1:b.remaining();

        return String.format("SslConnection@%x{%s,eio=%d/%d,di=%d} -> %s",
                hashCode(),
                _sslEngine.getHandshakeStatus(),
                ei,eo,di,
                _decryptedEndPoint.getConnection());
    }

    public class DecryptedEndPoint extends AbstractEndPoint
    {
        private boolean _fillRequiresFlushToProgress;
        private boolean _flushRequiresFillToProgress;
        private boolean _cannotAcceptMoreAppDataToFlush;
        private boolean _handshaken;
        private boolean _underFlown;

        private final Callback _writeCallback = new Callback()
        {
            @Override
            public void succeeded()
            {
                // This means that a write of encrypted data has completed.  Writes are done
                // only if there is a pending writeflusher or a read needed to write
                // data.  In either case the appropriate callback is passed on.
                boolean fillable = false;
                synchronized (DecryptedEndPoint.this)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("write.complete {}", SslConnection.this.getEndPoint());

                    releaseEncryptedOutputBuffer();

                    _cannotAcceptMoreAppDataToFlush = false;

                    if (_fillRequiresFlushToProgress)
                    {
                        _fillRequiresFlushToProgress = false;
                        fillable = true;
                    }
                }
                if (fillable)
                    getFillInterest().fillable();
                _runCompletWrite.run();
            }

            @Override
            public void failed(final Throwable x)
            {
                // This means that a write of data has failed.  Writes are done
                // only if there is an active writeflusher or a read needed to write
                // data.  In either case the appropriate callback is passed on.
                boolean fail_filler;
                synchronized (DecryptedEndPoint.this)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("write failed {}", SslConnection.this, x);

                    BufferUtil.clear(_encryptedOutput);
                    releaseEncryptedOutputBuffer();

                    _cannotAcceptMoreAppDataToFlush = false;
                    fail_filler = _fillRequiresFlushToProgress;
                    if (_fillRequiresFlushToProgress)
                        _fillRequiresFlushToProgress = false;
                }

                failedCallback(new Callback()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        if (fail_filler)
                            getFillInterest().onFail(x);
                        getWriteFlusher().onFail(x);
                    }
                }, x);
            }

            @Override
            public boolean isNonBlocking()
            {
                return getWriteFlusher().isCallbackNonBlocking();
            }
        };

        public DecryptedEndPoint()
        {
            // Disable idle timeout checking: no scheduler and -1 timeout for this instance.
            super(null, getEndPoint().getLocalAddress(), getEndPoint().getRemoteAddress());
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
        protected WriteFlusher getWriteFlusher()
        {
            return super.getWriteFlusher();
        }

        @Override
        protected void onIncompleteFlush()
        {
            // This means that the decrypted endpoint write method was called and not
            // all data could be wrapped. So either we need to write some encrypted data,
            // OR if we are handshaking we need to read some encrypted data OR
            // if neither then we should just try the flush again.
            boolean try_again = false;
            boolean write = false;
            boolean need_fill_interest = false;
            synchronized (DecryptedEndPoint.this)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("onIncompleteFlush {}", SslConnection.this);
                // If we have pending output data,
                if (BufferUtil.hasContent(_encryptedOutput))
                {
                    // write it
                    _cannotAcceptMoreAppDataToFlush = true;
                    write = true;
                }
                // If we are handshaking and need to read,
                else if (_sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP)
                {
                    // check if we are actually read blocked in order to write
                    _flushRequiresFillToProgress = true; 
                    need_fill_interest = !SslConnection.this.isFillInterested();
                }
                else
                {
                    // We can get here because the WriteFlusher might not see progress
                    // when it has just flushed the encrypted data, but not consumed anymore
                    // of the application buffers.  This is mostly avoided by another iteration
                    // within DecryptedEndPoint flush(), but I cannot convince myself that
                    // this is never ever the case.
                    try_again = true;
                }
            }

            if (write)
                getEndPoint().write(_writeCallback, _encryptedOutput);                
            else if (need_fill_interest)
                ensureFillInterested();
            else if (try_again)
            {
                // If the output is closed,
                if (isOutputShutdown())
                {
                    // don't bother writing, just notify of close
                    getWriteFlusher().onClose();
                }
                // Else,
                else
                {
                    // try to flush what is pending
                    // execute to avoid recursion
                    getExecutor().execute(_runCompletWrite);
                }
            }
        }

        @Override
        protected void needsFillInterest() throws IOException
        {
            // This means that the decrypted data consumer has called the fillInterested
            // method on the DecryptedEndPoint, so we have to work out if there is
            // decrypted data to be filled or what callbacks to setup to be told when there
            // might be more encrypted data available to attempt another call to fill
            boolean fillable;
            boolean write = false;
            synchronized (DecryptedEndPoint.this)
            {
                // Do we already have some app data, then app can fill now so return true
                fillable = (BufferUtil.hasContent(_decryptedInput))
                        // or if we have encryptedInput and have not underflowed yet, the it is worth trying a fill
                        || BufferUtil.hasContent(_encryptedInput) && !_underFlown;

                // If we have no encrypted data to decrypt OR we have some, but it is not enough
                if (!fillable)
                {
                    // We are not ready to read data

                    // Are we actually write blocked?
                    if (_fillRequiresFlushToProgress)
                    {
                        // we must be blocked trying to write before we can read

                        // Do we have data to write
                        if (BufferUtil.hasContent(_encryptedOutput))
                        {
                            // write it
                            _cannotAcceptMoreAppDataToFlush = true;
                            write = true;
                        }
                        else
                        {
                            // we have already written the net data
                            // pretend we are readable so the wrap is done by next readable callback
                            _fillRequiresFlushToProgress = false;
                            fillable=true;
                        }
                    }
                }
            }
            if (write)
                getEndPoint().write(_writeCallback, _encryptedOutput);
            else if (fillable)
                getExecutor().execute(_runFillable);
            else 
                ensureFillInterested();
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
                synchronized (this)
                {
                    Throwable failure = null;
                    try
                    {
                        // Do we already have some decrypted data?
                        if (BufferUtil.hasContent(_decryptedInput))
                            return BufferUtil.append(buffer,_decryptedInput);

                        // We will need a network buffer
                        if (_encryptedInput == null)
                            _encryptedInput = _bufferPool.acquire(_sslEngine.getSession().getPacketBufferSize(), _encryptedDirectBuffers);
                        else
                            BufferUtil.compact(_encryptedInput);


                        // loop filling and unwrapping until we have something
                        while (true)
                        {
                            // We also need an app buffer, but can use the passed buffer if it is big enough
                            ByteBuffer app_in;
                            int appBufferSize = getApplicationBufferSize();

                            if (BufferUtil.space(buffer) > appBufferSize)
                                app_in = buffer;
                            else if (_decryptedInput == null)
                                app_in = _decryptedInput = _bufferPool.acquire(appBufferSize, _decryptedDirectBuffers);
                            else
                                app_in = _decryptedInput;

                            acquireEncryptedInput();

                            // Let's try reading some encrypted data... even if we have some already.
                            int net_filled = getEndPoint().fill(_encryptedInput);

                            if (net_filled > 0 && !_handshaken && isOutboundDone())
                                throw new SSLHandshakeException("Closed during handshake");

                            decryption: while (true)
                            {
                                // Let's unwrap even if we have no net data because in that
                                // case we want to fall through to the handshake handling
                                int pos = BufferUtil.flipToFill(app_in);
                                SSLEngineResult unwrapResult;
                                try
                                {
                                    unwrapResult = unwrap(_sslEngine, _encryptedInput, app_in);
                                }
                                finally
                                {
                                    BufferUtil.flipToFlush(app_in, pos);
                                }

                                if (LOG.isDebugEnabled())
                                    LOG.debug("unwrap net_filled={} {} encryptedBuffer={} unwrapBuffer={} appBuffer={}",
                                        net_filled,
                                        unwrapResult.toString().replace('\n',' '),
                                        BufferUtil.toSummaryString(_encryptedInput),
                                        BufferUtil.toDetailString(app_in),
                                        BufferUtil.toDetailString(buffer));

                                HandshakeStatus handshakeStatus = _sslEngine.getHandshakeStatus();
                                HandshakeStatus unwrapHandshakeStatus = unwrapResult.getHandshakeStatus();
                                Status unwrapResultStatus = unwrapResult.getStatus();

                                // Extra check on unwrapResultStatus == OK with zero bytes consumed
                                // or produced is due to an SSL client on Android (see bug #454773).
                                _underFlown = unwrapResultStatus == Status.BUFFER_UNDERFLOW ||
                                        unwrapResultStatus == Status.OK && unwrapResult.bytesConsumed() == 0 && unwrapResult.bytesProduced() == 0;

                                if (_underFlown)
                                {
                                    if (net_filled < 0 && _sslEngine.getUseClientMode())
                                        closeInbound();
                                    if (net_filled <= 0)
                                        return net_filled;
                                }

                                switch (unwrapResultStatus)
                                {
                                    case CLOSED:
                                    {
                                        switch (handshakeStatus)
                                        {
                                            case NOT_HANDSHAKING:
                                                // We were not handshaking, so just tell the app we are closed
                                                return -1;
                                            case NEED_TASK:
                                                _sslEngine.getDelegatedTask().run();
                                                continue;
                                            case NEED_WRAP:
                                                // We need to send some handshake data (probably the close handshake).
                                                // We return -1 so that the application can drive the close by flushing
                                                // or shutting down the output.
                                                return -1;
                                            case NEED_UNWRAP:
                                                // We expected to read more, but we got closed.
                                                // Return -1 to indicate to the application to drive the close.
                                                return -1;
                                            default:
                                                throw new IllegalStateException();
                                        }
                                    }
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
                                            break decryption;
                                        }
                                        throw new IllegalStateException("Unexpected unwrap result " + unwrapResultStatus);

                                    case BUFFER_UNDERFLOW:
                                    case OK:
                                    {
                                        if (unwrapHandshakeStatus == HandshakeStatus.FINISHED)
                                            handshakeFinished();

                                        // Check whether re-negotiation is allowed
                                        if (!allowRenegotiate(handshakeStatus))
                                            return -1;

                                        // If bytes were produced, don't bother with the handshake status;
                                        // pass the decrypted data to the application, which will perform
                                        // another call to fill() or flush().
                                        if (unwrapResult.bytesProduced() > 0)
                                        {
                                            if (app_in == buffer)
                                                return unwrapResult.bytesProduced();
                                            return BufferUtil.append(buffer,_decryptedInput);
                                        }

                                        switch (handshakeStatus)
                                        {
                                            case NOT_HANDSHAKING:
                                            {
                                                if (_underFlown)
                                                    break decryption;
                                                continue;
                                            }
                                            case NEED_TASK:
                                            {
                                                _sslEngine.getDelegatedTask().run();
                                                continue;
                                            }
                                            case NEED_WRAP:
                                            {
                                                // If we are called from flush()
                                                // return to let it do the wrapping.
                                                if (_flushRequiresFillToProgress)
                                                    return 0;

                                                _fillRequiresFlushToProgress = true;
                                                flush(BufferUtil.EMPTY_BUFFER);
                                                if (BufferUtil.isEmpty(_encryptedOutput))
                                                {
                                                    // The flush wrote all the encrypted bytes so continue to fill.
                                                    _fillRequiresFlushToProgress = false;
                                                    if (_underFlown)
                                                        break decryption;
                                                    continue;
                                                }
                                                else
                                                {
                                                    // The flush did not complete, return from fill()
                                                    // and let the write completion mechanism to kick in.
                                                    return 0;
                                                }
                                            }
                                            case NEED_UNWRAP:
                                            {
                                                if (_underFlown)
                                                    break decryption;
                                                continue;
                                            }
                                            default:
                                            {
                                                throw new IllegalStateException();
                                            }
                                        }
                                    }
                                    default:
                                    {
                                        throw new IllegalStateException();
                                    }
                                }
                            }
                        }
                    }
                    catch (SSLHandshakeException x)
                    {
                        notifyHandshakeFailed(_sslEngine, x);
                        failure = x;
                        throw x;
                    }
                    catch (SSLException x)
                    {
                        if (!_handshaken)
                        {
                            x = (SSLException)new SSLHandshakeException(x.getMessage()).initCause(x);
                            notifyHandshakeFailed(_sslEngine, x);
                        }
                        failure = x;
                        throw x;
                    }
                    catch (Throwable x)
                    {
                        failure = x;
                        throw x;
                    }
                    finally
                    {
                        // If we are handshaking, then wake up any waiting write as well as it may have been blocked on the read
                        if (_flushRequiresFillToProgress)
                        {
                            _flushRequiresFillToProgress = false;
                            getExecutor().execute(failure == null ? _runCompletWrite : new FailWrite(failure));
                        }

                        releaseEncryptedInputBuffer();
                        releaseDecryptedInputBuffer();
                    }
                }
            }
            catch (Throwable x)
            {
                close(x);
                if(x instanceof IOException)
                    throw (IOException) x;
                throw new RuntimeIOException(x);
            }
        }

        private void handshakeFinished()
        {
            if (_handshaken)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Renegotiated {}", SslConnection.this);
                if (_renegotiationLimit>0)
                    _renegotiationLimit--;
            }
            else
            {
                _handshaken = true;
                if (LOG.isDebugEnabled())
                    LOG.debug("{} handshake succeeded {}/{} {}",
                        _sslEngine.getUseClientMode() ? "client" : "resumed server",
                            _sslEngine.getSession().getProtocol(),_sslEngine.getSession().getCipherSuite(),
                            SslConnection.this);
                notifyHandshakeSucceeded(_sslEngine);
            }
        }

        private boolean allowRenegotiate(HandshakeStatus handshakeStatus)
        {   
            if (!_handshaken || handshakeStatus == HandshakeStatus.NOT_HANDSHAKING)
                return true;

            if (!isRenegotiationAllowed())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Renegotiation denied {}", SslConnection.this);
                shutdownInput();
                return false;
            }
            
            if (getRenegotiationLimit()==0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Renegotiation limit exceeded {}", SslConnection.this);
                shutdownInput();
                return false;
            }
            
            return true;
        }

        private void shutdownInput()
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
            // The contract for flush does not require that all appOuts bytes are written
            // or even that any appOut bytes are written!  If the connection is write block
            // or busy handshaking, then zero bytes may be taken from appOuts and this method
            // will return 0 (even if some handshake bytes were flushed and filled).
            // it is the applications responsibility to call flush again - either in a busy loop
            // or better yet by using EndPoint#write to do the flushing.

            if (LOG.isDebugEnabled())
            {
                for (ByteBuffer b : appOuts)
                    LOG.debug("flush {} {}", BufferUtil.toHexSummary(b), SslConnection.this);
            }

            try
            {
                synchronized (this)
                {
                    try
                    {
                        if (_cannotAcceptMoreAppDataToFlush)
                        {
                            if (isOutboundDone())
                                throw new EofException(new ClosedChannelException());
                            return false;
                        }

                        while (true)
                        {
                            int packetBufferSize = getPacketBufferSize();
                            acquireEncryptedOutput();

                            // We call sslEngine.wrap to try to take bytes from appOuts
                            // buffers and encrypt them into the _encryptedOutput buffer.
                            BufferUtil.compact(_encryptedOutput);
                            int pos = BufferUtil.flipToFill(_encryptedOutput);
                            SSLEngineResult wrapResult;
                            try
                            {
                                wrapResult = wrap(_sslEngine, appOuts,_encryptedOutput);
                            }
                            finally
                            {
                                BufferUtil.flipToFlush(_encryptedOutput, pos);
                            }
                            if (LOG.isDebugEnabled())
                                LOG.debug("wrap {} {}", wrapResult.toString().replace('\n',' '), SslConnection.this);

                            Status wrapResultStatus = wrapResult.getStatus();

                            boolean allConsumed=true;
                            for (ByteBuffer b : appOuts)
                                if (BufferUtil.hasContent(b))
                                    allConsumed=false;

                            // and deal with the results returned from the sslEngineWrap
                            switch (wrapResultStatus)
                            {
                                case CLOSED:
                                {
                                    // The SSL engine has close, but there may be close handshake that needs to be written
                                    if (BufferUtil.hasContent(_encryptedOutput))
                                    {
                                        _cannotAcceptMoreAppDataToFlush = true;
                                        getEndPoint().flush(_encryptedOutput);
                                        getEndPoint().shutdownOutput();
                                        // If we failed to flush the close handshake then we will just pretend that
                                        // the write has progressed normally and let a subsequent call to flush
                                        // (or WriteFlusher#onIncompleteFlushed) to finish writing the close handshake.
                                        // The caller will find out about the close on a subsequent flush or fill.
                                        if (BufferUtil.hasContent(_encryptedOutput))
                                            return false;
                                    }
                                    // otherwise we have written, and the caller will close the underlying connection
                                    else
                                    {
                                        getEndPoint().shutdownOutput();
                                    }
                                    return allConsumed;
                                }
                                case BUFFER_UNDERFLOW:
                                {
                                    throw new IllegalStateException();
                                }
                                case BUFFER_OVERFLOW:
                                {
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
                                    if (BufferUtil.isEmpty(_encryptedOutput))
                                    {
                                        throw new IllegalStateException("Unexpected wrap result " + wrapResultStatus);
                                    }
                                    // fall-through default case to flush()
                                }
                                default:
                                {
                                    if (LOG.isDebugEnabled())
                                        LOG.debug("wrap {} {} {}", wrapResultStatus, BufferUtil.toHexSummary(_encryptedOutput), SslConnection.this);

                                    if (wrapResult.getHandshakeStatus() == HandshakeStatus.FINISHED)
                                        handshakeFinished();

                                    HandshakeStatus handshakeStatus = _sslEngine.getHandshakeStatus();

                                    // Check whether re-negotiation is allowed
                                    if (!allowRenegotiate(handshakeStatus))
                                    {
                                        getEndPoint().shutdownOutput();
                                        return allConsumed;
                                    }
                                    
                                    // if we have net bytes, let's try to flush them
                                    if (BufferUtil.hasContent(_encryptedOutput))
                                        if (!getEndPoint().flush(_encryptedOutput))
                                            getEndPoint().flush(_encryptedOutput); // one retry

                                    // But we also might have more to do for the handshaking state.
                                    switch (handshakeStatus)
                                    {
                                        case NOT_HANDSHAKING:
                                            // If we have not consumed all and had just finished handshaking, then we may
                                            // have just flushed the last handshake in the encrypted buffers, so we should
                                            // try again.
                                            if (!allConsumed && wrapResult.getHandshakeStatus()==HandshakeStatus.FINISHED && BufferUtil.isEmpty(_encryptedOutput))
                                                continue;

                                            // Return true if we consumed all the bytes and encrypted are all flushed
                                            return allConsumed && BufferUtil.isEmpty(_encryptedOutput);

                                        case NEED_TASK:
                                            // run the task and continue
                                            _sslEngine.getDelegatedTask().run();
                                            continue;

                                        case NEED_WRAP:
                                            // Hey we just wrapped! Oh well who knows what the sslEngine is thinking, so continue and we will wrap again
                                            continue;

                                        case NEED_UNWRAP:
                                            // Ah we need to fill some data so we can write.
                                            // So if we were not called from fill and the app is not reading anyway
                                            if (!_fillRequiresFlushToProgress && !getFillInterest().isInterested())
                                            {
                                                // Tell the onFillable method that there might be a write to complete
                                                _flushRequiresFillToProgress = true;
                                                fill(BufferUtil.EMPTY_BUFFER);
                                                // Check if after the fill() we need to wrap again
                                                if (_sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP)
                                                    continue;
                                            }
                                            return allConsumed && BufferUtil.isEmpty(_encryptedOutput);

                                        case FINISHED:
                                            throw new IllegalStateException();
                                    }
                                }
                            }
                        }
                    }
                    catch (SSLHandshakeException x)
                    {
                        notifyHandshakeFailed(_sslEngine, x);
                        throw x;
                    }
                    finally
                    {
                        releaseEncryptedOutputBuffer();
                    }
                }
            }
            catch (Throwable x)
            {
                close(x);
                throw x;
            }
        }

        private void releaseEncryptedOutputBuffer()
        {
            if (!Thread.holdsLock(DecryptedEndPoint.this))
                throw new IllegalStateException();
            if (_encryptedOutput != null && !_encryptedOutput.hasRemaining())
            {
                _bufferPool.release(_encryptedOutput);
                _encryptedOutput = null;
            }
        }

        @Override
        public void shutdownOutput()
        {
            try
            {
                boolean flush = false;
                boolean close = false;
                synchronized (_decryptedEndPoint)
                {
                    boolean ishut = isInputShutdown();
                    boolean oshut = isOutputShutdown();
                    if (LOG.isDebugEnabled())
                        LOG.debug("shutdownOutput: oshut={}, ishut={} {}", oshut, ishut, SslConnection.this);

                    if (oshut)
                        return;

                    if (!_closedOutbound)
                    {
                        _closedOutbound=true; // Only attempt this once
                        closeOutbound();
                        flush = true;
                    }

                    // TODO review close logic here
                    if (ishut)
                        close = true;
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
            if (getFillInterest().isCallbackNonBlocking())
            {
                SslConnection.this.tryFillInterested(_nonBlockingReadCallback);
            }
            else
            {
                SslConnection.this.tryFillInterested();
            }
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
        public void close()
        {
            // First send the TLS Close Alert, then the FIN.
            shutdownOutput();
            getEndPoint().close();
            super.close();
        }

        protected void close(Throwable failure)
        {
            // First send the TLS Close Alert, then the FIN.
            shutdownOutput();
            getEndPoint().close();
            super.close(failure);
        }

        @Override
        public Object getTransport()
        {
            return getEndPoint();
        }

        @Override
        public boolean isInputShutdown()
        {
            return getEndPoint().isInputShutdown() || isInboundDone();
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

        private void notifyHandshakeSucceeded(SSLEngine sslEngine)
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

        @Override
        public String toString()
        {
            return super.toString()+"->"+getEndPoint().toString();
        }

        private class FailWrite implements Runnable
        {
            private final Throwable failure;

            private FailWrite(Throwable failure)
            {
                this.failure = failure;
            }

            @Override
            public void run()
            {
                getWriteFlusher().onFail(failure);
            }
        }
    }
}
