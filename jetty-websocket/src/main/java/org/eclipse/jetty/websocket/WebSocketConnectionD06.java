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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;

public class WebSocketConnectionD06 extends AbstractConnection implements WebSocketConnection
{
    private final static byte[] MAGIC="258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(StringUtil.__ISO_8859_1_CHARSET);
    private final static byte[] NORMAL_CLOSE=new byte[] { 1000/0xff, (byte)(1000%0xff) }; 
    private final IdleCheck _idle;
    private final WebSocketParser _parser;
    private final WebSocketGenerator _generator;
    private final WebSocket _websocket;
    private boolean _closedIn;
    private boolean _closedOut;

    private final WebSocketParser.FrameHandler _frameHandler= new WebSocketParser.FrameHandler()
    {
        private final Utf8StringBuilder _utf8 = new Utf8StringBuilder();
        private byte _opcode=-1;

        public void onFrame(boolean more, byte flags, byte opcode, Buffer buffer)
        {
            synchronized(WebSocketConnectionD06.this)
            {
                // Ignore incoming after a close
                if (_closedIn)
                    return;
                
                try
                {
                    byte[] array=buffer.array();

                    switch(opcode)
                    {
                        case WebSocket.OP_CONTINUATION:
                        {
                            // If text, append to the message buffer
                            if (_opcode==WebSocket.OP_TEXT)
                            {
                                _utf8.append(buffer.array(),buffer.getIndex(),buffer.length());

                                // If this is the last fragment, deliver the text buffer
                                if (!more)
                                {
                                    String msg =_utf8.toString();
                                    _utf8.reset();
                                    _opcode=-1;
                                    _websocket.onMessage(WebSocket.OP_TEXT,msg);
                                }
                            }
                            else
                            {
                                // deliver the non-text fragment
                                if (!more)
                                    _opcode=-1;
                                _websocket.onFragment(more,_opcode,array,buffer.getIndex(),buffer.length());
                            }
                            break;
                        }

                        case WebSocket.OP_TEXT:
                        {
                            if (more)
                            {
                                // If this is a text fragment, append to buffer
                                _opcode=WebSocket.OP_TEXT;
                                _utf8.append(buffer.array(),buffer.getIndex(),buffer.length());
                            }
                            else
                            {
                                // Deliver the message
                                _websocket.onMessage(opcode,buffer.toString(StringUtil.__UTF8));
                            }
                            break;
                        }

                        case WebSocket.OP_PING:
                        {
                            Log.debug("PING {}",this);
                            if (!_closedOut)
                                getOutbound().sendMessage(WebSocket.OP_PONG,buffer.array(),buffer.getIndex(),buffer.length());
                            break;
                        }

                        case WebSocket.OP_PONG:
                        {
                            Log.debug("PONG {}",this);
                            break;
                        }

                        case WebSocket.OP_CLOSE:
                        {
                            int code=-1;
                            String message=null;
                            if (buffer.length()>=2)
                            {
                                code=buffer.array()[buffer.getIndex()]*0xff+buffer.array()[buffer.getIndex()+1];
                                if (buffer.length()>2)
                                    message=new String(buffer.array(),buffer.getIndex()+2,buffer.length()-2,StringUtil.__UTF8_CHARSET);
                            }
                            closeIn(code,message);
                            break;
                        }

                        default:
                        {
                            if (more)
                            {
                                _opcode=opcode;
                                _websocket.onFragment(more,opcode,array,buffer.getIndex(),buffer.length());
                            }
                            else
                                _websocket.onMessage(opcode,array,buffer.getIndex(),buffer.length());
                        }      
                    }
                }
                catch(ThreadDeath th)
                {
                    throw th;
                }
                catch(Throwable th)
                {
                    Log.warn(th);
                }
            }
        }
        
        public String toString()
        {
            return WebSocketConnectionD06.this.toString()+"FH";
        }
    };

