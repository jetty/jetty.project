package org.eclipse.jetty.websocket.server.examples.echo;

import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketListener;

public class LogSocket implements WebSocketListener
{
    private boolean verbose = false;

    public boolean isVerbose()
    {
        return verbose;
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        if (verbose)
        {
            System.err.printf("onWebSocketBinary(byte[%d] payload, %d, %d)%n",payload.length,offset,len);
        }
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        if (verbose)
        {
            System.err.printf("onWebSocketClose(%d, %s)%n",statusCode,quote(reason));
        }
    }

    @Override
    public void onWebSocketConnect(WebSocketConnection connection)
    {
        if (verbose)
        {
            System.err.printf("onWebSocketConnect(%s)%n",connection);
        }
    }

    @Override
    public void onWebSocketException(WebSocketException error)
    {
        if (verbose)
        {
            System.err.printf("onWebSocketException((%s) %s)%n",error.getClass().getName(),error.getMessage());
            error.printStackTrace(System.err);
        }
    }

    @Override
    public void onWebSocketText(String message)
    {
        if (verbose)
        {
            System.err.printf("onWebSocketText(%s)%n",quote(message));
        }
    }

    private String quote(String str)
    {
        if (str == null)
        {
            return "<null>";
        }
        return '"' + str + '"';
    }

    public void setVerbose(boolean verbose)
    {
        this.verbose = verbose;
    }

}
