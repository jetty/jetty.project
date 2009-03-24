// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http.ssl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.nio.NIOBuffer;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.util.log.Log;

/* ------------------------------------------------------------ */
/**
 * SslHttpChannelEndPoint.
 * 
 * 
 * 
 */
public class SslSelectChannelEndPoint extends SelectChannelEndPoint
{
    private static final ByteBuffer[] __NO_BUFFERS={};

    private Buffers _buffers;
    
    private SSLEngine _engine;
    private ByteBuffer _inBuffer;
    private NIOBuffer _inNIOBuffer;
    private ByteBuffer _outBuffer;
    private NIOBuffer _outNIOBuffer;

    private NIOBuffer[] _reuseBuffer=new NIOBuffer[2];    
    private ByteBuffer[] _gather=new ByteBuffer[2];

    private boolean _closing=false;
    private SSLEngineResult _result;
    private String _last;
    
    // ssl
    protected SSLSession _session;
    
    // TODO get rid of this
    // StringBuilder h = new StringBuilder(500);
    /*
    class H 
    {
        H append(Object o)
        {
            System.err.print(o);
            return this;
        }
    };
    H h = new H();
    */
    
    /* ------------------------------------------------------------ */
    public SslSelectChannelEndPoint(Buffers buffers,SocketChannel channel, SelectorManager.SelectSet selectSet, SelectionKey key, SSLEngine engine)
            throws SSLException, IOException
    {
        super(channel,selectSet,key);
        _buffers=buffers;
        
        // ssl
        _engine=engine;
        _session=engine.getSession();

        // TODO pool buffers and use only when needed.
        _outNIOBuffer=(NIOBuffer)buffers.getBuffer(_session.getPacketBufferSize());
        _outBuffer=_outNIOBuffer.getByteBuffer();
        _inNIOBuffer=(NIOBuffer)buffers.getBuffer(_session.getPacketBufferSize());
        _inBuffer=_inNIOBuffer.getByteBuffer();
        
        // h.append("CONSTRUCTED\n");
    }

    // TODO get rid of these dumps
    public void dump()
    {
        System.err.println(_result);
        // System.err.println(h.toString());
        // System.err.println("--");
    }
    
    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.eclipse.io.nio.SelectChannelEndPoint#idleExpired()
     */
    protected void idleExpired()
    {
        try
        {
            _selectSet.getManager().dispatch(new Runnable()
            {
                public void run() 
                { 
                    doIdleExpired();
                }
            });
        }
        catch(Exception e)
        {
            Log.ignore(e);
        }
    }
    
    /* ------------------------------------------------------------ */
    protected void doIdleExpired()
    {
        // h.append("IDLE EXPIRED\n");
        super.idleExpired();
    }

