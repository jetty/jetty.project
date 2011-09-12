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
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.Utf8Appendable;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.WebSocket.OnBinaryMessage;
import org.eclipse.jetty.websocket.WebSocket.OnControl;
import org.eclipse.jetty.websocket.WebSocket.OnFrame;
import org.eclipse.jetty.websocket.WebSocket.OnTextMessage;

public class WebSocketConnectionD13 extends AbstractConnection implements WebSocketConnection
{
    private static final Logger LOG = Log.getLogger(WebSocketConnectionD13.class);
    private static final boolean STRICT=Boolean.getBoolean("org.eclipse.jetty.websocket.STRICT");
    private static final boolean BRUTAL=Boolean.getBoolean("org.eclipse.jetty.websocket.BRUTAL");
    
    final static byte OP_CONTINUATION = 0x00;
    final static byte OP_TEXT = 0x01;
    final static byte OP_BINARY = 0x02;
    final static byte OP_EXT_DATA = 0x03;

    final static byte OP_CONTROL = 0x08;
    final static byte OP_CLOSE = 0x08;
    final static byte OP_PING = 0x09;
    final static byte OP_PONG = 0x0A;
    final static byte OP_EXT_CTRL = 0x0B;

    final static int CLOSE_NORMAL=1000;
    final static int CLOSE_SHUTDOWN=1001;
    final static int CLOSE_PROTOCOL=1002;
    final static int CLOSE_BAD_DATA=1003;
    final static int CLOSE_UNDEFINED=1004;
    final static int CLOSE_NO_CODE=1005;
    final static int CLOSE_NO_CLOSE=1006;
    final static int CLOSE_BAD_PAYLOAD=1007;
    final static int CLOSE_POLICY_VIOLATION=1008;
    final static int CLOSE_MESSAGE_TOO_LARGE=1009;
    final static int CLOSE_REQUIRED_EXTENSION=1010;

    final static int FLAG_FIN=0x8;

    final static int VERSION=13;

    static boolean isLastFrame(byte flags)
    {
        return (flags&FLAG_FIN)!=0;
    }

    static boolean isControlFrame(byte opcode)
    {
        return (opcode&OP_CONTROL)!=0;
    }

    private final static byte[] MAGIC;
    private final IdleCheck _idle;
    private final List<Extension> _extensions;
    private final WebSocketParserD13 _parser;
    private final WebSocketGeneratorD13 _generator;
    private final WebSocketGenerator _outbound;
    private final WebSocket _webSocket;
    private final OnFrame _onFrame;
    private final OnBinaryMessage _onBinaryMessage;
    private final OnTextMessage _onTextMessage;
    private final OnControl _onControl;
    private final String _protocol;
    private final int _draft;
    private final ClassLoader _context;
    private volatile int _closeCode;
    private volatile String _closeMessage;
    private volatile boolean _closedIn;
    private volatile boolean _closedOut;
    private int _maxTextMessageSize=-1;
    private int _maxBinaryMessageSize=-1;

    static
    {
        try
        {
            MAGIC="258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(StringUtil.__ISO_8859_1);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new RuntimeException(e);
        }
    }

    private final WebSocket.FrameConnection _connection = new WSFrameConnection();


    /* ------------------------------------------------------------ */
    public WebSocketConnectionD13(WebSocket websocket, EndPoint endpoint, WebSocketBuffers buffers, long timestamp, int maxIdleTime, String protocol, List<Extension> extensions,int draft)
        throws IOException
    {
        this(websocket,endpoint,buffers,timestamp,maxIdleTime,protocol,extensions,draft,null);
    }

