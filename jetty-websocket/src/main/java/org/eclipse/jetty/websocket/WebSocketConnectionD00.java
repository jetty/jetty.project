// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.WebSocket.OnFrame;

public class WebSocketConnectionD00 extends AbstractConnection implements WebSocketConnection, WebSocket.FrameConnection
{
    private static final Logger LOG = Log.getLogger(WebSocketConnectionD00.class);

    public final static byte LENGTH_FRAME=(byte)0x80;
    public final static byte SENTINEL_FRAME=(byte)0x00;

    final IdleCheck _idle;
    final WebSocketParser _parser;
    final WebSocketGenerator _generator;
    final WebSocket _websocket;
    final String _protocol;
    String _key1;
    String _key2;
    ByteArrayBuffer _hixieBytes;

    public WebSocketConnectionD00(WebSocket websocket, EndPoint endpoint, WebSocketBuffers buffers, long timestamp, int maxIdleTime, String protocol)
        throws IOException
    {
        super(endpoint,timestamp);
        if (endpoint instanceof AsyncEndPoint)
            ((AsyncEndPoint)endpoint).cancelIdle();

        _endp.setMaxIdleTime(maxIdleTime);

        _websocket = websocket;
        _protocol=protocol;

        _generator = new WebSocketGeneratorD00(buffers, _endp);
        _parser = new WebSocketParserD00(buffers, endpoint, new FrameHandlerD00(_websocket));

        if (_endp instanceof SelectChannelEndPoint)
        {
            final SelectChannelEndPoint scep=(SelectChannelEndPoint)_endp;
            scep.cancelIdle();
            _idle=new IdleCheck()
            {
                public void access(EndPoint endp)
                {
                    scep.scheduleIdle();
                }
            };
            scep.scheduleIdle();
        }
        else
        {
            _idle = new IdleCheck()
            {
                public void access(EndPoint endp)
                {}
            };
        }
    }

    /* ------------------------------------------------------------ */
    public org.eclipse.jetty.websocket.WebSocket.Connection getConnection()
    {
        return this;
    }


    /* ------------------------------------------------------------ */
    public void setHixieKeys(String key1,String key2)
    {
        _key1=key1;
        _key2=key2;
        _hixieBytes=new IndirectNIOBuffer(16);
    }

    /* ------------------------------------------------------------ */
    public Connection handle() throws IOException
    {
        try
        {
            // handle stupid hixie random bytes
            if (_hixieBytes!=null)
            {

                // take any available bytes from the parser buffer, which may have already been read
                Buffer buffer=_parser.getBuffer();
                if (buffer!=null && buffer.length()>0)
                {
                    int l=buffer.length();
                    if (l>(8-_hixieBytes.length()))
                        l=8-_hixieBytes.length();
                    _hixieBytes.put(buffer.peek(buffer.getIndex(),l));
                    buffer.skip(l);
                }

                // while we are not blocked
                while(_endp.isOpen())
                {
                    // do we now have enough
                    if (_hixieBytes.length()==8)
                    {
                        // we have the silly random bytes
                        // so let's work out the stupid 16 byte reply.
                        doTheHixieHixieShake();
                        _endp.flush(_hixieBytes);
                        _hixieBytes=null;
                        _endp.flush();
                        break;
                    }

                    // no, then let's fill
                    int filled=_endp.fill(_hixieBytes);
                    if (filled<0)
                    {
                        _endp.close();
                        break;
                    }
                }

                if (_websocket instanceof OnFrame)
                    ((OnFrame)_websocket).onHandshake(this);
                _websocket.onOpen(this);
                return this;
            }

            // handle the framing protocol
            boolean progress=true;

            while (progress)
            {
                int flushed=_generator.flush();
                int filled=_parser.parseNext();

                progress = flushed>0 || filled>0;

                if (filled<0 || flushed<0)
                {
                    _endp.close();
                    break;
                }
            }
        }
        catch(IOException e)
        {
            LOG.debug(e);
            try
            {
                _endp.close();
            }
            catch(IOException e2)
            {
                LOG.ignore(e2);
            }
            throw e;
        }
        finally
        {
            if (_endp.isOpen())
            {
                _idle.access(_endp);

                if (_endp.isInputShutdown() && _generator.isBufferEmpty())
                    _endp.close();
                else
                    checkWriteable();

                checkWriteable();
            }
        }
        return this;
    }

