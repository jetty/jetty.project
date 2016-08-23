//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.io;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.ClientUpgradeResponse;
import org.eclipse.jetty.websocket.common.AcceptHash;
import org.eclipse.jetty.websocket.common.SessionFactory;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.common.io.http.HttpResponseHeaderParser;
import org.eclipse.jetty.websocket.common.io.http.HttpResponseHeaderParser.ParseException;

/**
 * This is the initial connection handling that exists immediately after physical connection is established to
 * destination server.
 * <p>
 * Eventually, upon successful Upgrade request/response, this connection swaps itself out for the
 * WebSocektClientConnection handler.
 */
public class UpgradeConnection extends AbstractConnection implements Connection.UpgradeFrom
{
    public class SendUpgradeRequest extends FutureCallback implements Runnable
    {
        private final Logger LOG = Log.getLogger(UpgradeConnection.SendUpgradeRequest.class);
        
        @Override
        public void run()
        {
            URI uri = connectPromise.getRequest().getRequestURI();
            request.setRequestURI(uri);

            UpgradeListener handshakeListener = connectPromise.getUpgradeListener();
            if (handshakeListener != null)
            {
                handshakeListener.onHandshakeRequest(request);
            }

            String rawRequest = request.generate();

            ByteBuffer buf = BufferUtil.toBuffer(rawRequest,StandardCharsets.UTF_8);
            getEndPoint().write(this,buf);
        }

