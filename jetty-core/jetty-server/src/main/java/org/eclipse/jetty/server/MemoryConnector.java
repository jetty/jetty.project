//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MemoryEndPointPipe;
import org.eclipse.jetty.util.thread.ExecutionStrategy;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A server {@link Connector} that allows clients to communicate via memory.</p>
 * <p>Typical usage on the server-side:</p>
 * <pre>{@code
 * Server server = new Server();
 * MemoryConnector memoryConnector = new MemoryConnector(server, new HttpConnectionFactory());
 * server.addConnector(memoryConnector);
 * server.start();
 * }</pre>
 * <p>Typical usage on the client-side:</p>
 * <pre> {@code
 * // Connect to the server and get the local, client-side, EndPoint.
 * EndPoint clientEndPoint = memoryConnector.connect().getLocalEndPoint();
 *
 * // Be ready to read responses.
 * Callback readCallback = ...;
 * clientEndPoint.fillInterested(readCallback);
 *
 * // Write a request to the server.
 * ByteBuffer request = StandardCharsets.UTF_8.encode("""
 *     GET / HTTP/1.1
 *     Host: localhost
 *
 *     """);
 * Callback.Completable writeCallback = new Callback.Completable();
 * clientEndPoint.write(writeCallback, request);
 * }</pre>
 */
public class MemoryConnector extends AbstractConnector
{
    private static final Logger LOG = LoggerFactory.getLogger(MemoryConnector.class);

    private final SocketAddress socketAddress = new MemorySocketAddress();
    private final TaskProducer producer = new TaskProducer();
    private ExecutionStrategy strategy;

    public MemoryConnector(Server server, ConnectionFactory... factories)
    {
        this(server, null, null, null, factories);
    }

    public MemoryConnector(
                           Server server,
                           Executor executor,
                           Scheduler scheduler,
                           ByteBufferPool bufferPool,
                           ConnectionFactory... factories)
    {
        super(server, executor, scheduler, bufferPool, 0, factories);
    }

    @Override
    protected void doStart() throws Exception
    {
        strategy = new AdaptiveExecutionStrategy(producer, getExecutor());
        addBean(strategy);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        removeBean(strategy);
    }

    @Override
    public Object getTransport()
    {
        return null;
    }

    @Override
    protected void accept(int acceptorID) throws IOException, InterruptedException
    {
        // Nothing to do here.
    }

    /**
     * <p>Client-side applications use this method to connect to the server and obtain a {@link EndPoint.Pipe}.</p>
     * <p>Client-side applications should then use {@link EndPoint.Pipe#getLocalEndPoint()} to access the
     * client-side {@link EndPoint} to write requests bytes to the server and read response bytes.</p>
     *
     * @return a {@link EndPoint.Pipe} representing the connection between client and server
     */
    public EndPoint.Pipe connect()
    {
        MemoryEndPointPipe pipe = new MemoryEndPointPipe(getScheduler(), producer::offer, socketAddress);
        accept(pipe.getRemoteEndPoint());

        if (LOG.isDebugEnabled())
            LOG.debug("connected {} to {}", pipe, this);

        return pipe;
    }

    private void accept(EndPoint endPoint)
    {
        endPoint.setIdleTimeout(getIdleTimeout());

        AbstractConnection connection =
            (AbstractConnection)getDefaultConnectionFactory().newConnection(this, endPoint);
        endPoint.setConnection(connection);

        endPoint.onOpen();
        onEndPointOpened(endPoint);

        connection.addEventListener(new Connection.Listener()
        {
            @Override
            public void onClosed(Connection connection)
            {
                onEndPointClosed(endPoint);
            }
        });

        connection.onOpen();

        if (LOG.isDebugEnabled())
            LOG.debug("accepted {} in {}", endPoint, this);
    }

    /**
     * @return the local {@link SocketAddress} of this connector
     */
    public SocketAddress getLocalSocketAddress()
    {
        return socketAddress;
    }

    private class TaskProducer implements ExecutionStrategy.Producer
    {
        private final Queue<Invocable.Task> tasks = new ConcurrentLinkedQueue<>();

        @Override
        public Runnable produce()
        {
            return tasks.poll();
        }

        private void offer(Invocable.Task task)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("offer {} to {}", task, MemoryConnector.this);
            tasks.offer(task);
            strategy.produce();
        }
    }

    private class MemorySocketAddress extends SocketAddress
    {
        private final String address = "[memory:@%x]".formatted(System.identityHashCode(MemoryConnector.this));

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj instanceof MemorySocketAddress that)
                return address.equals(that.address);
            return false;
        }

        @Override
        public int hashCode()
        {
            return address.hashCode();
        }

        @Override
        public String toString()
        {
            return address;
        }
    }
}
