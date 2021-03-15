package org.eclipse.jetty.http3.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.EventListener;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.server.AbstractNetworkConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.thread.Scheduler;

public class ServerDatagramConnector extends AbstractNetworkConnector
{
    private final ServerDatagramSelectorManager _manager;
    private volatile DatagramChannel _datagramChannel;
    private volatile int _localPort = -1;
    private Closeable _acceptor;

    public ServerDatagramConnector(
        @Name("server") Server server,
        @Name("executor") Executor executor,
        @Name("scheduler") Scheduler scheduler,
        @Name("bufferPool") ByteBufferPool bufferPool,
        @Name("selectors") int selectors,
        @Name("factories") ConnectionFactory... factories)
    {
        super(server, executor, scheduler, bufferPool, 0, factories);
        _manager = new ServerDatagramSelectorManager(getExecutor(), getScheduler(), selectors);
        addBean(_manager, true);
        setAcceptorPriorityDelta(-2);
    }

    public ServerDatagramConnector(
        @Name("server") Server server,
        @Name("factories") ConnectionFactory... factories)
    {
        this(server, null, null, null, 1, factories);
    }

    @Override
    protected void doStart() throws Exception
    {
        for (EventListener l : getBeans(SelectorManager.SelectorManagerListener.class))
            _manager.addEventListener(l);
        super.doStart();
        _acceptor = _manager.datagramReader(_datagramChannel);
    }

    @Override
    protected void doStop() throws Exception
    {
        IO.close(_acceptor);
        super.doStop();
        for (EventListener l : getBeans(EventListener.class))
            _manager.removeEventListener(l);
    }

    @Override
    public boolean isOpen()
    {
        DatagramChannel channel = _datagramChannel;
        return channel != null && channel.isOpen();
    }

    @Override
    public void open() throws IOException
    {
        if (_datagramChannel == null)
        {
            _datagramChannel = openDatagramChannel();
            _datagramChannel.configureBlocking(false);
            _localPort = _datagramChannel.socket().getLocalPort();
            if (_localPort <= 0)
                throw new IOException("Datagram channel not bound");
            addBean(_datagramChannel);
        }
    }

    @Override
    public void close()
    {
        super.close();

        DatagramChannel datagramChannel = _datagramChannel;
        _datagramChannel = null;
        if (datagramChannel != null)
        {
            removeBean(datagramChannel);

            if (datagramChannel.isOpen())
            {
                try
                {
                    datagramChannel.close();
                }
                catch (IOException e)
                {
                    LOG.warn("Unable to close {}", datagramChannel, e);
                }
            }
        }
        _localPort = -2;
    }

    protected DatagramChannel openDatagramChannel() throws IOException
    {
        InetSocketAddress bindAddress = getHost() == null ? new InetSocketAddress(getPort()) : new InetSocketAddress(getHost(), getPort());
        DatagramChannel datagramChannel = DatagramChannel.open();
        try
        {
            datagramChannel.socket().bind(bindAddress);
        }
        catch (Throwable e)
        {
            IO.close(datagramChannel);
            throw new IOException("Failed to bind to " + bindAddress, e);
        }
        return datagramChannel;
    }

    @Override
    public Object getTransport()
    {
        return _datagramChannel;
    }

    @Override
    protected void accept(int acceptorID)
    {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has no accept mechanism");
    }

    private class ServerDatagramSelectorManager extends SelectorManager
    {
        private final ConcurrentMap<SocketAddress, ServerDatagramEndPoint> _acceptedChannels = new ConcurrentHashMap<>();

        protected ServerDatagramSelectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            super(executor, scheduler, selectors);
        }

        public Closeable datagramReader(SelectableChannel server)
        {
            ManagedSelector selector = chooseSelector();
            DatagramReader reader = new DatagramReader(server);
            selector.submit(reader);
            return reader;
        }

        @Override
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey) throws IOException
        {
            ManagedSelector.PeerAware attachment = (ManagedSelector.PeerAware)selectionKey.attachment();
            ServerDatagramEndPoint serverDatagramEndPoint = _acceptedChannels.get(attachment.peer());
            serverDatagramEndPoint.init(selector, selectionKey);
            return serverDatagramEndPoint;
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment) throws IOException
        {
            //TODO: return quic connection
            //return new QuicConnection();
            HttpConfiguration config = new HttpConfiguration();
            config.setHttpCompliance(HttpCompliance.LEGACY); // enable HTTP/0.9
            return new HttpConnection(config, ServerDatagramConnector.this, endpoint, false);
        }

        protected SocketAddress doReadDatagram(SelectableChannel channel) throws IOException
        {
            ByteBuffer buffer = getByteBufferPool().acquire(1200, true);
            BufferUtil.flipToFill(buffer);
            LOG.info("doReadDatagram {}", channel);
            DatagramChannel datagramChannel = (DatagramChannel)channel;
            SocketAddress peer = datagramChannel.receive(buffer);
            buffer.flip();
            LOG.info("doReadDatagram received {} byte(s)", buffer.remaining());
            SocketAddress localAddress = datagramChannel.getLocalAddress();

            boolean[] created = new boolean[1];
            ServerDatagramEndPoint endPoint = _acceptedChannels.computeIfAbsent(peer, remoteAddress ->
            {
                ServerDatagramEndPoint endp = new ServerDatagramEndPoint(getScheduler(), localAddress, remoteAddress, buffer, datagramChannel);
                created[0] = true;
                return endp;
            });

            if (created[0])
                return peer;
            endPoint.onData(buffer, peer);
            return null;
        }

        @Override
        public String toString()
        {
            return String.format("DatagramSelectorManager@%s", ServerDatagramConnector.this);
        }

        class DatagramReader implements ManagedSelector.SelectorUpdate, ManagedSelector.Selectable, Closeable, ManagedSelector.PeerAware
        {
            private final SelectableChannel _channel;
            private SelectionKey _key;
            private SocketAddress _peer;

            DatagramReader(SelectableChannel channel)
            {
                _channel = channel;
            }

            @Override
            public SocketAddress peer()
            {
                return _peer;
            }

            @Override
            public void update(Selector selector)
            {
                try
                {
                    _key = _channel.register(selector, SelectionKey.OP_READ, this);
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} reader={}", this, _channel);
                }
                catch (Throwable x)
                {
                    IO.close(_channel);
                    LOG.warn("Unable to register OP_READ on selector for {}", _channel, x);
                }
            }

            @Override
            public Runnable onSelected()
            {
                LOG.info("DatagramReader onSelected");
                try
                {
                    _peer = doReadDatagram(_channel);
                    if (_peer != null)
                    {
                        try
                        {
                            chooseSelector().createEndPoint(_channel, _key);
                            onAccepted(_channel);
                        }
                        catch (Throwable x)
                        {
                            LOG.warn("createEndPoint failed for channel {}", _channel, x);
                        }
                    }
                }
                catch (Throwable x)
                {
                    LOG.warn("Read failed for channel {}", _channel, x);
                }
                return null;
            }

            @Override
            public void updateKey()
            {
            }

            @Override
            public void replaceKey(SelectionKey newKey)
            {
                _key = newKey;
            }

            @Override
            public void close() throws IOException
            {
                // May be called from any thread.
                // Implements AbstractConnector.setAccepting(boolean).
                chooseSelector().submit(selector -> _key.cancel());
            }
        }
    }
}
