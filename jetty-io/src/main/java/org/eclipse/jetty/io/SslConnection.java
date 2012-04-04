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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.util.BufferUtil;
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
public class SslConnection extends SelectableConnection
{
    private static final Logger LOG = Log.getLogger("org.eclipse.jetty.io.nio.ssl");

    private static final ByteBuffer __ZERO_BUFFER=BufferUtil.allocate(0);

    private static final ThreadLocal<SslBuffers> __buffers = new ThreadLocal<SslBuffers>();
    private final SSLEngine _engine;
    private final SSLSession _session;
    private SelectableConnection _appConnection;
    private final AppEndPoint _appEndPoint;
    private int _allocations;
    private SslBuffers _buffers;
    private ByteBuffer _inNet;
    private ByteBuffer _inApp;
    private ByteBuffer _outNet;
    private SelectableEndPoint _endp;
    private boolean _allowRenegotiate=true;
    private boolean _handshook;
    private boolean _ishut;
    private boolean _oshut;
    private final AtomicBoolean _progressed = new AtomicBoolean();

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
    public SslConnection(SSLEngine engine,SelectableEndPoint endp)
    {
        this(engine,endp,System.currentTimeMillis());
    }

    /* ------------------------------------------------------------ */
    public SslConnection(SSLEngine engine,SelectableEndPoint endp, long timeStamp)
    {
        super(endp);
        _engine=engine;
        _session=_engine.getSession();
        _endp=endp;
        _appEndPoint = newAppEndPoint();
    }


    /* ------------------------------------------------------------ */
    public void setAppConnection(SelectableConnection connection)
    {        
        _appConnection=connection;
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
        synchronized (this)
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
    }

    /* ------------------------------------------------------------ */
    private void releaseBuffers()
    {
        synchronized (this)
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
    }
    /* ------------------------------------------------------------ */
    @Override
    public boolean isIdle()
    {
        return _appConnection.isIdle();
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
        try
        {
            LOG.debug("onIdleExpired {}ms on {}",idleForMs,this);
            if (_endp.isOutputShutdown())
                _appEndPoint.close();
            else
                _appEndPoint.shutdownOutput();
        }
        catch (IOException e)
        {
            LOG.warn(e);
            super.onIdleExpired(idleForMs);
        }
    }


