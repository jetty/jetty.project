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
public class SslConnection extends AbstractConnection
{
    private static final Logger LOG = Log.getLogger(SslConnection.class);

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
        WAIT_FOR_FLUSH, 
        FILLABLE
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
    private final boolean _encryptedDirectBuffers = true;
    private final boolean _decryptedDirectBuffers = false;
    private boolean _renegotiationAllowed;
    private int _renegotiationLimit = -1;
    private boolean _closedOutbound;
    private boolean _allowMissingCloseMessage = true;
    private FlushState _flushState = FlushState.IDLE;
    private FillState _fillState = FillState.IDLE;
    private AtomicReference<Handshake> _handshake = new AtomicReference<>(Handshake.INITIAL);
    private boolean _underflown;
    
    private abstract class RunnableTask  implements Runnable, Invocable
    {
        private final String _operation;

        protected RunnableTask(String op)
        {
            _operation=op;
        }

        @Override
        public String toString()
        {
            return String.format("SSL:%s:%s:%s",SslConnection.this,_operation,getInvocationType());
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
            return String.format("SSLC.NBReadCB@%x{%s}", SslConnection.this.hashCode(),SslConnection.this);
        }
    };

    public SslConnection(ByteBufferPool byteBufferPool, Executor executor, EndPoint endPoint, SSLEngine sslEngine)
    {
        // This connection does not execute calls to onFillable(), so they will be called by the selector thread.
        // onFillable() does not block and will only wakeup another thread to do the actual reading and handling.
        super(endPoint, executor);
        this._bufferPool = byteBufferPool;
        this._sslEngine = sslEngine;
        this._decryptedEndPoint = newDecryptedEndPoint();
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
            LOG.debug("onFillable enter {}", SslConnection.this);

        // We have received a close handshake, close the end point to send FIN.
        if (_decryptedEndPoint.isInputShutdown())
            _decryptedEndPoint.close();

        _decryptedEndPoint.onFillable();

        if (LOG.isDebugEnabled())
            LOG.debug("onFillable exit {}", SslConnection.this);
    }

    @Override
    public void onFillInterestedFailed(Throwable cause)
    {
        _decryptedEndPoint.onFillableFail(cause==null?new IOException():cause);
    }

