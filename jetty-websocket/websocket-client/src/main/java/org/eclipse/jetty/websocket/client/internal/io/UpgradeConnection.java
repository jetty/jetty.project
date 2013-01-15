//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.util.List;
import java.util.concurrent.Executor;

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
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Extension;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.ClientUpgradeResponse;
import org.eclipse.jetty.websocket.client.internal.ConnectPromise;
import org.eclipse.jetty.websocket.common.AcceptHash;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.EventDriver;

/**
 * This is the initial connection handling that exists immediately after physical connection is established to destination server.
 * <p>
 * Eventually, upon successful Upgrade request/response, this connection swaps itself out for the WebSocektClientConnection handler.
 */
public class UpgradeConnection extends AbstractConnection
{
    public class SendUpgradeRequest extends FutureCallback implements Runnable
    {
        @Override
        public void run()
        {
            URI uri = connectPromise.getRequest().getRequestURI();
            request.setRequestURI(uri);
            String rawRequest = request.generate();

            ByteBuffer buf = BufferUtil.toBuffer(rawRequest,StringUtil.__UTF8_CHARSET);
            getEndPoint().write(this,buf);
        }

        @Override
        public void succeeded()
        {
            // Writing the request header is complete.
            super.succeeded();
            // start the interest in fill
            fillInterested();
        }
    }

    private static final Logger LOG = Log.getLogger(UpgradeConnection.class);
    private final ByteBufferPool bufferPool;
    private final ConnectPromise connectPromise;
    private final HttpResponseHeaderParser parser;
    private ClientUpgradeRequest request;

    public UpgradeConnection(EndPoint endp, Executor executor, ConnectPromise connectPromise)
    {
        super(endp,executor);
        this.connectPromise = connectPromise;
        this.bufferPool = connectPromise.getClient().getBufferPool();
        this.parser = new HttpResponseHeaderParser();

        try
        {
            this.request = connectPromise.getRequest();
        }
        catch (ClassCastException e)
        {
            connectPromise.failed(new RuntimeException("Invalid Upgrade Request structure",e));
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

    private void notifyConnect(ClientUpgradeResponse response)
    {
        connectPromise.setResponse(response);
    }

    @Override
    public void onFillable()
    {
        ByteBuffer buffer = bufferPool.acquire(getInputBufferSize(),false);
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
                    ClientUpgradeResponse resp = parser.parse(buffer);
                    if (resp != null)
                    {
                        // Got a response!
                        validateResponse(resp);
                        notifyConnect(resp);
                        upgradeConnection(resp);
                        return false; // do no more reading
                    }
                }
            }
        }
        catch (IOException e)
        {
            connectPromise.failed(e);
            disconnect(false);
            return false;
        }
        catch (UpgradeException e)
        {
            connectPromise.failed(e);
            disconnect(false);
            return false;
        }
    }

    private void upgradeConnection(ClientUpgradeResponse response)
    {
        EndPoint endp = getEndPoint();
        Executor executor = getExecutor();
        WebSocketClientConnection connection = new WebSocketClientConnection(endp,executor,connectPromise);

        // Initialize / Negotiate Extensions
        EventDriver websocket = connectPromise.getDriver();
        WebSocketPolicy policy = connectPromise.getClient().getPolicy();
        String acceptedSubProtocol = response.getAcceptedSubProtocol();

        WebSocketSession session = new WebSocketSession(request.getRequestURI(),websocket,connection);
        session.setPolicy(policy);
        session.setNegotiatedSubprotocol(acceptedSubProtocol);

        connection.setSession(session);
        List<Extension> extensions = connectPromise.getClient().initExtensions(response.getExtensions());

        // Start with default routing.
        IncomingFrames incoming = session;
        // OutgoingFrames outgoing = connection;

        // Connect extensions
        if (extensions != null)
        {
            connection.getParser().configureFromExtensions(extensions);
            connection.getGenerator().configureFromExtensions(extensions);

            // FIXME
            // Iterator<Extension> extIter;
            // // Connect outgoings
            // extIter = extensions.iterator();
            // while (extIter.hasNext())
            // {
            // Extension ext = extIter.next();
            // ext.setNextOutgoingFrames(outgoing);
            // outgoing = ext;
            // }
            //
            // // Connect incomings
            // Collections.reverse(extensions);
            // extIter = extensions.iterator();
            // while (extIter.hasNext())
            // {
            // Extension ext = extIter.next();
            // ext.setNextIncomingFrames(incoming);
            // incoming = ext;
            // }
        }

        // configure session for outgoing flows
        // session.setOutgoing(outgoing);
        // configure connection for incoming flows
        connection.getParser().setIncomingFramesHandler(incoming);

        // Now swap out the connection
        endp.setConnection(connection);
        connection.onOpen();
    }

    private void validateResponse(ClientUpgradeResponse response)
    {
        // Check the Accept hash
        String reqKey = request.getKey();
        String expectedHash = AcceptHash.hashKey(reqKey);
        response.validateWebSocketHash(expectedHash);

        // Parse extensions
        List<ExtensionConfig> extensions = new ArrayList<>();
        List<String> extValues = response.getHeaders("Sec-WebSocket-Extensions");
        if (extValues != null)
        {
            for (String extVal : extValues)
            {
                QuotedStringTokenizer tok = new QuotedStringTokenizer(extVal,",");
                while (tok.hasMoreTokens())
                {
                    extensions.add(ExtensionConfig.parse(tok.nextToken()));
                }
            }
        }
        response.setExtensions(extensions);
    }
}