    private final WebSocket.Outbound _outbound = new WebSocket.Outbound()
    {
        volatile boolean _disconnecting;
        
        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.websocket.WebSocketConnection#sendMessage(java.lang.String)
         */
        public void sendMessage(String content) throws IOException
        {
            sendMessage(WebSocket.OP_TEXT,content);
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.websocket.WebSocketConnection#sendMessage(byte, java.lang.String)
         */
        public synchronized void sendMessage(byte opcode, String content) throws IOException
        {
            if (_closedOut)
                throw new IOException("closing");
            _generator.addFrame(opcode,content,_endp.getMaxIdleTime());
            _generator.flush();
            checkWriteable();
            _idle.access(_endp);
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.websocket.WebSocketConnection#sendMessage(byte, byte[], int, int)
         */
        public synchronized void sendMessage(byte opcode, byte[] content, int offset, int length) throws IOException
        {
            if (_closedOut)
                throw new IOException("closing");
            _generator.addFrame(opcode,content,offset,length,_endp.getMaxIdleTime());
            _generator.flush();
            checkWriteable();
            _idle.access(_endp);
        }

        /* ------------------------------------------------------------ */
        /**
         * @see org.eclipse.jetty.websocket.WebSocketConnection#sendFragment(boolean, byte, byte[], int, int)
         */
        public void sendFragment(boolean more,byte opcode, byte[] content, int offset, int length) throws IOException
        {
            if (_closedOut)
                throw new IOException("closing");
            _generator.addFragment(!more,opcode,content,offset,length,_endp.getMaxIdleTime());
            _generator.flush();
            checkWriteable();
            _idle.access(_endp);
        }


        /* ------------------------------------------------------------ */
        public boolean isOpen()
        {
            return _endp!=null&&_endp.isOpen();
        }

        /* ------------------------------------------------------------ */
        public void disconnect(int code, String message)
        {
            if (_disconnecting)
                return;
            _disconnecting=true;
            WebSocketConnectionD06.this.closeOut(code,message);
        }
        
        /* ------------------------------------------------------------ */
        public void disconnect()
        {
            if (_disconnecting)
                return;
            _disconnecting=true;
            WebSocketConnectionD06.this.closeOut(1000,null);
        }
        
    };
    
    /* ------------------------------------------------------------ */
    public WebSocketConnectionD06(WebSocket websocket, EndPoint endpoint,int draft)
        throws IOException
    {
        this(websocket,endpoint,new WebSocketBuffers(8192),System.currentTimeMillis(),300000,draft);
    }

    /* ------------------------------------------------------------ */
    public WebSocketConnectionD06(WebSocket websocket, EndPoint endpoint, WebSocketBuffers buffers, long timestamp, int maxIdleTime, int draft)
        throws IOException
    {
        super(endpoint,timestamp);
        
        // TODO - can we use the endpoint idle mechanism?
        if (endpoint instanceof AsyncEndPoint)
            ((AsyncEndPoint)endpoint).cancelIdle();
        
        _endp.setMaxIdleTime(maxIdleTime);
        
        _websocket = websocket;
        _generator = new WebSocketGeneratorD06(buffers, _endp,null);
        _parser = new WebSocketParserD06(buffers, endpoint, _frameHandler,true);

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
    public WebSocket.Outbound getOutbound()
    {
        return _outbound;
    }
    
    /* ------------------------------------------------------------ */
    public Connection handle() throws IOException
    {
        try
        {
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
            try
            {
                _endp.close();
            }
            catch(IOException e2)
            {
                Log.ignore(e2);
            }
            throw e;
        }
        finally
        {
            if (_endp.isOpen())
            {
                _idle.access(_endp);
                if (_closedIn && _closedOut && _generator.isBufferEmpty())
                    _endp.close();
                else
                    checkWriteable();
            }
           
        }
        return this;
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return _parser.isBufferEmpty() && _generator.isBufferEmpty();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void idleExpired()
    {
        closeOut(WebSocket.CLOSE_NORMAL,"Idle");
    }

    /* ------------------------------------------------------------ */
    public boolean isSuspended()
    {
        return false;
    }

    /* ------------------------------------------------------------ */
    public void closed()
    {
        _websocket.onDisconnect();
    }

    /* ------------------------------------------------------------ */
    public synchronized void closeIn(int code,String message)
    {
        Log.debug("ClosedIn {} {}",this,message);
        try
        {
            if (_closedOut)
                _endp.close();
            else 
                closeOut(code,message);
        }
        catch(IOException e)
        {
            Log.ignore(e);
        }
        finally
        {
            _closedIn=true;
        }
    }

    /* ------------------------------------------------------------ */
    public synchronized void closeOut(int code,String message)
    {
        Log.debug("ClosedOut {} {}",this,message);
        try
        {
            if (_closedIn || _closedOut)
                _endp.close();
            else if (code<=0)
            {
                _generator.addFrame(WebSocket.OP_CLOSE,NORMAL_CLOSE,0,2,_endp.getMaxIdleTime());
            }
            else
            {
                byte[] bytes = ("xx"+(message==null?"":message)).getBytes(StringUtil.__ISO_8859_1_CHARSET);
                bytes[0]=(byte)(code/0xff);
                bytes[1]=(byte)(code%0xff);
                _generator.addFrame(WebSocket.OP_CLOSE,bytes,0,bytes.length,_endp.getMaxIdleTime());
            }
            _generator.flush();
            
        }
        catch(IOException e)
        {
            Log.ignore(e);
        }
        finally
        {
            _closedOut=true;
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
    private interface IdleCheck
    {
        void access(EndPoint endp);
    }

    /* ------------------------------------------------------------ */
    public void handshake(HttpServletRequest request, HttpServletResponse response, String origin, String subprotocol) throws IOException
    {
        String uri=request.getRequestURI();
        String query=request.getQueryString();
        if (query!=null && query.length()>0)
            uri+="?"+query;
        String key = request.getHeader("Sec-WebSocket-Key");
        
        response.setHeader("Upgrade","WebSocket");
        response.addHeader("Connection","Upgrade");
        response.addHeader("Sec-WebSocket-Accept",hashKey(key));
        if (subprotocol!=null)
            response.addHeader("Sec-WebSocket-Protocol",subprotocol);
        response.sendError(101);

        _websocket.onConnect(_outbound);
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
    

}
