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
    private NIOBuffer _inNIOBuffer;
    private NIOBuffer _outNIOBuffer;
    private boolean _outNeeded;
    private boolean _inNeeded;

    private boolean _ishut=false;
    private boolean _oshut=false;
    private SSLEngineResult _result;

    private volatile boolean _handshook=false;
    private boolean _allowRenegotiate=true;

    private volatile boolean _debug = LOG.isDebugEnabled(); // snapshot debug status for optimizer

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
    private void needOutBuffer()
    {
        synchronized (this)
        {
            assert !_outNeeded;
            _outNeeded=true;
            if (_outNIOBuffer==null)
                _outNIOBuffer=(NIOBuffer)_buffers.getBuffer(_session.getPacketBufferSize());
        }
    }
    
    /* ------------------------------------------------------------ */
    private void releaseOutBuffer()
    {
        synchronized (this)
        {
            _outNeeded=false;
            freeOutBuffer();
        }
    }

    /* ------------------------------------------------------------ */
    private void freeOutBuffer()
    {
        synchronized (this)
        {
            if (!_outNeeded && _outNIOBuffer!=null && _outNIOBuffer.length()==0)
            {
                _buffers.returnBuffer(_outNIOBuffer);
                _outNIOBuffer=null;
            }
        }
    }

    /* ------------------------------------------------------------ */
    private void needInBuffer()
    {
        synchronized (this)
        {
            assert !_inNeeded;
            _inNeeded=true;
            if(_inNIOBuffer==null)
                _inNIOBuffer=(NIOBuffer)_buffers.getBuffer(_session.getPacketBufferSize());
        }
    }

    /* ------------------------------------------------------------ */
    private void releaseInBuffer()
    {
        synchronized (this)
        {
            _inNeeded=false;
            freeInBuffer();
        }
    }
    
    /* ------------------------------------------------------------ */
    private void freeInBuffer()
    {
        synchronized (this)
        {
            if (!_inNeeded && _inNIOBuffer!=null && _inNIOBuffer.length()==0)
            {
                _buffers.returnBuffer(_inNIOBuffer);
                _inNIOBuffer=null;
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
        _result=null;
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
    public boolean isInputShutdown()
    {
        return _ishut || (_engine!=null && _engine.isInboundDone()) || super.isInputShutdown();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public boolean isOutputShutdown()
    {
        return _oshut || (_engine!=null && _engine.isOutboundDone()) || super.isOutputShutdown();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void shutdownInput() throws IOException
    {
        LOG.debug("{} shutdownInput",_session);

        // All SSL closes should be graceful, as it is more secure.
        // So normal SSL close can be used here.

        if (_ishut)
            return;
        _ishut=true;

        try
        {
            _engine.closeInbound();
            process(null,null);
        }
        catch (IOException e)
        {
            // We could not write the SSL close message so close to be sure
            LOG.ignore(e);
            close();
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public void shutdownOutput() throws IOException
    {
        LOG.debug("{} shutdownOutput",_session);

        // All SSL closes should be graceful, as it is more secure.
        // So normal SSL close can be used here.
        
        if (_oshut)
            return;
        
        try
        {
            _oshut=true;
            _engine.closeOutbound();
            process(null,null);
        }
        catch (IOException e)
        {
            // We could not write the SSL close message so close to be sure
            LOG.ignore(e);
            close();
        }
    }

    /* ------------------------------------------------------------ */
    private synchronized int process(ByteBuffer inBBuf, Buffer outBuf) throws IOException
    {
        if (_debug)
            LOG.debug("{} process ishut={} oshut={} in={} out={}",_session,_ishut,_oshut,inBBuf,outBuf);

        // If there is no place to put incoming application data,
        if (inBBuf==null)
        {
            // use ZERO buffer
            inBBuf=__ZERO_BUFFER;
        }

        int received=0;
        int sent=0;


        HandshakeStatus initialStatus = _engine.getHandshakeStatus();
        boolean progress=true;

        while (progress)
        {
            progress=false;

            // flush output data
            int len=_outNIOBuffer==null?0:_outNIOBuffer.length();

            // we must flush it, as the other end might be
            // waiting for that outgoing data before sending
            // more incoming data
            flush();

            // If we have written some bytes, then progress has been made.
            progress|=(_outNIOBuffer==null?0:_outNIOBuffer.length())<len;

            // handle the current hand share status
            if (_debug) LOG.debug("status {} {}",_engine,_engine.getHandshakeStatus());
            switch(_engine.getHandshakeStatus())
            {
                case FINISHED:
                    throw new IllegalStateException();

                case NOT_HANDSHAKING:

                    // If closing, don't process application data
                    if (isOutputShutdown())
                    {
                        if (outBuf!=null && outBuf.hasContent())
                            throw new EofException("Write while closing");
                        break;
                    }

                    // Try wrapping some application data
                    if (outBuf!=null && outBuf.hasContent())
                    {
                        int c=wrap(outBuf);
                        progress=c>0||_result.bytesProduced()>0||_result.bytesConsumed()>0;

                        if (c>0)
                            sent+=c;
                        else if (c<0 && sent==0)
                            sent=-1;
                    }

                    // Try unwrapping some application data
                    if (inBBuf.remaining()>0 && _inNIOBuffer!=null && _inNIOBuffer.hasContent())
                    {
                        int space=inBBuf.remaining();
                        progress|=unwrap(inBBuf);
                        received+=space-inBBuf.remaining();
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
                        if (_debug) LOG.warn("{} JETTY-567",_session);
                        return -1;
                    }
                    break;
                }

                case NEED_WRAP:
                {
                    checkRenegotiate();

                    // The SSL needs to send some handshake data to the other side
                    int c=0;
                    if (outBuf!=null && outBuf.hasContent())
                        c=wrap(outBuf);
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
                    progress|=unwrap(inBBuf);
                    if (isInputShutdown())
                        inBBuf.clear();
                    break;
                }
            }

            if (_debug) LOG.debug("{} progress {}",_session,progress);
        }

        if (_debug) LOG.debug("{} received {} sent {}",_session,received,sent);

        freeInBuffer();
        return (received<0||sent<0)?-1:(received+sent);
    }


    /** Fill the buffer with unencrypted bytes.
     * Called by a Http Parser when more data is
     * needed to continue parsing a request or a response.
     */
    @Override
    public int fill(Buffer buffer) throws IOException
    {
        _debug=LOG.isDebugEnabled();
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
                process(bbuf,null);
            }
            finally
            {
                // reset the Buffers
                buffer.setPutIndex(bbuf.position());
                bbuf.position(0);
            }
        }
        // return the number of unencrypted bytes filled.
        int filled=buffer.length()-size;
        if (filled==0 && (isInputShutdown() || !isOpen()))
            return -1;

        return filled;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int flush(Buffer buffer) throws IOException
    {
        _debug=LOG.isDebugEnabled();
        LOG.debug("{} flush1",_session);
        return process(null,buffer);
    }


    /* ------------------------------------------------------------ */
    /*
     */
    @Override
    public int flush(Buffer header, Buffer buffer, Buffer trailer) throws IOException
    {
        _debug=LOG.isDebugEnabled();
        LOG.debug("{} flush3",_session);

        int len=0;
        int flushed=0;
        if (header!=null && header.hasContent())
        {
            len=header.length();
            flushed=flush(header);
        }
        if (flushed==len && buffer!=null && buffer.hasContent())
        {
            int f=flush(buffer);
            if (f>=0)
                flushed+=f;
            else if (flushed==0)
                flushed=-1;
        }

        return flushed;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void flush() throws IOException
    {
        LOG.debug("{} flush",_session);
        if (!isOpen())
            throw new EofException();

        if (isBufferingOutput())
        {
            int flushed=super.flush(_outNIOBuffer);
            if (_debug)
                LOG.debug("{} flushed={} left={}",_session,flushed,_outNIOBuffer.length());
        }
        else if (_engine.isOutboundDone() && super.isOpen())
        {
            if (_debug)
                LOG.debug("{} flush shutdownOutput",_session);
            try
            {
                super.shutdownOutput();
            }
            catch(IOException e)
            {
                LOG.ignore(e);
            }
        }

        freeOutBuffer();
    }

    /* ------------------------------------------------------------ */
    private void checkRenegotiate() throws IOException
    {
        if (_handshook && !_allowRenegotiate && _channel!=null && _channel.isOpen())
        {
            LOG.warn("SSL renegotiate denied: {}",_channel);
            super.close();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if progress is made
     */
    private synchronized boolean unwrap(ByteBuffer buffer) throws IOException
    {
        needInBuffer();
        try
        {
            ByteBuffer in_buffer=_inNIOBuffer.getByteBuffer();

            _inNIOBuffer.compact();

            int total_filled=0;
            boolean remoteClosed = false;

            LOG.debug("{} unwrap space={} open={}",_session,_inNIOBuffer.space(),super.isOpen());

            // loop filling as much encrypted data as we can into the buffer
            while (_inNIOBuffer.space()>0 && super.isOpen())
            {
                int filled=super.fill(_inNIOBuffer);
                if (_debug) LOG.debug("{} filled {}",_session,filled);
                if (filled < 0)
                    remoteClosed = true;
                // break the loop if no progress is made (we have read everything there is to read)
                if (filled<=0)
                    break;
                total_filled+=filled;
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
                if (!_handshook && _result.getHandshakeStatus()==SSLEngineResult.HandshakeStatus.FINISHED)
                    _handshook=true;
                if (_debug) LOG.debug("{} unwrap {}",_session,_result);

                // skip the bytes consumed
                _inNIOBuffer.skip(_result.bytesConsumed());
            }
            catch(SSLException e)
            {
                LOG.warn(getRemoteAddr() + ":" + getRemotePort() + " ",e);
                super.close();
                throw e;
            }
            finally
            {
                // reset the buffer so it can be managed by the _inNIOBuffer again.
                in_buffer.position(0);
                in_buffer.limit(in_buffer.capacity());
            }

            // handle the unwrap results
            switch(_result.getStatus())
            {
                case BUFFER_OVERFLOW:
                    LOG.debug("{} unwrap overflow",_session);
                    return false;

                case BUFFER_UNDERFLOW:
                    // Not enough data,
                    // If we are closed, we will never get more, so EOF
                    // else return and we will be tried again
                    // later when more data arriving causes another dispatch.
                    if (LOG.isDebugEnabled()) LOG.debug("{} unwrap {}",_session,_result);
                    if(!isOpen())
                    {
                        _inNIOBuffer.clear();
                        if (_outNIOBuffer!=null)
                            _outNIOBuffer.clear();
                        throw new EofException();
                    }
                    return (total_filled > 0);

                case CLOSED:
                    if (super.isOpen())
                        super.close();
                    // return true is some bytes somewhere were moved about.
                    return total_filled>0 ||_result.bytesConsumed()>0 || _result.bytesProduced()>0;

                case OK:
                    // return true is some bytes somewhere were moved about.
                    return total_filled>0 ||_result.bytesConsumed()>0 || _result.bytesProduced()>0;

                default:
                    LOG.warn("{} unwrap default: {}",_session,_result);
                    throw new IOException(_result.toString());
            }
        }
        finally
        {
            releaseInBuffer();
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
    private synchronized int wrap(final Buffer buffer) throws IOException
    {
        ByteBuffer bbuf=extractOutputBuffer(buffer);
        synchronized(bbuf)
        {
            int consumed=0;
            needOutBuffer();
            try
            {
                _outNIOBuffer.compact();
                ByteBuffer out_buffer=_outNIOBuffer.getByteBuffer();
                synchronized(out_buffer)
                {
                    try
                    {
                        bbuf.position(buffer.getIndex());
                        bbuf.limit(buffer.putIndex());
                        out_buffer.position(_outNIOBuffer.putIndex());
                        out_buffer.limit(out_buffer.capacity());
                        _result=_engine.wrap(bbuf,out_buffer);
                        if (_debug) LOG.debug("{} wrap {}",_session,_result);
                        if (!_handshook && _result.getHandshakeStatus()==SSLEngineResult.HandshakeStatus.FINISHED)
                            _handshook=true;
                        _outNIOBuffer.setPutIndex(out_buffer.position());
                        consumed=_result.bytesConsumed();
                    }
                    catch(SSLException e)
                    {
                        LOG.warn(getRemoteAddr()+":"+getRemotePort()+" ",e);
                        if (getChannel().isOpen())
                            getChannel().close(); // TODO ???
                        throw e;
                    }
                    finally
                    {
                        out_buffer.position(0);
                        bbuf.position(0);
                        bbuf.limit(bbuf.capacity());

                        if (consumed>0)
                        {
                            int len=consumed<buffer.length()?consumed:buffer.length();
                            buffer.skip(len);
                            consumed-=len;
                        }
                    }
                }
            }
            finally
            {
                releaseOutBuffer();
            }
        }
        
        switch(_result.getStatus())
        {
            case BUFFER_UNDERFLOW:
                throw new IllegalStateException();

            case BUFFER_OVERFLOW:
                LOG.debug("{} wrap {}",_session,_result);
                flush();
                return 0;

            case OK:
                return _result.bytesConsumed();
            case CLOSED:
                if (super.isOpen())
                    super.close();
                return _result.bytesConsumed()>0?_result.bytesConsumed():-1;

            default:
                LOG.warn("{} wrap default {}",_session,_result);
            throw new IOException(_result.toString());
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isBufferingInput()
    {
        final Buffer in = _inNIOBuffer;
        return in!=null && in.hasContent();
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isBufferingOutput()
    {
        final NIOBuffer out = _outNIOBuffer;
        return out!=null && out.hasContent();
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
    public String toString()
    {
        final NIOBuffer i=_inNIOBuffer;
        final NIOBuffer o=_outNIOBuffer;
        return "SSL"+super.toString()+","+(_engine==null?"-":_engine.getHandshakeStatus())+", in/out="+
        (i==null?0:i.length())+"/"+(o==null?0:o.length())+
        " bi/o="+isBufferingInput()+"/"+isBufferingOutput()+
        " "+_result;
    }
}
