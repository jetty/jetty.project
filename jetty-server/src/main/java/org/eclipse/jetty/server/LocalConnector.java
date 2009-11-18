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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.UpgradeConnectionException;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;

public class LocalConnector extends AbstractConnector
{
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<Request>();

    public Object getConnection()
    {
        return this;
    }

    /**
     * @deprecated Not needed anymore, as there is no need to reopen the connector to reset its state
     */
    @Deprecated
    public void reopen()
    {
    }

    public String getResponses(String requests) throws Exception
    {
        return getResponses(requests, false);
    }

    public String getResponses(String requests, boolean keepOpen) throws Exception
    {
        ByteArrayBuffer result = getResponses(new ByteArrayBuffer(requests, StringUtil.__ISO_8859_1), keepOpen);
        return result.toString(StringUtil.__ISO_8859_1);
    }

    public ByteArrayBuffer getResponses(ByteArrayBuffer requestsBuffer, boolean keepOpen) throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        Request request = new Request(requestsBuffer, keepOpen, latch);
        requests.add(request);
        latch.await();
        return request.getResponsesBuffer();
    }

    @Override
    protected void accept(int acceptorID) throws IOException, InterruptedException
    {
        Request request = requests.take();
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
        requests.add(request);
    }

    private class Request implements Runnable
    {
        private final ByteArrayBuffer requestsBuffer;
        private final boolean keepOpen;
        private final CountDownLatch latch;
        private volatile ByteArrayBuffer responsesBuffer;

        private Request(ByteArrayBuffer requestsBuffer, boolean keepOpen, CountDownLatch latch)
        {
            this.requestsBuffer = requestsBuffer;
            this.keepOpen = keepOpen;
            this.latch = latch;
        }

        public void run()
        {
            ByteArrayEndPoint endPoint = new ByteArrayEndPoint(requestsBuffer.asArray(), 1024)
            {
                @Override
                public void setConnection(Connection connection)
                {
                    connectionUpgraded(getConnection(),connection);
                    super.setConnection(connection);
                }
            };
            
            endPoint.setGrowOutput(true);
            HttpConnection connection = new HttpConnection(LocalConnector.this, endPoint, getServer());
            endPoint.setConnection(connection);
            connectionOpened(connection);
            
            boolean leaveOpen = keepOpen;
            try
            {
                while (endPoint.getIn().length() > 0)
                {
                    while(true)
                    {
                        try
                        {
                            endPoint.getConnection().handle();
                            break;
                        }
                        catch (UpgradeConnectionException e)
                        {
                            Log.debug(e.toString());
                            Log.ignore(e);
                            endPoint.setConnection(e.getConnection());
                        }
                    }
                }
            }
            catch (Exception x)
            {
                leaveOpen = false;
            }
            finally
            {
                if (!leaveOpen)
                    connectionClosed(connection);
                responsesBuffer = endPoint.getOut();
                if (latch != null)
                    latch.countDown();
            }
        }

        public ByteArrayBuffer getResponsesBuffer()
        {
            return responsesBuffer;
        }
    }
}
