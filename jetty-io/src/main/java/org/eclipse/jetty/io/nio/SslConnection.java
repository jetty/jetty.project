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

package org.eclipse.jetty.io.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/** SSL Connection.
 * An AysyncConnection that acts as an interceptor between and EndPoint and another
 * Connection, that implements TLS encryption using an {@link SSLEngine}.
 * <p>
 * The connector uses an {@link AsyncEndPoint} (like {@link SelectChannelEndPoint}) as
 * it's source/sink of encrypted data.   It then provides {@link #getSslEndPoint()} to
 * expose a source/sink of unencrypted data to another connection (eg HttpConnection).
 */
public class SslConnection extends AbstractConnection
{
    private final Logger _logger = Log.getLogger("org.eclipse.jetty.io.nio.ssl");

    private static final ByteBuffer __ZERO_BUFFER=BufferUtil.allocate(0);

    private static final ThreadLocal<SslBuffers> __buffers = new ThreadLocal<SslBuffers>();
    private final SSLEngine _engine;
    private final SSLSession _session;
    private Connection _connection;
    private final SslEndPoint _sslEndPoint;
    private int _allocations;
    private SslBuffers _buffers;
    private ByteBuffer _inbound;
    private ByteBuffer _unwrapBuf;
    private ByteBuffer _outbound;
    private AsyncEndPoint _aEndp;
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
        final ByteBuffer _in;
        final ByteBuffer _out;
        final ByteBuffer _unwrap;

