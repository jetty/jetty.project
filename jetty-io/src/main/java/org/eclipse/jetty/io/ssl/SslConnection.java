//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.Arrays;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.FillInterest;
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
    private static final boolean DEBUG = LOG.isDebugEnabled(); // Easy for the compiler to remove the code if DEBUG==false
    private static final ByteBuffer __FILL_CALLED_FLUSH= BufferUtil.allocate(0);
    private static final ByteBuffer __FLUSH_CALLED_FILL= BufferUtil.allocate(0);
    private final ByteBufferPool _bufferPool;
    private final SSLEngine _sslEngine;
    private final DecryptedEndPoint _decryptedEndPoint;
    private ByteBuffer _decryptedInput;
    private ByteBuffer _encryptedInput;
    private ByteBuffer _encryptedOutput;
    private final boolean _encryptedDirectBuffers = false;
    private final boolean _decryptedDirectBuffers = false;
    private final Runnable _runCompletWrite = new Runnable()
    {
        @Override
        public void run()
        {
            _decryptedEndPoint.getWriteFlusher().completeWrite();
        }
    };
    private boolean _renegotiationAllowed;

    public SslConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, SSLEngine sslEngine)
    {
        // This connection does not execute calls to onfillable, so they will be called by the selector thread.
        // onfillable does not block and will only wakeup another thread to do the actual reading and handling.
        super(endPoint, executor, !EXECUTE_ONFILLABLE);
        this._bufferPool = byteBufferPool;
        this._sslEngine = sslEngine;
        this._decryptedEndPoint = newDecryptedEndPoint();
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
        this._renegotiationAllowed = renegotiationAllowed;
    }

    @Override
    public void onOpen()
    {
        try
        {
            // Begin the handshake
            _sslEngine.beginHandshake();
            super.onOpen();
            getDecryptedEndPoint().getConnection().onOpen();
        }
        catch (SSLException x)
        {
            getEndPoint().close();
            throw new RuntimeIOException(x);
        }
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
    public void onFillable()
    {
        // onFillable means that there are encrypted bytes ready to be filled.
        // however we do not fill them here on this callback, but instead wakeup
        // the decrypted readInterest and/or writeFlusher so that they will attempt
        // to do the fill and/or flush again and these calls will do the actually
        // filling.

        if (DEBUG)
            LOG.debug("onFillable enter {}", _decryptedEndPoint);

        // We have received a close handshake, close the end point to send FIN.
        if (_decryptedEndPoint.isInputShutdown())
            _decryptedEndPoint.close();

        // wake up whoever is doing the fill or the flush so they can
        // do all the filling, unwrapping, wrapping and flushing
        _decryptedEndPoint.getFillInterest().fillable();

        // If we are handshaking, then wake up any waiting write as well as it may have been blocked on the read
        synchronized(_decryptedEndPoint)
        {
            if (_decryptedEndPoint._flushRequiresFillToProgress)
            {
                _decryptedEndPoint._flushRequiresFillToProgress = false;
                getExecutor().execute(_runCompletWrite);
            }
        }

        if (DEBUG)
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
                    if (DEBUG)
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
                getExecutor().execute(_runCompletWrite);
            }

            @Override
            public void failed(final Throwable x)
            {
                // This means that a write of data has failed.  Writes are done
                // only if there is an active writeflusher or a read needed to write
                // data.  In either case the appropriate callback is passed on.
                boolean fail_filler = false;
                synchronized (DecryptedEndPoint.this)
                {
                    if (DEBUG)
                        LOG.debug("{} write.failed", SslConnection.this, x);
                    BufferUtil.clear(_encryptedOutput);
                    releaseEncryptedOutputBuffer();

                    _cannotAcceptMoreAppDataToFlush = false;

                    if (_fillRequiresFlushToProgress)
                    {
                        _fillRequiresFlushToProgress = false;
                        fail_filler = true;
                    }
                }

                final boolean filler_failed=fail_filler;

                failedCallback(new Callback()
                {
                    @Override
                    public void succeeded()
                    {                        
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        if (filler_failed)
                            getFillInterest().onFail(x);
                        getWriteFlusher().onFail(x);
                    }
                    
                },x);
            }
        };

        public DecryptedEndPoint()
        {
            super(null,getEndPoint().getLocalAddress(), getEndPoint().getRemoteAddress());
            setIdleTimeout(getEndPoint().getIdleTimeout());
        }

        @Override
        protected FillInterest getFillInterest()
        {
            return super.getFillInterest();
        }

        @Override
        public void setIdleTimeout(long idleTimeout)
        {
            super.setIdleTimeout(idleTimeout);
            getEndPoint().setIdleTimeout(idleTimeout);
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
            synchronized (DecryptedEndPoint.this)
            {
                if (DEBUG)
                    LOG.debug("onIncompleteFlush {}", getEndPoint());
                // If we have pending output data,
                if (BufferUtil.hasContent(_encryptedOutput))
                {
                    // write it
                    _cannotAcceptMoreAppDataToFlush = true;
                    getEndPoint().write(_writeCallback, _encryptedOutput);
                }
                // If we are handshaking and need to read,
                else if (_sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP)
                {
                    // check if we are actually read blocked in order to write
                    _flushRequiresFillToProgress = true;
                    SslConnection.this.fillInterested();
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
            
            
            if (try_again)
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
                    // because this is a special case (see above) we could probably
                    // avoid the dispatch, but best to be sure
                    getExecutor().execute(_runCompletWrite);
                }
            }
        }

        @Override
        protected boolean needsFill() throws IOException
        {
            // This means that the decrypted data consumer has called the fillInterested
            // method on the DecryptedEndPoint, so we have to work out if there is
            // decrypted data to be filled or what callbacks to setup to be told when there
            // might be more encrypted data available to attempt another call to fill

            synchronized (DecryptedEndPoint.this)
            {
                // Do we already have some app data, then app can fill now so return true
                if (BufferUtil.hasContent(_decryptedInput))
                    return true;

                // If we have no encrypted data to decrypt OR we have some, but it is not enough
                if (BufferUtil.isEmpty(_encryptedInput) || _underFlown)
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
                            getEndPoint().write(_writeCallback, _encryptedOutput);
                        }
                        else
                        {
                            // we have already written the net data
                            // pretend we are readable so the wrap is done by next readable callback
                            _fillRequiresFlushToProgress = false;
                            return true;
                        }
                    }
                    else
                    {
                        // Normal readable callback
                        // Get called back on onfillable when then is more data to fill
                        SslConnection.this.fillInterested();
                    }

                    return false;
                }
                else
                {
                    // We are ready to read data
                    return true;
                }
            }
        }

        @Override
        public void setConnection(Connection connection)
        {
            if (connection instanceof AbstractConnection)
            {
                AbstractConnection a = (AbstractConnection)connection;
                if (a.getInputBufferSize()<_sslEngine.getSession().getApplicationBufferSize())
                    a.setInputBufferSize(_sslEngine.getSession().getApplicationBufferSize());
            }
            super.setConnection(connection);
        }

        public SslConnection getSslConnection()
        {
            return SslConnection.this;
        }

        @Override
        public synchronized int fill(ByteBuffer buffer) throws IOException
        {
            if (DEBUG)
                LOG.debug("{} fill enter", SslConnection.this);
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

                // We also need an app buffer, but can use the passed buffer if it is big enough
                ByteBuffer app_in;
                if (BufferUtil.space(buffer) > _sslEngine.getSession().getApplicationBufferSize())
                    app_in = buffer;
                else if (_decryptedInput == null)
                    app_in = _decryptedInput = _bufferPool.acquire(_sslEngine.getSession().getApplicationBufferSize(), _decryptedDirectBuffers);
                else
                    app_in = _decryptedInput;

                // loop filling and unwrapping until we have something
                while (true)
                {
                    // Let's try reading some encrypted data... even if we have some already.
                    int net_filled = getEndPoint().fill(_encryptedInput);
                    if (DEBUG)
                        LOG.debug("{} filled {} encrypted bytes", SslConnection.this, net_filled);

                    decryption: while (true)
                    {
                        // Let's unwrap even if we have no net data because in that
                        // case we want to fall through to the handshake handling
                        int pos = BufferUtil.flipToFill(app_in);
                        SSLEngineResult unwrapResult = _sslEngine.unwrap(_encryptedInput, app_in);
                        BufferUtil.flipToFlush(app_in, pos);
                        if (DEBUG)
                            LOG.debug("{} unwrap {}", SslConnection.this, unwrapResult);

                        HandshakeStatus handshakeStatus = _sslEngine.getHandshakeStatus();
                        HandshakeStatus unwrapHandshakeStatus = unwrapResult.getHandshakeStatus();
                        Status unwrapResultStatus = unwrapResult.getStatus();

                        _underFlown = unwrapResultStatus == Status.BUFFER_UNDERFLOW;

                        if (_underFlown)
                        {
                            if (net_filled < 0)
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
                                    {
                                        // We were not handshaking, so just tell the app we are closed
                                        return -1;
                                    }
                                    case NEED_TASK:
                                    {
                                        _sslEngine.getDelegatedTask().run();
                                        continue;
                                    }
                                    case NEED_WRAP:
                                    {
                                        // We need to send some handshake data (probably the close handshake).
                                        // We return -1 so that the application can drive the close by flushing
                                        // or shutting down the output.
                                        return -1;
                                    }
                                    case NEED_UNWRAP:
                                    {
                                        // We expected to read more, but we got closed.
                                        // Return -1 to indicate to the application to drive the close.
                                        return -1;
                                    }
                                    default:
                                    {
                                        throw new IllegalStateException();
                                    }
                                }
                            }
                            case BUFFER_UNDERFLOW:
                            case OK:
                            {
                                if (unwrapHandshakeStatus == HandshakeStatus.FINISHED && !_handshaken)
                                {
                                    _handshaken = true;
                                    if (DEBUG)
                                        LOG.debug("{} {} handshake completed", SslConnection.this,
                                                _sslEngine.getUseClientMode() ? "client-side" : "resumed session server-side");
                                }

                                // Check whether renegotiation is allowed
                                if (_handshaken && handshakeStatus != HandshakeStatus.NOT_HANDSHAKING && !isRenegotiationAllowed())
                                {
                                    if (DEBUG)
                                        LOG.debug("{} renegotiation denied", SslConnection.this);
                                    closeInbound();
                                    return -1;
                                }

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
                                        if (buffer == __FLUSH_CALLED_FILL)
                                            return 0;

                                        _fillRequiresFlushToProgress = true;
                                        flush(__FILL_CALLED_FLUSH);
                                        if (BufferUtil.isEmpty(_encryptedOutput))
                                        {
                                            // The flush wrote all the encrypted bytes so continue to fill
                                            _fillRequiresFlushToProgress = false;
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
            catch (Exception e)
            {
                getEndPoint().close();
                throw e;
            }
            finally
            {
                // If we are handshaking, then wake up any waiting write as well as it may have been blocked on the read
                if (_flushRequiresFillToProgress)
                {
                    _flushRequiresFillToProgress = false;
                    getExecutor().execute(_runCompletWrite);
                }

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
                if (DEBUG)
                    LOG.debug("{} fill exit", SslConnection.this);
            }
        }

        private void closeInbound()
        {
            try
            {
                _sslEngine.closeInbound();
            }
            catch (SSLException x)
            {
                LOG.ignore(x);
            }
        }

        @Override
        public synchronized boolean flush(ByteBuffer... appOuts) throws IOException
        {
            // The contract for flush does not require that all appOuts bytes are written
            // or even that any appOut bytes are written!  If the connection is write block
            // or busy handshaking, then zero bytes may be taken from appOuts and this method
            // will return 0 (even if some handshake bytes were flushed and filled).
            // it is the applications responsibility to call flush again - either in a busy loop
            // or better yet by using EndPoint#write to do the flushing.

            if (DEBUG)
                LOG.debug("{} flush enter {}", SslConnection.this, Arrays.toString(appOuts));
            int consumed=0;
            try
            {
                if (_cannotAcceptMoreAppDataToFlush)
                {
                    if (_sslEngine.isOutboundDone())
                        throw new EofException(new ClosedChannelException());
                    return false;
                }

                // We will need a network buffer
                if (_encryptedOutput == null)
                    _encryptedOutput = _bufferPool.acquire(_sslEngine.getSession().getPacketBufferSize(), _encryptedDirectBuffers);

                while (true)
                {
                    // We call sslEngine.wrap to try to take bytes from appOut buffers and encrypt them into the _netOut buffer
                    BufferUtil.compact(_encryptedOutput);
                    int pos = BufferUtil.flipToFill(_encryptedOutput);
                    SSLEngineResult wrapResult = _sslEngine.wrap(appOuts, _encryptedOutput);
                    if (DEBUG)
                        LOG.debug("{} wrap {}", SslConnection.this, wrapResult);
                    BufferUtil.flipToFlush(_encryptedOutput, pos);
                    if (wrapResult.bytesConsumed()>0)
                        consumed+=wrapResult.bytesConsumed();
                    Status wrapResultStatus = wrapResult.getStatus();
                    
                    boolean allConsumed=true;
                    for (ByteBuffer b : appOuts)
                        if (BufferUtil.hasContent(b))
                            allConsumed=false;  

                    // and deal with the results returned from the sslEngineWrap
                    switch (wrapResultStatus)
                    {
                        case CLOSED:
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

                        case BUFFER_UNDERFLOW:
                            throw new IllegalStateException();

                        default:
                            if (DEBUG)
                                LOG.debug("{} {} {}", this, wrapResultStatus, BufferUtil.toDetailString(_encryptedOutput));

                            if (wrapResult.getHandshakeStatus() == HandshakeStatus.FINISHED && !_handshaken)
                            {
                                _handshaken = true;
                                if (DEBUG)
                                    LOG.debug("{} {} handshake completed", SslConnection.this, "server-side");
                            }

                            HandshakeStatus handshakeStatus = _sslEngine.getHandshakeStatus();

                            // Check whether renegotiation is allowed
                            if (_handshaken && handshakeStatus != HandshakeStatus.NOT_HANDSHAKING && !isRenegotiationAllowed())
                            {
                                if (DEBUG)
                                    LOG.debug("{} renegotiation denied", SslConnection.this);
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
                                    if (appOuts[0]!=__FILL_CALLED_FLUSH && !getFillInterest().isInterested())
                                    {
                                        // Tell the onFillable method that there might be a write to complete
                                        _flushRequiresFillToProgress = true;
                                        fill(__FLUSH_CALLED_FILL);
                                        // Check if after the fill() we need to wrap again
                                        if (handshakeStatus == HandshakeStatus.NEED_WRAP)
                                            continue;
                                    }
                                    return allConsumed && BufferUtil.isEmpty(_encryptedOutput);

                                case FINISHED:
                                    throw new IllegalStateException();
                            }
                    }
                }
            }
            finally
            {
                if (DEBUG)
                    LOG.debug("{} flush exit, consumed {}", SslConnection.this, consumed);
                releaseEncryptedOutputBuffer();
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
            boolean ishut = isInputShutdown();
            boolean oshut = isOutputShutdown();
            if (DEBUG)
                LOG.debug("{} shutdownOutput: oshut={}, ishut={}", SslConnection.this, oshut, ishut);
            if (ishut)
            {
                // Aggressively close, since inbound close alert has already been processed
                // and the TLS specification allows to close the connection directly, which
                // is what most other implementations expect: a FIN rather than a TLS close
                // reply. If a TLS close reply is sent, most implementations send a RST.
                getEndPoint().close();
            }
            else if (!oshut)
            {
                try
                {
                    _sslEngine.closeOutbound();
                    flush(BufferUtil.EMPTY_BUFFER); // Send close handshake
                    SslConnection.this.fillInterested(); // seek reply FIN or RST or close handshake
                }
                catch (Exception e)
                {
                    LOG.ignore(e);
                    getEndPoint().close();
                }
            }
        }

        @Override
        public boolean isOutputShutdown()
        {
            return _sslEngine.isOutboundDone() || getEndPoint().isOutputShutdown();
        }

        @Override
        public void close()
        {
            super.close();
            // First send the TLS Close Alert, then the FIN
            shutdownOutput();
            getEndPoint().close();
        }

        @Override
        public boolean isOpen()
        {
            return getEndPoint().isOpen();
        }

        @Override
        public Object getTransport()
        {
            return getEndPoint();
        }

        @Override
        public boolean isInputShutdown()
        {
            return _sslEngine.isInboundDone();
        }

        @Override
        public String toString()
        {
            return super.toString()+"->"+getEndPoint().toString();
        }
    }
}
