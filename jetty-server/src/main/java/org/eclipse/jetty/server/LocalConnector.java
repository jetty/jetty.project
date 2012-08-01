// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.AsyncByteArrayEndPoint;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class LocalConnector extends AbstractConnector
{
    private static final Logger LOG = Log.getLogger(LocalConnector.class);

    private final BlockingQueue<LocalEndPoint> _connects = new LinkedBlockingQueue<>();
    
    public LocalConnector(Server server)
    {
        super(server,null,null,null,null, false,-1);
        setIdleTimeout(30000);
    }

    @Override
    public Object getTransport()
    {
        return this;
    }

    /** Sends requests and get responses based on thread activity.
     * Returns all the responses received once the thread activity has
     * returned to the level it was before the requests.
     * @param requests the requests
     * @return the responses
     * @throws Exception if the requests fail
     */
    public String getResponses(String requests) throws Exception
    {
        ByteBuffer result = getResponses(BufferUtil.toBuffer(requests,StringUtil.__UTF8_CHARSET));
        return result==null?null:BufferUtil.toString(result,StringUtil.__UTF8_CHARSET);
    }

    /** Sends requests and get responses based on thread activity.
     * Returns all the responses received once the thread activity has
     * returned to the level it was before the requests.
     * @param requests the requests
     * @return the responses
     * @throws Exception if the requests fail
     */
    public String getResponses(String requests,long idleFor,TimeUnit units) throws Exception
    {
        ByteBuffer result = getResponses(BufferUtil.toBuffer(requests,StringUtil.__UTF8_CHARSET),idleFor,units);
        return result==null?null:BufferUtil.toString(result,StringUtil.__UTF8_CHARSET);
    }

    /** Sends requests and get's responses based on thread activity.
     * Returns all the responses received once the thread activity has
     * returned to the level it was before the requests.
     * @param requestsBuffer the requests
     * @return the responses
     * @throws Exception if the requests fail
     */
    public ByteBuffer getResponses(ByteBuffer requestsBuffer) throws Exception
    {
        return getResponses(requestsBuffer,100,TimeUnit.MILLISECONDS);
    }

    /** Sends requests and get's responses based on thread activity.
     * Returns all the responses received once the thread activity has
     * returned to the level it was before the requests.
     * @param requestsBuffer the requests
     * @return the responses
     * @throws Exception if the requests fail
     */
    public ByteBuffer getResponses(ByteBuffer requestsBuffer,long idleFor,TimeUnit units) throws Exception
    {
        LOG.debug("getResponses");
        LocalEndPoint endp = new LocalEndPoint();
        endp.setInput(requestsBuffer);
        _connects.add(endp);
        endp.waitUntilClosedOrIdleFor(idleFor,units);
        return endp.takeOutput();
    }

    /**
     * Execute a request and return the EndPoint through which
     * responses can be received.
     * @param rawRequest the request
     * @return the local endpoint
     */
    public LocalEndPoint executeRequest(String rawRequest)
    {
        LocalEndPoint endp = new LocalEndPoint();
        endp.setInput(BufferUtil.toBuffer(rawRequest,StringUtil.__UTF8_CHARSET));
        _connects.add(endp);
        return endp;
    }

    @Override
    protected void accept(int acceptorID) throws IOException, InterruptedException
    {
        LOG.debug("accepting {}",acceptorID);
        LocalEndPoint endp = _connects.take();
        AsyncConnection connection=newConnection(endp);
        endp.setAsyncConnection(connection);
        endp.onOpen();
        connection.onOpen();
        connectionOpened(connection);
    }


    public class LocalEndPoint extends AsyncByteArrayEndPoint
    {
        private CountDownLatch _closed = new CountDownLatch(1);

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
            setInput(BufferUtil.toBuffer(s, StringUtil.__UTF8_CHARSET));
        }

        @Override
        public void close()
        {
            boolean was_open=isOpen();
            super.close();
            if (was_open)
            {
                connectionClosed(getAsyncConnection());
                getAsyncConnection().onClose();
                onClose();
            }
        }

        @Override
        public void onClose()
        {
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
                    {
                        System.err.println("wait timeout:\n--");
                        System.err.println(takeOutputString());
                        System.err.println("==");
                        break;
                    }
                }
                catch(Exception e)
                {
                    LOG.warn(e);
                }
            }
        }

        public void waitUntilClosedOrIdleFor(long idleFor,TimeUnit units)
        {
            int size=getOutput().remaining();
            
            while (isOpen())
            {
                try
                {
                    if (!_closed.await(idleFor,units))
                    {
                        if (size==getOutput().remaining())
                            return;
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
