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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class LocalConnector extends AbstractConnector
{
    private static final Logger LOG = Log.getLogger(LocalConnector.class);
    private final BlockingQueue<Request> _requests = new LinkedBlockingQueue<Request>();
    
    public LocalConnector()
    {
        setMaxIdleTime(30000);
    }

    public Object getConnection()
    {
        return this;
    }

    public String getResponses(String requests) throws Exception
    {
        return getResponses(requests, false);
    }

    public String getResponses(String requests, boolean keepOpen) throws Exception
    {
        ByteArrayBuffer result = getResponses(new ByteArrayBuffer(requests, StringUtil.__ISO_8859_1), keepOpen);
        return result==null?null:result.toString(StringUtil.__ISO_8859_1);
    }

    public ByteArrayBuffer getResponses(ByteArrayBuffer requestsBuffer, boolean keepOpen) throws Exception
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
        getThreadPool().dispatch(request);
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
        Request request = new Request(new ByteArrayBuffer(rawRequest, "UTF-8"), true, null);
        _requests.add(request);
    }

    private class Request implements Runnable
    {
        private final ByteArrayBuffer _requestsBuffer;
        private final boolean _keepOpen;
        private final CountDownLatch _latch;
        private volatile ByteArrayBuffer _responsesBuffer;

        private Request(ByteArrayBuffer requestsBuffer, boolean keepOpen, CountDownLatch latch)
        {
            _requestsBuffer = requestsBuffer;
            _keepOpen = keepOpen;
            _latch = latch;
        }

        public void run()
        {
            try
            {
                ByteArrayEndPoint endPoint = new ByteArrayEndPoint(_requestsBuffer.asArray(), 1024)
                {
                    @Override
                    public void setConnection(Connection connection)
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
                            final Connection con = endPoint.getConnection();
                            final Connection next = con.handle();
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
                    _responsesBuffer = endPoint.getOut();
                }
            }
            finally
            {
                if (_latch != null)
                    _latch.countDown();
            }
        }

        public ByteArrayBuffer getResponsesBuffer()
        {
            return _responsesBuffer;
        }
    }
}
