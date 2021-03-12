package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ReadPendingException;
import java.nio.channels.SelectionKey;
import java.nio.channels.WritePendingException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.FillInterest;
import org.eclipse.jetty.io.IdleTimeout;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerDatagramEndPoint extends IdleTimeout implements EndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerDatagramEndPoint.class);

    private final FillInterest fillInterest = new FillInterest() {
        @Override
        protected void needsFillInterest() throws IOException
        {

        }
    };
    private final DatagramChannel channel;
    private final SocketAddress localAddress;
    private SocketAddress remoteAddress;
    private ManagedSelector selector;
    private SelectionKey selectionKey;
    private Connection connection;
    private ByteBuffer data;
    private boolean open;

    public ServerDatagramEndPoint(Scheduler scheduler, SocketAddress localAddress, SocketAddress remoteAddress, ByteBuffer buffer, DatagramChannel channel)
    {
        super(scheduler);
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.channel = channel;
        this.data = buffer;
    }

    public void init(ManagedSelector selector, SelectionKey selectionKey)
    {
        this.selector = selector;
        this.selectionKey = selectionKey;
    }

    public void onData(ByteBuffer buffer, SocketAddress peer)
    {
        this.remoteAddress = peer;
        this.data = buffer;
        fillInterest.fillable();
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return (InetSocketAddress)localAddress;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return (InetSocketAddress)remoteAddress;
    }

    @Override
    public boolean isOpen()
    {
        return open;
    }

    @Override
    public long getCreatedTimeStamp()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void shutdownOutput()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOutputShutdown()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isInputShutdown()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close(Throwable cause)
    {
        LOG.info("closed endpoint");
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        if (data != null)
        {
            int before = data.remaining();
            LOG.info("fill; bytes remaining: {} byte(s)", before);
            BufferUtil.flipToFill(buffer);
            buffer.put(data);
            buffer.flip();
            int after = data.remaining();
            if (after == 0)
                data = null;
            int filled = before - after;
            LOG.info("filled {} byte(s)", filled);
            return filled;
        }
        return 0;
    }

    @Override
    public boolean flush(ByteBuffer... buffer) throws IOException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getTransport()
    {
        return this.channel;
    }

    @Override
    protected void onIdleExpired(TimeoutException timeout)
    {
        LOG.info("idle timeout", timeout);
    }

    @Override
    public void fillInterested(Callback callback) throws ReadPendingException
    {
        fillInterest.register(callback);
        if (data != null)
            fillInterest.fillable();
    }

    @Override
    public boolean tryFillInterested(Callback callback)
    {
        boolean registered = fillInterest.tryRegister(callback);
        if (registered && data != null)
            fillInterest.fillable();
        return registered;
    }

    @Override
    public boolean isFillInterested()
    {
        return fillInterest.isInterested();
    }

    @Override
    public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
    {
        for (ByteBuffer buffer : buffers)
        {
            try
            {
                int sent = channel.send(buffer, getRemoteAddress());
            }
            catch (IOException e)
            {
                callback.failed(e);
                return;
            }
        }
        callback.succeeded();
    }

    @Override
    public Connection getConnection()
    {
        return connection;
    }

    @Override
    public void setConnection(Connection connection)
    {
        this.connection = connection;
    }

    @Override
    public void onOpen()
    {
        open = true;
        LOG.info("onOpen");
    }

    @Override
    public void onClose(Throwable cause)
    {
        LOG.info("onClose");
        open = false;
    }

    @Override
    public void upgrade(Connection newConnection)
    {
        throw new UnsupportedOperationException();
    }
}
