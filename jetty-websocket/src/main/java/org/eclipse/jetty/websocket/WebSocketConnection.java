package org.eclipse.jetty.websocket;

import java.io.IOException;

import org.eclipse.jetty.io.Connection;

public class WebSocketConnection implements Connection
{
    WebSocketParser _parser;
    WebSocketGenerator _generator;

    public void handle() throws IOException
    {
        boolean more=true;
        
        while (more)
        {
            int flushed=_generator.flush();
            int filled=_parser.parseNext();
            
            more = flushed>0 || filled>0 || !_parser.isBufferEmpty();
        }
    }

    public boolean isIdle()
    {
        return _parser.isBufferEmpty() && _generator.isBufferEmpty();
    }

    public boolean isSuspended()
    {
        return false;
    }

}
