package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;

public class WebSocketConnection implements Connection, WebSocket.Outbound
{
    final IdleCheck _idle;
    final EndPoint _endp;
    final WebSocketParser _parser;
    final WebSocketGenerator _generator;
    final long _timestamp;
    final WebSocket _websocket;
    String _key1;
    String _key2;
    ByteArrayBuffer _hixie;

    public WebSocketConnection(WebSocket websocket, EndPoint endpoint,int draft)
        throws IOException
    {
        this(websocket,endpoint,new WebSocketBuffers(8192),System.currentTimeMillis(),300000,draft);
    }
    
    public WebSocketConnection(WebSocket websocket, EndPoint endpoint, WebSocketBuffers buffers, long timestamp, int maxIdleTime, int draft)
        throws IOException
    {
        // TODO - can we use the endpoint idle mechanism?
        if (endpoint instanceof AsyncEndPoint)
            ((AsyncEndPoint)endpoint).cancelIdle();
        
        _endp = endpoint;
        _endp.setMaxIdleTime(maxIdleTime);
        
        _timestamp = timestamp;
        _websocket = websocket;
        final WebSocketParser.FrameHandler handler = new WebSocketParser.FrameHandler()
        {
            boolean _fragmented=false;
            Utf8StringBuilder _utf8 = new Utf8StringBuilder();
            
            public void onFrame(boolean more, byte flags, byte opcode, Buffer buffer)
            {
                try
                {
                    byte[] array=buffer.array();
                    
                    if (opcode==0)
                    {
                        if (more)
                        {
                            _utf8.append(buffer.array(),buffer.getIndex(),buffer.length());
                            _fragmented=true;
                        }
                        else if (_fragmented)
                        {
                            _utf8.append(buffer.array(),buffer.getIndex(),buffer.length());
                            _websocket.onMessage(opcode,_utf8.toString());
                            _utf8.reset();
                            _fragmented=false;
                        }
                        else
                        {
                            _websocket.onMessage(opcode,buffer.toString("utf-8"));
                        }
                    }
                    else
                    {
                        if (more)
                        {
                            _websocket.onFragment(true,opcode,array,buffer.getIndex(),buffer.length());
                        }
                        else if (_fragmented)
                        {
                            _websocket.onFragment(false,opcode,array,buffer.getIndex(),buffer.length());
                        }
                        else
                        {
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
        };

        // Select the parser/generators to use
        switch(draft)
        {
            case 1:
                _generator = new WebSocketGeneratorD01(buffers, _endp);
                _parser = new WebSocketParserD01(buffers, endpoint, handler);
                break;
            default:
                _generator = new WebSocketGeneratorD00(buffers, _endp);
                _parser = new WebSocketParserD00(buffers, endpoint, handler);
        }

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
    
    public void setHixieKeys(String key1,String key2)
    {
        _key1=key1;
        _key2=key2;
        _hixie=new IndirectNIOBuffer(16);
    }

    public Connection handle() throws IOException
    {
        boolean progress=true;

        try
        {
            // handle stupid hixie random bytes
            if (_hixie!=null)
            { 
                while(progress)
                {
                    // take bytes from the parser buffer.
                    Buffer buffer=_parser.getBuffer();
                    if (buffer!=null && buffer.length()>0)
                    {
                        int l=buffer.length();
                        if (l>8)
                            l=8;
                        _hixie.put(buffer.peek(buffer.getIndex(),l));
                        buffer.skip(l);
                        progress=true;
                    }
                    
                    // do we have enough?
                    if (_hixie.length()<8)
                    {
                        // no, then let's fill
                        int filled=_endp.fill(_hixie);
                        progress |= filled>0;

                        if (filled<0)
                        {
                            _endp.close();
                            break;
                        }
                    }
                    
                    // do we now have enough
                    if (_hixie.length()==8)
                    {
                        // we have the silly random bytes
                        // so let's work out the stupid 16 byte reply.
                        doTheHixieHixieShake();
                        _endp.flush(_hixie);
                        _hixie=null;
                        _endp.flush();
                        break;
                    }
                }
                
                return this;
            }
            
            // handle the framing protocol
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
            throw e;
        }
        finally
        {
            if (_endp.isOpen())
            {
                _idle.access(_endp);
                checkWriteable();
            }
            else
                // TODO - not really the best way
                _websocket.onDisconnect();
        }
        return this;
    }

    private void doTheHixieHixieShake()
    {          
        byte[] result=WebSocketConnection.doTheHixieHixieShake(
                WebSocketConnection.hixieCrypt(_key1),
                WebSocketConnection.hixieCrypt(_key2),
                _hixie.asArray());
        _hixie.clear();
        _hixie.put(result);
    }
    
    public boolean isOpen()
    {
        return _endp!=null&&_endp.isOpen();
    }

    public boolean isIdle()
    {
        return _parser.isBufferEmpty() && _generator.isBufferEmpty();
    }

    public boolean isSuspended()
    {
        return false;
    }

    public long getTimeStamp()
    {
        return _timestamp;
    }

    public void sendMessage(String content) throws IOException
    {
        sendMessage(WebSocket.SENTINEL_FRAME,content);
    }

    public void sendMessage(byte frame, String content) throws IOException
    {
        _generator.addFrame(frame,content,_endp.getMaxIdleTime());
        _generator.flush();
        checkWriteable();
        _idle.access(_endp);
    }

    public void sendMessage(byte opcode, byte[] content, int offset, int length) throws IOException
    {
        _generator.addFrame(opcode,content,offset,length,_endp.getMaxIdleTime());
        _generator.flush();
        checkWriteable();
        _idle.access(_endp);
    }

    public void sendFragment(boolean more,byte opcode, byte[] content, int offset, int length) throws IOException
    {
        _generator.addFragment(more,opcode,content,offset,length,_endp.getMaxIdleTime());
        _generator.flush();
        checkWriteable();
        _idle.access(_endp);
    }

    public void disconnect()
    {
        try
        {
            _generator.flush(_endp.getMaxIdleTime());
            _endp.close();
        }
        catch(IOException e)
        {
            Log.ignore(e);
        }
    }

    public void fill(Buffer buffer)
    {
        _parser.fill(buffer);
    }


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
            for (int i=0;i<8;i++)
                fodder[8+i]=key3[i];
            md.update(fodder);
            byte[] result=md.digest();
            return result;
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
}
