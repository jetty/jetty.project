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

package org.eclipse.jetty.io.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
 * SslSelectChannelEndPoint
 * <p>
 * A SelectChannelEndPoint that uses an {@link SSLEngine} to handle an
 * SSL connection.
 * <p>
 * There is a named logger "org.eclipse.jetty.http.ssl"
 * </p>
 */
public class SslSelectChannelEndPoint extends SelectChannelEndPoint
{
    public static final Logger LOG=Log.getLogger("org.eclipse.jetty.io.nio").getLogger("ssl");

    private static final Buffer __EMPTY_BUFFER=new DirectNIOBuffer(0);
    private static final ByteBuffer __ZERO_BUFFER=ByteBuffer.allocate(0);

    private final Buffers _buffers;

    private final SSLEngine _engine;
    private final SSLSession _session;
    private int _inCount;
    private volatile NIOBuffer _inNIOBuffer;
    private volatile NIOBuffer _outNIOBuffer;

    private final ByteBuffer[] _gather=new ByteBuffer[2];

    private boolean _closing=false;
    private SSLEngineResult _result;

    private boolean _handshook=false;
    private boolean _allowRenegotiate=false;

    private final boolean _debug = LOG.isDebugEnabled(); // snapshot debug status for optimizer

    /* ------------------------------------------------------------ */
    public SslSelectChannelEndPoint(Buffers buffers,SocketChannel channel, SelectorManager.SelectSet selectSet, SelectionKey key, SSLEngine engine, int maxIdleTime)
            throws IOException
    {
        super(channel,selectSet,key, maxIdleTime);
        _buffers=buffers;

        // ssl
        _engine=engine;
        _session=engine.getSession();

        if (_debug) LOG.debug(_session+" channel="+channel);
    }

    /* ------------------------------------------------------------ */
    public SslSelectChannelEndPoint(Buffers buffers,SocketChannel channel, SelectorManager.SelectSet selectSet, SelectionKey key, SSLEngine engine)
            throws IOException
    {
        super(channel,selectSet,key);
        _buffers=buffers;

        // ssl
        _engine=engine;
        _session=engine.getSession();

        if (_debug) LOG.debug(_session+" channel="+channel);
    }

    int _outCount;

    /* ------------------------------------------------------------ */
    private void needOutBuffer()
    {
        synchronized (this)
        {
            _outCount++;
            if (_outNIOBuffer==null)
                _outNIOBuffer=(NIOBuffer)_buffers.getBuffer(_session.getPacketBufferSize());
        }
    }

    /* ------------------------------------------------------------ */
    private void freeOutBuffer()
    {
        synchronized (this)
        {
            if (--_outCount<=0 && _outNIOBuffer!=null && _outNIOBuffer.length()==0)
            {
                _buffers.returnBuffer(_outNIOBuffer);
                _outNIOBuffer=null;
                _outCount=0;
            }
        }
    }

    /* ------------------------------------------------------------ */
    private void needInBuffer()
    {
        synchronized (this)
        {
            _inCount++;
            if(_inNIOBuffer==null)
                _inNIOBuffer=(NIOBuffer)_buffers.getBuffer(_session.getPacketBufferSize());
        }
    }

