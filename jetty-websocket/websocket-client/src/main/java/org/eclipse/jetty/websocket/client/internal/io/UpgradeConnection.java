//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.internal.io;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.client.internal.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.internal.IWebSocketClient;
import org.eclipse.jetty.websocket.protocol.AcceptHash;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;

/**
 * This is the initial connection handling that exists immediately after physical connection is established to destination server.
 * <p>
 * Eventually, upon successful Upgrade request/response, this connection swaps itself out for the WebSocektClientConnection handler.
 */
public class UpgradeConnection extends AbstractConnection
{
    public class SendUpgradeRequest extends FutureCallback<String> implements Runnable
    {
        @Override
        public void completed(String context)
        {
            // Writing the request header is complete.
            super.completed(context);
            // start the interest in fill
            fillInterested();
        }

        @Override
        public void run()
        {
            URI uri = client.getWebSocketUri();
            String rawRequest = request.generate(uri);

            ByteBuffer buf = BufferUtil.toBuffer(rawRequest,StringUtil.__UTF8_CHARSET);
            getEndPoint().write("REQ",this,buf);
        }
    }

    private static final Logger LOG = Log.getLogger(UpgradeConnection.class);
    private final ByteBufferPool bufferPool;
    private final ScheduledExecutorService scheduler;
    private final IWebSocketClient client;
    private final HttpResponseHeaderParser parser;
    private ClientUpgradeRequest request;

    public UpgradeConnection(EndPoint endp, Executor executor, IWebSocketClient client)
    {
        super(endp,executor);
        this.client = client;
        this.bufferPool = client.getFactory().getBufferPool();
        this.scheduler = client.getFactory().getScheduler();
        this.parser = new HttpResponseHeaderParser();

        try
        {
            this.request = (ClientUpgradeRequest)client.getUpgradeRequest();
        }
        catch (ClassCastException e)
        {
            client.failed(null,new RuntimeException("Invalid Upgrade Request structure",e));
        }
    }

    public void disconnect(boolean onlyOutput)
    {
        EndPoint endPoint = getEndPoint();
        // We need to gently close first, to allow
        // SSL close alerts to be sent by Jetty
        LOG.debug("Shutting down output {}",endPoint);
        endPoint.shutdownOutput();
        if (!onlyOutput)
        {
            LOG.debug("Closing {}",endPoint);
            endPoint.close();
        }
    }

    private void notifyConnect()
    {
        client.completed(client.getUpgradeResponse());
    }

    @Override
    public void onFillable()
    {
        int bufSize = client.getPolicy().getBufferSize();
        ByteBuffer buffer = bufferPool.acquire(bufSize,false);
        BufferUtil.clear(buffer);
        boolean readMore = false;
        try
        {
            readMore = read(buffer);
        }
        finally
        {
            bufferPool.release(buffer);
        }

        if (readMore)
        {
            fillInterested();
        }
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        // TODO: handle timeout
        getExecutor().execute(new SendUpgradeRequest());
    }

    /**
     * Read / Parse the waiting read/fill buffer
     * 
     * @param buffer
     *            the buffer to fill into from the endpoint
     * @return true if there is more to read, false if reading should stop
     */
    private boolean read(ByteBuffer buffer)
    {
        EndPoint endPoint = getEndPoint();
        try
        {
            while (true)
            {
                int filled = endPoint.fill(buffer);
                if (filled == 0)
                {
                    return true;
                }
                else if (filled < 0)
                {
                    LOG.debug("read - EOF Reached");
                    return false;
                }
                else
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Filled {} bytes - {}",filled,BufferUtil.toDetailString(buffer));
                    }
                    UpgradeResponse resp = parser.parse(buffer);
                    if (resp != null)
                    {
                        // Got a response!
                        client.setUpgradeResponse(resp);
                        validateResponse(resp);
                        notifyConnect();
                        upgradeConnection();
                        return false; // do no more reading
                    }
                }
            }
        }
        catch (IOException e)
        {
            LOG.warn(e);
            client.failed(null,e);
            disconnect(false);
            return false;
        }
        catch (UpgradeException e)
        {
            LOG.warn(e);
            client.failed(null,e);
            disconnect(false);
            return false;
        }
    }

    private void upgradeConnection()
    {
        EndPoint endp = getEndPoint();
        Executor executor = getExecutor();
        WebSocketClientConnection conn = new WebSocketClientConnection(endp,executor,client);
        endp.setConnection(conn);
    }

    private void validateResponse(UpgradeResponse response)
    {
        // Check the Accept hash
        String reqKey = request.getKey();
        String expectedHash = AcceptHash.hashKey(reqKey);
        response.validateWebSocketHash(expectedHash);

        // Parse extensions
        List<ExtensionConfig> extensions = new ArrayList<>();
        Iterator<String> extIter = response.getHeaderValues("Sec-WebSocket-Extensions");
        while (extIter.hasNext())
        {
            String extVal = extIter.next();
            QuotedStringTokenizer tok = new QuotedStringTokenizer(extVal,",");
            while (tok.hasMoreTokens())
            {
                extensions.add(ExtensionConfig.parse(tok.nextToken()));
            }
        }
        response.setExtensions(extensions);
    }
}
