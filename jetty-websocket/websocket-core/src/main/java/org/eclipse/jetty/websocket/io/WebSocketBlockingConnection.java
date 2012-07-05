package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketConnection;

public class WebSocketBlockingConnection
{
    private WebSocketConnection conn;

    public WebSocketBlockingConnection(WebSocketConnection conn)
    {
        this.conn = conn;
    }

    /**
     * Send a binary message.
     * <p>
     * Basic usage, results in a blocking write.
     */
    public void write(byte[] data, int offset, int length) throws IOException
    {

    }

    /**
     * Send a series of binary messages.
     * <p>
     * Note: each buffer results in its own binary message frame.
     * <p>
     * Basic usage, results in a series of blocking writes.
     */
    public void write(ByteBuffer... buffers) throws IOException
    {

    }

    /**
     * Send text messages.
     * <p>
     * Basic usage, results in a series of blocking writes.
     */
    public void write(String... messages) throws IOException
    {

    }
}