    /* ------------------------------------------------------------ */
    private void freeInBuffer()
    {
        synchronized (this)
        {
            if (--_inCount<=0 &&_inNIOBuffer!=null && _inNIOBuffer.length()==0)
            {
                _buffers.returnBuffer(_inNIOBuffer);
                _inNIOBuffer=null;
                _inCount=0;
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if the endpoint has produced/consumed bytes itself (non application data).
     */
    public boolean isProgressing()
    {
        SSLEngineResult result = _result;
        return result!=null && (result.bytesConsumed()>0 || result.bytesProduced()>0);
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
     * not be allowed.
     * @param allowRenegotiate true if re-negotiation is allowed (default false)
     */
    public void setAllowRenegotiate(boolean allowRenegotiate)
    {
        _allowRenegotiate = allowRenegotiate;
    }


    /* ------------------------------------------------------------ */
    @Override
    public boolean isOutputShutdown()
    {
        return _engine!=null && _engine.isOutboundDone();
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isInputShutdown()
    {
        return _engine!=null && _engine.isInboundDone();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void shutdownOutput() throws IOException
    {
        LOG.debug("{} shutdownOutput",_session);
        try
        {
            // All SSL closes should be graceful, as it is more secure.
            // So normal SSL close can be used here.
            close();
        }
        finally
        {
            super.shutdownOutput();
        }
    }

    /* ------------------------------------------------------------ */
    private int process(ByteBuffer inbuf, Buffer header, Buffer buffer) throws IOException
    {
        if (_debug)
            LOG.debug("process {} {} {}",inbuf!=null,header!=null,buffer!=null);

        // If there is no place to put incoming application data,
        if (inbuf==null)
        {
            // use ZERO buffer
            inbuf=__ZERO_BUFFER;
        }

        int received=0;
        int sent=0;


        HandshakeStatus initialStatus = _engine.getHandshakeStatus();
        boolean progress=true;

        while (progress)
        {
            progress=false;

            // flush output data
            {
                int len=_outNIOBuffer==null?0:_outNIOBuffer.length();

                // we must flush it, as the other end might be
                // waiting for that outgoing data before sending
                // more incoming data
                flush();

                // If we have written some bytes, then progress has been made.
                progress|=(_outNIOBuffer==null?0:_outNIOBuffer.length())<len;
            }

            // handle the current hand share status
            if (_debug) LOG.debug("status {} {}",_engine,_engine.getHandshakeStatus());
            switch(_engine.getHandshakeStatus())
            {
                case FINISHED:
                    throw new IllegalStateException();

                case NOT_HANDSHAKING:

                    _handshook=true;

                    // If closing, don't process application data
                    if (_closing)
                        break;

                    // Try wrapping some application data
                    if (header!=null||buffer!=null)
                    {
                        int c=0;
                        if (header!=null && header.hasContent())
                        {
                            if (buffer!=null && buffer.hasContent())
                                c=wrap(header,buffer);
                            else
                                c=wrap(header);
                            progress=_result.bytesProduced()>0||_result.bytesConsumed()>0;
                        }
                        else if (buffer!=null && buffer.hasContent())
                        {
                            c=wrap(buffer);
                            progress=_result.bytesProduced()>0||_result.bytesConsumed()>0;
                        }

                        if (c>0)
                            sent+=c;
                        else if (c<0 && sent==0)
                            sent=-1;
                    }

                    // Try unwrapping some application data
                    if (inbuf.remaining()>0 && _inNIOBuffer!=null && _inNIOBuffer.hasContent())
                    {
                        int space=inbuf.remaining();
                        progress|=unwrap(inbuf);
                        received+=space-inbuf.remaining();
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

                    // Detect SUN JVM Bug!!!
                    if(initialStatus==HandshakeStatus.NOT_HANDSHAKING &&
                       _engine.getHandshakeStatus()==HandshakeStatus.NEED_UNWRAP && sent==0)
                    {
                        // This should be NEED_WRAP
                        // The fix simply detects the signature of the bug and then close the connection (fail-fast) so that ff3 will delegate to using SSL instead of TLS.
                        // This is a jvm bug on java1.6 where the SSLEngine expects more data from the initial handshake when the client(ff3-tls) already had given it.
                        // See http://jira.codehaus.org/browse/JETTY-567 for more details
                        if (_debug) LOG.warn(_session+" JETTY-567");
                        return -1;
                    }
                    break;
                }

                case NEED_WRAP:
                {
                    checkRenegotiate();

                    // The SSL needs to send some handshake data to the other side
                    int c=0;
                    if (header!=null && header.hasContent())
                    {
                        if (buffer!=null && buffer.hasContent())
                            c=wrap(header,buffer);
                        else
                            c=wrap(header);
                    }
                    else if (buffer!=null && buffer.hasContent())
                        c=wrap(buffer);
                    else
                        c=wrap(__EMPTY_BUFFER);

                    progress=_result.bytesProduced()>0||_result.bytesConsumed()>0;
                    if (c>0)
                        sent+=c;
                    else if (c<0 && sent==0)
                        sent=-1;


                    break;
                }

                case NEED_UNWRAP:
                {
                    checkRenegotiate();

                    // Need more data to be unwrapped so try another call to unwrap
                    progress|=unwrap(inbuf);
                    if (_closing)
                        inbuf.clear();

                    break;
                }
            }

            if (_debug) LOG.debug("{} progress {}",_session,progress);
        }

        if (_debug) LOG.debug("{} received {} sent {}",_session,received,sent);
        return (received<0||sent<0)?-1:(received+sent);
    }



    /* ------------------------------------------------------------ */
    @Override
    public void close() throws IOException
    {
        if (_closing)
            return;
        LOG.debug("{} close",_session);
        _closing=true;

        // Processing will call flush(), which will handle the closing state with a closeOutbound then shutdownOutput
        process(null,null,null);
    }

    /* ------------------------------------------------------------ */
    /** Fill the buffer with unencrypted bytes.
     * Called by a Http Parser when more data is
     * needed to continue parsing a request or a response.
     */
    @Override
    public int fill(Buffer buffer) throws IOException
    {
        LOG.debug("{} fill",_session);
        // This end point only works on NIO buffer type (director
        // or indirect), so extract the NIO buffer that is wrapped
        // by the passed jetty Buffer.
        ByteBuffer bbuf=((NIOBuffer)buffer).getByteBuffer();


        // remember the original size of the unencrypted buffer
        int size=buffer.length();


        synchronized (bbuf)
        {
            bbuf.position(buffer.putIndex());
            try
            {
                // Call the SSLEngine unwrap method to process data in
                // the inBuffer.  If there is no data in the inBuffer, then
                // super.fill is called to read encrypted bytes.
                unwrap(bbuf);

                int progress = process(bbuf,null,null);

                // TODO ???
            }
            finally
            {
                // reset the Buffers
                buffer.setPutIndex(bbuf.position());
                bbuf.position(0);
            }

            // return the number of unencrypted bytes filled.
            int filled=buffer.length()-size;
            if (filled>0)
                _handshook=true;
            return filled;
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public int flush(Buffer buffer) throws IOException
    {
        LOG.debug("{} flush1",_session);
        return process(null,buffer,null);
    }


    /* ------------------------------------------------------------ */
    /*
     */
    @Override
    public int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException
    {
        LOG.debug("{} flush3",_session);
        return process(null,header,buffer);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void flush() throws IOException
    {
        if (isBufferingOutput())
        {
            int flushed=super.flush(_outNIOBuffer);
            if (_debug)
                LOG.debug(_session+" flushed "+flushed+" left="+_outNIOBuffer.length());
        }
        else if (_closing)
        {
            if (_engine.isOutboundDone())
            {
                if (!super.isOutputShutdown())
                {
                    if (_debug)
                        LOG.debug(_session+" flush shutdownOutput");
                    super.shutdownOutput();
                }
            }
            else
            {
                if (_debug)
                    LOG.debug(_session+" flush closeOutbound");
                _engine.closeOutbound();
            }
        }
    }

    /* ------------------------------------------------------------ */
    private void checkRenegotiate() throws IOException
    {
        if (_handshook && !_allowRenegotiate && _channel!=null && _channel.isOpen())
        {
            LOG.warn("SSL renegotiate denied: "+_channel);
            super.close();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if progress is made
     */
    private boolean unwrap(ByteBuffer buffer) throws IOException
    {
        needInBuffer();
        ByteBuffer in_buffer=_inNIOBuffer.getByteBuffer();

        if (_inNIOBuffer.hasContent())
            _inNIOBuffer.compact();
        else
            _inNIOBuffer.clear();

        int total_filled=0;
        boolean remoteClosed = false;
        // loop filling as much encrypted data as we can into the buffer
        while (_inNIOBuffer.space()>0 && super.isOpen())
        {
            try
            {
                int filled=super.fill(_inNIOBuffer);
                if (_debug) LOG.debug(_session+" filled "+filled);
                if (filled < 0)
                    remoteClosed = true;
                // break the loop if no progress is made (we have read everything there is to read)
                if (filled<=0)
                    break;
                total_filled+=filled;
            }
            catch(IOException e)
            {
                if (_inNIOBuffer.length()==0)
                {
                    freeInBuffer();
                    if (_outNIOBuffer!=null)
                    {
                        _outNIOBuffer.clear();
                        freeOutBuffer();
                    }
                    throw e;
                }
                break;
            }
        }

        // If we have no progress and no data
        if (total_filled==0 && _inNIOBuffer.length()==0)
        {
            // Do we need to close?
            if (isOpen() && remoteClosed)
            {
                try
                {
                    _engine.closeInbound();
                }
                catch (SSLException x)
                {
                    // It may happen, for example, in case of truncation
                    // attacks, we close so that we do not spin forever
                    super.close();
                }
            }

            freeInBuffer();
            freeOutBuffer();

            if (!isOpen())
                throw new EofException();

            return false;
        }

        // We have some in data, so try to unwrap it.
        try
        {
            // inBuffer is the NIO buffer inside the _inNIOBuffer,
            // so update its position and limit from the inNIOBuffer.
            in_buffer.position(_inNIOBuffer.getIndex());
            in_buffer.limit(_inNIOBuffer.putIndex());

            // Do the unwrap
            _result=_engine.unwrap(in_buffer,buffer);
            if (_debug) LOG.debug(_session+" unwrap "+_result);

            // skip the bytes consumed
            _inNIOBuffer.skip(_result.bytesConsumed());
        }
        catch(SSLException e)
        {
            LOG.warn(getRemoteAddr() + ":" + getRemotePort() + " " + e);
            freeOutBuffer();
            super.close();
            throw e;
        }
        finally
        {
            // reset the buffer so it can be managed by the _inNIOBuffer again.
            in_buffer.position(0);
            in_buffer.limit(in_buffer.capacity());
            freeInBuffer();
        }

        // handle the unwrap results
        switch(_result.getStatus())
        {
            case BUFFER_OVERFLOW:
                LOG.debug("{} BUFFER_OVERFLOW",_session);
                return false;

            case BUFFER_UNDERFLOW:
                // Not enough data,
                // If we are closed, we will never get more, so EOF
                // else return and we will be tried again
                // later when more data arriving causes another dispatch.
                if (LOG.isDebugEnabled()) LOG.debug("unwrap {}",_result);
                if(!isOpen())
                {
                    _inNIOBuffer.clear();
                    if (_outNIOBuffer!=null)
                        _outNIOBuffer.clear();
                    throw new EofException();
                }
                return (total_filled > 0);

            case CLOSED:
                _closing=true;
                // return true is some bytes somewhere were moved about.
                return total_filled>0 ||_result.bytesConsumed()>0 || _result.bytesProduced()>0;
            case OK:
                // return true is some bytes somewhere were moved about.
                return total_filled>0 ||_result.bytesConsumed()>0 || _result.bytesProduced()>0;
            default:
                LOG.warn("unwrap "+_result);
                throw new IOException(_result.toString());
        }
    }


    /* ------------------------------------------------------------ */
    private ByteBuffer extractOutputBuffer(Buffer buffer)
    {
        if (buffer.buffer() instanceof NIOBuffer)
            return ((NIOBuffer)buffer.buffer()).getByteBuffer();

        return ByteBuffer.wrap(buffer.array());
    }

    /* ------------------------------------------------------------ */
    private int wrap(final Buffer header, final Buffer buffer) throws IOException
    {
        _gather[0]=extractOutputBuffer(header);

        synchronized(_gather[0])
        {
            _gather[0].position(header.getIndex());
            _gather[0].limit(header.putIndex());

            _gather[1]=extractOutputBuffer(buffer);

            synchronized(_gather[1])
            {
                _gather[1].position(buffer.getIndex());
                _gather[1].limit(buffer.putIndex());

                needOutBuffer();
                ByteBuffer out_buffer=_outNIOBuffer.getByteBuffer();
                synchronized(out_buffer)
                {
                    int consumed=0;
                    try
                    {
                        _outNIOBuffer.clear();
                        out_buffer.position(0);
                        out_buffer.limit(out_buffer.capacity());

                        _result=null;
                        _result=_engine.wrap(_gather,out_buffer);
                        if (_debug) LOG.debug(_session+" wrap wrap "+_result);
                        _outNIOBuffer.setGetIndex(0);
                        _outNIOBuffer.setPutIndex(_result.bytesProduced());
                        consumed=_result.bytesConsumed();
                    }
                    catch(SSLException e)
                    {
                        LOG.warn(getRemoteAddr()+":"+getRemotePort()+" "+e);
                        super.close();
                        throw e;
                    }
                    finally
                    {
                        out_buffer.position(0);

                        if (consumed>0)
                        {
                            int len=consumed<header.length()?consumed:header.length();
                            header.skip(len);
                            consumed-=len;
                            _gather[0].position(0);
                            _gather[0].limit(_gather[0].capacity());
                        }
                        if (consumed>0)
                        {
                            int len=consumed<buffer.length()?consumed:buffer.length();
                            buffer.skip(len);
                            consumed-=len;
                            _gather[1].position(0);
                            _gather[1].limit(_gather[1].capacity());
                        }
                        assert consumed==0;

                        freeOutBuffer();
                    }
                }
            }
        }


        switch(_result.getStatus())
        {
            case BUFFER_OVERFLOW:
            case BUFFER_UNDERFLOW:
                LOG.warn("unwrap {}",_result);
                _closing=true;
                return _result.bytesConsumed()>0?_result.bytesConsumed():-1;

            case OK:
                return _result.bytesConsumed();
            case CLOSED:
                _closing=true;
                return _result.bytesConsumed()>0?_result.bytesConsumed():-1;

            default:
                LOG.warn("wrap "+_result);
            throw new IOException(_result.toString());
        }
    }

    /* ------------------------------------------------------------ */
    private int wrap(final Buffer buffer) throws IOException
    {
        _gather[0]=extractOutputBuffer(buffer);
        synchronized(_gather[0])
        {
            ByteBuffer bb;

            _gather[0].position(buffer.getIndex());
            _gather[0].limit(buffer.putIndex());

            int consumed=0;
            needOutBuffer();
            ByteBuffer out_buffer=_outNIOBuffer.getByteBuffer();
            synchronized(out_buffer)
            {
                try
                {
                    _outNIOBuffer.clear();
                    out_buffer.position(0);
                    out_buffer.limit(out_buffer.capacity());
                    _result=null;
                    _result=_engine.wrap(_gather[0],out_buffer);
                    if (_debug) LOG.debug(_session+" wrap "+_result);
                    _outNIOBuffer.setGetIndex(0);
                    _outNIOBuffer.setPutIndex(_result.bytesProduced());
                    consumed=_result.bytesConsumed();
                }
                catch(SSLException e)
                {
                    LOG.warn(getRemoteAddr()+":"+getRemotePort()+" "+e);
                    super.close();
                    throw e;
                }
                finally
                {
                    out_buffer.position(0);

                    if (consumed>0)
                    {
                        int len=consumed<buffer.length()?consumed:buffer.length();
                        buffer.skip(len);
                        consumed-=len;
                        _gather[0].position(0);
                        _gather[0].limit(_gather[0].capacity());
                    }
                    assert consumed==0;

                    freeOutBuffer();
                }
            }
        }
        switch(_result.getStatus())
        {
            case BUFFER_OVERFLOW:
            case BUFFER_UNDERFLOW:
                LOG.warn("unwrap {}",_result);
                _closing=true;
                return _result.bytesConsumed()>0?_result.bytesConsumed():-1;

            case OK:
                return _result.bytesConsumed();
            case CLOSED:
                _closing=true;
                return _result.bytesConsumed()>0?_result.bytesConsumed():-1;

            default:
                LOG.warn("wrap "+_result);
            throw new IOException(_result.toString());
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isBufferingInput()
    {
        final Buffer in = _inNIOBuffer;
        return in==null?false:_inNIOBuffer.hasContent();
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isBufferingOutput()
    {
        final NIOBuffer b=_outNIOBuffer;
        return b==null?false:b.hasContent();
    }

    /* ------------------------------------------------------------ */
    @Override
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
    @Override
    public void scheduleWrite()
    {
        // only set !writable if we are not waiting for input
        if (!HandshakeStatus.NEED_UNWRAP.equals(_engine.getHandshakeStatus()) || super.isBufferingOutput())
        super.scheduleWrite();
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        final NIOBuffer i=_inNIOBuffer;
        final NIOBuffer o=_outNIOBuffer;
        return "SSL"+super.toString()+","+_engine.getHandshakeStatus()+", in/out="+
        (i==null?0:i.length())+"/"+(o==null?0:o.length())+
        " bi/o="+isBufferingInput()+"/"+isBufferingOutput()+
        " "+_result;
    }
}