    /* ------------------------------------------------------------ */
    public void close() throws IOException
    {
        // TODO - this really should not be done in a loop here - but with async callbacks.

        // h.append("CLOSE\n");
        _closing=true;
        try
        {   
            int tries=0;
            
            while (_outNIOBuffer.length()>0)
            {
                // TODO REMOVE loop check
                if (tries++>100)
                    throw new IllegalStateException();
                flush();
                Thread.sleep(100); // TODO yuck
            }

            _engine.closeOutbound();

            loop: while (isOpen() && !(_engine.isInboundDone() && _engine.isOutboundDone()))
            {   
                // TODO REMOVE loop check
                if (tries++>100)
                    throw new IllegalStateException();
                
                if (_outNIOBuffer.length()>0)
                {
                    flush();
                    Thread.sleep(100); // TODO yuck
                }

                switch(_engine.getHandshakeStatus())
                {
                    case FINISHED:
                    case NOT_HANDSHAKING:
                        break loop;
                        
                    case NEED_UNWRAP:
                        Buffer buffer =_buffers.getBuffer(_engine.getSession().getApplicationBufferSize());
                        try
                        {
                            ByteBuffer bbuffer = ((NIOBuffer)buffer).getByteBuffer();
                            if (!unwrap(bbuffer) && _engine.getHandshakeStatus()==HandshakeStatus.NEED_UNWRAP)
                            {
                                // h.append("break loop\n");
                                break loop;
                            }
                        }
                        catch(SSLException e)
                        {
                            Log.ignore(e);
                        }
                        finally
                        {
                            _buffers.returnBuffer(buffer);
                        }
                        break;
                        
                    case NEED_TASK:
                    {
                        Runnable task;
                        while ((task=_engine.getDelegatedTask())!=null)
                        {
                            task.run();
                        }
                        break;
                    }
                        
                    case NEED_WRAP:
                    {
                        if (_outNIOBuffer.length()>0)
                            flush();
                        
                        try
                        {
                            _outNIOBuffer.compact();
                            int put=_outNIOBuffer.putIndex();
                            _outBuffer.position(put);
                            _result=null;
                            _last="close wrap";
                            _result=_engine.wrap(__NO_BUFFERS,_outBuffer);
                            _outNIOBuffer.setPutIndex(put+_result.bytesProduced());
                        }
                        finally
                        {
                            _outBuffer.position(0);
                        }
                        
                        flush();
                        
                        break;
                    }
                }
            }
        }
        catch(IOException e)
        {
            Log.ignore(e);
        }
        catch (InterruptedException e)
        {
            Log.ignore(e);
        }
        finally
        {
            super.close();
            
            if (_inNIOBuffer!=null)
                _buffers.returnBuffer(_inNIOBuffer);
            if (_outNIOBuffer!=null)
                _buffers.returnBuffer(_outNIOBuffer);
            if (_reuseBuffer[0]!=null)
                _buffers.returnBuffer(_reuseBuffer[0]);
            if (_reuseBuffer[1]!=null)
                _buffers.returnBuffer(_reuseBuffer[1]);
        }   
    }

    /* ------------------------------------------------------------ */
    /* 
     */
    public int fill(Buffer buffer) throws IOException
    {
        ByteBuffer bbuf=extractInputBuffer(buffer);
        int size=buffer.length();
        HandshakeStatus initialStatus = _engine.getHandshakeStatus();
        synchronized (bbuf)
        {
            try
            {
                unwrap(bbuf);

                int tries=0, wraps=0;
                loop: while (true)
                {
                    // TODO REMOVE loop check
                    if (tries++>100)
                        throw new IllegalStateException();

                    // h.append("Fill(Buffer)\n");
                    
                    if (_outNIOBuffer.length()>0)
                        flush();

                    // h.append("status=").append(_engine.getHandshakeStatus()).append('\n');
                    switch(_engine.getHandshakeStatus())
                    {
                        case FINISHED:
                        case NOT_HANDSHAKING:
                            if (_closing)
                                return -1;
                            break loop;

                        case NEED_UNWRAP:
                            if (!unwrap(bbuf) && _engine.getHandshakeStatus()==HandshakeStatus.NEED_UNWRAP)
                            {
                                // h.append("break loop\n");
                                break loop;
                            }
                            break;

                        case NEED_TASK:
                        {
                            Runnable task;
                            while ((task=_engine.getDelegatedTask())!=null)
                            {
                                // h.append("run task\n");
                                task.run();
                            }
                            if(initialStatus==HandshakeStatus.NOT_HANDSHAKING && 
                                    HandshakeStatus.NEED_UNWRAP==_engine.getHandshakeStatus() && wraps==0)
                            {
                                // This should be NEED_WRAP
                                // The fix simply detects the signature of the bug and then close the connection (fail-fast) so that ff3 will delegate to using SSL instead of TLS.
                                // This is a jvm bug on java1.6 where the SSLEngine expects more data from the initial handshake when the client(ff3-tls) already had given it.
                                // See http://jira.codehaus.org/browse/JETTY-567 for more details
                                return -1;
                            }
                            break;
                        }

                        case NEED_WRAP:
                        {
                            wraps++;
                            synchronized(_outBuffer)
                            {
                                try
                                {
                                    _outNIOBuffer.compact();
                                    int put=_outNIOBuffer.putIndex();
                                    _outBuffer.position();
                                    _result=null;
                                    _last="fill wrap";
                                    _result=_engine.wrap(__NO_BUFFERS,_outBuffer);
                                    switch(_result.getStatus())
                                    {
                                        case BUFFER_OVERFLOW:
                                        case BUFFER_UNDERFLOW:
                                            Log.warn("wrap {}",_result);
                                        case CLOSED:
                                            _closing=true;
                                    }
                                    
                                    // h.append("wrap ").append(_result).append('\n');
                                    _outNIOBuffer.setPutIndex(put+_result.bytesProduced());
                                }
                                finally
                                {
                                    _outBuffer.position(0);
                                }
                            }

                            flush();

                            break;
                        }
                    }
                }
            }
            catch(SSLException e)
            {
                Log.warn(e.toString());
                Log.debug(e);
                throw e;
            }
            finally
            {
                buffer.setPutIndex(bbuf.position());
                bbuf.position(0);
            }
        }
        return buffer.length()-size; 

    }

