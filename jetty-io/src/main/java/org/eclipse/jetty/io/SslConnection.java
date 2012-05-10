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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/** SSL Connection.
 * An AysyncConnection that acts as an interceptor between and EndPoint and another
 * Connection, that implements TLS encryption using an {@link SSLEngine}.
 * <p>
 * The connector uses an {@link EndPoint} (like {@link SelectChannelEndPoint}) as
 * it's source/sink of encrypted data.   It then provides {@link #getAppEndPoint()} to
 * expose a source/sink of unencrypted data to another connection (eg HttpConnection).
 */
public class SslConnection extends AbstractAsyncConnection
{
    static final Logger LOG = Log.getLogger("org.eclipse.jetty.io.ssl");

    private static final ByteBuffer __ZERO_BUFFER=BufferUtil.allocate(0);
    private static final ThreadLocal<SslBuffers> __buffers = new ThreadLocal<SslBuffers>();

    private final Lock _lock = new ReentrantLock();
    
    private final AtomicBoolean _writing = new AtomicBoolean();
    private final NetWriteCallback _netWriteCallback = new NetWriteCallback();


    private final SSLEngine _engine;
    private final SSLSession _session;
    private AsyncConnection _appConnection;
    private final AppEndPoint _appEndPoint;
    private int _allocations;
    private SslBuffers _buffers;
    private ByteBuffer _inNet;
    private ByteBuffer _inApp;
    private ByteBuffer _outNet;
    private AsyncEndPoint _endp;
    private boolean _allowRenegotiate=true;
    private boolean _handshook;
    private boolean _oshut;
    
    

    private final class NetWriteCallback implements Callback<Void>
    {
        @Override
        public void completed(Void context)
        {
            if (_writing.compareAndSet(true,false))
                _appEndPoint.completeWrite();
        }

        @Override
        public void failed(Void context, Throwable cause)
        {
            LOG.debug("write FAILED",cause);
            if (_writing.compareAndSet(true,false))
                _appEndPoint.writeFailed(cause);
        }
    }


    /* ------------------------------------------------------------ */
    /* this is a half baked buffer pool
     */
    private static class SslBuffers
    {
        final ByteBuffer _inNet;
        final ByteBuffer _outNet;
        final ByteBuffer _inApp;

        SslBuffers(int packetSize, int appSize)
        {
            _inNet=BufferUtil.allocateDirect(packetSize);
            _outNet=BufferUtil.allocateDirect(packetSize);
            _inApp=BufferUtil.allocate(appSize);
        }
    }

    /* ------------------------------------------------------------ */
    public SslConnection(SSLEngine engine,AsyncEndPoint endp,Executor executor)
    {
        this(engine,endp,System.currentTimeMillis(),executor);
    }

    /* ------------------------------------------------------------ */
    public SslConnection(SSLEngine engine,AsyncEndPoint endp, long timeStamp,Executor executor)
    {
        super(endp, executor);
        _engine=engine;
        _session=_engine.getSession();
        _endp=endp;
        _appEndPoint = newAppEndPoint();
    }

