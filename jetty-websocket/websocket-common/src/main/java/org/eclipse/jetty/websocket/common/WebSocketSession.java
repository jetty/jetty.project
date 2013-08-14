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

package org.eclipse.jetty.websocket.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.CloseStatus;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.io.IOState;
import org.eclipse.jetty.websocket.common.io.IOState.ConnectionStateListener;

@ManagedObject("A Jetty WebSocket Session")
public class WebSocketSession extends ContainerLifeCycle implements Session, IncomingFrames, ConnectionStateListener
{
    private static final Logger LOG = Log.getLogger(WebSocketSession.class);
    private final URI requestURI;
    private final EventDriver websocket;
    private final LogicalConnection connection;
    private final Executor executor;
    private ExtensionFactory extensionFactory;
    private String protocolVersion;
    private Map<String, String[]> parameterMap = new HashMap<>();
    private WebSocketRemoteEndpoint remote;
    private IncomingFrames incomingHandler;
    private OutgoingFrames outgoingHandler;
    private WebSocketPolicy policy;
    private UpgradeRequest upgradeRequest;
    private UpgradeResponse upgradeResponse;

    public WebSocketSession(URI requestURI, EventDriver websocket, LogicalConnection connection)
    {
        if (requestURI == null)
        {
            throw new RuntimeException("Request URI cannot be null");
        }

        this.requestURI = requestURI;
        this.websocket = websocket;
        this.connection = connection;
        this.executor = connection.getExecutor();
        this.outgoingHandler = connection;
        this.incomingHandler = websocket;

        this.connection.getIOState().addListener(this);

        // Get the parameter map (use the jetty MultiMap to do this right)
        MultiMap<String> params = new MultiMap<>();
        String query = requestURI.getQuery();
        if (StringUtil.isNotBlank(query))
        {
            UrlEncoded.decodeTo(query,params,StringUtil.__UTF8_CHARSET,-1);
        }

        for (String name : params.keySet())
        {
            List<String> valueList = params.getValues(name);
            String valueArr[] = new String[valueList.size()];
            valueArr = valueList.toArray(valueArr);
            parameterMap.put(name,valueArr);
        }
    }

    @Override
    public void close()
    {
        this.close(StatusCode.NORMAL,null);
    }

    @Override
    public void close(CloseStatus closeStatus)
    {
        this.close(closeStatus.getCode(),closeStatus.getPhrase());
    }

    @Override
    public void close(int statusCode, String reason)
    {
        connection.close(statusCode,reason);
        notifyClose(statusCode,reason);
    }

    /**
     * Harsh disconnect
     */
    @Override
    public void disconnect()
    {
        connection.disconnect();

        // notify of harsh disconnect
        notifyClose(StatusCode.NO_CLOSE,"Harsh disconnect");
    }

    public void dispatch(Runnable runnable)
    {
        executor.execute(runnable);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        super.dump(out,indent);
        out.append(indent).append(" +- incomingHandler : ");
        if (incomingHandler instanceof Dumpable)
        {
            ((Dumpable)incomingHandler).dump(out,indent + "    ");
        }
        else
        {
            out.append(incomingHandler.toString()).append('\n');
        }

        out.append(indent).append(" +- outgoingHandler : ");
        if (outgoingHandler instanceof Dumpable)
        {
            ((Dumpable)outgoingHandler).dump(out,indent + "    ");
        }
        else
        {
            out.append(outgoingHandler.toString()).append('\n');
        }
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        WebSocketSession other = (WebSocketSession)obj;
        if (connection == null)
        {
            if (other.connection != null)
            {
                return false;
            }
        }
        else if (!connection.equals(other.connection))
        {
            return false;
        }
        return true;
    }

    public ByteBufferPool getBufferPool()
    {
        return this.connection.getBufferPool();
    }

    public LogicalConnection getConnection()
    {
        return connection;
    }

    public ExtensionFactory getExtensionFactory()
    {
        return extensionFactory;
    }

    /**
     * The idle timeout in milliseconds
     */
    @Override
    public long getIdleTimeout()
    {
        return connection.getMaxIdleTimeout();
    }

    @ManagedAttribute(readonly = true)
    public IncomingFrames getIncomingHandler()
    {
        return incomingHandler;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return connection.getLocalAddress();
    }

