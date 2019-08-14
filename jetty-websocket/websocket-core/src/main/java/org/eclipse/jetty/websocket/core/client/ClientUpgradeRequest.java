//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpConversation;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.HttpUpgrader;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.UpgradeException;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.internal.ExtensionStack;
import org.eclipse.jetty.websocket.core.internal.Negotiated;
import org.eclipse.jetty.websocket.core.internal.WebSocketConnection;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;

public abstract class ClientUpgradeRequest extends HttpRequest implements Response.CompleteListener, HttpUpgrader.Factory
{
    public static ClientUpgradeRequest from(WebSocketCoreClient webSocketClient, URI requestURI, FrameHandler frameHandler)
    {
        return new ClientUpgradeRequest(webSocketClient, requestURI)
        {
            @Override
            public FrameHandler getFrameHandler()
            {
                return frameHandler;
            }
        };
    }

    private static final Logger LOG = Log.getLogger(ClientUpgradeRequest.class);
    protected final CompletableFuture<FrameHandler.CoreSession> futureCoreSession;
    private final WebSocketCoreClient wsClient;
    private FrameHandler frameHandler;
    private FrameHandler.ConfigurationCustomizer customizer = new FrameHandler.ConfigurationCustomizer();
    private List<UpgradeListener> upgradeListeners = new ArrayList<>();

    public ClientUpgradeRequest(WebSocketCoreClient webSocketClient, URI requestURI)
    {
        super(webSocketClient.getHttpClient(), new HttpConversation(), requestURI);

        // Validate websocket URI
        if (!requestURI.isAbsolute())
        {
            throw new IllegalArgumentException("WebSocket URI must be absolute");
        }

        if (StringUtil.isBlank(requestURI.getScheme()))
        {
            throw new IllegalArgumentException("WebSocket URI must include a scheme");
        }

        String scheme = requestURI.getScheme();
        if (!HttpScheme.WS.is(scheme) && !HttpScheme.WSS.is(scheme))
        {
            throw new IllegalArgumentException("WebSocket URI scheme only supports [ws] and [wss], not [" + scheme + "]");
        }

        if (requestURI.getHost() == null)
        {
            throw new IllegalArgumentException("Invalid WebSocket URI: host not present");
        }

        this.wsClient = webSocketClient;
        this.futureCoreSession = new CompletableFuture<>();
    }

    public void setConfiguration(FrameHandler.ConfigurationCustomizer config)
    {
        config.customize(customizer);
    }

    public void addListener(UpgradeListener listener)
    {
        upgradeListeners.add(listener);
    }

    public void addExtensions(ExtensionConfig... configs)
    {
        HttpFields headers = getHeaders();
        for (ExtensionConfig config : configs)
        {
            headers.add(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, config.getParameterizedName());
        }
    }

    public void addExtensions(String... configs)
    {
        HttpFields headers = getHeaders();
        for (String config : configs)
        {
            headers.add(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, ExtensionConfig.parse(config).getParameterizedName());
        }
    }

    public List<ExtensionConfig> getExtensions()
    {
        return getHeaders().getCSV(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, true)
            .stream()
            .map(ExtensionConfig::parse)
            .collect(Collectors.toList());
    }

    public void setExtensions(List<ExtensionConfig> configs)
    {
        HttpFields headers = getHeaders();
        headers.remove(HttpHeader.SEC_WEBSOCKET_EXTENSIONS);
        for (ExtensionConfig config : configs)
        {
            headers.add(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, config.getParameterizedName());
        }
    }

    public List<String> getSubProtocols()
    {
        return getHeaders().getCSV(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, true);
    }

