/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.eclipse.jetty.spdy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.*;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.spdy.CompressionFactory.Compressor;
import org.eclipse.jetty.spdy.CompressionFactory.Decompressor;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.Session.FrameListener;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.nio.AsyncSPDYConnection;
import org.eclipse.jetty.spdy.parser.Parser;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;

public class SPDYClient
{
    private final Factory factory;
    private SocketAddress bindAddress;
    private long maxIdleTime;

    protected SPDYClient(Factory factory)
    {
        this.factory = factory;
    }

    /**
     * @return the address to bind the socket channel to
     * @see #setBindAddress(SocketAddress)
     */
    public SocketAddress getBindAddress()
    {
        return bindAddress;
    }

    /**
     * @param bindAddress the address to bind the socket channel to
     * @see #getBindAddress()
     */
    public void setBindAddress(SocketAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }

    public Future<Session> connect(InetSocketAddress address, FrameListener listener) throws IOException
    {
        if (!factory.isStarted())
            throw new IllegalStateException(Factory.class.getSimpleName() + " is not started");

        SocketChannel channel = SocketChannel.open();
        if (bindAddress != null)
            channel.bind(bindAddress);
        channel.socket().setTcpNoDelay(true);
        channel.configureBlocking(false);

        SessionFuture result = new SessionFuture(this, channel, listener);

        channel.connect(address);
        factory.selector.register(channel, result);

        return result;
    }

    public long getMaxIdleTime()
    {
        return maxIdleTime;
    }

    public void setMaxIdleTime(long maxIdleTime)
    {
        this.maxIdleTime = maxIdleTime;
    }

    protected CompressionFactory newCompressionFactory()
    {
        return new StandardCompressionFactory();
    }

    protected Parser newParser(Decompressor decompressor)
    {
        return new Parser(decompressor);
    }

    protected Generator newGenerator(Compressor compressor)
    {
        return new Generator(compressor);
    }

    private Session newSession(ISession.Controller controller, FrameListener listener, Parser parser, Generator generator)
    {
        StandardSession session = new StandardSession(controller, 1, listener, generator);
        parser.addListener(session);
        return session;
    }

    public static class Factory extends AggregateLifeCycle
    {
        private final ThreadPool threadPool;
        private final SelectorManager selector;

        public Factory()
        {
            this(null);
        }

        public Factory(ThreadPool threadPool)
        {
            if (threadPool == null)
                threadPool = new QueuedThreadPool();
            this.threadPool = threadPool;
            addBean(this.threadPool);

            selector = new ClientSelectorManager();
            addBean(selector);
        }

        public SPDYClient newSPDYClient()
        {
            return new SPDYClient(this);
        }

        public void join() throws InterruptedException
        {
            threadPool.join();
        }

        private class ClientSelectorManager extends SelectorManager
        {
            @Override
            public boolean dispatch(Runnable task)
            {
                return threadPool.dispatch(task);
            }

            @Override
            protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key) throws IOException
            {
                SessionFuture attachment = (SessionFuture)key.attachment();

                long maxIdleTime = attachment.getMaxIdleTime();
                if (maxIdleTime < 0)
                    maxIdleTime = getMaxIdleTime();
                SelectChannelEndPoint result = new SelectChannelEndPoint(channel, selectSet, key, (int)maxIdleTime);

                // TODO: handle SSL

                AsyncConnection connection = newConnection(channel, result, attachment);
                result.setConnection(connection);
                return result;
            }

            @Override
            protected void endPointOpened(SelectChannelEndPoint endpoint)
            {
            }

            @Override
            protected void endPointUpgraded(ConnectedEndPoint endpoint, Connection oldConnection)
            {
            }

            @Override
            protected void endPointClosed(SelectChannelEndPoint endpoint)
            {
                endpoint.getConnection().onClose();
            }

            @Override
            public AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endPoint, Object attachment)
            {
                SessionFuture sessionFuture = (SessionFuture)attachment;
                SPDYClient client = sessionFuture.client;

                CompressionFactory compressionFactory = client.newCompressionFactory();
                Parser parser = client.newParser(compressionFactory.newDecompressor());
                Generator generator = client.newGenerator(compressionFactory.newCompressor());

                AsyncSPDYConnection connection = new AsyncSPDYConnection(endPoint, parser);
                Session session = client.newSession(connection, sessionFuture.listener, parser, generator);
                sessionFuture.connected(session);
                return connection;
            }
        }
    }

    private class SessionFuture implements Future<Session>
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final SPDYClient client;
        private final SocketChannel channel;
        private final FrameListener listener;
        private volatile boolean cancelled;
        private volatile Throwable failure;
        private volatile Session session;

        private SessionFuture(SPDYClient client, SocketChannel channel, FrameListener listener)
        {
            this.client = client;
            this.channel = channel;
            this.listener = listener;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning)
        {
            cancelled = true;
            return false;
        }

        @Override
        public boolean isCancelled()
        {
            return cancelled;
        }

        @Override
        public boolean isDone()
        {
            return cancelled || latch.getCount() == 0;
        }

        @Override
        public Session get() throws InterruptedException, ExecutionException
        {
            latch.await();
            return result();
        }

        @Override
        public Session get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
        {
            latch.await(timeout, unit);
            return result();
        }

        private Session result() throws ExecutionException
        {
            Throwable failure = this.failure;
            if (failure != null)
                throw new ExecutionException(failure);
            return session;
        }

        private long getMaxIdleTime()
        {
            return client.getMaxIdleTime();
        }

        private void connected(Session session)
        {
            this.session = session;
            latch.countDown();
        }
    }
}
