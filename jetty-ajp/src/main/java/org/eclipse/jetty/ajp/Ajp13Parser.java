// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.ajp;

import java.io.IOException;
import java.io.InterruptedIOException;

import javax.servlet.ServletInputStream;

import org.eclipse.jetty.http.HttpTokens;
import org.eclipse.jetty.http.Parser;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.BufferUtil;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.View;
import org.eclipse.jetty.util.log.Log;

/**
 * 
 */
public class Ajp13Parser implements Parser
{
    private final static int STATE_START = -1;
    private final static int STATE_END = 0;
    private final static int STATE_AJP13CHUNK_START = 1;
    private final static int STATE_AJP13CHUNK = 2;

    private int _state = STATE_START;
    private long _contentLength;
    private long _contentPosition;
    private int _chunkLength;
    private int _chunkPosition;
    private int _headers;
    private Buffers _buffers;
    private EndPoint _endp;
    private Buffer _buffer;
    private Buffer _header; // Buffer for header data (and small _content)
    private Buffer _body; // Buffer for large content
    private View _contentView = new View();
    private EventHandler _handler;
    private Ajp13Generator _generator;
    private View _tok0; // Saved token: header name, request method or response version
    private View _tok1; // Saved token: header value, request URI orresponse code
    protected int _length;
    protected int _packetLength;
    

    /* ------------------------------------------------------------------------------- */
    public Ajp13Parser(Buffers buffers, EndPoint endPoint)
    {
        _buffers = buffers;
        _endp = endPoint;
    }
    
    /* ------------------------------------------------------------------------------- */
    public void setEventHandler(EventHandler handler)
    {
        _handler=handler;
    }
    
    /* ------------------------------------------------------------------------------- */
    public void setGenerator(Ajp13Generator generator)
    {
        _generator=generator;
    }

    /* ------------------------------------------------------------------------------- */
    public long getContentLength()
    {
        return _contentLength;
    }