    public void setSubProtocols(String... protocols)
    {
        HttpFields headers = getHeaders();
        headers.remove(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
        for (String protocol : protocols)
        {
            headers.add(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, protocol);
        }
    }

    public void setSubProtocols(List<String> protocols)
    {
        HttpFields headers = getHeaders();
        headers.remove(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
        for (String protocol : protocols)
        {
            headers.add(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, protocol);
        }
    }

    @Override
    public void send(final Response.CompleteListener listener)
    {
        frameHandler = getFrameHandler();
        if (frameHandler == null)
            throw new IllegalArgumentException("FrameHandler could not be created");
        initWebSocketHeaders();
        super.send(listener);
    }

    public CompletableFuture<FrameHandler.CoreSession> sendAsync()
    {
        send(this);
        return futureCoreSession;
    }

    @SuppressWarnings("Duplicates")
    @Override
    public void onComplete(Result result)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onComplete() - {}", result);
        }

        URI requestURI = result.getRequest().getURI();
        Response response = result.getResponse();
        int responseStatusCode = response.getStatus();
        String responseLine = responseStatusCode + " " + response.getReason();

        if (result.isFailed())
        {
            if (LOG.isDebugEnabled())
            {
                if (result.getFailure() != null)
                    LOG.debug("General Failure", result.getFailure());
                if (result.getRequestFailure() != null)
                    LOG.debug("Request Failure", result.getRequestFailure());
                if (result.getResponseFailure() != null)
                    LOG.debug("Response Failure", result.getResponseFailure());
            }

            Throwable failure = result.getFailure();
            boolean wrapFailure = !((failure instanceof java.net.SocketException) ||
                (failure instanceof java.io.InterruptedIOException) ||
                (failure instanceof UpgradeException));
            if (wrapFailure)
                failure = new UpgradeException(requestURI, responseStatusCode, responseLine, failure);
            handleException(failure);
        }

        if (responseStatusCode != HttpStatus.SWITCHING_PROTOCOLS_101)
        {
            // Failed to upgrade (other reason)
            handleException(
                new UpgradeException(requestURI, responseStatusCode, "Failed to upgrade to websocket: Unexpected HTTP Response Status Code: " + responseLine));
        }
    }

    protected void handleException(Throwable failure)
    {
        futureCoreSession.completeExceptionally(failure);
        if (frameHandler != null)
        {
            try
            {
                frameHandler.onError(failure, Callback.NOOP);
            }
            catch (Throwable t)
            {
                LOG.warn("FrameHandler onError threw", t);
            }
        }
    }

    @Override
    public HttpUpgrader newHttpUpgrader(HttpVersion version)
    {
        if (version == HttpVersion.HTTP_1_1)
            return new HttpUpgraderOverHTTP(this);
        else if (version == HttpVersion.HTTP_2)
            return new HttpUpgraderOverHTTP2(this);
        else
            throw new UnsupportedOperationException("Unsupported HTTP version for upgrade: " + version);
    }

    /**
     * Allow for overridden customization of endpoint (such as special transport level properties: e.g. TCP keepAlive)
     */
    protected void customize(EndPoint endPoint)
    {
    }

    protected WebSocketConnection newWebSocketConnection(EndPoint endPoint, Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, WebSocketCoreSession coreSession)
    {
        return new WebSocketConnection(endPoint, executor, scheduler, byteBufferPool, coreSession);
    }

    protected WebSocketCoreSession newWebSocketCoreSession(FrameHandler handler, Negotiated negotiated)
    {
        return new WebSocketCoreSession(handler, Behavior.CLIENT, negotiated);
    }

    public abstract FrameHandler getFrameHandler();

    private void initWebSocketHeaders()
    {
        notifyUpgradeListeners((listener) -> listener.onHandshakeRequest(this));
    }

    private void notifyUpgradeListeners(Consumer<UpgradeListener> action)
    {
        for (UpgradeListener listener : upgradeListeners)
        {
            try
            {
                action.accept(listener);
            }
            catch (Throwable t)
            {
                LOG.info("Exception while invoking listener " + listener, t);
            }
        }
    }