        @Override
        public void succeeded()
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Upgrade Request Write Success");
            }
            // Writing the request header is complete.
            super.succeeded();
            state = State.RESPONSE;
            // start the interest in fill
            fillInterested();
        }

        @Override
        public void failed(Throwable cause)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Upgrade Request Write Failure",cause);
            }
            super.failed(cause);
            state = State.FAILURE;
            // Fail the connect promise when a fundamental exception during connect occurs.
            connectPromise.failed(cause);
        }
    }

    /** HTTP Response Code: 101 Switching Protocols */
    private static final int SWITCHING_PROTOCOLS = 101;

    private enum State
    {
        REQUEST,
        RESPONSE,
        FAILURE,
        UPGRADE
    }

    private static final Logger LOG = Log.getLogger(UpgradeConnection.class);
    private final ByteBufferPool bufferPool;
    private final ConnectPromise connectPromise;
    private final HttpResponseHeaderParser parser;
    private State state = State.REQUEST;
    private ClientUpgradeRequest request;
    private ClientUpgradeResponse response;

    public UpgradeConnection(EndPoint endp, Executor executor, ConnectPromise connectPromise)
    {
        super(endp,executor);
        this.connectPromise = connectPromise;
        this.bufferPool = connectPromise.getClient().getBufferPool();
        this.request = connectPromise.getRequest();

        // Setup the parser
        this.parser = new HttpResponseHeaderParser(new ClientUpgradeResponse());
    }

    public void disconnect(boolean onlyOutput)
    {
        EndPoint endPoint = getEndPoint();
        // We need to gently close first, to allow
        // SSL close alerts to be sent by Jetty
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Shutting down output {}",endPoint);
        }
        
        endPoint.shutdownOutput();
        if (!onlyOutput)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Closing {}",endPoint);
            }
            endPoint.close();
        }
    }

    private void failUpgrade(Throwable cause)
    {
        close();
        connectPromise.failed(cause);
    }

    private void notifyConnect(ClientUpgradeResponse response)
    {
        connectPromise.setResponse(response);

        UpgradeListener handshakeListener = connectPromise.getUpgradeListener();
        if (handshakeListener != null)
        {
            handshakeListener.onHandshakeResponse(response);
        }
    }
    
    @Override
    public ByteBuffer onUpgradeFrom()
    {
        return connectPromise.getResponse().getRemainingBuffer();
    }

    @Override
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onFillable");
        }
        ByteBuffer buffer = bufferPool.acquire(getInputBufferSize(),false);
        BufferUtil.clear(buffer);
        try
        {
            read(buffer);
        }
        finally
        {
            bufferPool.release(buffer);
        }

        if (state == State.RESPONSE)
        {
            // Continue Reading
            fillInterested();
        }
        else if (state == State.UPGRADE)
        {
            // Stop Reading, upgrade the connection now
            upgradeConnection(response);
        }
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        getExecutor().execute(new SendUpgradeRequest());
    }

    @Override
    public void onClose()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Closed connection {}",this);
        }
        super.onClose();
    }

    @Override
    protected boolean onReadTimeout()
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Timeout on connection {}",this);
        }

        failUpgrade(new IOException("Timeout while performing WebSocket Upgrade"));

        return super.onReadTimeout();
    }

    /**
     * Read / Parse the waiting read/fill buffer
     * 
     * @param buffer
     *            the buffer to fill into from the endpoint
     */
    private void read(ByteBuffer buffer)
    {
        EndPoint endPoint = getEndPoint();
        try
        {
            while (true)
            {
                int filled = endPoint.fill(buffer);
                if (filled == 0)
                {
                    return;
                }
                else if (filled < 0)
                {
                    LOG.warn("read - EOF Reached");
                    state = State.FAILURE;
                    failUpgrade(new EOFException("Reading WebSocket Upgrade response"));
                    return;
                }
                else
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Filled {} bytes - {}",filled,BufferUtil.toDetailString(buffer));
                    }
                    response = (ClientUpgradeResponse)parser.parse(buffer);
                    if (response != null)
                    {
                        // Got a response!
                        validateResponse(response);
                        notifyConnect(response);
                        state = State.UPGRADE;
                        return; // do no more reading
                    }
                }
            }
        }
        catch (IOException | ParseException e)
        {
            LOG.ignore(e);
            state = State.FAILURE;
            UpgradeException ue = new UpgradeException(request.getRequestURI(),e);
            connectPromise.failed(ue);
            disconnect(false);
        }
        catch (UpgradeException e)
        {
            LOG.ignore(e);
            state = State.FAILURE;
            connectPromise.failed(e);
            disconnect(false);
        }
    }

    private void upgradeConnection(ClientUpgradeResponse response)
    {
        EndPoint endp = getEndPoint();
        Executor executor = getExecutor();

        Object websocket = connectPromise.getWebSocketEndpoint();
        WebSocketPolicy policy = connectPromise.getClient().getPolicy();

        // Establish Connection
        WebSocketClientConnection connection = new WebSocketClientConnection(endp,executor,connectPromise,policy);

        // Create WebSocket Session
        SessionFactory sessionFactory = connectPromise.getClient().getSessionFactory();
        WebSocketSession session = sessionFactory.createSession(request.getRequestURI(),websocket,policy,connection);
        session.setUpgradeRequest(request);
        session.setUpgradeResponse(response);

        // Wire up Session <-> Connection
        connection.addListener(session);
        connectPromise.setSession(session);

        // Initialize / Negotiate Extensions
        ExtensionStack extensionStack = new ExtensionStack(connectPromise.getClient().getExtensionFactory());
        extensionStack.negotiate(response.getExtensions());

        extensionStack.configure(connection.getParser());
        extensionStack.configure(connection.getGenerator());

        // Setup Incoming Routing
        connection.setNextIncomingFrames(extensionStack);
        extensionStack.setNextIncoming(session);

        // Setup Outgoing Routing
        session.setOutgoingHandler(extensionStack);
        extensionStack.setNextOutgoing(connection);

        session.addManaged(extensionStack);
        connectPromise.getClient().addManaged(session);

        // Now swap out the connection
        endp.upgrade(connection);
    }

    private void validateResponse(ClientUpgradeResponse response)
    {
        // Validate Response Status Code
        if (response.getStatusCode() != SWITCHING_PROTOCOLS)
        {
            // TODO: use jetty-http and org.eclipse.jetty.http.HttpStatus for more meaningful exception messages 
            throw new UpgradeException(request.getRequestURI(),response.getStatusCode(),"Didn't switch protocols, expected status <" + SWITCHING_PROTOCOLS
                    + ">, but got <" + response.getStatusCode() + ">");
        }

        // Validate Connection header
        String connection = response.getHeader("Connection");
        if (!"upgrade".equalsIgnoreCase(connection))
        {
            throw new UpgradeException(request.getRequestURI(),response.getStatusCode(),"Connection is " + connection + " (expected upgrade)");
        }

        // Check the Accept hash
        String reqKey = request.getKey();
        String expectedHash = AcceptHash.hashKey(reqKey);
        String respHash = response.getHeader("Sec-WebSocket-Accept");

        response.setSuccess(true);
        if (expectedHash.equalsIgnoreCase(respHash) == false)
        {
            response.setSuccess(false);
            throw new UpgradeException(request.getRequestURI(),response.getStatusCode(),"Invalid Sec-WebSocket-Accept hash");
        }

        // Parse extensions
        List<ExtensionConfig> extensions = new ArrayList<>();
        List<String> extValues = response.getHeaders("Sec-WebSocket-Extensions");
        if (extValues != null)
        {
            for (String extVal : extValues)
            {
                // TODO use QuotedCSV ???
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