    /* ------------------------------------------------------------ */
    public int flush(Buffer buffer) throws IOException
    {
        return flush(buffer,null,null);
    }


    /* ------------------------------------------------------------ */
    /*     
     */
    public int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException
    {   
        int consumed=0;
        int available=header.length();
        if (buffer!=null)
            available+=buffer.length();
        
        int tries=0;
        loop: while (true)
        {
            // TODO REMOVE loop check
            if (tries++>100)
                throw new IllegalStateException();
            
            // h.append("Flush ").append(tries).append(' ').append(_outNIOBuffer.length()).append('\n');
            
            if (_outNIOBuffer.length()>0)
                flush();

            // h.append(_engine.getHandshakeStatus()).append('\n');
            
            switch(_engine.getHandshakeStatus())
            {
                case FINISHED:
                case NOT_HANDSHAKING:

                    if (_closing || available==0)
                    {
                        if (consumed==0)
                            consumed= -1;
                        break loop;
                    }
                        
                    int c=(header!=null && buffer!=null)?wrap(header,buffer):wrap(header);
                    if (c>0)
                    {
                        consumed+=c;
                        available-=c;
                    }
                    else if (c<0)
                    {
                        if (consumed==0)
                            consumed=-1;
                        break loop;
                    }
                    
                    break;

                case NEED_UNWRAP:
                    Buffer buf =_buffers.getBuffer(_engine.getSession().getApplicationBufferSize());
                    try
                    {
                        ByteBuffer bbuf = ((NIOBuffer)buf).getByteBuffer();
                        if (!unwrap(bbuf) && _engine.getHandshakeStatus()==HandshakeStatus.NEED_UNWRAP)
                        {
                            // h.append("break").append('\n');
                            break loop;
                        }
                    }
                    finally
                    {
                        _buffers.returnBuffer(buf);
                    }
                    
                    break;

                case NEED_TASK:
                {
                    Runnable task;
                    while ((task=_engine.getDelegatedTask())!=null)
                    {
                        // h.append("run task\n");
                        task.run();
                    }
                    break;
                }

                case NEED_WRAP:
                {
                    synchronized(_outBuffer)
                    {
                        try
                        {
                            _outNIOBuffer.compact();
                            int put=_outNIOBuffer.putIndex();
                            _outBuffer.position();
                            _result=null;
                            _last="flush wrap";
                            _result=_engine.wrap(__NO_BUFFERS,_outBuffer);
                            switch(_result.getStatus())
                            {
                                case BUFFER_OVERFLOW:
                                case BUFFER_UNDERFLOW:
                                    Log.warn("unwrap {}",_result);
                                case CLOSED:
                                    _closing=true;
                            }
                            // h.append("wrap=").append(_result).append('\n');
                            _outNIOBuffer.setPutIndex(put+_result.bytesProduced());
                        }
                        finally
                        {
                            _outBuffer.position(0);
                        }
                    }

                    flush();

                    break;
                }
            }
        }
        
        return consumed;
    }
    
    
    /* ------------------------------------------------------------ */
    public void flush() throws IOException
    {
        while (_outNIOBuffer.length()>0)
        {
            int flushed=super.flush(_outNIOBuffer);

            // h.append("flushed=").append(flushed).append(" of ").append(_outNIOBuffer.length()).append('\n');
            if (flushed==0)
            {
                Thread.yield();
                flushed=super.flush(_outNIOBuffer);
                // h.append("flushed2=").append(flushed).append(" of ").append(_outNIOBuffer.length()).append('\n');
            }
        }
    }

