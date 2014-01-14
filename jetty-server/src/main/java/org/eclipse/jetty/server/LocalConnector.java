//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

public class LocalConnector extends AbstractConnector
{
    private final BlockingQueue<LocalEndPoint> _connects = new LinkedBlockingQueue<>();


    public LocalConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool pool, int acceptors, ConnectionFactory... factories)
    {
        super(server,executor,scheduler,pool,acceptors,factories);
        setIdleTimeout(30000);
    }

    public LocalConnector(Server server)
    {
        this(server, null, null, null, -1, new HttpConnectionFactory());
    }

    public LocalConnector(Server server, SslContextFactory sslContextFactory)
    {
        this(server, null, null, null, -1,AbstractConnectionFactory.getFactories(sslContextFactory,new HttpConnectionFactory()));
    }

    public LocalConnector(Server server, ConnectionFactory connectionFactory)
    {
        this(server, null, null, null, -1, connectionFactory);
    }

    public LocalConnector(Server server, ConnectionFactory connectionFactory, SslContextFactory sslContextFactory)
    {
        this(server, null, null, null, -1, AbstractConnectionFactory.getFactories(sslContextFactory,connectionFactory));
    }

    @Override
    public Object getTransport()
    {
        return this;
    }

    /** Sends requests and get responses based on thread activity.
     * Returns all the responses received once the thread activity has
     * returned to the level it was before the requests.
     * <p>
     * This methods waits until the connection is closed or
     * is idle for 1s before returning the responses.
     * @param requests the requests
     * @return the responses
     * @throws Exception if the requests fail
     */
    public String getResponses(String requests) throws Exception
    {
        return getResponses(requests, 5, TimeUnit.SECONDS);
    }

    /** Sends requests and get responses based on thread activity.
     * Returns all the responses received once the thread activity has
     * returned to the level it was before the requests.
     * <p>
     * This methods waits until the connection is closed or
     * an idle period before returning the responses.
     * @param requests the requests
     * @param idleFor The time the response stream must be idle for before returning
     * @param units The units of idleFor
     * @return the responses
     * @throws Exception if the requests fail
     */
    public String getResponses(String requests,long idleFor,TimeUnit units) throws Exception
    {
        ByteBuffer result = getResponses(BufferUtil.toBuffer(requests,StandardCharsets.UTF_8),idleFor,units);
        return result==null?null:BufferUtil.toString(result,StandardCharsets.UTF_8);
    }

    /** Sends requests and get's responses based on thread activity.
     * Returns all the responses received once the thread activity has
     * returned to the level it was before the requests.
     * <p>
     * This methods waits until the connection is closed or
     * is idle for 1s before returning the responses.
     * @param requestsBuffer the requests
     * @return the responses
     * @throws Exception if the requests fail
     */
    public ByteBuffer getResponses(ByteBuffer requestsBuffer) throws Exception
    {
        return getResponses(requestsBuffer, 5, TimeUnit.SECONDS);
    }

    /** Sends requests and get's responses based on thread activity.
     * Returns all the responses received once the thread activity has
     * returned to the level it was before the requests.
     * <p>
     * This methods waits until the connection is closed or
     * an idle period before returning the responses.
     * @param requestsBuffer the requests
     * @param idleFor The time the response stream must be idle for before returning
     * @param units The units of idleFor
     * @return the responses
     * @throws Exception if the requests fail
     */
    public ByteBuffer getResponses(ByteBuffer requestsBuffer,long idleFor,TimeUnit units) throws Exception
    {
        LOG.debug("requests {}", BufferUtil.toUTF8String(requestsBuffer));
        LocalEndPoint endp = executeRequest(requestsBuffer);
        endp.waitUntilClosedOrIdleFor(idleFor,units);
        ByteBuffer responses = endp.takeOutput();
        endp.getConnection().close();
        LOG.debug("responses {}", BufferUtil.toUTF8String(responses));
        return responses;
    }

    /**
     * Execute a request and return the EndPoint through which
     * responses can be received.
     * @param rawRequest the request
     * @return the local endpoint
     */
    public LocalEndPoint executeRequest(String rawRequest)
    {
        return executeRequest(BufferUtil.toBuffer(rawRequest, StandardCharsets.UTF_8));
    }

    private LocalEndPoint executeRequest(ByteBuffer rawRequest)
    {
        LocalEndPoint endp = new LocalEndPoint();
        endp.setInput(rawRequest);
        _connects.add(endp);
        return endp;
    }

    @Override
    protected void accept(int acceptorID) throws IOException, InterruptedException
    {
        LOG.debug("accepting {}", acceptorID);
        LocalEndPoint endPoint = _connects.take();
        endPoint.onOpen();
        onEndPointOpened(endPoint);

        Connection connection = getDefaultConnectionFactory().newConnection(this, endPoint);
        endPoint.setConnection(connection);

        connection.onOpen();
    }

    public class LocalEndPoint extends ByteArrayEndPoint
    {
        private final CountDownLatch _closed = new CountDownLatch(1);

        public LocalEndPoint()
        {
            super(getScheduler(), LocalConnector.this.getIdleTimeout());
            setGrowOutput(true);
        }

        public void addInput(String s)
        {
            // TODO this is a busy wait
            while(getIn()==null || BufferUtil.hasContent(getIn()))
                Thread.yield();
            setInput(BufferUtil.toBuffer(s, StandardCharsets.UTF_8));
        }

        @Override
        public void close()
        {
            boolean wasOpen=isOpen();
            super.close();
            if (wasOpen)
            {
//                connectionClosed(getConnection());
                getConnection().onClose();
                onClose();
            }
        }

        @Override
        public void onClose()
        {
            LocalConnector.this.onEndPointClosed(this);
            super.onClose();
            _closed.countDown();
        }

        @Override
        public void shutdownOutput()
        {
            super.shutdownOutput();
            close();
        }

        public void waitUntilClosed()
        {
            while (isOpen())
            {
                try
                {
                    if (!_closed.await(10,TimeUnit.SECONDS))
                        break;
                }
                catch(Exception e)
                {
                    LOG.warn(e);
                }
            }
        }

        public void waitUntilClosedOrIdleFor(long idleFor,TimeUnit units)
        {
            Thread.yield();
            int size=getOutput().remaining();
            while (isOpen())
            {
                try
                {
                    if (!_closed.await(idleFor,units))
                    {
                        if (size==getOutput().remaining())
                        {
                            LOG.debug("idle for {} {}",idleFor,units);
                            return;
                        }
                        size=getOutput().remaining();
                    }
                }
                catch(Exception e)
                {
                    LOG.warn(e);
                }
            }
        }
    }
}
