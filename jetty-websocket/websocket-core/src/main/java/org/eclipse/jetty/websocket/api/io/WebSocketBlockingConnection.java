package org.eclipse.jetty.websocket.api.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.BinaryFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.io.RawConnection;

/**
 * For working with the {@link WebSocketConnection} in a blocking technique.
 * <p>
 * This is an end-user accessible class.
 */
public class WebSocketBlockingConnection
{
    private final RawConnection conn;
    private final ByteBufferPool bufferPool;
    private final WebSocketPolicy policy;
    private final Generator generator;

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
        this.bufferPool = this.conn.getBufferPool();
        this.policy = conn.getPolicy();
        this.generator = new Generator(this.policy);
    }

    /**
     * Send a binary message.
     * <p>
     * Basic usage, results in a blocking write.
     */
    public void write(byte[] data, int offset, int length) throws IOException
    {
        BinaryFrame frame = new BinaryFrame(data,offset,length);
        ByteBuffer buf = bufferPool.acquire(policy.getBufferSize(),false);
        try
        {
            generator.generate(buf,frame);
            FutureCallback<Void> blocking = new FutureCallback<>();
            this.conn.writeRaw(null,blocking,buf);
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
        finally
        {
            bufferPool.release(buf);
        }
    }

    /**
     * Send text message.
     * <p>
     * Basic usage, results in a blocking write.
     */
    public void write(String message) throws IOException
    {
        TextFrame frame = new TextFrame(message);
        ByteBuffer buf = bufferPool.acquire(policy.getBufferSize(),false);
        try
        {
            generator.generate(buf,frame);
            FutureCallback<Void> blocking = new FutureCallback<>();
            this.conn.writeRaw(null,blocking,buf);
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
        finally
        {
            bufferPool.release(buf);
        }
    }
}
