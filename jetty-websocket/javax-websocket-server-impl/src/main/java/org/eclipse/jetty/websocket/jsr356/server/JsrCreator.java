//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.websocket.Extension;
import javax.websocket.Extension.Parameter;
import javax.websocket.server.ServerEndpointConfig;
import javax.websocket.server.ServerEndpointConfig.Configurator;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.jsr356.JsrExtension;
import org.eclipse.jetty.websocket.jsr356.endpoints.EndpointInstance;
import org.eclipse.jetty.websocket.jsr356.server.pathmap.WebSocketPathSpec;
import org.eclipse.jetty.websocket.server.pathmap.PathSpec;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

public class JsrCreator implements WebSocketCreator
{
    public static final String PROP_REMOTE_ADDRESS = "javax.websocket.endpoint.remoteAddress";
    public static final String PROP_LOCAL_ADDRESS = "javax.websocket.endpoint.localAddress";
    public static final String PROP_LOCALES = "javax.websocket.upgrade.locales";
    private static final Logger LOG = Log.getLogger(JsrCreator.class);
    private final WebSocketContainerScope containerScope;
    private final ServerEndpointMetadata metadata;
    private final ExtensionFactory extensionFactory;

    public JsrCreator(WebSocketContainerScope containerScope, ServerEndpointMetadata metadata, ExtensionFactory extensionFactory)
    {
        this.containerScope = containerScope;
        this.metadata = metadata;
        this.extensionFactory = extensionFactory;
    }

    @Override
    public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
    {
        JsrHandshakeRequest hsreq = new JsrHandshakeRequest(req);
        JsrHandshakeResponse hsresp = new JsrHandshakeResponse(resp);

        // Get raw config, as defined when the endpoint was added to the container
        ServerEndpointConfig config = metadata.getConfig();
        
        // Establish a copy of the config, so that the UserProperties are unique
        // per upgrade request.
        config = new BasicServerEndpointConfig(containerScope, config);
        
        // Bug 444617 - Expose localAddress and remoteAddress for jsr modify handshake to use
        // This is being implemented as an optional set of userProperties so that
        // it is not JSR api breaking.  A few users on #jetty and a few from cometd
        // have asked for access to this information.
        Map<String, Object> userProperties = config.getUserProperties();
        userProperties.put(PROP_LOCAL_ADDRESS,req.getLocalSocketAddress());
        userProperties.put(PROP_REMOTE_ADDRESS,req.getRemoteSocketAddress());
        userProperties.put(PROP_LOCALES,Collections.list(req.getLocales()));

        // Get Configurator from config object (not guaranteed to be unique per endpoint upgrade)
        ServerEndpointConfig.Configurator configurator = config.getConfigurator();

        // modify handshake
        configurator.modifyHandshake(config,hsreq,hsresp);

        // check origin
        if (!configurator.checkOrigin(req.getOrigin()))
        {
            try
            {
                resp.sendForbidden("Origin mismatch");
            }
            catch (IOException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Unable to send error response",e);
            }
            return null;
        }

        // deal with sub protocols
        List<String> supported = config.getSubprotocols();
        List<String> requested = req.getSubProtocols();
        String subprotocol = configurator.getNegotiatedSubprotocol(supported,requested);
        if (StringUtil.isNotBlank(subprotocol))
        {
            resp.setAcceptedSubProtocol(subprotocol);
        }

        // deal with extensions
        List<Extension> installedExts = new ArrayList<>();
        for (String extName : extensionFactory.getAvailableExtensions().keySet())
        {
            installedExts.add(new JsrExtension(extName));
        }
        List<Extension> requestedExts = new ArrayList<>();
        for (ExtensionConfig reqCfg : req.getExtensions())
        {
            requestedExts.add(new JsrExtension(reqCfg));
        }
        List<Extension> usedExts = configurator.getNegotiatedExtensions(installedExts,requestedExts);
        List<ExtensionConfig> configs = new ArrayList<>();
        if (usedExts != null)
        {
            for (Extension used : usedExts)
            {
                ExtensionConfig ecfg = new ExtensionConfig(used.getName());
                for (Parameter param : used.getParameters())
                {
                    ecfg.setParameter(param.getName(),param.getValue());
                }
                configs.add(ecfg);
            }
        }
        resp.setExtensions(configs);

        // create endpoint class
        try
        {
            Class<?> endpointClass = config.getEndpointClass();
            Configurator configr = config.getConfigurator();
            Object endpoint = configr.getEndpointInstance(endpointClass);
            // Do not decorate here (let the Connection and Session start first)
            // This will allow CDI to see Session for injection into Endpoint classes.
            PathSpec pathSpec = hsreq.getRequestPathSpec();
            if (pathSpec instanceof WebSocketPathSpec)
            {
                // We have a PathParam path spec
                WebSocketPathSpec wspathSpec = (WebSocketPathSpec)pathSpec;
                String requestPath = req.getRequestPath();
                // Wrap the config with the path spec information
                config = new PathParamServerEndpointConfig(containerScope,config,wspathSpec,requestPath);
            }
            return new EndpointInstance(endpoint,config,metadata);
        }
        catch (InstantiationException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to create websocket: " + config.getEndpointClass().getName(),e);
            return null;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[metadata=%s]",this.getClass().getName(),metadata);
    }
}
