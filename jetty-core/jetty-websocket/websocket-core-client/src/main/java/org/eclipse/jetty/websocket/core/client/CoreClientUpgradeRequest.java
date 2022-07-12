//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.client;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.client.internal.HttpUpgraderOverHTTP;
import org.eclipse.jetty.websocket.core.client.internal.HttpUpgraderOverHTTP2;
import org.eclipse.jetty.websocket.core.exception.UpgradeException;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.core.internal.ExtensionStack;
import org.eclipse.jetty.websocket.core.internal.Negotiated;
import org.eclipse.jetty.websocket.core.internal.WebSocketConnection;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CoreClientUpgradeRequest extends HttpRequest implements Response.CompleteListener, HttpUpgrader.Factory
{
    public static CoreClientUpgradeRequest from(WebSocketCoreClient webSocketClient, URI requestURI, FrameHandler frameHandler)
    {
        return new CoreClientUpgradeRequest(webSocketClient, requestURI)
        {
            @Override
            public FrameHandler getFrameHandler()
            {
                return frameHandler;
            }
        };
    }

    private static final Logger LOG = LoggerFactory.getLogger(CoreClientUpgradeRequest.class);
    protected final CompletableFuture<CoreSession> futureCoreSession;
    private final WebSocketCoreClient wsClient;
    private FrameHandler frameHandler;
    private final Configuration.ConfigurationCustomizer customizer = new Configuration.ConfigurationCustomizer();
    private final List<UpgradeListener> upgradeListeners = new ArrayList<>();
    private List<ExtensionConfig> requestedExtensions = new ArrayList<>();

    public CoreClientUpgradeRequest(WebSocketCoreClient webSocketClient, URI requestURI)
    {
        super(webSocketClient.getHttpClient(), new HttpConversation(), requestURI);

        // Validate websocket URI
        if (!requestURI.isAbsolute())
            throw new IllegalArgumentException("WebSocket URI must be absolute");

        if (StringUtil.isBlank(requestURI.getScheme()))
            throw new IllegalArgumentException("WebSocket URI must include a scheme");

        String scheme = requestURI.getScheme();
        if (!HttpScheme.WS.is(scheme) && !HttpScheme.WSS.is(scheme))
            throw new IllegalArgumentException("WebSocket URI scheme only supports [ws] and [wss], not [" + scheme + "]");

        if (requestURI.getHost() == null)
            throw new IllegalArgumentException("Invalid WebSocket URI: host not present");

        this.wsClient = webSocketClient;
        this.futureCoreSession = new CompletableFuture<>();
        this.futureCoreSession.whenComplete((session, throwable) ->
        {
            if (throwable != null)
                abort(throwable);
        });
    }

    public void setConfiguration(Configuration.ConfigurationCustomizer config)
    {
        config.customize(customizer);
    }

    public void addListener(UpgradeListener listener)
    {
        upgradeListeners.add(listener);
    }

    public void addExtensions(ExtensionConfig... configs)
    {
        requestedExtensions.addAll(Arrays.asList(configs));
    }

    public void addExtensions(String... configs)
    {
        for (String config : configs)
        {
            requestedExtensions.add(ExtensionConfig.parse(config));
        }
    }

    public List<ExtensionConfig> getExtensions()
    {
        return requestedExtensions;
    }

    public void setExtensions(List<ExtensionConfig> configs)
    {
        requestedExtensions = configs;
    }

    public List<String> getSubProtocols()
    {
        return getHeaders().getCSV(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, true);
    }

    public void setSubProtocols(String... protocols)
    {
        headers(headers ->
        {
            headers.remove(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
            for (String protocol : protocols)
            {
                headers.add(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, protocol);
            }
        });
    }

    public void setSubProtocols(List<String> protocols)
    {
        headers(headers ->
        {
            headers.remove(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
            for (String protocol : protocols)
            {
                headers.add(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, protocol);
            }
        });
    }

    @Override
    public void send(final Response.CompleteListener listener)
    {
        try
        {
            frameHandler = getFrameHandler();
            if (frameHandler == null)
                throw new IllegalArgumentException("FrameHandler could not be created");
        }
        catch (Throwable t)
        {
            throw new IllegalArgumentException("FrameHandler could not be created", t);
        }

        super.send(listener);
    }

    public CompletableFuture<CoreSession> sendAsync()
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
            boolean wrapFailure = !(failure instanceof IOException) && !(failure instanceof UpgradeException);
            if (wrapFailure)
                failure = new UpgradeException(requestURI, responseStatusCode, responseLine, failure);
            handleException(failure);
            return;
        }

        if (responseStatusCode != HttpStatus.SWITCHING_PROTOCOLS_101)
        {
            // Failed to upgrade (other reason)
            handleException(new UpgradeException(requestURI, responseStatusCode,
                "Failed to upgrade to websocket: Unexpected HTTP Response Status Code: " + responseLine));
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

    public abstract FrameHandler getFrameHandler();

    public void requestComplete()
    {
        // Add extensions header filtering out internal extensions and internal parameters.
        String extensionString = requestedExtensions.stream()
            .filter(ec -> !ec.getName().startsWith("@"))
            .map(ExtensionConfig::getParameterizedNameWithoutInternalParams)
            .collect(Collectors.joining(","));

        if (!StringUtil.isEmpty(extensionString))
            headers(headers -> headers.add(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, extensionString));

        // Notify the listener which may change the headers directly.
        Exception listenerError = notifyUpgradeListeners((listener) -> listener.onHandshakeRequest(this));
        if (listenerError != null)
        {
            abort(listenerError);
            return;
        }

        // Check if extensions were set in the headers from the upgrade listener.
        String extsAfterListener = String.join(",", getHeaders().getCSV(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, true));
        if (!extensionString.equals(extsAfterListener))
        {
            // If extensions were set in both the ClientUpgradeRequest and UpgradeListener throw ISE.
            if (!requestedExtensions.isEmpty())
                abort(new IllegalStateException("Extensions set in both the ClientUpgradeRequest and UpgradeListener"));

            // Otherwise reparse the new set of requested extensions.
            requestedExtensions = ExtensionConfig.parseList(extsAfterListener);
        }
    }

    private Exception notifyUpgradeListeners(Consumer<UpgradeListener> action)
    {
        MultiException multiException = null;
        for (UpgradeListener listener : upgradeListeners)
        {
            try
            {
                action.accept(listener);
            }
            catch (Throwable t)
            {
                LOG.info("Exception while invoking listener {}", listener, t);
                if (multiException == null)
                     multiException = new MultiException();
                multiException.add(t);
            }
        }

        return multiException;
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

        // Get list of negotiated extensions with internal extensions in the correct order.
        List<ExtensionConfig> negotiatedWithInternal = new ArrayList<>(requestedExtensions);
        for (Iterator<ExtensionConfig> iterator = negotiatedWithInternal.iterator(); iterator.hasNext();)
        {
            ExtensionConfig extConfig = iterator.next();

            // Always keep internal extensions.
            if (extConfig.isInternalExtension())
                continue;

            // If it was not negotiated by the server remove.
            long negExtsCount = negotiatedExtensions.stream().filter(ec -> extConfig.getName().equals(ec.getName())).count();
            if (negExtsCount < 1)
            {
                iterator.remove();
                continue;
            }

            // Remove if we have duplicates.
            long duplicateCount = negotiatedWithInternal.stream().filter(ec -> extConfig.getName().equals(ec.getName())).count();
            if (duplicateCount > 1)
                iterator.remove();
        }

        // Verify the Negotiated Extensions
        for (ExtensionConfig config : negotiatedExtensions)
        {
            if (config.getName().startsWith("@"))
                continue;

            boolean wasRequested = false;
            for (ExtensionConfig requestedConfig : requestedExtensions)
            {
                if (config.getName().equalsIgnoreCase(requestedConfig.getName()))
                {
                    for (Map.Entry<String, String> entry : requestedConfig.getInternalParameters())
                    {
                        config.setParameter(entry.getKey(), entry.getValue());
                    }

                    wasRequested = true;
                    break;
                }
            }
            if (!wasRequested)
                throw new WebSocketException("Upgrade failed: Sec-WebSocket-Extensions contained extension not requested");

            long numExtsWithSameName = negotiatedExtensions.stream().filter(c -> config.getName().equalsIgnoreCase(c.getName())).count();
            if (numExtsWithSameName > 1)
                throw new WebSocketException("Upgrade failed: Sec-WebSocket-Extensions contained more than one extension of the same name");
        }

        // Negotiate the extension stack
        ExtensionStack extensionStack = new ExtensionStack(wsClient.getWebSocketComponents(), Behavior.CLIENT);
        extensionStack.negotiate(requestedExtensions, negotiatedWithInternal);

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
        if (negotiatedSubProtocol != null && !offeredSubProtocols.contains(negotiatedSubProtocol))
            throw new WebSocketException("Upgrade failed: subprotocol [" + negotiatedSubProtocol + "] not found in offered subprotocols " + offeredSubProtocols);

        // We can upgrade
        customize(endPoint);

        Request request = response.getRequest();
        Negotiated negotiated = new Negotiated(
            request.getURI(),
            negotiatedSubProtocol,
            HttpClient.isSchemeSecure(request.getScheme()),
            extensionStack,
            WebSocketConstants.SPEC_VERSION_STRING);

        WebSocketCoreSession coreSession = new WebSocketCoreSession(frameHandler, Behavior.CLIENT, negotiated, wsClient.getWebSocketComponents());
        coreSession.setClassLoader(wsClient.getClassLoader());
        customizer.customize(coreSession);

        HttpClient httpClient = wsClient.getHttpClient();
        ByteBufferPool bufferPool = wsClient.getWebSocketComponents().getBufferPool();
        RetainableByteBufferPool retainableByteBufferPool = bufferPool.asRetainableByteBufferPool();
        WebSocketConnection wsConnection = new WebSocketConnection(endPoint, httpClient.getExecutor(), httpClient.getScheduler(), bufferPool, retainableByteBufferPool, coreSession);
        wsClient.getEventListeners().forEach(wsConnection::addEventListener);
        coreSession.setWebSocketConnection(wsConnection);
        Exception listenerError = notifyUpgradeListeners((listener) -> listener.onHandshakeResponse(this, response));
        if (listenerError != null)
            throw new WebSocketException("onHandshakeResponse error", listenerError);

        // Now swap out the connection
        try
        {
            endPoint.upgrade(wsConnection);

            // Try to complete the future but if we could't we should abort the CoreSession
            if (!futureCoreSession.complete(coreSession))
            {
                futureCoreSession.exceptionally(t ->
                {
                    coreSession.processConnectionError(t, Callback.NOOP);
                    return null;
                });
            }
        }
        catch (Throwable t)
        {
            futureCoreSession.completeExceptionally(t);
        }
    }
}