    public void upgrade(HttpResponse response, EndPoint endPoint)
    {
        // Parse the Negotiated Extensions
        List<ExtensionConfig> negotiatedExtensions = new ArrayList<>();
        HttpField extField = response.getHeaders().getField(HttpHeader.SEC_WEBSOCKET_EXTENSIONS);
        if (extField != null)
        {
            String[] extValues = extField.getValues();
            if (extValues != null)
            {
                for (String extVal : extValues)
                {
                    QuotedStringTokenizer tok = new QuotedStringTokenizer(extVal, ",");
                    while (tok.hasMoreTokens())
                    {
                        negotiatedExtensions.add(ExtensionConfig.parse(tok.nextToken()));
                    }
                }
            }
        }

        // Verify the Negotiated Extensions
        List<ExtensionConfig> offeredExtensions = getExtensions();
        for (ExtensionConfig config : negotiatedExtensions)
        {
            if (config.getName().startsWith("@"))
                continue;

            long numMatch = offeredExtensions.stream().filter(c -> config.getName().equalsIgnoreCase(c.getName())).count();
            if (numMatch < 1)
                throw new WebSocketException("Upgrade failed: Sec-WebSocket-Extensions contained extension not requested");

            numMatch = negotiatedExtensions.stream().filter(c -> config.getName().equalsIgnoreCase(c.getName())).count();
            if (numMatch > 1)
                throw new WebSocketException("Upgrade failed: Sec-WebSocket-Extensions contained more than one extension of the same name");
        }

        // Negotiate the extension stack
        ExtensionStack extensionStack = new ExtensionStack(wsClient.getWebSocketComponents(), Behavior.CLIENT);
        extensionStack.negotiate(offeredExtensions, negotiatedExtensions);

        // Get the negotiated subprotocol
        String negotiatedSubProtocol = null;
        HttpField subProtocolField = response.getHeaders().getField(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
        if (subProtocolField != null)
        {
            String[] values = subProtocolField.getValues();
            if (values != null)
            {
                if (values.length > 1)
                    throw new WebSocketException("Upgrade failed: Too many WebSocket subprotocol's in response: " + Arrays.toString(values));
                else if (values.length == 1)
                    negotiatedSubProtocol = values[0];
            }
        }

        // Verify the negotiated subprotocol
        List<String> offeredSubProtocols = getSubProtocols();
        if (negotiatedSubProtocol == null && !offeredSubProtocols.isEmpty())
            throw new WebSocketException("Upgrade failed: no subprotocol selected from offered subprotocols ");
        if (negotiatedSubProtocol != null && !offeredSubProtocols.contains(negotiatedSubProtocol))
            throw new WebSocketException("Upgrade failed: subprotocol [" + negotiatedSubProtocol + "] not found in offered subprotocols " + offeredSubProtocols);

        // We can upgrade
        customize(endPoint);

        Request request = response.getRequest();
        Negotiated negotiated = new Negotiated(
            request.getURI(),
            negotiatedSubProtocol,
            HttpScheme.HTTPS.is(request.getScheme()), // TODO better than this?
            extensionStack,
            WebSocketConstants.SPEC_VERSION_STRING);

        WebSocketCoreSession coreSession = newWebSocketCoreSession(frameHandler, negotiated);
        customizer.customize(coreSession);

        HttpClient httpClient = wsClient.getHttpClient();
        WebSocketConnection wsConnection = newWebSocketConnection(endPoint, httpClient.getExecutor(), httpClient.getScheduler(), httpClient.getByteBufferPool(), coreSession);

        for (Connection.Listener listener : wsClient.getBeans(Connection.Listener.class))
        {
            wsConnection.addListener(listener);
        }

        coreSession.setWebSocketConnection(wsConnection);

        notifyUpgradeListeners((listener) -> listener.onHandshakeResponse(this, response));

        // Now swap out the connection
        try
        {
            endPoint.upgrade(wsConnection);
            futureCoreSession.complete(coreSession);
        }
        catch (Throwable t)
        {
            futureCoreSession.completeExceptionally(t);
        }
    }
}
