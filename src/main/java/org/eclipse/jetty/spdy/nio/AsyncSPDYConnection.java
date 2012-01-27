package org.eclipse.jetty.spdy.nio;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.DirectNIOBuffer;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.NIOBuffer;
import org.eclipse.jetty.spdy.ISession;
import org.eclipse.jetty.spdy.ISession.Controller;
import org.eclipse.jetty.spdy.api.SPDYException;
import org.eclipse.jetty.spdy.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AsyncSPDYConnection extends AbstractConnection implements AsyncConnection, Controller {

    private static final Logger logger = LoggerFactory.getLogger(AsyncSPDYConnection.class);
    private final Parser parser;
    private ByteBuffer buffer;
    private Handler handler;
    private volatile boolean flushing;

    public AsyncSPDYConnection(EndPoint endp, Parser parser)
    {
        super(endp);
        this.parser = parser;
    }

    @Override
    public Connection handle() throws IOException
    {
        AsyncEndPoint endPoint = getEndPoint();
        boolean progress = true;
        while (endPoint.isOpen() && progress)
        {
            progress = false;

            int filled = fill();
            progress |= filled > 0;
            logger.debug("Filled {} from {}", filled, endPoint);

            int flushed = flush();
            progress |= flushed > 0;
            logger.debug("Flushed {} to {}", flushed, endPoint);

            endPoint.flush();

            progress |= endPoint.hasProgressed();

            if (!progress && filled < 0)
                close(false);
        }
        return this;
    }

    protected int fill() throws IOException
    {
        NIOBuffer jettyBuffer = new DirectNIOBuffer(1024);
        AsyncEndPoint endPoint = getEndPoint();
        int filled = endPoint.fill(jettyBuffer);
        if (filled > 0)
        {
            ByteBuffer buffer = jettyBuffer.getByteBuffer();
            buffer.limit(jettyBuffer.putIndex());
            buffer.position(jettyBuffer.getIndex());
            parser.parse(buffer);
        }

        return filled;
    }

    protected int flush()
    {
        // Volatile read to ensure visibility of buffer and handler
        if (!flushing)
            return 0;
        return write(buffer, handler);
    }

    @Override
    public int write(ByteBuffer buffer, ISession.Controller.Handler handler)
    {
        int remaining = buffer.remaining();
        Buffer jettyBuffer = buffer.isDirect() ? new DirectNIOBuffer(buffer, false) : new IndirectNIOBuffer(buffer, false);
        AsyncEndPoint endPoint = getEndPoint();
        try
        {
            int written = endPoint.flush(jettyBuffer);
            logger.debug("Written {} bytes", written);
        }
        catch (IOException x)
        {
            close(false);
            throw new SPDYException(x);
        }
        finally
        {
            buffer.limit(jettyBuffer.putIndex());
            buffer.position(jettyBuffer.getIndex());
        }

        if (buffer.hasRemaining())
        {
            // Save buffer and handler in order to finish the write later in flush()
            this.buffer = buffer;
            this.handler = handler;
            // Volatile write to ensure visibility of buffer and handler
            flushing = true;
            endPoint.scheduleWrite();
        }
        else
        {
            if (flushing)
            {
                this.buffer = null;
                this.handler = null;
                // Volatile write to ensure visibility of buffer and handler
                flushing = false;
            }
            handler.complete();
        }

        return remaining - buffer.remaining();
    }

    @Override
    public void close(boolean onlyOutput)
    {
        try
        {
            AsyncEndPoint endPoint = getEndPoint();
            if (onlyOutput)
            {
                try
                {
                    logger.debug("Shutting down output {}", endPoint);
                    endPoint.shutdownOutput();
                }
                catch (IOException x)
                {
                    endPoint.close();
                }
            }
            else
            {
                logger.debug("Closing {}", endPoint);
                endPoint.close();
            }
        }
        catch (IOException x)
        {
            logger.trace("", x);
        }
    }

    @Override
    public AsyncEndPoint getEndPoint()
    {
        return (AsyncEndPoint) super.getEndPoint();
    }

    @Override
    public boolean isIdle()
    {
        return false;
    }

    @Override
    public boolean isSuspended()
    {
        return false;
    }

    @Override
    public void onClose()
    {
        // TODO
    }

    @Override
    public void onInputShutdown() throws IOException
    {
        // TODO
    }
}