    /* ------------------------------------------------------------ */
    public WebSocketConnectionD13(WebSocket websocket, EndPoint endpoint, WebSocketBuffers buffers, long timestamp, int maxIdleTime, String protocol, List<Extension> extensions,int draft, MaskGen maskgen)
        throws IOException
    {
        super(endpoint,timestamp);

        _context=Thread.currentThread().getContextClassLoader();

        if (endpoint instanceof AsyncEndPoint)
            ((AsyncEndPoint)endpoint).cancelIdle();

        _draft=draft;
        _endp.setMaxIdleTime(maxIdleTime);

        _webSocket = websocket;
        _onFrame=_webSocket instanceof OnFrame ? (OnFrame)_webSocket : null;
        _onTextMessage=_webSocket instanceof OnTextMessage ? (OnTextMessage)_webSocket : null;
        _onBinaryMessage=_webSocket instanceof OnBinaryMessage ? (OnBinaryMessage)_webSocket : null;
        _onControl=_webSocket instanceof OnControl ? (OnControl)_webSocket : null;
        _generator = new WebSocketGeneratorD13(buffers, _endp,maskgen);

        _extensions=extensions;
        WebSocketParser.FrameHandler frameHandler = new WSFrameHandler();
        if (_extensions!=null)
        {
            int e=0;
            for (Extension extension : _extensions)
            {
                extension.bind(
                        _connection,
                        e==extensions.size()-1? frameHandler :extensions.get(e+1),
                        e==0?_generator:extensions.get(e-1));
                e++;
            }
        }

        _outbound=(_extensions==null||_extensions.size()==0)?_generator:extensions.get(extensions.size()-1);
        WebSocketParser.FrameHandler inbound = (_extensions == null || _extensions.size() == 0) ? frameHandler : extensions.get(0);

        _parser = new WebSocketParserD13(buffers, endpoint, inbound,maskgen==null);

        _protocol=protocol;

        // TODO should these be AsyncEndPoint checks/calls?
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
    public WebSocket.Connection getConnection()
    {
        return _connection;
    }

    /* ------------------------------------------------------------ */
    public List<Extension> getExtensions()
    {
        if (_extensions==null)
            return Collections.emptyList();

        return _extensions;
    }

    /* ------------------------------------------------------------ */
    public Connection handle() throws IOException
    {
        Thread current = Thread.currentThread();
        ClassLoader oldcontext = current.getContextClassLoader();
        current.setContextClassLoader(_context);
        try
        {
            // handle the framing protocol
            boolean progress=true;

            while (progress)
            {
                int flushed=_generator.flushBuffer();
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
            current.setContextClassLoader(oldcontext);
            _parser.returnBuffer();
            _generator.returnBuffer();
            if (_endp.isOpen())
            {
                _idle.access(_endp);
                if (_closedIn && _closedOut && _outbound.isBufferEmpty())
                    _endp.close();
                else if (_endp.isInputShutdown() && !_closedIn)
                    closeIn(CLOSE_NO_CLOSE,null);
                else
                    checkWriteable();
            }
        }
        return this;
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return _parser.isBufferEmpty() && _outbound.isBufferEmpty();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void idleExpired()
    {
        long idle = System.currentTimeMillis()-((SelectChannelEndPoint)_endp).getIdleTimestamp();
        closeOut(WebSocketConnectionD13.CLOSE_NORMAL,"Idle for "+idle+"ms > "+_endp.getMaxIdleTime()+"ms");
    }

    /* ------------------------------------------------------------ */
    public boolean isSuspended()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    public void closed()
    {
        final boolean closed;
        synchronized (this)
        {
            closed=_closeCode==0;
            if (closed)
                _closeCode=WebSocketConnectionD13.CLOSE_NO_CLOSE;
        }
        if (closed)
            _webSocket.onClose(WebSocketConnectionD13.CLOSE_NO_CLOSE,"closed");
    }

    /* ------------------------------------------------------------ */
    public void closeIn(int code,String message)
    {
        LOG.debug("ClosedIn {} {}",this,message);

        final boolean close;
        final boolean tell_app;
        synchronized (this)
        {
            close=_closedOut;
            _closedIn=true;
            tell_app=_closeCode==0;
            if (tell_app)
            {
                _closeCode=code;
                _closeMessage=message;
            }
        }

        try
        {
            if (tell_app)
                _webSocket.onClose(code,message);
        }
        finally
        {
            try
            {
                if (close)
                    _endp.close();
                else
                    closeOut(code,message);
            }
            catch(IOException e)
            {
                LOG.ignore(e);
            }
        }
    }

    /* ------------------------------------------------------------ */
    public void closeOut(int code,String message)
    {
        LOG.debug("ClosedOut {} {}",this,message);

        final boolean close;
        final boolean tell_app;
        final boolean send_close;
        synchronized (this)
        {
            close=_closedIn;
            send_close=!_closedOut;
            _closedOut=true;
            tell_app=_closeCode==0;
            if (tell_app)
            {
                _closeCode=code;
                _closeMessage=message;
            }
        }

        try
        {
            if (tell_app)
                _webSocket.onClose(code,message);
        }
        finally
        {
            try
            {
                if (send_close)
                {
                    if (code<=0)
                        code=WebSocketConnectionD13.CLOSE_NORMAL;
                    byte[] bytes = ("xx"+(message==null?"":message)).getBytes(StringUtil.__ISO_8859_1);
                    bytes[0]=(byte)(code/0x100);
                    bytes[1]=(byte)(code%0x100);
                    _outbound.addFrame((byte)FLAG_FIN,WebSocketConnectionD13.OP_CLOSE,bytes,0,bytes.length);
                    _outbound.flush();
                    if (close)
                        _endp.shutdownOutput();
                }
                else if (close)
                    _endp.close();

            }
            catch(IOException e)
            {
                LOG.ignore(e);
            }
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
        if (!_outbound.isBufferEmpty() && _endp instanceof AsyncEndPoint)
        {
            ((AsyncEndPoint)_endp).scheduleWrite();
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class WSFrameConnection implements WebSocket.FrameConnection
    {
        volatile boolean _disconnecting;

        /* ------------------------------------------------------------ */
        public void sendMessage(String content) throws IOException
        {
            if (_closedOut)
                throw new IOException("closedOut "+_closeCode+":"+_closeMessage);
            byte[] data = content.getBytes(StringUtil.__UTF8);
            _outbound.addFrame((byte)FLAG_FIN,WebSocketConnectionD13.OP_TEXT,data,0,data.length);
            checkWriteable();
            _idle.access(_endp);
        }

        /* ------------------------------------------------------------ */
        public void sendMessage(byte[] content, int offset, int length) throws IOException
        {
            if (_closedOut)
                throw new IOException("closedOut "+_closeCode+":"+_closeMessage);
            _outbound.addFrame((byte)FLAG_FIN,WebSocketConnectionD13.OP_BINARY,content,offset,length);
            checkWriteable();
            _idle.access(_endp);
        }

        /* ------------------------------------------------------------ */
        public void sendFrame(byte flags,byte opcode, byte[] content, int offset, int length) throws IOException
        {
            if (_closedOut)
                throw new IOException("closedOut "+_closeCode+":"+_closeMessage);
            _outbound.addFrame(flags,opcode,content,offset,length);
            checkWriteable();
            _idle.access(_endp);
        }

        /* ------------------------------------------------------------ */
        public void sendControl(byte ctrl, byte[] data, int offset, int length) throws IOException
        {
            if (_closedOut)
                throw new IOException("closedOut "+_closeCode+":"+_closeMessage);
            _outbound.addFrame((byte)FLAG_FIN,ctrl,data,offset,length);
            checkWriteable();
            _idle.access(_endp);
        }

        /* ------------------------------------------------------------ */
        public boolean isMessageComplete(byte flags)
        {
            return isLastFrame(flags);
        }

        /* ------------------------------------------------------------ */
        public boolean isOpen()
        {
            return _endp!=null&&_endp.isOpen();
        }

        /* ------------------------------------------------------------ */
        public void close(int code, String message)
        {
            if (_disconnecting)
                return;
            _disconnecting=true;
            WebSocketConnectionD13.this.closeOut(code,message);
        }

        /* ------------------------------------------------------------ */
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

        /* ------------------------------------------------------------ */
        public void setMaxTextMessageSize(int size)
        {
            _maxTextMessageSize=size;
        }

        /* ------------------------------------------------------------ */
        public void setMaxBinaryMessageSize(int size)
        {
            _maxBinaryMessageSize=size;
        }

        /* ------------------------------------------------------------ */
        public int getMaxIdleTime()
        {
            return _endp.getMaxIdleTime();
        }

        /* ------------------------------------------------------------ */
        public int getMaxTextMessageSize()
        {
            return _maxTextMessageSize;
        }

        /* ------------------------------------------------------------ */
        public int getMaxBinaryMessageSize()
        {
            return _maxBinaryMessageSize;
        }

        /* ------------------------------------------------------------ */
        public String getProtocol()
        {
            return _protocol;
        }

        /* ------------------------------------------------------------ */
        public byte binaryOpcode()
        {
            return OP_BINARY;
        }

        /* ------------------------------------------------------------ */
        public byte textOpcode()
        {
            return OP_TEXT;
        }

        /* ------------------------------------------------------------ */
        public byte continuationOpcode()
        {
            return OP_CONTINUATION;
        }

        /* ------------------------------------------------------------ */
        public byte finMask()
        {
            return FLAG_FIN;
        }

        /* ------------------------------------------------------------ */
        public boolean isControl(byte opcode)
        {
            return isControlFrame(opcode);
        }

        /* ------------------------------------------------------------ */
        public boolean isText(byte opcode)
        {
            return opcode==OP_TEXT;
        }

        /* ------------------------------------------------------------ */
        public boolean isBinary(byte opcode)
        {
            return opcode==OP_BINARY;
        }

        /* ------------------------------------------------------------ */
        public boolean isContinuation(byte opcode)
        {
            return opcode==OP_CONTINUATION;
        }

        /* ------------------------------------------------------------ */
        public boolean isClose(byte opcode)
        {
            return opcode==OP_CLOSE;
        }

        /* ------------------------------------------------------------ */
        public boolean isPing(byte opcode)
        {
            return opcode==OP_PING;
        }

        /* ------------------------------------------------------------ */
        public boolean isPong(byte opcode)
        {
            return opcode==OP_PONG;
        }

        /* ------------------------------------------------------------ */
        public void disconnect()
        {
            close(CLOSE_NORMAL,null);
        }

        /* ------------------------------------------------------------ */
        public void setAllowFrameFragmentation(boolean allowFragmentation)
        {
            _parser.setFakeFragments(allowFragmentation);
        }

        /* ------------------------------------------------------------ */
        public boolean isAllowFrameFragmentation()
        {
            return _parser.isFakeFragments();
        }

        /* ------------------------------------------------------------ */
        @Override
        public String toString()
        {
            return this.getClass().getSimpleName()+"@"+_endp.getLocalAddr()+":"+_endp.getLocalPort()+"<->"+_endp.getRemoteAddr()+":"+_endp.getRemotePort();
        }
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    private class WSFrameHandler implements WebSocketParser.FrameHandler
    {
        private final Utf8StringBuilder _utf8 = new Utf8StringBuilder(512); // TODO configure initial capacity
        private ByteArrayBuffer _aggregate;
        private byte _opcode=-1;

        public void onFrame(final byte flags, final byte opcode, final Buffer buffer)
        {
            boolean lastFrame = isLastFrame(flags);

            System.err.println("flags "+flags);
            System.err.println("opcode "+opcode);
            System.err.println("buffer "+TypeUtil.toHexString(buffer.asArray()));
            
            
            synchronized(WebSocketConnectionD13.this)
            {
                // Ignore incoming after a close
                if (_closedIn)
                    return;
            }
            try
            {
                byte[] array=buffer.array();

                if (STRICT)
                {
                    if (isControlFrame(opcode) && buffer.length()>125)
                    {
                        errorClose(WebSocketConnectionD13.CLOSE_PROTOCOL,"Control frame too large");
                        return;
                    }

                    if ((flags&0x7)!=0)
                    {
                        errorClose(WebSocketConnectionD13.CLOSE_PROTOCOL,"RSV bits set 0x"+Integer.toHexString(flags));
                        return;
                    }

                    // Ignore all frames after error close
                    if (_closeCode!=0 && _closeCode!=CLOSE_NORMAL && opcode!=OP_CLOSE)
                        return;
                }
                
                // Deliver frame if websocket is a FrameWebSocket
                if (_onFrame!=null)
                {
                    if (_onFrame.onFrame(flags,opcode,array,buffer.getIndex(),buffer.length()))
                        return;
                }

                if (_onControl!=null && isControlFrame(opcode))
                {
                    if (_onControl.onControl(opcode,array,buffer.getIndex(),buffer.length()))
                        return;
                }
                
                switch(opcode)
                {
                    case WebSocketConnectionD13.OP_CONTINUATION:
                    {
                        if (_opcode==-1)
                        {
                            errorClose(WebSocketConnectionD13.CLOSE_PROTOCOL,"Bad Continuation");
                            return;
                        }
                        
                        // If text, append to the message buffer
                        if (_onTextMessage!=null && _opcode==WebSocketConnectionD13.OP_TEXT)
                        {
                            if (_utf8.append(buffer.array(),buffer.getIndex(),buffer.length(),_connection.getMaxTextMessageSize()))
                            {
                                // If this is the last fragment, deliver the text buffer
                                if (lastFrame)
                                {
                                    _opcode=-1;
                                    String msg =_utf8.toString();
                                    _utf8.reset();
                                    _onTextMessage.onMessage(msg);
                                }
                            }
                            else
                                textMessageTooLarge();
                        }

                        if (_opcode>=0 && _connection.getMaxBinaryMessageSize()>=0)
                        {
                            if (_aggregate!=null && checkBinaryMessageSize(_aggregate.length(),buffer.length()))
                            {
                                _aggregate.put(buffer);

                                // If this is the last fragment, deliver
                                if (lastFrame && _onBinaryMessage!=null)
                                {
                                    try
                                    {
                                        _onBinaryMessage.onMessage(_aggregate.array(),_aggregate.getIndex(),_aggregate.length());
                                    }
                                    finally
                                    {
                                        _opcode=-1;
                                        _aggregate.clear();
                                    }
                                }
                            }
                        }
                        break;
                    }
                    case WebSocketConnectionD13.OP_PING:
                    {
                        LOG.debug("PING {}",this);
                        if (!_closedOut)
                            _connection.sendControl(WebSocketConnectionD13.OP_PONG,buffer.array(),buffer.getIndex(),buffer.length());
                        break;
                    }

                    case WebSocketConnectionD13.OP_PONG:
                    {
                        LOG.debug("PONG {}",this);
                        break;
                    }

                    case WebSocketConnectionD13.OP_CLOSE:
                    {
                        int code=WebSocketConnectionD13.CLOSE_NO_CODE;
                        String message=null;
                        if (buffer.length()>=2)
                        {
                            code=(0xff&buffer.array()[buffer.getIndex()])*0x100+(0xff&buffer.array()[buffer.getIndex()+1]);
                            if (buffer.length()>2)
                                message=new String(buffer.array(),buffer.getIndex()+2,buffer.length()-2,StringUtil.__UTF8);
                        }
                        closeIn(code,message);
                        break;
                    }

                    case WebSocketConnectionD13.OP_TEXT:
                    {
                        if (STRICT && _opcode!=-1)
                        {
                            errorClose(WebSocketConnectionD13.CLOSE_PROTOCOL,"Expected Continuation"+Integer.toHexString(opcode));
                            return;
                        }
                        
                        if(_onTextMessage!=null)
                        {
                            if (_connection.getMaxTextMessageSize()<=0)
                            {
                                // No size limit, so handle only final frames
                                if (lastFrame)
                                    _onTextMessage.onMessage(buffer.toString(StringUtil.__UTF8));
                                else
                                {
                                    LOG.warn("Frame discarded. Text aggregation disabled for {}",_endp);
                                    errorClose(WebSocketConnectionD13.CLOSE_POLICY_VIOLATION,"Text frame aggregation disabled");
                                }
                            }
                            // append bytes to message buffer (if they fit)
                            else if (_utf8.append(buffer.array(),buffer.getIndex(),buffer.length(),_connection.getMaxTextMessageSize()))
                            {
                                if (lastFrame)
                                {
                                    String msg =_utf8.toString();
                                    _utf8.reset();
                                    _onTextMessage.onMessage(msg);
                                }
                                else
                                {
                                    _opcode=WebSocketConnectionD13.OP_TEXT;
                                }
                            }
                            else
                                textMessageTooLarge();
                        }
                        break;
                    }
                        
                    case WebSocketConnectionD13.OP_BINARY:
                    {
                        if (STRICT && _opcode!=-1)
                        {
                            errorClose(WebSocketConnectionD13.CLOSE_PROTOCOL,"Expected Continuation"+Integer.toHexString(opcode));
                            return;
                        }
                        
                        if (_onBinaryMessage!=null && checkBinaryMessageSize(0,buffer.length()))
                        {
                            if (lastFrame)
                            {
                                _onBinaryMessage.onMessage(array,buffer.getIndex(),buffer.length());
                            }
                            else if (_connection.getMaxBinaryMessageSize()>=0)
                            {
                                _opcode=opcode;
                                // TODO use a growing buffer rather than a fixed one.
                                if (_aggregate==null)
                                    _aggregate=new ByteArrayBuffer(_connection.getMaxBinaryMessageSize());
                                _aggregate.put(buffer);
                            }
                            else
                            {
                                LOG.warn("Frame discarded. Binary aggregation disabed for {}",_endp);
                                errorClose(WebSocketConnectionD13.CLOSE_POLICY_VIOLATION,"Binary frame aggregation disabled");
                            }
                        }
                        break;
                    }

                    default:
                        if (STRICT)
                            errorClose(WebSocketConnectionD13.CLOSE_PROTOCOL,"Bad opcode 0x"+Integer.toHexString(opcode));
                        return;
                }
            }
            catch(ThreadDeath th)
            {
                throw th;
            }
            catch(Utf8Appendable.NotUtf8Exception notUtf8)
            {
                LOG.warn("{} for {}",notUtf8,_endp);
                LOG.debug(notUtf8);
                errorClose(WebSocketConnectionD13.CLOSE_BAD_PAYLOAD,"Invalid UTF-8");
            }
            catch(Throwable probablyNotUtf8)
            {
                LOG.warn("{} for {}",probablyNotUtf8,_endp);
                LOG.debug(probablyNotUtf8);
                errorClose(WebSocketConnectionD13.CLOSE_BAD_PAYLOAD,"Invalid Payload: "+probablyNotUtf8);
            }
        }

        private void errorClose(int code, String message)
        {
            _connection.close(code,message);
            if (BRUTAL)
            {
                try
                {
                    _endp.close();
                }
                catch (IOException e)
                {
                    LOG.warn(e.toString());
                    LOG.debug(e);
                }
            }
        }
        
        private boolean checkBinaryMessageSize(int bufferLen, int length)
        {
            int max = _connection.getMaxBinaryMessageSize();
            if (max>0 && (bufferLen+length)>max)
            {
                LOG.warn("Binary message too large > {}B for {}",_connection.getMaxBinaryMessageSize(),_endp);
                _connection.close(WebSocketConnectionD13.CLOSE_MESSAGE_TOO_LARGE,"Message size > "+_connection.getMaxBinaryMessageSize());
                _opcode=-1;
                if (_aggregate!=null)
                    _aggregate.clear();
                return false;
            }
            return true;
        }

        private void textMessageTooLarge()
        {
            LOG.warn("Text message too large > {} chars for {}",_connection.getMaxTextMessageSize(),_endp);
            _connection.close(WebSocketConnectionD13.CLOSE_MESSAGE_TOO_LARGE,"Text message size > "+_connection.getMaxTextMessageSize()+" chars");

            _opcode=-1;
            _utf8.reset();
        }

        public void close(int code,String message)
        {
            if (code!=CLOSE_NORMAL)
                LOG.warn("Close: "+code+" "+message);
            _connection.close(code,message);
        }

        @Override
        public String toString()
        {
            return WebSocketConnectionD13.this.toString()+"FH";
        }
    }

    /* ------------------------------------------------------------ */
    private interface IdleCheck
    {
        void access(EndPoint endp);
    }

    /* ------------------------------------------------------------ */
    public void handshake(HttpServletRequest request, HttpServletResponse response, String subprotocol) throws IOException
    {
        String key = request.getHeader("Sec-WebSocket-Key");

        response.setHeader("Upgrade","WebSocket");
        response.addHeader("Connection","Upgrade");
        response.addHeader("Sec-WebSocket-Accept",hashKey(key));
        if (subprotocol!=null)
            response.addHeader("Sec-WebSocket-Protocol",subprotocol);

        for(Extension ext : _extensions)
            response.addHeader("Sec-WebSocket-Extensions",ext.getParameterizedName());

        response.sendError(101);

        if (_onFrame!=null)
            _onFrame.onHandshake(_connection);
        _webSocket.onOpen(_connection);
    }

    /* ------------------------------------------------------------ */
    public static String hashKey(String key)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA1");
            md.update(key.getBytes("UTF-8"));
            md.update(MAGIC);
            return new String(B64Code.encode(md.digest()));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
         return "WS/D"+_draft+"-"+_endp;
    }
}