    /* ------------------------------------------------------------ */
    private void doTheHixieHixieShake()
    {
        byte[] result=WebSocketConnectionD00.doTheHixieHixieShake(
                WebSocketConnectionD00.hixieCrypt(_key1),
                WebSocketConnectionD00.hixieCrypt(_key2),
                _hixieBytes.asArray());
        _hixieBytes.clear();
        _hixieBytes.put(result);
    }

    /* ------------------------------------------------------------ */
    public boolean isOpen()
    {
        return _endp!=null&&_endp.isOpen();
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return _parser.isBufferEmpty() && _generator.isBufferEmpty();
    }

    /* ------------------------------------------------------------ */
    public boolean isSuspended()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    public void closed()
    {
        _websocket.onClose(WebSocketConnectionD06.CLOSE_NORMAL,"");
    }

    /* ------------------------------------------------------------ */
    /**
     */
    public void sendMessage(String content) throws IOException
    {
        byte[] data = content.getBytes(StringUtil.__UTF8);
        _generator.addFrame((byte)0,SENTINEL_FRAME,data,0,data.length);
        _generator.flush();
        checkWriteable();
        _idle.access(_endp);
    }

    /* ------------------------------------------------------------ */
    public void sendMessage(byte[] data, int offset, int length) throws IOException
    {
        _generator.addFrame((byte)0,LENGTH_FRAME,data,offset,length);
        _generator.flush();
        checkWriteable();
        _idle.access(_endp);
    }

    /* ------------------------------------------------------------ */
    public boolean isMore(byte flags)
    {
        return (flags&0x8) != 0;
    }

    /* ------------------------------------------------------------ */
    /**
     * {@inheritDoc}
     */
    public void sendControl(byte code, byte[] content, int offset, int length) throws IOException
    {
    }

    /* ------------------------------------------------------------ */
    public void sendFrame(byte flags,byte opcode, byte[] content, int offset, int length) throws IOException
    {
        _generator.addFrame((byte)0,opcode,content,offset,length);
        _generator.flush();
        checkWriteable();
        _idle.access(_endp);
    }

    /* ------------------------------------------------------------ */
    public void close(int code, String message)
    {
        throw new UnsupportedOperationException();
    }

    /* ------------------------------------------------------------ */
    public void disconnect()
    {
        try
        {
            _generator.flush();
            _endp.close();
        }
        catch(IOException e)
        {
            LOG.ignore(e);
        }
    }

    /* ------------------------------------------------------------ */
    public void fillBuffersFrom(Buffer buffer)
    {
        _parser.fill(buffer);
    }


    /* ------------------------------------------------------------ */
    private void checkWriteable()
    {
        if (!_generator.isBufferEmpty() && _endp instanceof AsyncEndPoint)
            ((AsyncEndPoint)_endp).scheduleWrite();
    }

    /* ------------------------------------------------------------ */
    static long hixieCrypt(String key)
    {
        // Don't ask me what all this is about.
        // I think it's pretend secret stuff, kind of
        // like talking in pig latin!
        long number=0;
        int spaces=0;
        for (char c : key.toCharArray())
        {
            if (Character.isDigit(c))
                number=number*10+(c-'0');
            else if (c==' ')
                spaces++;
        }
        return number/spaces;
    }

    public static byte[] doTheHixieHixieShake(long key1,long key2,byte[] key3)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte [] fodder = new byte[16];

            fodder[0]=(byte)(0xff&(key1>>24));
            fodder[1]=(byte)(0xff&(key1>>16));
            fodder[2]=(byte)(0xff&(key1>>8));
            fodder[3]=(byte)(0xff&key1);
            fodder[4]=(byte)(0xff&(key2>>24));
            fodder[5]=(byte)(0xff&(key2>>16));
            fodder[6]=(byte)(0xff&(key2>>8));
            fodder[7]=(byte)(0xff&key2);
            System.arraycopy(key3, 0, fodder, 8, 8);
            md.update(fodder);
            return md.digest();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private interface IdleCheck
    {
        void access(EndPoint endp);
    }

