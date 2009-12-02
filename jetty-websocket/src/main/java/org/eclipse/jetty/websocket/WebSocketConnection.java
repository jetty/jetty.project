package org.eclipse.jetty.websocket;

import java.io.IOException;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.log.Log;

public class WebSocketConnection implements Connection, WebSocket.Outbound
{
    final IdleCheck _idle;
    final EndPoint _endp;
    final WebSocketParser _parser;
    final WebSocketGenerator _generator;
    final long _timestamp;
    final WebSocket _websocket;
    final int _maxIdleTimeMs=30000;
    
    public WebSocketConnection(WebSocketBuffers buffers, EndPoint endpoint, long timestamp, WebSocket websocket)
    {
        _endp = endpoint;
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
        
        _idle = (_endp instanceof SelectChannelEndPoint) ?
                new IdleCheck()
        {
            public void access(EndPoint endp)
            {
                ((SelectChannelEndPoint)_endp).scheduleIdle();
            }
            
        }
        :new IdleCheck()
        {
            public void access(EndPoint endp)
            {
            }  
        };
            
    }
    
    public void handle() throws IOException
    {
        boolean more=true;
        
        try
        {
            while (more)
            {
                int flushed=_generator.flush();
                int filled=_parser.parseNext();

                more = flushed>0 || filled>0 || !_parser.isBufferEmpty() || !_generator.isBufferEmpty();
                
                // System.err.println("flushed="+flushed+" filled="+filled+" more="+more+" p.e="+_parser.isBufferEmpty()+" g.e="+_generator.isBufferEmpty());
                
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
                _idle.access(_endp);
            else
                // TODO - not really the best way
                _websocket.onDisconnect();
        }
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

    public void sendMessage(byte frame, String content) throws IOException
    {
        _generator.addFrame(frame,content,_maxIdleTimeMs);
        _generator.flush();
        _idle.access(_endp);
    }

    public void sendMessage(byte frame, byte[] content) throws IOException
    {
        _generator.addFrame(frame,content,_maxIdleTimeMs);
        _generator.flush();
        _idle.access(_endp);
    }

    public void sendMessage(byte frame, byte[] content, int offset, int length) throws IOException
    {
        _generator.addFrame(frame,content,offset,length,_maxIdleTimeMs);
        _generator.flush();
        _idle.access(_endp);
    }

    public void disconnect()
    {
        try
        {
            _generator.flush(_maxIdleTimeMs);
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
    
    private interface IdleCheck
    {
        void access(EndPoint endp);
    }
}
