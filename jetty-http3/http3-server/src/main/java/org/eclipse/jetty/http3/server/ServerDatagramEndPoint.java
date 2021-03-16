package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ReadPendingException;
import java.nio.channels.SelectionKey;
import java.nio.channels.WritePendingException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

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

public class ServerDatagramEndPoint extends IdleTimeout implements EndPoint, ManagedSelector.Selectable
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerDatagramEndPoint.class);

    private final FillInterest fillInterest = new FillInterest() {
        @Override
        protected void needsFillInterest() throws IOException
        {

        }
    };
    private final DatagramChannel channel;
    private final ManagedSelector selector;
    private final SelectionKey selectionKey;
    private Connection connection;
    private boolean open;

    public ServerDatagramEndPoint(Scheduler scheduler, DatagramChannel channel, ManagedSelector selector, SelectionKey selectionKey)
    {
        super(scheduler);
        this.channel = channel;
        this.selector = selector;
        this.selectionKey = selectionKey;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        try
        {
            return (InetSocketAddress)channel.getLocalAddress();
        }
        catch (IOException e)
        {
            return null;
        }
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return null;
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
        int headerPosition = buffer.position();
        buffer.position(buffer.position() + ENCODED_ADDRESS_LENGTH);
        InetSocketAddress peer = (InetSocketAddress)channel.receive(buffer);
        if (peer == null)
        {
            buffer.position(headerPosition);
            buffer.flip();
            return 0;
        }
        int finalPosition = buffer.position();

        buffer.position(headerPosition);
        encodeInetSocketAddress(buffer, peer);
        buffer.position(finalPosition);

        buffer.flip();

        return finalPosition - ENCODED_ADDRESS_LENGTH;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        InetSocketAddress peer = decodeInetSocketAddress(buffers[0]);
        for (int i = 1; i < buffers.length; i++)
        {
            ByteBuffer buffer = buffers[i];
            int sent = channel.send(buffer, peer);
            if (sent == 0)
                return false;
        }
        return true;
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

    //TODO: this is racy
    private final AtomicBoolean fillable = new AtomicBoolean();

    @Override
    public Runnable onSelected()
    {
        return () ->
        {
            if (!fillInterest.fillable())
                fillable.set(true);
        };
    }

    @Override
    public void updateKey()
    {
        // TODO: change interest?
    }

    @Override
    public void replaceKey(SelectionKey newKey)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fillInterested(Callback callback) throws ReadPendingException
    {
        fillInterest.register(callback);
        if (fillable.getAndSet(false))
            fillInterest.fillable();
    }

    @Override
    public boolean tryFillInterested(Callback callback)
    {
        boolean registered = fillInterest.tryRegister(callback);
        if (registered && fillable.getAndSet(false))
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
        try
        {
            boolean done = flush(buffers);
            if (done)
                callback.succeeded();
        }
        catch (IOException e)
        {
            callback.failed(e);
        }
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

    static final int ENCODED_ADDRESS_LENGTH = 19;

    static InetSocketAddress decodeInetSocketAddress(ByteBuffer buffer) throws IOException
    {
        int headerPosition = buffer.position();
        byte ipVersion = buffer.get();
        byte[] address;
        if (ipVersion == 4)
            address = new byte[4];
        else if (ipVersion == 6)
            address = new byte[16];
        else throw new IOException("Unsupported IP version: " + ipVersion);
        buffer.get(address);
        int port = buffer.getChar();
        buffer.position(headerPosition + ENCODED_ADDRESS_LENGTH);
        return new InetSocketAddress(InetAddress.getByAddress(address), port);
    }

    static void encodeInetSocketAddress(ByteBuffer buffer, InetSocketAddress peer) throws IOException
    {
        int headerPosition = buffer.position();
        byte[] addressBytes = peer.getAddress().getAddress();
        int port = peer.getPort();
        byte ipVersion;
        if (peer.getAddress() instanceof Inet4Address)
            ipVersion = 4;
        else if (peer.getAddress() instanceof Inet6Address)
            ipVersion = 6;
        else throw new IOException("Unsupported address type: " + peer.getAddress().getClass());

        buffer.put(ipVersion);
        buffer.put(addressBytes);
        buffer.putChar((char)port);
        buffer.position(headerPosition + ENCODED_ADDRESS_LENGTH);
    }
}