    /* ------------------------------------------------------------ */
    private ByteBuffer extractInputBuffer(Buffer buffer)
    {
        assert buffer instanceof NIOBuffer;
        NIOBuffer nbuf=(NIOBuffer)buffer;
        ByteBuffer bbuf=nbuf.getByteBuffer();
        bbuf.position(buffer.putIndex());
        return bbuf;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if progress is made
     */
    private boolean unwrap(ByteBuffer buffer) throws IOException
    {
        if (_inNIOBuffer.hasContent())
            _inNIOBuffer.compact();
        else 
            _inNIOBuffer.clear();

        int total_filled=0;
        while (_inNIOBuffer.space()>0 && super.isOpen())
        {
            try
            {
                int filled=super.fill(_inNIOBuffer);
                // h.append("fill=").append(filled).append('\n');
                if (filled<=0)
                    break;
                total_filled+=filled;
            }
            catch(IOException e)
            {
                if (_inNIOBuffer.length()==0)
                    throw e;
                break;
            }
        }

        // h.append("inNIOBuffer=").append(_inNIOBuffer.length()).append('\n');
        
        if (_inNIOBuffer.length()==0)
        {
            if(!isOpen())
                throw new org.eclipse.jetty.io.EofException();
            return false;
        }

        try
        {
            _inBuffer.position(_inNIOBuffer.getIndex());
            _inBuffer.limit(_inNIOBuffer.putIndex());
            _result=null;
            _last="unwrap";
            _result=_engine.unwrap(_inBuffer,buffer);
            // h.append("unwrap=").append(_result).append('\n');
            _inNIOBuffer.skip(_result.bytesConsumed());
        }
        finally
        {
            _inBuffer.position(0);
            _inBuffer.limit(_inBuffer.capacity());
        }
        

        switch(_result.getStatus())
        {
            case BUFFER_OVERFLOW:
            case BUFFER_UNDERFLOW:
                if (Log.isDebugEnabled()) Log.debug("unwrap {}",_result);
                return (total_filled > 0);
                
            case CLOSED:
                _closing=true;
            case OK:
                boolean progress=total_filled>0 ||_result.bytesConsumed()>0 || _result.bytesProduced()>0;    
                // h.append("progress=").append(progress).append('\n');
                return progress;
            default:
                Log.warn("unwrap "+_result);
                throw new IOException(_result.toString());
        }
    }

    
    /* ------------------------------------------------------------ */
    private ByteBuffer extractOutputBuffer(Buffer buffer,int n)
    {
        NIOBuffer nBuf=null;

        if (buffer.buffer() instanceof NIOBuffer)
        {
            nBuf=(NIOBuffer)buffer.buffer();
            return nBuf.getByteBuffer();
        }
        else
        {
            if (_reuseBuffer[n]==null)
                _reuseBuffer[n] = (NIOBuffer)_buffers.getBuffer(_session.getApplicationBufferSize());
            NIOBuffer buf = _reuseBuffer[n];
            buf.clear();
            buf.put(buffer);
            return buf.getByteBuffer();
        }
    }

    /* ------------------------------------------------------------ */
    private int wrap(Buffer header, Buffer buffer) throws IOException
    {
        _gather[0]=extractOutputBuffer(header,0);
        synchronized(_gather[0])
        {
            _gather[0].position(header.getIndex());
            _gather[0].limit(header.putIndex());

            _gather[1]=extractOutputBuffer(buffer,1);

            synchronized(_gather[1])
            {
                _gather[1].position(buffer.getIndex());
                _gather[1].limit(buffer.putIndex());

                synchronized(_outBuffer)
                {
                    int consumed=0;
                    try
                    {
                        _outNIOBuffer.clear();
                        _outBuffer.position(0);
                        _outBuffer.limit(_outBuffer.capacity());

                        _result=null;
                        _last="wrap wrap";
                        _result=_engine.wrap(_gather,_outBuffer);
                        // h.append("wrap2=").append(_result).append('\n');
                        _outNIOBuffer.setGetIndex(0);
                        _outNIOBuffer.setPutIndex(_result.bytesProduced());
                        consumed=_result.bytesConsumed();
                    }
                    finally
                    {
                        _outBuffer.position(0);

                        if (consumed>0 && header!=null)
                        {
                            int len=consumed<header.length()?consumed:header.length();
                            header.skip(len);
                            consumed-=len;
                            _gather[0].position(0);
                            _gather[0].limit(_gather[0].capacity());
                        }
                        if (consumed>0 && buffer!=null)
                        {
                            int len=consumed<buffer.length()?consumed:buffer.length();
                            buffer.skip(len);
                            consumed-=len;
                            _gather[1].position(0);
                            _gather[1].limit(_gather[1].capacity());
                        }
                        assert consumed==0;
                    }
                }
            }
        }
        

        switch(_result.getStatus())
        {
            case BUFFER_OVERFLOW:
            case BUFFER_UNDERFLOW:
                Log.warn("unwrap {}",_result);
                
            case OK:
                return _result.bytesConsumed();
            case CLOSED:
                _closing=true;
                return _result.bytesConsumed()>0?_result.bytesConsumed():-1;

            default:
                Log.warn("wrap "+_result);
            throw new IOException(_result.toString());
        }
    }

    /* ------------------------------------------------------------ */
    private int wrap(Buffer header) throws IOException
    {
        _gather[0]=extractOutputBuffer(header,0);
        synchronized(_gather[0])
        {
            _gather[0].position(header.getIndex());
            _gather[0].limit(header.putIndex());

            int consumed=0;
            synchronized(_outBuffer)
            {
                try
                {
                    _outNIOBuffer.clear();
                    _outBuffer.position(0);
                    _outBuffer.limit(_outBuffer.capacity());
                    _result=null;
                    _last="wrap wrap";
                    _result=_engine.wrap(_gather[0],_outBuffer);
                    // h.append("wrap1=").append(_result).append('\n');
                    _outNIOBuffer.setGetIndex(0);
                    _outNIOBuffer.setPutIndex(_result.bytesProduced());
                    consumed=_result.bytesConsumed();
                }
                finally
                {
                    _outBuffer.position(0);

                    if (consumed>0 && header!=null)
                    {
                        int len=consumed<header.length()?consumed:header.length();
                        header.skip(len);
                        consumed-=len;
                        _gather[0].position(0);
                        _gather[0].limit(_gather[0].capacity());
                    }
                    assert consumed==0;
                }
            }
        }
        switch(_result.getStatus())
        {
            case BUFFER_OVERFLOW:
            case BUFFER_UNDERFLOW:
                Log.warn("unwrap {}",_result);
                
            case OK:
                return _result.bytesConsumed();
            case CLOSED:
                _closing=true;
                return _result.bytesConsumed()>0?_result.bytesConsumed():-1;

            default:
                Log.warn("wrap "+_result);
            throw new IOException(_result.toString());
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isBufferingInput()
    {
        return _inNIOBuffer.hasContent();
    }

    /* ------------------------------------------------------------ */
    public boolean isBufferingOutput()
    {
        return _outNIOBuffer.hasContent();
    }

    /* ------------------------------------------------------------ */
    public boolean isBufferred()
    {
        return true;
    }

    /* ------------------------------------------------------------ */
    public SSLEngine getSSLEngine()
    {
        return _engine;
    }
    
    /* ------------------------------------------------------------ */
    public String toString()
    {
        return super.toString()+","+_engine.getHandshakeStatus()+", in/out="+_inNIOBuffer.length()+"/"+_outNIOBuffer.length()+" last "+_last+" "+_result;
    }
}
