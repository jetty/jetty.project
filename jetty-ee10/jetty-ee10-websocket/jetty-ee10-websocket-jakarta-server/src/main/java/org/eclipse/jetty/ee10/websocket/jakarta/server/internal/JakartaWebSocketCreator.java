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

package org.eclipse.jetty.ee10.websocket.jakarta.server.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.websocket.Extension;
import jakarta.websocket.Extension.Parameter;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee10.websocket.jakarta.common.ConfiguredEndpoint;
import org.eclipse.jetty.ee10.websocket.jakarta.common.JakartaWebSocketContainer;
import org.eclipse.jetty.ee10.websocket.jakarta.common.JakartaWebSocketExtension;
import org.eclipse.jetty.ee10.websocket.jakarta.common.ServerEndpointConfigWrapper;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.core.server.WebSocketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JakartaWebSocketCreator implements WebSocketCreator
{
    public static final String PROP_REMOTE_ADDRESS = "jakarta.websocket.endpoint.remoteAddress";
    public static final String PROP_LOCAL_ADDRESS = "jakarta.websocket.endpoint.localAddress";
    public static final String PROP_LOCALES = "jakarta.websocket.upgrade.locales";
    private static final Logger LOG = LoggerFactory.getLogger(JakartaWebSocketCreator.class);
    private final JakartaWebSocketContainer containerScope;

    private final ServerEndpointConfig baseConfig;
    private final WebSocketExtensionRegistry extensionRegistry;

    public JakartaWebSocketCreator(JakartaWebSocketContainer containerScope, ServerEndpointConfig config, WebSocketExtensionRegistry extensionRegistry)
    {
        this.containerScope = containerScope;
        this.baseConfig = config;
        this.extensionRegistry = extensionRegistry;
    }

    @Override
    public Object createWebSocket(ServerUpgradeRequest request, ServerUpgradeResponse response, Callback callback)
    {
        final JsrHandshakeRequest jsrHandshakeRequest = new JsrHandshakeRequest(request);
        final JsrHandshakeResponse jsrHandshakeResponse = new JsrHandshakeResponse(response);

        // Establish a copy of the config, so that the UserProperties are unique
        // per upgrade request.
        ServerEndpointConfig config = new ServerEndpointConfigWrapper(baseConfig)
        {
            final Map<String, Object> userProperties = new HashMap<>(baseConfig.getUserProperties());

            @Override
            public Map<String, Object> getUserProperties()
            {
                return userProperties;
            }
        };

        // Bug 444617 - Expose localAddress and remoteAddress for jsr modify handshake to use
        // This is being implemented as an optional set of userProperties so that
        // it is not JSR api breaking.  A few users on #jetty and a few from cometd
        // have asked for access to this information.
        Map<String, Object> userProperties = config.getUserProperties();
        userProperties.put(PROP_LOCAL_ADDRESS, request.getConnectionMetaData().getLocalSocketAddress());
        userProperties.put(PROP_REMOTE_ADDRESS, request.getConnectionMetaData().getRemoteSocketAddress());
        userProperties.put(PROP_LOCALES, Request.getLocales(request));

        // Get Configurator from config object (not guaranteed to be unique per endpoint upgrade)
        ServerEndpointConfig.Configurator configurator = config.getConfigurator();

        // [JSR] Step 1: check origin
        if (!configurator.checkOrigin(request.getHeaders().get(HttpHeader.ORIGIN)))
        {
            Response.writeError(request, response, callback, HttpStatus.FORBIDDEN_403, "Origin mismatch");
            return null;
        }

        // [JSR] Step 2: deal with sub protocols
        List<String> supported = config.getSubprotocols();
        List<String> requested = request.getSubProtocols();
        String subprotocol = configurator.getNegotiatedSubprotocol(supported, requested);
        if (StringUtil.isNotBlank(subprotocol))
        {
            response.setAcceptedSubProtocol(subprotocol);
        }

        // [JSR] Step 3: deal with extensions
        List<Extension> installedExtensions = new ArrayList<>();
        for (String extName : extensionRegistry.getAvailableExtensions().keySet())
        {
            installedExtensions.add(new JakartaWebSocketExtension(extName));
        }
        List<Extension> requestedExts = new ArrayList<>();
        for (ExtensionConfig reqCfg : request.getExtensions())
        {
            requestedExts.add(new JakartaWebSocketExtension(reqCfg));
        }
        List<Extension> usedExtensions = configurator.getNegotiatedExtensions(installedExtensions, requestedExts);
        List<ExtensionConfig> configs = new ArrayList<>();
        if (usedExtensions != null)
        {
            for (Extension used : usedExtensions)
            {
                ExtensionConfig ecfg = new ExtensionConfig(used.getName());
                for (Parameter param : used.getParameters())
                {
                    ecfg.setParameter(param.getName(), param.getValue());
                }
                configs.add(ecfg);
            }
        }
        response.setExtensions(configs);

        // [JSR] Step 4: build out new ServerEndpointConfig
        Object pathSpecObject = jsrHandshakeRequest.getRequestPathSpec();
        if (pathSpecObject instanceof UriTemplatePathSpec)
        {
            // We can get path params from PathSpec and Request Path.
            UriTemplatePathSpec pathSpec = (UriTemplatePathSpec)pathSpecObject;
            Map<String, String> pathParams = pathSpec.getPathParams(Request.getPathInContext(request));

            // Wrap the config with the path spec information.
            config = new PathParamServerEndpointConfig(config, pathParams);
        }
        else
        {
            Map<String, String> pathParams = jsrHandshakeRequest.getPathParams();
            if (pathParams != null)
            {
                // Wrap the config with the path spec information.
                config = new PathParamServerEndpointConfig(config, pathParams);
            }
        }

        // [JSR] Step 5: Call modifyHandshake
        configurator.modifyHandshake(config, jsrHandshakeRequest, jsrHandshakeResponse);
        // Set modified headers Map back into response properly
        jsrHandshakeResponse.setHeaders(jsrHandshakeResponse.getHeaders());

        try
        {
            // [JSR] Step 6: create endpoint class
            Class<?> endpointClass = config.getEndpointClass();
            Object endpoint = config.getConfigurator().getEndpointInstance(endpointClass);
            return new ConfiguredEndpoint(endpoint, config);
        }
        catch (InstantiationException e)
        {
            LOG.warn("Unable to create websocket: {}", config.getEndpointClass().getName(), e);
            return null;
        }
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        JakartaWebSocketCreator that = (JakartaWebSocketCreator)o;
        return Objects.equals(baseConfig, that.baseConfig);
    }

    @Override
    public int hashCode()
    {
        return (baseConfig != null ? baseConfig.hashCode() : 0);
    }

    @Override
    public String toString()
    {
        return String.format("JsrCreator[%s%s]", (baseConfig instanceof AnnotatedServerEndpointConfig ? "@" : ""), baseConfig.getEndpointClass().getName());
    }
}