    /* ------------------------------------------------------------ */
    @Override
    public void doRead()
    {
        try
        {        
            allocateBuffers();     

            boolean progress=true;
            while(progress)
            {
                progress=false;

                // Fill the input buffer with everything available
                if (!BufferUtil.isFull(_inNet))
                    progress|=_endp.fill(_inNet)>0;
                    
                progress|=process(null);
                
                if (BufferUtil.hasContent(_inApp) && _appEndPoint.isReadInterested())
                {
                    progress=true;
                    Runnable task =_appConnection.onReadable();
                    if (task!=null)
                        task.run();
                }
            }
        }
        catch(IOException e)
        {
            LOG.warn(e);
        }
        finally
        {
            releaseBuffers();
            _endp.setReadInterested(_appEndPoint.isReadInterested());
            _endp.setWriteInterested(BufferUtil.hasContent(_outNet));
        }
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void doWrite()
    {
        try
        {
            while (BufferUtil.hasContent(_outNet))
            {
                int written = _endp.flush(_outNet);

                if (written>0 && _appEndPoint.isWriteInterested())
                {
                    Runnable task =_appConnection.onWriteable();
                    if (task!=null)
                        task.run();
                }
            } 
        }
        catch(IOException e)
        {
            LOG.warn(e);
        }
        finally
        {
            if (BufferUtil.hasContent(_outNet))
                _endp.setWriteInterested(true);
        }
    }

    /* ------------------------------------------------------------ */
    private synchronized boolean process(ByteBuffer appOut) throws IOException
    {
        boolean some_progress=false;
        try
        {
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
                        if (!BufferUtil.isFull(_inApp) && BufferUtil.hasContent(_inNet) && unwrap())
                            progress=true;

                        // Try wrapping some application data
                        if (BufferUtil.hasContent(appOut) && !BufferUtil.isFull(_outNet) && wrap(appOut))
                            progress=true;
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
                        else if (wrap(appOut))
                            progress=true;
                    }
                    break;

                    case NEED_UNWRAP:
                    {
                        // The SSL needs to receive some handshake data from the other side
                        if (_handshook && !_allowRenegotiate)
                            _endp.close();
                        else if (BufferUtil.isEmpty(_inNet) && _endp.isInputShutdown())
                            _endp.close();
                        else if (unwrap())
                            progress=true;
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
        finally
        {
            if (some_progress)
                _progressed.set(true);
        }
        return some_progress;
    }

    private synchronized boolean wrap(final ByteBuffer outApp) throws IOException
    {
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

        int flushed = _endp.flush(_outNet);
        
        return result.bytesConsumed()>0 || result.bytesProduced()>0 || flushed>0;
    }

    private synchronized boolean unwrap() throws IOException
    {
        if (BufferUtil.isEmpty(_inNet))
            return false;

        final SSLEngineResult result;

        int pos = BufferUtil.flipToFill(_inApp);
        try
        {
            result=_engine.unwrap(_inNet,_inApp);            
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
                _inNet.compact().flip();
                if (_endp.isInputShutdown())
                    _inNet.clear().limit(0);
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

        //if (LOG.isDebugEnabled() && result.bytesProduced()>0)
        //    LOG.debug("{} unwrapped '{}'",_session,buffer);

        return result.bytesConsumed()>0 || result.bytesProduced()>0;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onInputShutdown()
    {        
    }
    
    /* ------------------------------------------------------------ */
    public SelectableEndPoint getAppEndPoint()
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
    public class AppEndPoint implements SelectableEndPoint
    {
        boolean _readInterested=true;
        boolean _writeInterested;
        
        public SSLEngine getSslEngine()
        {
            return _engine;
        }

        public EndPoint getIoEndPoint()
        {
            return _endp;
        }

        @Override
        public void shutdownOutput() throws IOException
        {
            synchronized (SslConnection.this)
            {
                LOG.debug("{} ssl endp.oshut {}",_session,this);
                _engine.closeOutbound();
                _oshut=true;
            }
            flush();
        }

        @Override
        public boolean isOutputShutdown()
        {
            synchronized (SslConnection.this)
            {
                return _oshut||!isOpen()||_engine.isOutboundDone();
            }
        }

        @Override
        public void shutdownInput() throws IOException
        {
            LOG.debug("{} ssl endp.ishut!",_session);
            // We do not do a closeInput here, as SSL does not support half close.
            // isInputShutdown works it out itself from buffer state and underlying endpoint state.
        }

        @Override
        public boolean isInputShutdown()
        {
            synchronized (SslConnection.this)
            {
                return _endp.isInputShutdown() &&
                !(_inApp!=null&&BufferUtil.hasContent(_inApp)) &&
                !(_inNet!=null&&BufferUtil.hasContent(_inNet));
            }
        }

        @Override
        public void close() throws IOException
        {
            LOG.debug("{} ssl endp.close",_session);
            _endp.close();
        }

        @Override
        public int fill(ByteBuffer buffer) throws IOException
        {
            int size=buffer.remaining();
            synchronized (this)
            {
                if (!BufferUtil.hasContent(_inApp))
                    process(null);

                if (BufferUtil.hasContent(_inApp))
                    BufferUtil.flipPutFlip(_inApp,buffer);
            }
            int filled=buffer.remaining()-size;

            if (filled==0 && isInputShutdown())
                return -1;
            return filled;
        }

        @Override
        public int flush(ByteBuffer... buffers) throws IOException
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

        public void flush() throws IOException
        {
            process(null);
        }

        @Override
        public void onIdleExpired(long idleForMs)
        {
            _endp.onIdleExpired(idleForMs);
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
        public InetSocketAddress getLocalAddress()
        {
            return _endp.getLocalAddress();
        }

        @Override
        public InetSocketAddress getRemoteAddress()
        {
            return _endp.getRemoteAddress();
        }

        @Override
        public int getMaxIdleTime()
        {
            return _endp.getMaxIdleTime();
        }

        @Override
        public void setMaxIdleTime(int timeMs) throws IOException
        {
            _endp.setMaxIdleTime(timeMs);
        }

        @Override
        public SelectableConnection getSelectableConnection()
        {
            return _appConnection;
        }

        public void setSelectableConnection(SelectableConnection connection)
        {
            _appConnection=(SelectableConnection)connection;
        }

        @Override
        public String toString()
        {
            // Do NOT use synchronized (SslConnection.this)
            // because it's very easy to deadlock when debugging is enabled.
            // We do a best effort to print the right toString() and that's it.
            ByteBuffer inbound = _inNet;
            ByteBuffer outbound = _outNet;
            ByteBuffer unwrap = _inApp;
            int i = inbound == null? -1 : inbound.remaining();
            int o = outbound == null ? -1 : outbound.remaining();
            int u = unwrap == null ? -1 : unwrap.remaining();
            return String.format("SSL %s i/o/u=%d/%d/%d ishut=%b oshut=%b {%s}",
                    _engine.getHandshakeStatus(),
                    i, o, u,
                    _ishut, _oshut,
                    _appConnection);
        }

        @Override
        public void setWriteInterested(boolean interested)
        {
            _writeInterested=interested;
        }

        @Override
        public boolean isWriteInterested()
        {
            return _writeInterested;
        }

        @Override
        public void setReadInterested(boolean interested)
        {
            _readInterested=interested;
        }

        @Override
        public boolean isReadInterested()
        {
            return _readInterested;
        }

        @Override
        public long getLastNotIdleTimestamp()
        {
            return _endp.getLastNotIdleTimestamp();
        }

        @Override
        public void checkForIdle(long now)
        {
        }
    }
}