        SslBuffers(int packetSize, int appSize)
        {
            _in=BufferUtil.allocateDirect(packetSize);
            _out=BufferUtil.allocateDirect(packetSize);
            _unwrap=BufferUtil.allocate(appSize);
        }
    }

    /* ------------------------------------------------------------ */
    public SslConnection(SSLEngine engine,AsyncEndPoint endp)
    {
        this(engine,endp,System.currentTimeMillis());
    }

    /* ------------------------------------------------------------ */
    public SslConnection(SSLEngine engine,AsyncEndPoint endp, long timeStamp)
    {
        super(endp,timeStamp);
        _engine=engine;
        _session=_engine.getSession();
        _aEndp=(AsyncEndPoint)endp;
        _sslEndPoint = newSslEndPoint();
    }

    /* ------------------------------------------------------------ */
    protected SslEndPoint newSslEndPoint()
    {
        return new SslEndPoint();
    }

    /* ------------------------------------------------------------ */
    public EndPoint getEndPoint()
    {
        return _aEndp;
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
                        _buffers=new SslBuffers(_session.getPacketBufferSize()*2,_session.getApplicationBufferSize()*2);
                    _inbound=_buffers._in;
                    _outbound=_buffers._out;
                    _unwrapBuf=_buffers._unwrap;
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
                    _inbound.remaining()==0 &&
                    _outbound.remaining()==0 &&
                    _unwrapBuf.remaining()==0)
                {
                    _inbound=null;
                    _outbound=null;
                    _unwrapBuf=null;
                    _buffers._in.clear().limit(0);
                    _buffers._out.clear().limit(0);
                    _buffers._unwrap.clear().limit(0);
                    
                    __buffers.set(_buffers);
                    _buffers=null;
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void canRead() throws IOException
    {
        try
        {
            allocateBuffers();

            boolean progress=true;

            while (progress)
            {
                progress=false;

                // If we are handshook let the delegate connection
                if (_engine.getHandshakeStatus()!=HandshakeStatus.NOT_HANDSHAKING)
                    progress=process(null,null);

                // handle the delegate connection
                _connection.canRead();

                _logger.debug("{} handle {} progress={}", _session, this, progress);
            }
        }
        finally
        {
            releaseBuffers();

            if (!_ishut && _sslEndPoint.isInputShutdown() && _sslEndPoint.isOpen())
            {
                _ishut=true;
                try
                {
                    _connection.onInputShutdown();
                }
                catch(Throwable x)
                {
                    _logger.warn("onInputShutdown failed", x);
                    try{_sslEndPoint.close();}
                    catch(IOException e2){
                        _logger.ignore(e2);}
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void canWrite() throws IOException
    {
        // TODO
    }
    
    
    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return _connection.isIdle();
    }

    /* ------------------------------------------------------------ */
    public boolean isReadInterested()
    {
        return _connection.isReadInterested();
    }

    /* ------------------------------------------------------------ */
    public void onClose()
    {
        _connection.onClose();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onIdleExpired(long idleForMs)
    {
        try
        {
            _logger.debug("onIdleExpired {}ms on {}",idleForMs,this);
            if (_endp.isOutputShutdown())
                _sslEndPoint.close();
            else
                _sslEndPoint.shutdownOutput();
        }
        catch (IOException e)
        {
            _logger.warn(e);
            super.onIdleExpired(idleForMs);
        }
    }

    /* ------------------------------------------------------------ */
    public void onInputShutdown() throws IOException
    {

    }

    /* ------------------------------------------------------------ */
    private synchronized boolean process(ByteBuffer toFill, ByteBuffer toFlush) throws IOException
    {
        boolean some_progress=false;
        try
        {
            // We need buffers to progress
            allocateBuffers();

            // if we don't have a buffer to put received data into
            if (toFill==null)
            {
                // use the unwrapbuffer to hold received data.
                _unwrapBuf.compact().flip();
                toFill=_unwrapBuf;
            }
            // Else if the fill buffer is too small for the SSL session
            else if (toFill.capacity()<_session.getApplicationBufferSize())
            {
                // fill to the temporary unwrapBuffer
                boolean progress=process(null,toFlush);

                // if we received any data,
                if (BufferUtil.hasContent(_unwrapBuf))
                {
                    // transfer from temp buffer to fill buffer
                    BufferUtil.flipPutFlip(_unwrapBuf,toFill);
                    return true;
                }
                else
                    // return progress from recursive call
                    return progress;
            }
            // Else if there is some temporary data
            else if (BufferUtil.hasContent(_unwrapBuf))
            {
                // transfer from temp buffer to fill buffer
                BufferUtil.flipPutFlip(_unwrapBuf,toFill);
                return true;
            }

            // If we are here, we have a buffer ready into which we can put some read data.

            // If we have no data to flush, flush the empty buffer
            if (toFlush==null)
                toFlush=__ZERO_BUFFER;

            // While we are making progress processing SSL engine
            boolean progress=true;
            while (progress)
            {
                progress=false;

                // Do any real IO
                int filled=0,flushed=0;
                try
                {
                    // Read any available data
                    if (!BufferUtil.isFull(_inbound) && (filled=_endp.fill(_inbound))>0)
                        progress = true;
                    else
                        _inbound.compact().flip();

                    // flush any output data
                    if (BufferUtil.hasContent(_outbound) && (flushed=_endp.flush(_outbound))>0)
                    {
                        progress = true;
                        _outbound.compact().flip();
                    }
                    
                }
                catch (IOException e)
                {
                    _endp.close();
                    throw e;
                }
                finally
                {
                    _logger.debug("{} {} {} filled={}/{} flushed={}/{}",_session,this,_engine.getHandshakeStatus(),filled,_inbound.remaining(),flushed,_outbound.remaining());
                }

                // handle the current hand share status
                switch(_engine.getHandshakeStatus())
                {
                    case FINISHED:
                        throw new IllegalStateException();

                    case NOT_HANDSHAKING:
                    {
                        // Try unwrapping some application data
                        if (!BufferUtil.isFull(toFill) && BufferUtil.hasContent(_inbound) && unwrap(toFill))
                            progress=true;

                        // Try wrapping some application data
                        if (BufferUtil.hasContent(toFlush) && !BufferUtil.isFull(_outbound) && wrap(toFlush))
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
                        else if (wrap(toFlush))
                            progress=true;
                    }
                    break;

                    case NEED_UNWRAP:
                    {
                        // The SSL needs to receive some handshake data from the other side
                        if (_handshook && !_allowRenegotiate)
                            _endp.close();
                        else if (BufferUtil.isEmpty(_inbound)&&filled==-1)
                        {
                            // No more input coming
                            _endp.shutdownInput();
                        }
                        else if (unwrap(toFill))
                            progress=true;
                    }
                    break;
                }

                // pass on ishut/oshut state
                if (_endp.isOpen() && _endp.isInputShutdown() && BufferUtil.isEmpty(_inbound))
                    _engine.closeInbound();

                if (_endp.isOpen() && _engine.isOutboundDone() && BufferUtil.isEmpty(_outbound))
                    _endp.shutdownOutput();

                // remember if any progress has been made
                some_progress|=progress;
            }

            // If we are reading into the temp buffer and it has some content, then we should be dispatched.
            if (toFill==_unwrapBuf && BufferUtil.hasContent(_unwrapBuf))
                _aEndp.asyncDispatch();
        }
        finally
        {
            releaseBuffers();
            if (some_progress)
                _progressed.set(true);
        }
        return some_progress;
    }

    private synchronized boolean wrap(final ByteBuffer buffer) throws IOException
    {
        final SSLEngineResult result;

        _outbound.compact();
        result=_engine.wrap(buffer,_outbound);
        if (_logger.isDebugEnabled())
            _logger.debug("{} wrap {} {} consumed={} produced={}",
                    _session,
                    result.getStatus(),
                    result.getHandshakeStatus(),
                    result.bytesConsumed(),
                    result.bytesProduced());
        _outbound.flip();

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
                _logger.debug("wrap CLOSE {} {}",this,result);
                if (result.getHandshakeStatus()==HandshakeStatus.FINISHED)
                    _endp.close();
                break;

            default:
                _logger.debug("{} wrap default {}",_session,result);
            throw new IOException(result.toString());
        }

        return result.bytesConsumed()>0 || result.bytesProduced()>0;
    }

    private synchronized boolean unwrap(final ByteBuffer buffer) throws IOException
    {
        if (BufferUtil.isEmpty(_inbound))
            return false;

        final SSLEngineResult result;

        try
        {
            buffer.compact();
            result=_engine.unwrap(_inbound,buffer);
            buffer.flip();
            
            if (_logger.isDebugEnabled())
                _logger.debug("{} unwrap {} {} consumed={} produced={}",
                        _session,
                        result.getStatus(),
                        result.getHandshakeStatus(),
                        result.bytesConsumed(),
                        result.bytesProduced());
            
        }
        catch(SSLException e)
        {
            _logger.debug(String.valueOf(_endp), e);
            _endp.close();
            throw e;
        }

        switch(result.getStatus())
        {
            case BUFFER_UNDERFLOW:
                _inbound.compact().flip();
                if (_endp.isInputShutdown())
                    _inbound.clear().limit(0);
                break;

            case BUFFER_OVERFLOW:
                _logger.debug("{} unwrap {} {}->{}",_session,result.getStatus(),_inbound,buffer);
                break;

            case OK:
                if (result.getHandshakeStatus()==HandshakeStatus.FINISHED)
                    _handshook=true;
                break;

            case CLOSED:
                _logger.debug("unwrap CLOSE {} {}",this,result);
                if (result.getHandshakeStatus()==HandshakeStatus.FINISHED)
                    _endp.close();
                break;

            default:
                _logger.debug("{} wrap default {}",_session,result);
            throw new IOException(result.toString());
        }

        //if (LOG.isDebugEnabled() && result.bytesProduced()>0)
        //    LOG.debug("{} unwrapped '{}'",_session,buffer);

        return result.bytesConsumed()>0 || result.bytesProduced()>0;
    }

    /* ------------------------------------------------------------ */
    public AsyncEndPoint getSslEndPoint()
    {
        return _sslEndPoint;
    }

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return String.format("%s %s", super.toString(), _sslEndPoint);
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public class SslEndPoint implements AsyncEndPoint
    {
        public SSLEngine getSslEngine()
        {
            return _engine;
        }

        public AsyncEndPoint getIoEndPoint()
        {
            return _aEndp;
        }

        public void shutdownOutput() throws IOException
        {
            synchronized (SslConnection.this)
            {
                _logger.debug("{} ssl endp.oshut {}",_session,this);
                _engine.closeOutbound();
                _oshut=true;
            }
            flush();
        }

        public boolean isOutputShutdown()
        {
            synchronized (SslConnection.this)
            {
                return _oshut||!isOpen()||_engine.isOutboundDone();
            }
        }

        public void shutdownInput() throws IOException
        {
            _logger.debug("{} ssl endp.ishut!",_session);
            // We do not do a closeInput here, as SSL does not support half close.
            // isInputShutdown works it out itself from buffer state and underlying endpoint state.
        }

        public boolean isInputShutdown()
        {
            synchronized (SslConnection.this)
            {
                return _endp.isInputShutdown() &&
                !(_unwrapBuf!=null&&BufferUtil.hasContent(_unwrapBuf)) &&
                !(_inbound!=null&&BufferUtil.hasContent(_inbound));
            }
        }

        public void close() throws IOException
        {
            _logger.debug("{} ssl endp.close",_session);
            _endp.close();
        }

        public int fill(ByteBuffer buffer) throws IOException
        {
            int size=buffer.remaining();
            process(buffer, null);

            int filled=buffer.remaining()-size;

            if (filled==0 && isInputShutdown())
                return -1;
            return filled;
        }

        public int flush(ByteBuffer... buffers) throws IOException
        {
            int len=0;
            for (ByteBuffer b : buffers)
            {
                if (b.hasRemaining())
                {
                    int l = b.remaining();
                    process(null, b);
                    l=l-b.remaining();
                    
                    if (l>0)
                        len+=l;
                    else
                        break;
                }
            }
            return len;
        }

        public boolean isOpen()
        {
            return _endp.isOpen();
        }

        public Object getTransport()
        {
            return _endp;
        }

        public void flush() throws IOException
        {
            process(null, null);
        }

        public void onIdleExpired(long idleForMs)
        {
            _aEndp.onIdleExpired(idleForMs);
        }

        public void setCheckForIdle(boolean check)
        {
            _aEndp.setCheckForIdle(check);
        }

        public boolean isCheckForIdle()
        {
            return _aEndp.isCheckForIdle();
        }

        public InetSocketAddress getLocalAddress()
        {
            return _aEndp.getLocalAddress();
        }

        public InetSocketAddress getRemoteAddress()
        {
            return _aEndp.getRemoteAddress();
        }

        public boolean isBlocking()
        {
            return false;
        }

        public int getMaxIdleTime()
        {
            return _aEndp.getMaxIdleTime();
        }

        public void setMaxIdleTime(int timeMs) throws IOException
        {
            _aEndp.setMaxIdleTime(timeMs);
        }
        
        public Connection getConnection()
        {
            return _connection;
        }

        public void setConnection(Connection connection)
        {
            _connection=(Connection)connection;
        }

        public String toString()
        {
            // Do NOT use synchronized (SslConnection.this)
            // because it's very easy to deadlock when debugging is enabled.
            // We do a best effort to print the right toString() and that's it.
            ByteBuffer inbound = _inbound;
            ByteBuffer outbound = _outbound;
            ByteBuffer unwrap = _unwrapBuf;
            int i = inbound == null? -1 : inbound.remaining();
            int o = outbound == null ? -1 : outbound.remaining();
            int u = unwrap == null ? -1 : unwrap.remaining();
            return String.format("SSL %s i/o/u=%d/%d/%d ishut=%b oshut=%b {%s}",
                    _engine.getHandshakeStatus(),
                    i, o, u,
                    _ishut, _oshut,
                    _connection);
        }
    }
}