    @ManagedAttribute(readonly = true)
    public OutgoingFrames getOutgoingHandler()
    {
        return outgoingHandler;
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    public String getProtocolVersion()
    {
        return protocolVersion;
    }

    @Override
    public RemoteEndpoint getRemote()
    {
        ConnectionState state = connection.getIOState().getConnectionState();

        if ((state == ConnectionState.OPEN) || (state == ConnectionState.CONNECTED))
        {
            return remote;
        }

        throw new WebSocketException("RemoteEndpoint unavailable, current state [" + state + "], expecting [OPEN or CONNECTED]");
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return remote.getInetSocketAddress();
    }

    public URI getRequestURI()
    {
        return requestURI;
    }

    @Override
    public UpgradeRequest getUpgradeRequest()
    {
        return this.upgradeRequest;
    }

    @Override
    public UpgradeResponse getUpgradeResponse()
    {
        return this.upgradeResponse;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((connection == null)?0:connection.hashCode());
        return result;
    }

    /**
     * Incoming Errors from Parser
     */
    @Override
    public void incomingError(Throwable t)
    {
        if (connection.getIOState().isInputAvailable())
        {
            // Forward Errors to User WebSocket Object
            websocket.incomingError(t);
        }
    }

    /**
     * Incoming Raw Frames from Parser
     */
    @Override
    public void incomingFrame(Frame frame)
    {
        if (connection.getIOState().isInputAvailable())
        {
            // Forward Frames Through Extension List
            incomingHandler.incomingFrame(frame);
        }
    }

    @Override
    public boolean isOpen()
    {
        if (this.connection == null)
        {
            return false;
        }
        return this.connection.isOpen();
    }

    @Override
    public boolean isSecure()
    {
        if (upgradeRequest == null)
        {
            throw new IllegalStateException("No valid UpgradeRequest yet");
        }

        URI requestURI = upgradeRequest.getRequestURI();

        return "wss".equalsIgnoreCase(requestURI.getScheme());
    }

    public void notifyClose(int statusCode, String reason)
    {
        websocket.onClose(new CloseInfo(statusCode,reason));
    }

    public void notifyError(Throwable cause)
    {
        incomingError(cause);
    }

    @Override
    public void onConnectionStateChange(ConnectionState state)
    {
        if (state == ConnectionState.CLOSED)
        {
            IOState ioState = this.connection.getIOState();
            // The session only cares about abnormal close, as we need to notify
            // the endpoint of this close scenario.
            if (ioState.wasAbnormalClose())
            {
                CloseInfo close = ioState.getCloseInfo();
                LOG.debug("Detected abnormal close: {}",close);
                // notify local endpoint
                notifyClose(close.getStatusCode(),close.getReason());
            }
        }
    }

    /**
     * Open/Activate the session
     */
    public void open()
    {
        if (remote != null)
        {
            // already opened
            return;
        }

        // Upgrade success
        connection.getIOState().onConnected();

        // Connect remote
        remote = new WebSocketRemoteEndpoint(connection,outgoingHandler);

        // Open WebSocket
        websocket.openSession(this);

        // Open connection
        connection.getIOState().onOpened();

        if (LOG.isDebugEnabled())
        {
            LOG.debug("open -> {}",dump());
        }
    }

    public void setExtensionFactory(ExtensionFactory extensionFactory)
    {
        this.extensionFactory = extensionFactory;
    }

    /**
     * Set the timeout in milliseconds
     */
    @Override
    public void setIdleTimeout(long ms)
    {
        connection.setMaxIdleTimeout(ms);
    }

    public void setOutgoingHandler(OutgoingFrames outgoing)
    {
        this.outgoingHandler = outgoing;
    }

    public void setPolicy(WebSocketPolicy policy)
    {
        this.policy = policy;
    }

    public void setUpgradeRequest(UpgradeRequest request)
    {
        this.upgradeRequest = request;
        this.protocolVersion = request.getProtocolVersion();
    }

    public void setUpgradeResponse(UpgradeResponse response)
    {
        this.upgradeResponse = response;
    }

    @Override
    public SuspendToken suspend()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("WebSocketSession[");
        builder.append("websocket=").append(websocket);
        builder.append(",behavior=").append(policy.getBehavior());
        builder.append(",connection=").append(connection);
        builder.append(",remote=").append(remote);
        builder.append(",incoming=").append(incomingHandler);
        builder.append(",outgoing=").append(outgoingHandler);
        builder.append("]");
        return builder.toString();
    }
}