    @Override
    public String toConnectionString()
    {
        ByteBuffer b = _encryptedInput;
        int ei=b==null?-1:b.remaining();
        b = _encryptedOutput;
        int eo=b==null?-1:b.remaining();
        b = _decryptedInput;
        int di=b==null?-1:b.remaining();

        Connection connection = _decryptedEndPoint.getConnection();
        return String.format("%s@%x{%s,eio=%d/%d,di=%d,fill=%s,flush=%s}~>%s=>%s",
                getClass().getSimpleName(),
                hashCode(),
                _sslEngine.getHandshakeStatus(),
                ei,eo,di,
                _fillState,_flushState,
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
                boolean complete_write = false;
                synchronized(_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("onFillable {}", SslConnection.this);

                    _fillState = FillState.IDLE;
                    switch(_flushState)
                    {
                        case WAIT_FOR_FILL:
                            complete_write = true;
                            break;
                        default:
                            break;
                    }      
                }

                // Ensure a fill is always done if needed then wake up any fill interest
                if (complete_write)
                    fill(BufferUtil.EMPTY_BUFFER);
                getFillInterest().fillable();
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
            synchronized(_decryptedEndPoint)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("onFillableFail {}", SslConnection.this, failure);
                
                _fillState = FillState.IDLE;
                switch(_flushState)
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
        public int fill(ByteBuffer buffer) throws IOException
        {
            int filled = Integer.MIN_VALUE;
            try
            {
                synchronized(_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("fill enter {} {}", SslConnection.this, BufferUtil.toDetailString(buffer));
                    
                    try
                    {
                        if (_fillState!=FillState.IDLE)
                            return filled = 0;
                        
                        // Do we already have some decrypted data?
                        if (BufferUtil.hasContent(_decryptedInput))
                            return filled = BufferUtil.append(buffer,_decryptedInput);
         
                        // loop filling and unwrapping until we have something
                        while (true)
                        {
                            switch(_sslEngine.getHandshakeStatus())
                            {
                                case NEED_UNWRAP:
                                case NEED_UNWRAP_AGAIN:
                                    // How lucky! we are just about to do an unwrap anyway, so lets break out and do it below:
                                    break;
                                    
                                case NOT_HANDSHAKING:
                                    // handle below
                                    break;
                                    
                                case NEED_TASK:
                                    _sslEngine.getDelegatedTask().run();
                                    continue;
                                    
                                case NEED_WRAP:
                                    if (_flushState==FlushState.IDLE && flush(BufferUtil.EMPTY_BUFFER))
                                        continue;
                                    // handle in needsFillInterest
                                    return filled = 0;
                                    
                                default:
                                    throw new IllegalStateException();
                            }
                            
                            if (_encryptedInput==null)
                                _encryptedInput = _bufferPool.acquire(_sslEngine.getSession().getPacketBufferSize(), _encryptedDirectBuffers);
                                
                            // can we use the passed buffer if it is big enough
                            ByteBuffer app_in = null;
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

                            if (net_filled > 0 && _handshake.get() == Handshake.INITIAL && _sslEngine.isOutboundDone())
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
                                LOG.debug("unwrap {} {} {}", 
                                    net_filled, 
                                    unwrapResult.toString().replace('\n',' '),
                                    BufferUtil.toDetailString(buffer));

                            SSLEngineResult.Status status = unwrapResult.getStatus();

                            // Extra check on unwrapResultStatus == OK with zero bytes consumed
                            // or produced is due to an SSL client on Android (see bug #454773).
                            if (status==Status.OK && unwrapResult.bytesConsumed() == 0 && unwrapResult.bytesProduced() == 0)
                                status = Status.BUFFER_UNDERFLOW;
                            
                            switch (status)
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
                                    
                                    return filled = getEndPoint().isInputShutdown()?-1:0;

                                case BUFFER_OVERFLOW:
                                case OK:
                                {
                                    if (unwrapResult.getHandshakeStatus() == HandshakeStatus.FINISHED)
                                        handshakeSucceeded();

                                    // Check whether re-negotiation is allowed
                                    if (!allowRenegotiate(_sslEngine.getHandshakeStatus()))
                                        return filled = -1;

                                    // If bytes were produced, don't bother with the handshake status;
                                    // pass the decrypted data to the application, which will perform
                                    // another call to fill() or flush().
                                    if (unwrapResult.bytesProduced() > 0)
                                    {
                                        if (app_in==buffer)
                                            return filled = unwrapResult.bytesProduced();
                                        return filled = BufferUtil.append(buffer,_decryptedInput);
                                    }
                                    
                                    break;
                                }
                                
                                default:
                                    throw new IllegalStateException();
                            }
                        }
                    }
                    catch (Throwable x)
                    {
                        handshakeFailed(x);

                        if (_flushState==FlushState.WAIT_FOR_FILL)
                        {
                            _flushState=FlushState.IDLE;
                            getExecutor().execute(()->_decryptedEndPoint.getWriteFlusher().onFail(x));
                        }
                        filled = Integer.MIN_VALUE;
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

                        if (_flushState==FlushState.WAIT_FOR_FILL)
                        {
                            _flushState=FlushState.IDLE;
                            getExecutor().execute(()->_decryptedEndPoint.getWriteFlusher().completeWrite());
                        }
                        
                        if (LOG.isDebugEnabled())
                            LOG.debug("fill exit {} uf={} {}", filled, _underflown, SslConnection.this);
                    }
                }
            }
            catch (Throwable x)
            {
                close(x);
                throw x;
            }
        }

        @Override
        protected void needsFillInterest()
        {
            boolean fillable;
            ByteBuffer write = null;
            boolean interest = true;
            synchronized(_decryptedEndPoint)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("needFillInterest uf={} {}", _underflown, SslConnection.this);
                    LOG.debug("ei={}",BufferUtil.toDetailString(_encryptedInput));
                    LOG.debug("di={}",BufferUtil.toDetailString(_decryptedInput));
                }
                                
                if (_fillState!=FillState.IDLE)
                    throw new IllegalStateException();
                
                switch(_sslEngine.getHandshakeStatus())
                {
                    case NEED_TASK:
                        fillable = true;
                        break;
                        
                    case NEED_UNWRAP:
                    case NEED_UNWRAP_AGAIN:
                    case NOT_HANDSHAKING:
                        if ((BufferUtil.hasContent(_encryptedInput) && !_underflown)
                        ||   BufferUtil.hasContent(_decryptedInput))
                        {
                            fillable = true;
                            break;
                        }
                        fillable = false;
                        interest = true;
                        _fillState = FillState.INTERESTED;
                        break;
                        
                    case NEED_WRAP:
                        if ((BufferUtil.hasContent(_encryptedInput) && !_underflown)
                        ||   BufferUtil.hasContent(_decryptedInput))
                        {
                            fillable = true;
                            break;
                        }

                        fillable = false;
                        _fillState = FillState.WAIT_FOR_FLUSH;
                        if (_flushState==FlushState.IDLE)
                        {
                            _flushState = FlushState.WRITING;
                            write = BufferUtil.hasContent(_encryptedOutput)?_encryptedOutput:BufferUtil.EMPTY_BUFFER;
                        }
                        break;
                        
                    default:
                        throw new IllegalStateException();
                }
            }
           
