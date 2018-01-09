//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rhttp.connector;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.rhttp.client.RHTTPClient;
import org.eclipse.jetty.rhttp.client.RHTTPListener;
import org.eclipse.jetty.rhttp.client.RHTTPRequest;
import org.eclipse.jetty.rhttp.client.RHTTPResponse;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.BlockingHttpConnection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An implementation of a Jetty connector that uses a {@link RHTTPClient} connected
 * to a gateway server to receive requests, feed them to the Jetty server, and
 * forward responses from the Jetty server to the gateway server.
 *
 * @version $Revision$ $Date$
 */
public class ReverseHTTPConnector extends AbstractConnector implements RHTTPListener
{
    private static final Logger LOG = Log.getLogger(ReverseHTTPConnector.class);

    private final BlockingQueue<RHTTPRequest> requests = new LinkedBlockingQueue<RHTTPRequest>();
    private final RHTTPClient client;

    public ReverseHTTPConnector(RHTTPClient client)
    {
        this.client = client;
        super.setHost(client.getHost());
        super.setPort(client.getPort());
    }

    @Override
    public void setHost(String host)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setPort(int port)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doStart() throws Exception
    {
        if (client instanceof LifeCycle)
            ((LifeCycle)client).start();
        super.doStart();
        client.connect();
    }

    @Override
    protected void doStop() throws Exception
    {
        client.disconnect();
        super.doStop();
        if (client instanceof LifeCycle)
            ((LifeCycle)client).stop();
    }

    public void open()
    {
        client.addListener(this);
    }

    public void close()
    {
        client.removeListener(this);
    }

    public int getLocalPort()
    {
        return -1;
    }

    public Object getConnection()
    {
        return this;
    }

    @Override
    protected void accept(int acceptorId) throws IOException, InterruptedException
    {
        RHTTPRequest request = requests.take();
        IncomingRequest incomingRequest = new IncomingRequest(request);
        getThreadPool().dispatch(incomingRequest);
    }

    @Override
    public void persist(EndPoint endpoint) throws IOException
    {
        // Signals that the connection should not be closed
        // Do nothing in this case, as we run from memory
    }

    public void onRequest(RHTTPRequest request) throws Exception
    {
        requests.add(request);
    }

    private class IncomingRequest implements Runnable
    {
        private final RHTTPRequest request;

        private IncomingRequest(RHTTPRequest request)
        {
            this.request = request;
        }

        public void run()
        {
            byte[] requestBytes = request.getRequestBytes();

            ByteArrayEndPoint endPoint = new ByteArrayEndPoint(requestBytes, 1024);
            endPoint.setGrowOutput(true);

            AbstractHttpConnection connection = new BlockingHttpConnection(ReverseHTTPConnector.this, endPoint, getServer());
            
            connectionOpened(connection);
            try
            {
                // Loop over the whole content, since handle() only
                // reads up to the connection buffer's capacities
                while (endPoint.getIn().length() > 0)
                    connection.handle();

                byte[] responseBytes = endPoint.getOut().asArray();
                RHTTPResponse response = RHTTPResponse.fromResponseBytes(request.getId(), responseBytes);
                client.deliver(response);
            }
            catch (Exception x)
            {
                LOG.debug(x);
            }
            finally
            {
                connectionClosed(connection);
            }
        }
    }
}