    public void handshake(HttpServletRequest request, HttpServletResponse response, String subprotocol) throws IOException
    {
        String uri=request.getRequestURI();
        String query=request.getQueryString();
        if (query!=null && query.length()>0)
            uri+="?"+query;
        uri=new HttpURI(uri).toString();
        String host=request.getHeader("Host");

        String origin=request.getHeader("Sec-WebSocket-Origin");
        if (origin==null)
            origin=request.getHeader("Origin");
        if (origin!=null)
            origin= QuotedStringTokenizer.quoteIfNeeded(origin, "\r\n");


        String key1 = request.getHeader("Sec-WebSocket-Key1");

        if (key1!=null)
        {
            String key2 = request.getHeader("Sec-WebSocket-Key2");
            setHixieKeys(key1,key2);

            response.setHeader("Upgrade","WebSocket");
            response.addHeader("Connection","Upgrade");
            if (origin!=null)
                response.addHeader("Sec-WebSocket-Origin",origin);
            response.addHeader("Sec-WebSocket-Location",(request.isSecure()?"wss://":"ws://")+host+uri);
            if (subprotocol!=null)
                response.addHeader("Sec-WebSocket-Protocol",subprotocol);
            response.sendError(101,"WebSocket Protocol Handshake");
        }
        else
        {
            response.setHeader("Upgrade","WebSocket");
            response.addHeader("Connection","Upgrade");
            response.addHeader("WebSocket-Origin",origin);
            response.addHeader("WebSocket-Location",(request.isSecure()?"wss://":"ws://")+host+uri);
            if (subprotocol!=null)
                response.addHeader("WebSocket-Protocol",subprotocol);
            response.sendError(101,"Web Socket Protocol Handshake");
            response.flushBuffer();
            if (_websocket instanceof OnFrame)
                ((OnFrame)_websocket).onHandshake(this);
            _websocket.onOpen(this);
        }
    }

    public void setMaxTextMessageSize(int size)
    {
    }

    public void setMaxIdleTime(int ms)
    {
        try
        {
            _endp.setMaxIdleTime(ms);
        }
        catch(IOException e)
        {
            LOG.warn(e);
        }
    }

    public void setMaxBinaryMessageSize(int size)
    {
    }

    public int getMaxTextMessageSize()
    {
        return -1;
    }

    public int getMaxIdleTime()
    {
        return _endp.getMaxIdleTime();
    }

    public int getMaxBinaryMessageSize()
    {
        return -1;
    }

    public String getProtocol()
    {
        return _protocol;
    }

    static class FrameHandlerD00 implements WebSocketParser.FrameHandler
    {
        final WebSocket _websocket;

        FrameHandlerD00(WebSocket websocket)
        {
            _websocket=websocket;
        }

        public void onFrame(byte flags, byte opcode, Buffer buffer)
        {
            try
            {
                byte[] array=buffer.array();

                if (opcode==0)
                {
                    if (_websocket instanceof WebSocket.OnTextMessage)
                        ((WebSocket.OnTextMessage)_websocket).onMessage(buffer.toString(StringUtil.__UTF8));
                }
                else
                {
                    if (_websocket instanceof WebSocket.OnBinaryMessage)
                        ((WebSocket.OnBinaryMessage)_websocket).onMessage(array,buffer.getIndex(),buffer.length());
                }
            }
            catch(ThreadDeath th)
            {
                throw th;
            }
            catch(Throwable th)
            {
                LOG.warn(th);
            }
        }

        public void close(int code,String message)
        {
        }
    }

    public boolean isMessageComplete(byte flags)
    {
        return true;
    }

    public byte binaryOpcode()
    {
        return LENGTH_FRAME;
    }

    public byte textOpcode()
    {
        return SENTINEL_FRAME;
    }

    public boolean isControl(byte opcode)
    {
        return false;
    }

    public boolean isText(byte opcode)
    {
        return (opcode&LENGTH_FRAME)==0;
    }

    public boolean isBinary(byte opcode)
    {
        return (opcode&LENGTH_FRAME)!=0;
    }

    public boolean isContinuation(byte opcode)
    {
        return false;
    }

    public boolean isClose(byte opcode)
    {
        return false;
    }

    public boolean isPing(byte opcode)
    {
        return false;
    }

    public boolean isPong(byte opcode)
    {
        return false;
    }

    public List<Extension> getExtensions()
    {
        return Collections.emptyList();
    }

    public byte continuationOpcode()
    {
        return 0;
    }

    public byte finMask()
    {
        return 0;
    }

    public void setAllowFrameFragmentation(boolean allowFragmentation)
    {
    }

    public boolean isAllowFrameFragmentation()
    {
        return false;
    }
}