            if (LOG.isDebugEnabled())
                LOG.debug("needFillInterest f={} i={} w={}",fillable,interest,BufferUtil.toDetailString(write));
            
            if (write!=null)
                getEndPoint().write(_incompleteWriteCallback, write);
            else if (fillable)
                getExecutor().execute(_runFillable);
            else if (interest)
                ensureFillInterested();
        }

        private void handshakeSucceeded()
        {
            if (_handshake.compareAndSet(Handshake.INITIAL, Handshake.SUCCEEDED))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("handshake succeeded {} {} {}/{}",SslConnection.this,
                        _sslEngine.getUseClientMode() ? "client" : "resumed server",
                            _sslEngine.getSession().getProtocol(),_sslEngine.getSession().getCipherSuite());
                notifyHandshakeSucceeded(_sslEngine);
            }
            else if (_handshake.get() == Handshake.SUCCEEDED)
            {
                if (_renegotiationLimit>0)
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
        }
        
        @Override
        public boolean flush(ByteBuffer... appOuts) throws IOException
        {
            try
            {
                synchronized(_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("flush enter {}", SslConnection.this);
                        for (ByteBuffer b : appOuts)
                            LOG.debug("buffer {}", BufferUtil.toDetailString(b));
                    }
                    
                    try
                    {                    
                        if (_flushState!=FlushState.IDLE)
                            return false;

                        // We will need a network buffer
                        if (_encryptedOutput == null)
                            _encryptedOutput = _bufferPool.acquire(_sslEngine.getSession().getPacketBufferSize(), _encryptedDirectBuffers);

                        // Keep going while we can make progress or until we are done
                        while (!getEndPoint().isOutputShutdown())
                        {
                            switch(_sslEngine.getHandshakeStatus())
                            {
                                case NEED_WRAP:
                                    // That's lucky because wrapping is what we want to do anyway, so break and wrap below
                                    break;
                                    
                                case NOT_HANDSHAKING:
                                    // Let's just break here and do the wrapping below
                                    break;
                                    
                                case NEED_TASK:
                                    // run the task and continue
                                    _sslEngine.getDelegatedTask().run();
                                    continue;
                                    
                                case NEED_UNWRAP:
                                case NEED_UNWRAP_AGAIN:
                                    // TODO try an unwrap here as an optimization, but for simplicity let's 
                                    // allow onIncompleteFlush to do that progression.
                                    return false;
                                    
                                default:
                                    throw new IllegalStateException();
                            }
                           
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
                            
                            // Was all the data consumed?
                            boolean allConsumed=true;
                            for (ByteBuffer b : appOuts)
                                if (BufferUtil.hasContent(b))
                                    allConsumed=false;

                            if (LOG.isDebugEnabled())
                                LOG.debug("wrap {} ac={} {}", wrapResult.toString().replace('\n',' '), allConsumed, BufferUtil.toHexSummary(_encryptedOutput));
                            
                            // if we have net bytes, let's try to flush them
                            boolean flushed;
                            if (BufferUtil.isEmpty(_encryptedOutput))
                                flushed = true;
                            else
                            {
                                flushed = getEndPoint().flush(_encryptedOutput);
                                if (LOG.isDebugEnabled())
                                    LOG.debug("flushed {} {}",flushed,BufferUtil.toHexSummary(_encryptedOutput));
                            }

                            // and now finally deal with the results returned from the sslEngineWrap
                            switch (wrapResult.getStatus())
                            {
                                case CLOSED:
                                {
                                    if (!flushed)
                                        return false;
                                    getEndPoint().shutdownOutput();
                                    if (allConsumed)
                                        return true;
                                    throw new IOException("Broken pipe");
                                }
                                
                                case BUFFER_UNDERFLOW:
                                    throw new IllegalStateException();
                                
                                case BUFFER_OVERFLOW:
                                    if (!flushed)
                                        return false;
                                    throw new IllegalStateException();
                                    
                                case OK:
                                {
                                    if (wrapResult.getHandshakeStatus() == HandshakeStatus.FINISHED)
                                        handshakeSucceeded();

                                    HandshakeStatus handshakeStatus = _sslEngine.getHandshakeStatus();
                                    if (!allowRenegotiate(handshakeStatus))
                                    {
                                        getEndPoint().shutdownOutput();
                                        if (allConsumed && BufferUtil.isEmpty(_encryptedOutput))
                                            return true;
                                        throw new IOException("Broken pipe");
                                    }

                                    if (!flushed)
                                        return false;
                                    if (allConsumed)
                                        return true;
                                    if (appOuts.length==1 && appOuts[0]==BufferUtil.EMPTY_BUFFER)
                                        return false;
                                }
                            }
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
                            LOG.debug("flush exit {}", SslConnection.this);
                    }
                }
            }
            catch (Throwable x)
            {
                close(x);
                throw x;
            }
            
            return false;
        }
        

        @Override
        protected void onIncompleteFlush()
        {
            boolean fillInterest = false;
            ByteBuffer write = null;
            synchronized(_decryptedEndPoint)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("onIncompleteFlush {} {}", SslConnection.this, BufferUtil.toDetailString(_encryptedOutput));
                
                if (_flushState!=FlushState.IDLE)
                    throw new IllegalStateException();

                while(true)
                {
                    HandshakeStatus status = _sslEngine.getHandshakeStatus();
                    switch(status)
                    {
                        case NEED_TASK:
                        case NEED_WRAP:
                        case NOT_HANDSHAKING:
                            // write what we have or an empty buffer to reschedule a call to flush
                            write = BufferUtil.hasContent(_encryptedOutput)?_encryptedOutput:BufferUtil.EMPTY_BUFFER;
                            _flushState = FlushState.WRITING;
                            break;

                        case NEED_UNWRAP:
                        case NEED_UNWRAP_AGAIN:
                            // If we have something to write, then write it and ignore the needed unwrap for now.
                            if (BufferUtil.hasContent(_encryptedOutput))
                            {
                                write = _encryptedOutput;
                                _flushState = FlushState.WRITING;
                                break;
                            }
                            
                            if (_fillState!=FillState.IDLE)
                            {
                                // wait for a fill that is happening anyway
                                _flushState = FlushState.WAIT_FOR_FILL;
                                break;
                            }
                                
                            // Try filling ourselves
                            try
                            {
                                fill(BufferUtil.EMPTY_BUFFER);
                                // If this changed the status, let's try again
                                if (_sslEngine.getHandshakeStatus()!=status)
                                    continue;
                            }
                            catch(IOException e)
                            {
                                LOG.debug(e);
                                close(e);
                                write = BufferUtil.EMPTY_BUFFER;
                                break;
                            }

                            // Else we have to wait for a fill we schedule ourselves
                            fillInterest = true;
                            _fillState = FillState.INTERESTED;
                            _flushState = FlushState.WAIT_FOR_FILL;
                            break;

                        default:
                            throw new IllegalStateException();    
                    }
                    
                    break;
                }
            }

            if (write!=null)
                getEndPoint().write(_incompleteWriteCallback, write);
            else if (fillInterest)
                ensureFillInterested();
        }

        
        @Override
        public void doShutdownOutput()
        {
            try
            {
                boolean flush = false;
                boolean close = false;
                synchronized(_decryptedEndPoint)
                {
                    boolean ishut = isInputShutdown();
                    boolean oshut = isOutputShutdown();
                    if (LOG.isDebugEnabled())
                        LOG.debug("shutdownOutput: {} oshut={}, ishut={} {}", SslConnection.this, oshut, ishut);

                    if (oshut)
                        return;

                    if (!_closedOutbound)
                    {
                        _closedOutbound=true; // Only attempt this once
                        _sslEngine.closeOutbound();
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

        private void ensureFillInterested()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("fillInterested SSL NB {}",SslConnection.this);
            SslConnection.this.tryFillInterested(_sslReadCallback);
        }

        @Override
        public boolean isOutputShutdown()
        {
            return _sslEngine.isOutboundDone() || getEndPoint().isOutputShutdown();
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
            return getEndPoint().isInputShutdown() || _sslEngine.isInboundDone();
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
            return super.toEndPointString();
        }

        private boolean allowRenegotiate(HandshakeStatus handshakeStatus)
        {   
            if (_handshake.get() == Handshake.INITIAL || handshakeStatus == HandshakeStatus.NOT_HANDSHAKING)
                return true;
        
            if (!isRenegotiationAllowed())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Renegotiation denied {}", SslConnection.this);
                terminateInput();
                return false;
            }
            
            if (getRenegotiationLimit()==0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Renegotiation limit exceeded {}", SslConnection.this);
                terminateInput();
                return false;
            }
            
            return true;
        }

        private final class IncompleteWriteCallback implements Callback, Invocable
        {
            @Override
            public void succeeded()
            {            
                boolean fillable;
                synchronized(_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("IncompleteWriteCB.succeeded {}", SslConnection.this);
        
                    releaseEncryptedOutputBuffer();
                    _flushState = FlushState.IDLE;
                    fillable = _fillState==FillState.WAIT_FOR_FLUSH;
                    if (fillable)
                        _fillState = FillState.FILLABLE;
                }
                
                _decryptedEndPoint.getWriteFlusher().completeWrite();

                if (fillable)
                    _decryptedEndPoint.getFillInterest().fillable();
            }
        
            @Override
            public void failed(final Throwable x)
            {
                boolean fail_fill_interest;
                synchronized(_decryptedEndPoint)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("IncompleteWriteCB failed {}", SslConnection.this, x);
        
                    BufferUtil.clear(_encryptedOutput);
                    releaseEncryptedOutputBuffer();
                    
                    _flushState = FlushState.IDLE;
                    fail_fill_interest = _fillState==FillState.WAIT_FOR_FLUSH;
                    if (fail_fill_interest)
                        _fillState = FillState.IDLE;
                }
        
                getExecutor().execute(()->
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
                return String.format("SSL@%h.DEP.writeCallback",SslConnection.this);
            }
        }
    }
}
