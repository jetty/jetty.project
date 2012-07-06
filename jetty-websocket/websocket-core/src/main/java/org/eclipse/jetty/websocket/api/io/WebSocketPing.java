package org.eclipse.jetty.websocket.api.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.io.RawConnection;

public class WebSocketPing
{
    private RawConnection conn;
    private ByteBufferPool bufferPool;
    private WebSocketPolicy policy;
    private Generator generator;

    public WebSocketPing(WebSocketConnection conn)
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

    public void sendPing(byte data[]) throws IOException
    {
        PingFrame frame = new PingFrame(data);
        ByteBuffer buf = bufferPool.acquire(policy.getBufferSize(),false);
        try
        {
            generator.generate(buf,frame);
            FutureCallback<Void> blocking = new FutureCallback<>();
            this.conn.writeRaw(null,blocking,buf);
            blocking.get(); // block till finished sending?
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
