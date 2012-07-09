package org.eclipse.jetty.websocket.api.io;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.io.DataFrameBytes;
import org.eclipse.jetty.websocket.io.RawConnection;
import org.eclipse.jetty.websocket.protocol.FrameBuilder;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * For working with the {@link WebSocketConnection} in a blocking technique.
 * <p>
 * This is an end-user accessible class.
 */
public class WebSocketBlockingConnection
{
    private final RawConnection conn;

    public WebSocketBlockingConnection(WebSocketConnection conn)
    {
        if (conn instanceof RawConnection)
        {
            this.conn = (RawConnection)conn;
        }
        else
        {
            throw new IllegalArgumentException("WebSocketConnection must implement internal RawConnection interface");
        }
    }

    /**
     * Send a binary message.
     * <p>
     * Basic usage, results in a blocking write.
     */
    public void write(byte[] data, int offset, int length) throws IOException
    {
        WebSocketFrame frame = FrameBuilder.binary(data,offset,length).asFrame();
        try
        {
            FutureCallback<Void> blocking = new FutureCallback<>();
            DataFrameBytes<Void> bytes = new DataFrameBytes<>(conn,blocking,null,frame);
            this.conn.getQueue().append(bytes);
            blocking.get(); // block till finished
        }
        catch (InterruptedException e)
        {
            throw new IOException("Blocking write failed",e);
        }
        catch (ExecutionException e)
        {
            FutureCallback.rethrow(e);
        }
    }

    /**
     * Send text message.
     * <p>
     * Basic usage, results in a blocking write.
     */
    public void write(String message) throws IOException
    {
        WebSocketFrame frame = FrameBuilder.text(message).asFrame();
        try
        {
            FutureCallback<Void> blocking = new FutureCallback<>();
            DataFrameBytes<Void> bytes = new DataFrameBytes<>(conn,blocking,null,frame);
            this.conn.getQueue().append(bytes);
            blocking.get(); // block till finished
        }
        catch (InterruptedException e)
        {
            throw new IOException("Blocking write failed",e);
        }
        catch (ExecutionException e)
        {
            FutureCallback.rethrow(e);
        }
    }
}
