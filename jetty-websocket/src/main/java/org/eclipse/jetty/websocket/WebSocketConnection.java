package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.Checksum;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.security.Credential.MD5;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.Timeout;

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

    public WebSocketConnection(WebSocket websocket, EndPoint endpoint)
        throws IOException
    {
        this(websocket,endpoint,new WebSocketBuffers(8192),System.currentTimeMillis(),300000);
    }
    
    public WebSocketConnection(WebSocket websocket, EndPoint endpoint, WebSocketBuffers buffers, long timestamp, int maxIdleTime)
        throws IOException
    {
        // TODO - can we use the endpoint idle mechanism?
        if (endpoint instanceof AsyncEndPoint)
            ((AsyncEndPoint)endpoint).cancelIdle();
        
        _endp = endpoint;
        _endp.setMaxIdleTime(maxIdleTime);
        
        _timestamp = timestamp;
        _websocket = websocket;
        _generator = new WebSocketGenerator(buffers, _endp);
        _parser = new WebSocketParser(buffers, endpoint, new WebSocketParser.EventHandler()
        {
            public void onFrame(byte frame, String data)
            {
                try
                {
                    _websocket.onMessage(frame,data);
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

            public void onFrame(byte frame, Buffer buffer)
            {
                try
                {
                    byte[] array=buffer.array();

                    _websocket.onMessage(frame,array,buffer.getIndex(),buffer.length());
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
        });

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
                    if (_parser.getBuffer().length()>0)
                    {
                        int l=_parser.getBuffer().length();
                        if (l>8)
                            l=8;
                        _hixie.put(_parser.getBuffer().peek(_parser.getBuffer().getIndex(),l));
                        _parser.getBuffer().skip(l);
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
            e.printStackTrace();
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
        byte[] result=WebSocketGenerator.doTheHixieHixieShake(
                WebSocketParser.hixieCrypt(_key1),
                WebSocketParser.hixieCrypt(_key2),
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

    public void sendMessage(byte frame, byte[] content) throws IOException
    {
        sendMessage(frame, content, 0, content.length);
    }

    public void sendMessage(byte frame, byte[] content, int offset, int length) throws IOException
    {
        _generator.addFrame(frame,content,offset,length,_endp.getMaxIdleTime());
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

    private interface IdleCheck
    {
        void access(EndPoint endp);
    }
}