    /* ------------------------------------------------------------ */
    protected AppEndPoint newAppEndPoint()
    {
        return new AppEndPoint();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if SSL re-negotiation is allowed (default false)
     */
    public boolean isAllowRenegotiate()
    {
        return _allowRenegotiate;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set if SSL re-negotiation is allowed. CVE-2009-3555 discovered
     * a vulnerability in SSL/TLS with re-negotiation.  If your JVM
     * does not have CVE-2009-3555 fixed, then re-negotiation should
     * not be allowed.  CVE-2009-3555 was fixed in Sun java 1.6 with a ban
     * of renegotiates in u19 and with RFC5746 in u22.
     *
     * @param allowRenegotiate
     *            true if re-negotiation is allowed (default false)
     */
    public void setAllowRenegotiate(boolean allowRenegotiate)
    {
        _allowRenegotiate = allowRenegotiate;
    }

    /* ------------------------------------------------------------ */
    private void allocateBuffers()
    {
        if (!_lock.tryLock())
            throw new IllegalStateException();
        try
        {
            if (_allocations++==0)
            {
                if (_buffers==null)
                {
                    _buffers=__buffers.get();
                    if (_buffers==null)
                        _buffers=new SslBuffers(_session.getPacketBufferSize(),_session.getApplicationBufferSize());
                    _inNet=_buffers._inNet;
                    _outNet=_buffers._outNet;
                    _inApp=_buffers._inApp;
                    __buffers.set(null);
                }
            }
        }
        finally
        {
            _lock.unlock();
        }
    }

    /* ------------------------------------------------------------ */
    private void releaseBuffers()
    {
        if (!_lock.tryLock())
            throw new IllegalStateException();
        try
        {
            if (--_allocations==0)
            {
                if (_buffers!=null &&
                    _inNet.remaining()==0 &&
                    _outNet.remaining()==0 &&
                    _inApp.remaining()==0)
                {
                    _inNet=null;
                    _outNet=null;
                    _inApp=null;
                    _buffers._inNet.clear().limit(0);
                    _buffers._outNet.clear().limit(0);
                    _buffers._inApp.clear().limit(0);

                    __buffers.set(_buffers);
                    _buffers=null;
                }
            }
        }
        finally
        {
            _lock.unlock();
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onClose()
    {
        _appConnection.onClose();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onIdleExpired(long idleForMs)
    {
        _appConnection.onIdleExpired(idleForMs);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onReadable()
    {
        _lock.lock();
        try
        {
            LOG.debug("onReadable {}",this);

            allocateBuffers();

            boolean progress=true;
            while(progress)
            {
                progress=false;

                // Read into the input network buffer
                if (!BufferUtil.isFull(_inNet))
                {
                    int filled = _endp.fill(_inNet);
                    LOG.debug("filled {}",filled);
                    if (filled>0)
                        progress=true;
                }

                // process the data
                progress|=process(null);

            }
        }
        catch(IOException e)
        {
            LOG.warn(e);
        }
        finally
        {
            releaseBuffers();
            if (_appEndPoint._readCallback!=null &&  !BufferUtil.isFull(_inNet))
                scheduleOnReadable();

            _lock.unlock();
        }

    }

    /* ------------------------------------------------------------ */
    @Override
    public void onReadFail(Throwable cause)
    {
        _lock.lock();
        try
        {
            _appEndPoint.readFailed(cause);
        }
        finally
        {
            _lock.unlock();
        }
    }

    /* ------------------------------------------------------------ */
    private boolean process(ByteBuffer appOut) throws IOException
    {
        boolean some_progress=false;

        if (!_lock.tryLock())
            throw new IllegalStateException();

        try
        {
            allocateBuffers();

            // If we have no data to flush, flush the empty buffer
            if (appOut==null)
                appOut=__ZERO_BUFFER;

            // While we are making progress processing SSL engine
            boolean progress=true;
            while (progress)
            {
                progress=false;

                // handle the current hand share status
                switch(_engine.getHandshakeStatus())
                {
                    case FINISHED:
                        throw new IllegalStateException();

                    case NOT_HANDSHAKING:
                    {
                        // Try unwrapping some application data
                        if (!BufferUtil.isFull(_inApp) && BufferUtil.hasContent(_inNet))
                            progress|=unwrap();

                        // Try wrapping some application data
                        if (BufferUtil.hasContent(appOut) && !BufferUtil.isFull(_outNet))
                            progress|=wrap(appOut);
                    }
                    break;

                    case NEED_TASK:
                    {
                        // A task needs to be run, so run it!
                        Runnable task;
                        while ((task=_engine.getDelegatedTask())!=null)
                        {
                            progress=true;
                            task.run();
                        }
                    }
                    break;

                    case NEED_WRAP:
                    {
                        // The SSL needs to send some handshake data to the other side
                        if (_handshook && !_allowRenegotiate)
                            _endp.close();
                        else
                            progress|=wrap(appOut);
                    }
                    break;

                    case NEED_UNWRAP:
                    {
                        // The SSL needs to receive some handshake data from the other side
                        if (_handshook && !_allowRenegotiate)
                            _endp.close();
                        else if (BufferUtil.isEmpty(_inNet) && _endp.isInputShutdown())
                            _endp.close();
                        else
                            progress|=unwrap();
                    }
                    break;
                }

                // pass on ishut/oshut state
                if (_endp.isOpen() && _endp.isInputShutdown() && BufferUtil.isEmpty(_inNet))
                    _engine.closeInbound();
                if (_endp.isOpen() && _engine.isOutboundDone() && BufferUtil.isEmpty(_outNet))
                    _endp.shutdownOutput();

                // remember if any progress has been made
                some_progress|=progress;
            }
        }
        catch(SSLException e)
        {
            LOG.warn(e.toString());
            LOG.debug(e);
            _endp.close();
        }
        finally
        {
            // Has the net data consumed allowed us to release net backpressure?
            if (BufferUtil.compact(_inNet) && _appEndPoint._readCallback!=null)
                scheduleOnReadable();

            releaseBuffers();
            _lock.unlock();
        }
        return some_progress;
    }

    private boolean wrap(final ByteBuffer outApp) throws IOException
    {
        if (_writing.get())
            return false;

        final SSLEngineResult result;

        int pos=BufferUtil.flipToFill(_outNet);
        try
        {
            result=_engine.wrap(outApp,_outNet);
        }
        finally
        {
            BufferUtil.flipToFlush(_outNet,pos);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} wrap {} {} consumed={} produced={}",
                    _session,
                    result.getStatus(),
                    result.getHandshakeStatus(),
                    result.bytesConsumed(),
                    result.bytesProduced());

        switch(result.getStatus())
        {
            case BUFFER_UNDERFLOW:
                throw new IllegalStateException();

            case BUFFER_OVERFLOW:
                break;

            case OK:
                if (result.getHandshakeStatus()==HandshakeStatus.FINISHED)
                    _handshook=true;
                break;

            case CLOSED:
                LOG.debug("wrap CLOSE {} {}",this,result);
                if (result.getHandshakeStatus()==HandshakeStatus.FINISHED)
                    _endp.close();
                break;

            default:
                LOG.debug("{} wrap default {}",_session,result);
            throw new IOException(result.toString());
        }

        if (BufferUtil.hasContent(_outNet) && _writing.compareAndSet(false,true))
            _endp.write(null,_netWriteCallback,_outNet);

        return result.bytesConsumed()>0 || result.bytesProduced()>0 ;
    }

    private boolean unwrap() throws IOException
    {
        if (BufferUtil.isEmpty(_inNet))
        {
            scheduleOnReadable();
            LOG.debug("{} unwrap read {}",_session);
            return false;
        }

        final SSLEngineResult result;

        int pos = BufferUtil.flipToFill(_inApp);
        try
        {
            result=_engine.unwrap(_inNet, _inApp);
        }
        catch(SSLException e)
        {
            LOG.debug(String.valueOf(_endp), e);
            _endp.close();
            throw e;
        }
        finally
        {
            BufferUtil.flipToFlush(_inApp,pos);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} unwrap {} {} consumed={} produced={}",
                    _session,
                    result.getStatus(),
                    result.getHandshakeStatus(),
                    result.bytesConsumed(),
                    result.bytesProduced());

        switch(result.getStatus())
        {
            case BUFFER_UNDERFLOW:
                // need to wait for more net data
                if (_endp.isInputShutdown())
                    _inNet.clear().limit(0);
                else 
                    scheduleOnReadable();

                break;

            case BUFFER_OVERFLOW:
                // need to wait until more app data has been consumed.
                LOG.debug("{} unwrap {} {}->{}",_session,result.getStatus(),_inNet,_inApp);
                break;

            case OK:
                if (result.getHandshakeStatus()==HandshakeStatus.FINISHED)
                    _handshook=true;
                break;

            case CLOSED:
                LOG.debug("unwrap CLOSE {} {}",this,result);
                if (result.getHandshakeStatus()==HandshakeStatus.FINISHED)
                    _endp.close();
                break;

            default:
                LOG.debug("{} wrap default {}",_session,result);
            throw new IOException(result.toString());
        }

        // If any bytes were produced and we have an app read waiting, make it ready.
        if (result.bytesProduced()>0)
            _appEndPoint.readCompleted();

        return result.bytesConsumed()>0 || result.bytesProduced()>0;
    }

    /* ------------------------------------------------------------ */
    public AsyncEndPoint getAppEndPoint()
    {
        return _appEndPoint;
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("%s %s", super.toString(), _appEndPoint);
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class AppEndPoint extends AbstractEndPoint implements AsyncEndPoint
    {
        ByteBuffer[] _writeBuffers;
        private Callback _readCallback;
        private Object _readContext;
        private Callback _writeCallback;
        private Object _writeContext;
        
        
        AppEndPoint()
        {
            super(_endp.getLocalAddress(),_endp.getRemoteAddress());
        }

        /* ------------------------------------------------------------ */
        private void readCompleted()
        {
            if (_readCallback!=null)
            {
                Callback cb=_readCallback;
                Object ctx=_readContext;
                _readCallback=null;
                _readContext=null;
                cb.completed(ctx); // TODO after lock released?
            }
        }
        
        /* ------------------------------------------------------------ */
        private void writeCompleted()
        {
            if (_writeCallback!=null)
            {
                Callback cb=_writeCallback;
                Object ctx=_writeContext;
                _writeCallback=null;
                _writeContext=null;
                cb.completed(ctx); // TODO after lock released?
            }
        }
        
        /* ------------------------------------------------------------ */
        private void readFailed(Throwable cause)
        {
            if (_readCallback!=null)
            {
                Callback cb=_readCallback;
                Object ctx=_readContext;
                _readCallback=null;
                _readContext=null;
                cb.failed(ctx,cause); // TODO after lock released?
            }
        }
        
        /* ------------------------------------------------------------ */
        private void writeFailed(Throwable cause)
        {
            if (_writeCallback!=null)
            {
                Callback cb=_writeCallback;
                Object ctx=_writeContext;
                _writeCallback=null;
                _writeContext=null;
                cb.failed(ctx,cause); // TODO after lock released?
            }
        }

        
        
        public SSLEngine getSslEngine()
        {
            return _engine;
        }

        @Override
        public void shutdownOutput()
        {
            _lock.lock();
            try
            {
                LOG.debug("{} ssl endp.oshut {}",_session,this);
                _engine.closeOutbound();
                _oshut=true;
                process(null);
            }
            catch (IOException e)
            {
                LOG.debug(e);
            }
            finally
            {
                _lock.unlock();
            }
        }

        @Override
        public boolean isOutputShutdown()
        {
            _lock.lock();
            try
            {
                return _oshut||!isOpen()||_engine.isOutboundDone();
            }
            finally
            {
                _lock.unlock();
            }
        }

        @Override
        public boolean isInputShutdown()
        {
            _lock.lock();
            try
            {
                return !isOpen()||_engine.isInboundDone();
            }
            finally
            {
                _lock.unlock();
            }
        }

        @Override
        public void close()
        {
            LOG.debug("{} ssl endp.close",_session);
            _endp.close();
        }

        @Override
        public int fill(ByteBuffer buffer) throws IOException
        {
            int size=buffer.remaining();
            _lock.lock();
            try
            {
                if (!BufferUtil.hasContent(_inApp))
                    process(null);

                if (BufferUtil.hasContent(_inApp))
                {
                    BufferUtil.append(_inApp,buffer);
                    BufferUtil.compact(_inApp);
                }
            }
            finally
            {
                _lock.unlock();
            }
            int filled=buffer.remaining()-size;

            if (filled==0 && _endp.isInputShutdown())
                return -1;
            return filled;
        }

        @Override
        public int flush(ByteBuffer... buffers) throws IOException
        {
            _lock.lock();
            try
            {
                int len=0;
                bufloop: for (ByteBuffer b : buffers)
                {
                    while (b.hasRemaining())
                    {
                        int l = b.remaining();
                        if (!process(b))
                            break bufloop;
                        l=l-b.remaining();

                        if (l>0)
                            len+=l;
                        else
                            break bufloop;
                    }
                }
                return len;
            }
            finally
            {
                _lock.unlock();
            }
        }

        @Override
        public boolean isOpen()
        {
            return _endp.isOpen();
        }

        @Override
        public Object getTransport()
        {
            return _endp;
        }

        @Override
        public void setCheckForIdle(boolean check)
        {
            _endp.setCheckForIdle(check);
        }

        @Override
        public boolean isCheckForIdle()
        {
            return _endp.isCheckForIdle();
        }


        @Override
        public String toString()
        {
            // Do NOT use _lock.lock();try
            // because it's very easy to deadlock when debugging is enabled.
            // We do a best effort to print the right toString() and that's it.
            ByteBuffer inbound = _inNet;
            ByteBuffer outbound = _outNet;
            ByteBuffer unwrap = _inApp;
            int i = inbound == null? -1 : inbound.remaining();
            int o = outbound == null ? -1 : outbound.remaining();
            int u = unwrap == null ? -1 : unwrap.remaining();
            return String.format("SSL%s[%s,i/o/u=%d/%d/%d,ep.ishut=%b,oshut=%b,r=%s,w=%s}-{%s}",
                    super.toString(),
                    _engine.getHandshakeStatus(),
                    i, o, u,
                    _endp.isInputShutdown(), _oshut,
                    _readCallback,_writeCallback,
                    _appConnection);
        }


        @Override
        public long getIdleTimestamp()
        {
            return _endp.getIdleTimestamp();
        }

        @Override
        public long getCreatedTimeStamp()
        {
            return _endp.getCreatedTimeStamp();
        }

        @Override    
        public <C> void readable(C context, Callback<C> callback) throws IllegalStateException
        {
            _lock.lock();
            try
            {
                if (_readCallback != null)
                    throw new IllegalStateException("previous read not complete");

                // Do we already have application input data?
                if (BufferUtil.hasContent(_inApp))
                {
                    callback.completed(context);
                    return;
                }

                // No, we need to schedule a network read
                _readContext=context;
                _readCallback=callback;
                scheduleOnReadable();
            }
            finally
            {
                _lock.unlock();
            }
        }

        @Override
        public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IllegalStateException
        {
            _lock.lock();
            try
            {
                if (_writeCallback!=null)
                    throw new IllegalStateException("previous write not complete");

                // Try to process all
                for (ByteBuffer b : buffers)
                {
                    process(b);

                    if (b.hasRemaining())
                    {
                        _writeBuffers=buffers;
                        _writeContext=context;
                        _writeCallback=callback;
                        return;
                    }
                }
                callback.completed(context);
            }
            catch (IOException e)
            {
                callback.failed(context,e);
            }
            finally
            {
                _lock.unlock();
            }
        }

        void completeWrite()
        {
            _lock.lock();
            try
            {
                // Try to process all
                for (ByteBuffer b : _writeBuffers)
                {
                    process(b);

                    if (b.hasRemaining())
                        return;
                }
                writeCompleted();
            }
            catch (IOException e)
            {
                writeFailed(e);
            }
            finally
            {
                _lock.unlock();
            }
        }

        @Override
        public AsyncConnection getAsyncConnection()
        {
            return _appConnection;
        }

        @Override
        public void setAsyncConnection(AsyncConnection connection)
        {
            _appConnection = connection;
        }
    }
}
