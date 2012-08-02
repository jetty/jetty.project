// ========================================================================
// Copyright (c) 2004-2011 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.io.ssl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.io.AbstractAsyncConnection;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.ReadInterest;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An AsyncConnection that acts as an intercepter between an AsyncEndPoint providing SSL encrypted data
 * and another consumer of an  AsyncEndPoint (typically an {@link AsyncConnection} like HttpConnection) that 
 * wants unencrypted data.
 * <p>
 * The connector uses an {@link AsyncEndPoint} (typically {@link SelectChannelEndPoint}) as
 * it's source/sink of encrypted data.   It then provides an endpoint via {@link #getDecryptedEndPoint()} to
 * expose a source/sink of unencrypted data to another connection (eg HttpConnection).
 * <p>
 * The design of this class is based on a clear separation between the passive methods, which do not block nor schedule any 
 * asynchronous callbacks, and active methods that do schedule asynchronous callbacks.
 * <p>
 * The passive methods are {@link DecryptedEndPoint#fill(ByteBuffer)} and {@link DecryptedEndPoint#flush(ByteBuffer...)}. They make best
 * effort attempts to progress the connection using only calls to the encrypted {@link AsyncEndPoint#fill(ByteBuffer)} and {@link AsyncEndPoint#flush(ByteBuffer...)}
 * methods.  They will never block nor schedule any readInterest or write callbacks.   If a fill/flush cannot progress either because
 * of network congestion or waiting for an SSL handshake message, then the fill/flush will simply return with zero bytes filled/flushed.
 * Specifically, if a flush cannot proceed because it needs to receive a handshake message, then the flush will attempt to fill bytes from the 
 * encrypted endpoint, but if insufficient bytes are read it will NOT call {@link AsyncEndPoint#fillInterested(Object, Callback)}.
 * <p>
 * It is only the active methods : {@link DecryptedEndPoint#fillInterested(Object, Callback)} and 
 * {@link DecryptedEndPoint#write(Object, Callback, ByteBuffer...)} that may schedule callbacks by calling the encrypted 
 * {@link AsyncEndPoint#fillInterested(Object, Callback)} and {@link AsyncEndPoint#write(Object, Callback, ByteBuffer...)}
 * methods.  For normal data handling, the decrypted fillInterest method will result in an encrypted fillInterest and a decrypted
 * write will result in an encrypted write. However, due to SSL handshaking requirements, it is also possible for a decrypted fill 
 * to call the encrypted write and for the decrypted flush to call the encrypted fillInterested methods.
 * <p>
 * MOST IMPORTANTLY, the encrypted callbacks from the active methods (#onFillable() and WriteFlusher#completeWrite()) do no filling or flushing 
 * themselves.  Instead they simple make the callbacks to the decrypted callbacks, so that the passive encyrpted fill/flush will
 * be called again and make another best effort attempt to progress the connection.
 * 
 */
public class SslConnection extends AbstractAsyncConnection
{
    private static final Logger LOG = Log.getLogger(SslConnection.class);
    private final ByteBufferPool _bufferPool;
    private final SSLEngine _sslEngine;
    private final DecryptedEndPoint _decryptedEndPoint;
    private ByteBuffer _decryptedInput;
    private ByteBuffer _encryptedInput;
    private ByteBuffer _encryptedOutput;
    private final boolean _encryptedDirectBuffers = false;
    private final boolean _decryptedDirectBuffers = false;

    public SslConnection(ByteBufferPool byteBufferPool, Executor executor, AsyncEndPoint endPoint, SSLEngine sslEngine)
    {
        super(endPoint, executor, true);
        this._bufferPool = byteBufferPool;
        this._sslEngine = sslEngine;
        this._decryptedEndPoint = new DecryptedEndPoint();
    }

    public SSLEngine getSSLEngine()
    {
        return _sslEngine;
    }

    public AsyncEndPoint getDecryptedEndPoint()
    {
        return _decryptedEndPoint;
    }

    @Override
    public void onOpen()
    {
        try
        {
            super.onOpen();

            // Begin the handshake
            _sslEngine.beginHandshake();

            if (_sslEngine.getUseClientMode())
                _decryptedEndPoint.write(null, new Callback.Empty<>(), BufferUtil.EMPTY_BUFFER);
            
            getDecryptedEndPoint().getAsyncConnection().onOpen();
        }
        catch (SSLException x)
        {
            getEndPoint().close();
            throw new RuntimeIOException(x);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onFillable()
    {
        // onFillable means that there are encrypted bytes ready to be filled.
        // however we do not fill them here on this callback, but instead wakeup
        // the decrypted readInterest and/or writeFlusher so that they will attempt
        // to do the fill and/or flush again and these calls will do the actually
        // filling. 
        
        LOG.debug("{} onReadable", this);

        synchronized(_decryptedEndPoint)
        {
            // wake up whoever is doing the fill or the flush so they can
            // do all the filling, unwrapping ,wrapping and flushing
            if (_decryptedEndPoint._readInterest.isInterested())
                _decryptedEndPoint._readInterest.readable();

            // If we are handshaking, then wake up any waiting write as well as it may have been blocked on the read
            if ( _decryptedEndPoint._flushRequiresFillToProgress)
            {
                _decryptedEndPoint._flushRequiresFillToProgress = false;
                _decryptedEndPoint._writeFlusher.completeWrite();
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onFillInterestedFailed(Throwable cause)
    {
        // this means that the fill interest in encrypted bytes has failed.
        // However we do not handle that here on this callback, but instead wakeup
        // the decrypted readInterest and/or writeFlusher so that they will attempt
        // to do the fill and/or flush again and these calls will do the actually
        // handle the cause.
        
        super.onFillInterestedFailed(cause);

        synchronized(_decryptedEndPoint)
        {
            if (_decryptedEndPoint._readInterest.isInterested())
                _decryptedEndPoint._readInterest.failed(cause);

            if (_decryptedEndPoint._flushRequiresFillToProgress)
            {
                _decryptedEndPoint._flushRequiresFillToProgress = false;
                _decryptedEndPoint._writeFlusher.failed(cause);
            }
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("SslConnection@%x{%s,%s%s}",
                hashCode(),
                _sslEngine.getHandshakeStatus(),
                _decryptedEndPoint._readInterest.isInterested() ? "R" : "",
                _decryptedEndPoint._writeFlusher.isWriting() ? "W" : "");
    }

    /* ------------------------------------------------------------ */
    public class DecryptedEndPoint extends AbstractEndPoint implements AsyncEndPoint
    {
        private AsyncConnection _connection;
        private boolean _fillRequiresFlushToProgress;
        private boolean _flushRequiresFillToProgress;
        private boolean _cannotAcceptMoreAppDataToFlush;
        private boolean _needToFillMoreDataToProgress;
        private boolean _ishut = false;

        @Override
        public void onOpen()
        {
        }

        @Override
        public void onClose()
        {
        }

        private final Callback<Void> _writeCallback = new Callback<Void>()
        {

            @Override
            public void completed(Void context)
            {
                // This means that a write of data has completed.  Writes are done
                // only if there is an active writeflusher or a read needed to write
                // data.  In either case the appropriate callback is passed on.
                synchronized (DecryptedEndPoint.this)
                {
                    LOG.debug("{} write.complete {}", SslConnection.this, _cannotAcceptMoreAppDataToFlush ? (_fillRequiresFlushToProgress ? "FW" : "F") : (_fillRequiresFlushToProgress ? "W" : ""));

                    releaseNetOut();

                    _cannotAcceptMoreAppDataToFlush = false;
                    
                    if (_fillRequiresFlushToProgress)
                    {
                        _fillRequiresFlushToProgress = false;
                        _readInterest.readable();
                    }

                    if (_writeFlusher.isWriting())
                        _writeFlusher.completeWrite();
                }
            }

            @Override
            public void failed(Void context, Throwable x)
            {
                // This means that a write of data has failed.  Writes are done
                // only if there is an active writeflusher or a read needed to write
                // data.  In either case the appropriate callback is passed on.
                synchronized (DecryptedEndPoint.this)
                {
                    LOG.debug("{} write.failed", SslConnection.this, x);
                    if (_encryptedOutput != null)
                        BufferUtil.clear(_encryptedOutput);
                    releaseNetOut();
                    
                    _cannotAcceptMoreAppDataToFlush = false;
                    
                    if (_fillRequiresFlushToProgress)
                    {
                        _fillRequiresFlushToProgress = false;
                        _readInterest.failed(x);
                    }

                    if (_writeFlusher.isWriting())
                        _writeFlusher.failed(x);

                    // TODO release all buffers??? or may in onClose
                }
            }
        };

        private final ReadInterest _readInterest = new ReadInterest()
        {
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
                    if (BufferUtil.isEmpty(_encryptedInput) || _needToFillMoreDataToProgress)
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
                                getEndPoint().write(null, _writeCallback, _encryptedOutput);
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
                            // Normal readable callback
                            // Get called back on onfillable when then is more data to fill
                            SslConnection.this.fillInterested();

                        return false;
                    }
                    else
                    {
                        // We are ready to read data
                        return true;
                    }
                }
            }
        };

        private final WriteFlusher _writeFlusher = new WriteFlusher(this)
        {
            @Override
            protected void onIncompleteFlushed()
            {
                // This means that the decripted endpoint write method was called and not
                // all data could be wrapped. So either we need to write some encrypted data,
                // OR if we are handshaking we need to read some encrypted data OR
                // if neither than we should just try the flush again.
                synchronized (DecryptedEndPoint.this)
                {
                    // If we have pending output data,
                    if (BufferUtil.hasContent(_encryptedOutput))
                    {
                        // write it
                        _cannotAcceptMoreAppDataToFlush = true;
                        getEndPoint().write(null, _writeCallback, _encryptedOutput);
                    }
                    else if (_sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP)
                        // we are actually read blocked in order to write
                        SslConnection.this.fillInterested();
                    else
                        // try the flush again
                        completeWrite();
                }
            }
        };

        public DecryptedEndPoint()
        {
            super(getEndPoint().getLocalAddress(), getEndPoint().getRemoteAddress());
        }

        public SslConnection getSslConnection()
        {
            return SslConnection.this;
        }

        @Override
        public <C> void fillInterested(C context, Callback<C> callback) throws IllegalStateException
        {
            _readInterest.register(context, callback);
        }

        @Override
        public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IllegalStateException
        {
            _writeFlusher.write(context, callback, buffers);
        }

        @Override
        public synchronized int fill(ByteBuffer buffer) throws IOException
        {
            LOG.debug("{} fill enter", SslConnection.this);
            try
            {
                // Do we already have some decrypted data?
                if (BufferUtil.hasContent(_decryptedInput))
                    return BufferUtil.append(_decryptedInput, buffer);

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
                    LOG.debug("{} filled {} encrypted bytes", SslConnection.this, net_filled);
                    if (net_filled > 0)
                        _needToFillMoreDataToProgress = false;

                    // Let's try the SSL thang even if we have no net data because in that
                    // case we want to fall through to the handshake handling
                    int pos = BufferUtil.flipToFill(app_in);
                    SSLEngineResult unwrapResult = _sslEngine.unwrap(_encryptedInput, app_in);
                    LOG.debug("{} unwrap {}", SslConnection.this, unwrapResult);
                    BufferUtil.flipToFlush(app_in, pos);

                    // and deal with the results
                    switch (unwrapResult.getStatus())
                    {
                        case BUFFER_OVERFLOW:
                            throw new IllegalStateException();

                        case CLOSED:
                            // Dang! we have to care about the handshake state specially for close
                            switch (_sslEngine.getHandshakeStatus())
                            {
                                case NOT_HANDSHAKING:
                                    // We were not handshaking, so just tell the app we are closed
                                    return -1;

                                case NEED_TASK:
                                    // run the task
                                    _sslEngine.getDelegatedTask().run();
                                    continue;

                                case NEED_WRAP:
                                    // we need to send some handshake data (probably to send a close handshake).
                                    if (_flushRequiresFillToProgress)
                                        return -1; // we were called from flush, so it can deal with sending the close handshake

                                    // We need to call flush to cause the wrap to happen
                                    _fillRequiresFlushToProgress = true;
                                    try
                                    {
                                        // flushing an empty buffer will invoke the wrap mechanisms
                                        flush(BufferUtil.EMPTY_BUFFER);
                                        // If encrypted output is all written, we can proceed with close
                                        if (BufferUtil.isEmpty(_encryptedOutput))
                                        {
                                            _fillRequiresFlushToProgress = false;
                                            return -1;
                                        }
                                        
                                        // Otherwise return as if a normal fill and let a subsequent call
                                        // return -1 to the caller.
                                        return unwrapResult.bytesProduced();
                                    }
                                    catch(IOException e)
                                    {
                                        LOG.debug(e);
                                        // The flush failed, oh well nothing more to do than tell the app
                                        // that the connection is closed.
                                        return -1;
                                    }
                            }
                            throw new IllegalStateException();
                            
                        default:
                            if (unwrapResult.getStatus()==Status.BUFFER_UNDERFLOW)
                                _needToFillMoreDataToProgress=true;
                            
                            // if we produced bytes, we don't care about the handshake state for now and it can be dealt with on another call to fill or flush
                            if (unwrapResult.bytesProduced() > 0)
                            {
                                if (app_in == buffer)
                                    return unwrapResult.bytesProduced();
                                return BufferUtil.append(_decryptedInput, buffer);
                            }

                            // Dang! we have to care about the handshake state
                            switch (_sslEngine.getHandshakeStatus())
                            {
                                case NOT_HANDSHAKING:
                                    // we just didn't read anything.
                                    if (net_filled < 0)
                                        _sslEngine.closeInbound();
                                    return 0;

                                case NEED_TASK:
                                    // run the task
                                    _sslEngine.getDelegatedTask().run();
                                    continue;

                                case NEED_WRAP:
                                    // we need to send some handshake data
                                    if (_flushRequiresFillToProgress)
                                        return 0;
                                    _fillRequiresFlushToProgress = true;
                                    flush(BufferUtil.EMPTY_BUFFER);
                                    if (BufferUtil.isEmpty(_encryptedOutput))
                                    {
                                        // the flush completed so continue 
                                        _fillRequiresFlushToProgress = false;
                                        continue;
                                    }
                                    return 0;

                                case NEED_UNWRAP:
                                    // if we just filled some net data
                                    if (net_filled < 0)
                                        _sslEngine.closeInbound();
                                    else if (net_filled > 0)
                                        // maybe we will fill some more on a retry
                                        continue;
                                    // we need to wait for more net data
                                    return 0;

                                case FINISHED:
                                    throw new IllegalStateException();
                            }
                    }
                }
            }
            catch (SSLException e)
            {
                getEndPoint().close();
                LOG.debug(e);
                throw new EofException(e);
            }
            catch (Exception e)
            {
                getEndPoint().close();
                throw e;
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
                LOG.debug("{} fill exit", SslConnection.this);
            }
        }

        @Override
        public synchronized int flush(ByteBuffer... appOuts) throws IOException
        {
            // The contract for flush does not require that all appOuts bytes are written
            // or even that any appOut bytes are written!  If the connection is write block
            // or busy handshaking, then zero bytes may be taken from appOuts and this method
            // will return 0 (even if some handshake bytes were flushed and filled).
            // it is the applications responsibility to call flush again - either in a busy loop
            // or better yet by using AsyncEndPoint#write to do the flushing.

            LOG.debug("{} flush enter {}", SslConnection.this, Arrays.toString(appOuts));
            try
            {
                if (_cannotAcceptMoreAppDataToFlush)
                    return 0;

                // We will need a network buffer
                if (_encryptedOutput == null)
                    _encryptedOutput = _bufferPool.acquire(_sslEngine.getSession().getPacketBufferSize() * 2, _encryptedDirectBuffers);

                int consumed=0;
                while (true)
                {
                    // do the funky SSL thang!
                    // We call sslEngine.wrap to try to take bytes from appOut buffers and encrypt them into the _netOut buffer
                    BufferUtil.compact(_encryptedOutput);
                    int pos = BufferUtil.flipToFill(_encryptedOutput);
                    SSLEngineResult wrapResult = _sslEngine.wrap(appOuts, _encryptedOutput);
                    LOG.debug("{} wrap {}", SslConnection.this, wrapResult);
                    BufferUtil.flipToFlush(_encryptedOutput, pos);
                    consumed+=wrapResult.bytesConsumed();

                    // and deal with the results returned from the sslEngineWrap
                    switch (wrapResult.getStatus())
                    {
                        case CLOSED:
                            // The SSL engine has close, but there may be close handshake that needs to be written
                            if (BufferUtil.hasContent(_encryptedOutput))
                            {
                                _cannotAcceptMoreAppDataToFlush = true;
                                getEndPoint().flush(_encryptedOutput);
                                // If we failed to flush the close handshake then we will just pretend that 
                                // the write has progressed normally and let a subsequent call to flush (or WriteFlusher#onIncompleteFlushed)
                                // to finish writing the close handshake.   The caller will find out about the close on a subsequent flush or fill.
                                if (BufferUtil.hasContent(_encryptedOutput))
                                    return consumed;
                            }
                            
                            // If we we flushing because of a fill needing to wrap, return normally and it will handle the closed state.
                            if (_fillRequiresFlushToProgress)
                                return consumed;
                            
                            // otherwise it is an exception to write to a closed endpoint
                            throw new EofException();

                        case BUFFER_UNDERFLOW:
                            throw new IllegalStateException();

                        default:
                            if (LOG.isDebugEnabled())
                                LOG.debug("{} {} {}", this, wrapResult.getStatus(), BufferUtil.toDetailString(_encryptedOutput));
                            
                            // if we have net bytes, let's try to flush them
                            if (BufferUtil.hasContent(_encryptedOutput))
                                getEndPoint().flush(_encryptedOutput);

                            // But we also might have more to do for the handshaking state.
                            switch (_sslEngine.getHandshakeStatus())
                            {
                                case NOT_HANDSHAKING:
                                    // Return with the number of bytes consumed (which may be 0)
                                    return consumed;

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
                                    if (!_fillRequiresFlushToProgress && !_readInterest.isInterested())
                                    {
                                        // Tell the onFillable method that there might be a write to complete 
                                        // TODO move this to the writeFlusher?
                                        _flushRequiresFillToProgress = true;
                                        fill(BufferUtil.EMPTY_BUFFER);
                                    }
                                    return consumed;

                                case FINISHED:
                                    throw new IllegalStateException();

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
                LOG.debug("{} flush exit", SslConnection.this);
                releaseNetOut();
            }
        }

        private void releaseNetOut()
        {
            if (_encryptedOutput != null && !_encryptedOutput.hasRemaining())
            {
                _bufferPool.release(_encryptedOutput);
                _encryptedOutput = null;
                if (_sslEngine.isOutboundDone())
                    getEndPoint().shutdownOutput();
            }
        }

        @Override
        public void shutdownOutput()
        {
            _sslEngine.closeOutbound();
            try
            {
                flush(BufferUtil.EMPTY_BUFFER);
            }
            catch (IOException e)
            {
                LOG.ignore(e);
                getEndPoint().close();
            }
        }

        @Override
        public boolean isOutputShutdown()
        {
            return _sslEngine.isOutboundDone() || !getEndPoint().isOpen();
        }

        @Override
        public void close()
        {
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
            return _ishut;
        }

        @Override
        public AsyncConnection getAsyncConnection()
        {
            return _connection;
        }

        @Override
        public void setAsyncConnection(AsyncConnection connection)
        {
            _connection = connection;
        }

        @Override
        public String toString()
        {
            return String.format("%s{%s%s%s}", super.toString(), _readInterest.isInterested() ? "R" : "", _writeFlusher.isWriting() ? "W" : "", _cannotAcceptMoreAppDataToFlush ? "w" : "");
        }

    }
}
