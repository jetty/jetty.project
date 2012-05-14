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

import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class LocalConnector extends AbstractHttpConnector
{
    private static final Logger LOG = Log.getLogger(LocalConnector.class);
    private final BlockingQueue<Request> _requests = new LinkedBlockingQueue<Request>();
    
    public LocalConnector()
    {
        setMaxIdleTime(30000);
    }

    @Override
    public Object getTransport()
    {
        return this;
    }

    public String getResponses(String requests) throws Exception
    {
        return getResponses(requests, false);
    }

    public String getResponses(String requests, boolean keepOpen) throws Exception
    {
        ByteBuffer result = getResponses(BufferUtil.toBuffer(requests,StringUtil.__UTF8_CHARSET), keepOpen);
        return result==null?null:BufferUtil.toString(result,StringUtil.__UTF8_CHARSET);
    }

    public ByteBuffer getResponses(ByteBuffer requestsBuffer, boolean keepOpen) throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        Request request = new Request(requestsBuffer, keepOpen, latch);
        _requests.add(request);
        latch.await(getMaxIdleTime(),TimeUnit.MILLISECONDS);
        return request.getResponsesBuffer();
    }

    @Override
    protected void accept(int acceptorID) throws IOException, InterruptedException
    {
        Request request = _requests.take();
        findExecutor().execute(request);
    }

    public void open() throws IOException
    {
    }

    public void close() throws IOException
    {
    }

    public int getLocalPort()
    {
        return -1;
    }

    public void executeRequest(String rawRequest) throws IOException
    {
        Request request = new Request(BufferUtil.toBuffer(rawRequest,StringUtil.__UTF8_CHARSET),true,null);
                
        _requests.add(request);
    }

    private class Request implements Runnable
    {
        private final ByteBuffer _requestsBuffer;
        private final boolean _keepOpen;
        private final CountDownLatch _latch;
        private volatile ByteBuffer _responsesBuffer;

        private Request(ByteBuffer requestsBuffer, boolean keepOpen, CountDownLatch latch)
        {
            _requestsBuffer = requestsBuffer;
            _keepOpen = keepOpen;
            _latch = latch;
        }

        public void run()
        {
            /*
            try
            {
                ByteArrayEndPoint endPoint = new ByteArrayEndPoint(_requestsBuffer.asArray(), 1024)
                {
                    @Override
                    public void setConnection(AsyncConnection connection)
                    {
                        if (getConnection()!=null && connection!=getConnection())
                            connectionUpgraded(getConnection(),connection);
                        super.setConnection(connection);
                    }
                };

                endPoint.setGrowOutput(true);
                AbstractHttpConnection connection = new BlockingHttpConnection(LocalConnector.this, endPoint, getServer());
                endPoint.setConnection(connection);
                connectionOpened(connection);

                boolean leaveOpen = _keepOpen;
                try
                {
                    while (endPoint.getIn().length() > 0 && endPoint.isOpen())
                    {
                        while (true)
                        {
                            final AsyncConnection con = endPoint.getConnection();
                            final AsyncConnection next = con.handle();
                            if (next!=con)
                            {  
                                endPoint.setConnection(next);
                                continue;
                            }
                            break;
                        }
                    }
                }
                catch (IOException x)
                {
                    LOG.debug(x);
                    leaveOpen = false;
                }
                catch (Exception x)
                {
                    LOG.warn(x);
                    leaveOpen = false;
                }
                finally
                {
                    if (!leaveOpen)
                        connectionClosed(connection);
                    _responsesBuffer = endPoint.getOutput();
                }
            }
            finally
            {
                if (_latch != null)
                    _latch.countDown();
            }
            */
        }

        public ByteBuffer getResponsesBuffer()
        {
            return _responsesBuffer;
        }
    }
}