    /* ------------------------------------------------------------------------------- */
    public int getState()
    {
        return _state;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean inContentState()
    {
        return _state > 0;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean inHeaderState()
    {
        return _state < 0;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isIdle()
    {
        return _state == STATE_START;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isComplete()
    {
        return _state == STATE_END;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isMoreInBuffer()
    {

        if (_header != null && _header.hasContent() || _body != null && _body.hasContent())
            return true;

        return false;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isState(int state)
    {
        return _state == state;
    }

    /* ------------------------------------------------------------------------------- */
    public void parse() throws IOException
    {
        if (_state == STATE_END)
            reset(false);
        if (_state != STATE_START)
            throw new IllegalStateException("!START");

        // continue parsing
        while (!isComplete())
        {
            parseNext();
        }
    }

    /* ------------------------------------------------------------------------------- */
    public long parseAvailable() throws IOException
    {
        long len = parseNext();
        long total = len > 0 ? len : 0;

        // continue parsing
        while (!isComplete() && _buffer != null && _buffer.length() > 0)
        {
            len = parseNext();
            if (len > 0)
                total += len;
            else
                break;
        }
        return total;
    }

    /* ------------------------------------------------------------------------------- */
    private int fill() throws IOException
    {
        int filled = -1;
        if (_body != null && _buffer != _body)
        {
            // mod_jk implementations may have some partial data from header
            // check if there are partial contents in the header
            // copy it to the body if there are any
            if(_header.length() > 0)
            {
                // copy the patial data from the header to the body
                _body.put(_header);
            }

            _buffer = _body;
            
            if (_buffer.length()>0)
            {            
                filled = _buffer.length();
                return filled;
            }
        }

        if (_buffer.markIndex() == 0 && _buffer.putIndex() == _buffer.capacity())
            throw new IOException("FULL");
        if (_endp != null && filled <= 0)
        {
            // Compress buffer if handling _content buffer
            // TODO check this is not moving data too much
            if (_buffer == _body)
                _buffer.compact();

            if (_buffer.space() == 0)
                throw new IOException("FULL");

            try
            {
                filled = _endp.fill(_buffer);
            }
            catch (IOException e)
            {
                // This is normal in AJP since the socket closes on timeout only
                Log.debug(e);
                reset(true);
                throw (e instanceof EofException) ? e : new EofException(e);
            }
        }
        
        if (filled < 0)
        {
            if (_state > STATE_END)
            {
                _state = STATE_END;
                _handler.messageComplete(_contentPosition);
                return filled;
            }
            reset(true);
            throw new EofException();
        }
    
        return filled;
    }
    
    /* ------------------------------------------------------------------------------- */
    public long parseNext() throws IOException
    {
        long total_filled = -1;

        if (_buffer == null)
        {
            if (_header == null)
                _header = _buffers.getHeader();
           
            _buffer = _header;
            _tok0 = new View(_header);
            _tok1 = new View(_header);
            _tok0.setPutIndex(_tok0.getIndex());
            _tok1.setPutIndex(_tok1.getIndex());
        }

        if (_state == STATE_END)
            throw new IllegalStateException("STATE_END");
        if (_state > STATE_END && _contentPosition == _contentLength)
        {
            _state = STATE_END;
            _handler.messageComplete(_contentPosition);
            return total_filled;
        }
        
        if (_state < 0)
        {
            // have we seen a packet?
            if (_packetLength<=0)
            {
                if (_buffer.length()<4)
                {
                    if (total_filled<0) 
                        total_filled=0;
                    total_filled+=fill();
                    if (_buffer.length()<4)
                        return total_filled;
                }
                
                _contentLength = HttpTokens.UNKNOWN_CONTENT;
                int _magic = Ajp13RequestPacket.getInt(_buffer);
                if (_magic != Ajp13RequestHeaders.MAGIC)
                    throw new IOException("Bad AJP13 rcv packet: " + "0x" + Integer.toHexString(_magic) + " expected " + "0x" + Integer.toHexString(Ajp13RequestHeaders.MAGIC) + " " + this);


                _packetLength = Ajp13RequestPacket.getInt(_buffer);
                if (_packetLength > Ajp13Packet.MAX_PACKET_SIZE)
                    throw new IOException("AJP13 packet (" + _packetLength + "bytes) too large for buffer");
                
            }
            
            if (_buffer.length() < _packetLength)
            {
                if (total_filled<0) 
                    total_filled=0;
                total_filled+=fill();
                if (_buffer.length() < _packetLength)
                    return total_filled;
            }

            // Parse Header
            Buffer bufHeaderName = null;
            Buffer bufHeaderValue = null;
            int attr_type = 0;

            byte packetType = Ajp13RequestPacket.getByte(_buffer);

            switch (packetType)
            {
                case Ajp13Packet.FORWARD_REQUEST_ORDINAL:
                    _handler.startForwardRequest();
                    break;
                case Ajp13Packet.CPING_REQUEST_ORDINAL:
                    (_generator).sendCPong();
                    
                    if(_header != null)
                    {
                        _buffers.returnBuffer(_header);
                        _header = null;
                    }

                    if(_body != null)
                    {
                        _buffers.returnBuffer(_body);
                        _body = null;
                    }

                    _buffer= null;

                    reset(true);

                    return -1;
                case Ajp13Packet.SHUTDOWN_ORDINAL:
                    shutdownRequest();

                    return -1;

                default:
                    // XXX Throw an Exception here?? Close
                    // connection!
                    Log.warn("AJP13 message type ({PING}: "+packetType+" ) not supported/recognized as an AJP request");
                throw new IllegalStateException("PING is not implemented");
            }


            _handler.parsedMethod(Ajp13RequestPacket.getMethod(_buffer));
            _handler.parsedProtocol(Ajp13RequestPacket.getString(_buffer, _tok0));
            _handler.parsedUri(Ajp13RequestPacket.getString(_buffer, _tok1));
            _handler.parsedRemoteAddr(Ajp13RequestPacket.getString(_buffer, _tok1));
            _handler.parsedRemoteHost(Ajp13RequestPacket.getString(_buffer, _tok1));
            _handler.parsedServerName(Ajp13RequestPacket.getString(_buffer, _tok1));
            _handler.parsedServerPort(Ajp13RequestPacket.getInt(_buffer));
            _handler.parsedSslSecure(Ajp13RequestPacket.getBool(_buffer));


            _headers = Ajp13RequestPacket.getInt(_buffer);

            for (int h=0;h<_headers;h++)
            {
                bufHeaderName = Ajp13RequestPacket.getHeaderName(_buffer, _tok0);
                bufHeaderValue = Ajp13RequestPacket.getString(_buffer, _tok1);

                if (bufHeaderName != null && bufHeaderName.toString().equals(Ajp13RequestHeaders.CONTENT_LENGTH))
                {
                    _contentLength = BufferUtil.toLong(bufHeaderValue);
                    if (_contentLength == 0)
                        _contentLength = HttpTokens.NO_CONTENT;
                }

                _handler.parsedHeader(bufHeaderName, bufHeaderValue);
            }



            attr_type = Ajp13RequestPacket.getByte(_buffer) & 0xff;
            while (attr_type != 0xFF)
            {

                switch (attr_type)
                {
                    // XXX How does this plug into the web
                    // containers
                    // authentication?

                    case Ajp13RequestHeaders.REMOTE_USER_ATTR:
                        _handler.parsedRemoteUser(Ajp13RequestPacket.getString(_buffer, _tok1));
                        break;
                    case Ajp13RequestHeaders.AUTH_TYPE_ATTR:
                        //XXX JASPI how does this make sense?
                        _handler.parsedAuthorizationType(Ajp13RequestPacket.getString(_buffer, _tok1));
                        break;

                    case Ajp13RequestHeaders.QUERY_STRING_ATTR:
                        _handler.parsedQueryString(Ajp13RequestPacket.getString(_buffer, _tok1));
                        break;

                    case Ajp13RequestHeaders.JVM_ROUTE_ATTR:
                        // XXX Using old Jetty 5 key,
                        // should change!
                        // Note used in
                        // org.eclipse.jetty.servlet.HashSessionIdManager
                        _handler.parsedRequestAttribute("org.eclipse.http.ajp.JVMRoute", Ajp13RequestPacket.getString(_buffer, _tok1));
                        break;

                    case Ajp13RequestHeaders.SSL_CERT_ATTR:
                        _handler.parsedSslCert(Ajp13RequestPacket.getString(_buffer, _tok1));
                        break;

                    case Ajp13RequestHeaders.SSL_CIPHER_ATTR:
                        _handler.parsedSslCipher(Ajp13RequestPacket.getString(_buffer, _tok1));
                        // SslSocketConnector.customize()
                        break;

                    case Ajp13RequestHeaders.SSL_SESSION_ATTR:
                        _handler.parsedSslSession(Ajp13RequestPacket.getString(_buffer, _tok1));
                        break;

                    case Ajp13RequestHeaders.REQUEST_ATTR:
                        _handler.parsedRequestAttribute(Ajp13RequestPacket.getString(_buffer, _tok0).toString(), Ajp13RequestPacket.getString(_buffer, _tok1));
                        break;

                        // New Jk API?
                        // Check if experimental or can they
                        // assumed to be
                        // supported
                        
                    case Ajp13RequestHeaders.SSL_KEYSIZE_ATTR:
                        
                        // This has been implemented in AJP13 as either a string or a integer.
                        // Servlet specs say javax.servlet.request.key_size must be an Integer
                        
                        // Does it look like a string containing digits?
                        int length = Ajp13RequestPacket.getInt(_buffer);
                        
                        if (length>0 && length<16)
                        {
                            // this must be a string length rather than a key length
                            _buffer.skip(-2);
                            _handler.parsedSslKeySize(Integer.parseInt(Ajp13RequestPacket.getString(_buffer, _tok1).toString()));
                        }
                        else
                            _handler.parsedSslKeySize(length);
                        
                        break;

                        
                        // Used to lock down jk requests with a
                        // secreate
                        // key.
                        
                    case Ajp13RequestHeaders.SECRET_ATTR:
                        // XXX Investigate safest way to
                        // deal with
                        // this...
                        // should this tie into shutdown
                        // packet?
                        break;

                    case Ajp13RequestHeaders.STORED_METHOD_ATTR:
                        // XXX Confirm this should
                        // really overide
                        // previously parsed method?
                        // _handler.parsedMethod(Ajp13PacketMethods.CACHE.get(Ajp13RequestPacket.getString()));
                        break;


                    case Ajp13RequestHeaders.CONTEXT_ATTR:
                        _handler.parsedContextPath(Ajp13RequestPacket.getString(_buffer, _tok1));
                        break;
                    case Ajp13RequestHeaders.SERVLET_PATH_ATTR:
                        _handler.parsedServletPath(Ajp13RequestPacket.getString(_buffer, _tok1));

                        break;
                    default:
                        Log.warn("Unsupported Ajp13 Request Attribute {}", new Integer(attr_type));
                    break;
                }

                attr_type = Ajp13RequestPacket.getByte(_buffer) & 0xff;
            }






            _contentPosition = 0;
            switch ((int) _contentLength)
            {

                case HttpTokens.NO_CONTENT:
                    _state = STATE_END;
                    _handler.headerComplete();
                    _handler.messageComplete(_contentPosition);

                    break;

                case HttpTokens.UNKNOWN_CONTENT:

                    _generator.getBodyChunk();
                    if (_buffers != null && _body == null && _buffer == _header && _header.length() <= 0)
                    {
                        _body = _buffers.getBuffer();
                        _body.clear();
                    }
                    _state = STATE_AJP13CHUNK_START;
                    _handler.headerComplete(); // May recurse here!

                    return total_filled;

                default:

                    if (_buffers != null && _body == null && _buffer == _header && _contentLength > (_header.capacity() - _header.getIndex()))
                    {
                        _body = _buffers.getBuffer();
                        _body.clear();

                    }
                _state = STATE_AJP13CHUNK_START;
                _handler.headerComplete(); // May recurse here!
                return total_filled;
            }
        }


        Buffer chunk;

        while (_state>STATE_END)
        {
            switch (_state)
            {
                case STATE_AJP13CHUNK_START:
                    if (_buffer.length()<6)
                    {
                        if (total_filled<0) 
                            total_filled=0;
                        total_filled+=fill();
                        if (_buffer.length()<6)
                            return total_filled;
                    }
                    int _magic=Ajp13RequestPacket.getInt(_buffer);
                    if (_magic!=Ajp13RequestHeaders.MAGIC)
                    {
                        throw new IOException("Bad AJP13 rcv packet: "+"0x"+Integer.toHexString(_magic)+" expected "+"0x"
                                +Integer.toHexString(Ajp13RequestHeaders.MAGIC)+" "+this);
                    }
                    _chunkPosition=0;
                    _chunkLength=Ajp13RequestPacket.getInt(_buffer)-2;
                    Ajp13RequestPacket.getInt(_buffer);
                    if (_chunkLength==0)
                    {
                        _state=STATE_END;
                         _generator.gotBody();
                        _handler.messageComplete(_contentPosition);
                        return total_filled;
                    }
                    _state=STATE_AJP13CHUNK;

                case STATE_AJP13CHUNK:
                    if (_buffer.length()<_chunkLength)
                    {
                        if (total_filled<0) 
                            total_filled=0;
                        total_filled+=fill();
                        if (_buffer.length()<_chunkLength)
                            return total_filled;
                    }

                    int remaining=_chunkLength-_chunkPosition;

                    if (remaining==0)
                    {
                        _state=STATE_AJP13CHUNK_START;
                        if (_contentPosition<_contentLength)
                        {
                            _generator.getBodyChunk();
                        }
                        else
                        {
                            _generator.gotBody();
                        }

                        return total_filled;
                    }

                    if (_buffer.length()<remaining)
                    {
                        remaining=_buffer.length();
                    }

                    chunk=Ajp13RequestPacket.get(_buffer,remaining);
                    _contentPosition+=chunk.length();
                    _chunkPosition+=chunk.length();
                    _contentView.update(chunk);

                    remaining=_chunkLength-_chunkPosition;

                    if (remaining==0)
                    {
                        _state=STATE_AJP13CHUNK_START;
                        if (_contentPosition<_contentLength || _contentLength == HttpTokens.UNKNOWN_CONTENT)
                        {
                            _generator.getBodyChunk();
                        }
                        else
                        {
                            _generator.gotBody();
                        }
                    }

                    _handler.content(chunk);

                return total_filled;

            default:
                throw new IllegalStateException("Invalid Content State");

            }

        }

        return total_filled;
    }

    /* ------------------------------------------------------------------------------- */
    public void reset(boolean returnBuffers)
    {
        _state = STATE_START;
        _contentLength = HttpTokens.UNKNOWN_CONTENT;
        _contentPosition = 0;
        _length = 0;
        _packetLength = 0;

        if (_body != null)
        {
            if (_body.hasContent())
            {
                _header.setMarkIndex(-1);
                _header.compact();
                // TODO if pipelined requests received after big
                // input - maybe this is not good?.
                _body.skip(_header.put(_body));

            }

            if (_body.length() == 0)
            {
                if (_buffers != null && returnBuffers)
                    _buffers.returnBuffer(_body);
                _body = null;
            }
            else
            {
                _body.setMarkIndex(-1);
                _body.compact();
            }
        }

        if (_header != null)
        {
            _header.setMarkIndex(-1);
            if (!_header.hasContent() && _buffers != null && returnBuffers)
            {
                _buffers.returnBuffer(_header);
                _header = null;
                _buffer = null;
            }
            else
            {
                _header.compact();
                _tok0.update(_header);
                _tok0.update(0, 0);
                _tok1.update(_header);
                _tok1.update(0, 0);
            }
        }

        _buffer = _header;
    }

    /* ------------------------------------------------------------------------------- */
    Buffer getHeaderBuffer()
    {
        return _buffer;
    }

    private void shutdownRequest()
    {
        _state = STATE_END;

        if(!Ajp13SocketConnector.__allowShutdown)
        {
            Log.warn("AJP13: Shutdown Request is Denied, allowShutdown is set to false!!!");
            return;
        }

        if(Ajp13SocketConnector.__secretWord != null)
        {
            Log.warn("AJP13: Validating Secret Word");
            try
            {
                String secretWord = Ajp13RequestPacket.getString(_buffer, _tok1).toString();

                if(!Ajp13SocketConnector.__secretWord.equals(secretWord))
                {
                    Log.warn("AJP13: Shutdown Request Denied, Invalid Sercret word!!!");
                    throw new IllegalStateException("AJP13: Secret Word is Invalid: Peer has requested shutdown but, Secret Word did not match");
                }
            }
            catch (Exception e)
            {
                Log.warn("AJP13: Secret Word is Required!!!");
                Log.debug(e);
                throw new IllegalStateException("AJP13: Secret Word is Required: Peer has requested shutdown but, has not provided a Secret Word");
            }


            Log.warn("AJP13: Shutdown Request is Denied, allowShutdown is set to false!!!");
            return;
        }

        Log.warn("AJP13: Peer Has Requested for Shutdown!!!");
        Log.warn("AJP13: Jetty 6 is shutting down !!!");
        System.exit(0);
    }

    /* ------------------------------------------------------------------------------- */
    public interface EventHandler
    {

        // public void shutdownRequest() throws IOException;
        // public void cpingRequest() throws IOException;

        public void content(Buffer ref) throws IOException;

        public void headerComplete() throws IOException;

        public void messageComplete(long contextLength) throws IOException;

        public void parsedHeader(Buffer name, Buffer value) throws IOException;

        public void parsedMethod(Buffer method) throws IOException;

        public void parsedProtocol(Buffer protocol) throws IOException;

        public void parsedQueryString(Buffer value) throws IOException;

        public void parsedRemoteAddr(Buffer addr) throws IOException;

        public void parsedRemoteHost(Buffer host) throws IOException;

        public void parsedRequestAttribute(String key, Buffer value) throws IOException;
        
        public void parsedRequestAttribute(String key, int value) throws IOException;

        public void parsedServerName(Buffer name) throws IOException;

        public void parsedServerPort(int port) throws IOException;

        public void parsedSslSecure(boolean secure) throws IOException;

        public void parsedUri(Buffer uri) throws IOException;

        public void startForwardRequest() throws IOException;

        public void parsedAuthorizationType(Buffer authType) throws IOException;
        
        public void parsedRemoteUser(Buffer remoteUser) throws IOException;

        public void parsedServletPath(Buffer servletPath) throws IOException;
        
        public void parsedContextPath(Buffer context) throws IOException;

        public void parsedSslCert(Buffer sslCert) throws IOException;

        public void parsedSslCipher(Buffer sslCipher) throws IOException;

        public void parsedSslSession(Buffer sslSession) throws IOException;

        public void parsedSslKeySize(int keySize) throws IOException;





    }

    /* ------------------------------------------------------------ */
    /**
     * TODO Make this common with HttpParser
     * 
     */
    public static class Input extends ServletInputStream
    {
        private Ajp13Parser _parser;
        private EndPoint _endp;
        private long _maxIdleTime;
        private View _content;

        /* ------------------------------------------------------------ */
        public Input(Ajp13Parser parser, long maxIdleTime)
        {
            _parser = parser;
            _endp = parser._endp;
            _maxIdleTime = maxIdleTime;
            _content = _parser._contentView;
        }

        /* ------------------------------------------------------------ */
        @Override
        public int read() throws IOException
        {
            int c = -1;
            if (blockForContent())
                c = 0xff & _content.get();
            return c;
        }

        /* ------------------------------------------------------------ */
        /*
         * @see java.io.InputStream#read(byte[], int, int)
         */
        @Override
        public int read(byte[] b, int off, int len) throws IOException
        {
            int l = -1;
            if (blockForContent())
                l = _content.get(b, off, len);
            return l;
        }

        /* ------------------------------------------------------------ */
        private boolean blockForContent() throws IOException
        {
            if (_content.length() > 0)
                return true;
            if (_parser.isState(Ajp13Parser.STATE_END))
                return false;

            // Handle simple end points.
            if (_endp == null)
                _parser.parseNext();

            // Handle blocking end points
            else if (_endp.isBlocking())
            {
                _parser.parseNext();
                
                // parse until some progress is made (or IOException thrown for timeout)
                while (_content.length() == 0 && !_parser.isState(Ajp13Parser.STATE_END))
                {
                    // Try to get more _parser._content
                    _parser.parseNext();
                }
            }
            else // Handle non-blocking end point
            {
                long filled = _parser.parseNext();
                boolean blocked = false;

                // parse until some progress is made (or
                // IOException thrown for timeout)
                while (_content.length() == 0 && !_parser.isState(Ajp13Parser.STATE_END))
                {
                    // if fill called, but no bytes read,
                    // then block
                    if (filled > 0)
                        blocked = false;
                    else if (filled == 0)
                    {
                        if (blocked)
                            throw new InterruptedIOException("timeout");

                        blocked = true;
                        _endp.blockReadable(_maxIdleTime);
                    }

                    // Try to get more _parser._content
                    filled = _parser.parseNext();
                }
            }

            return _content.length() > 0;
        }

    }
}
