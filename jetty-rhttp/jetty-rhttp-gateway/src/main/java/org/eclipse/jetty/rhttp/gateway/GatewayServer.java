//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rhttp.gateway;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.rhttp.client.RHTTPClient;
import org.eclipse.jetty.rhttp.client.RHTTPRequest;
import org.eclipse.jetty.rhttp.client.RHTTPResponse;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>The gateway server is a server component that acts as intermediary between
 * <em>external clients</em> which perform requests for resources, and the
 * <em>resource providers</em>.</p>
 * <p>The particularity of the gateway server is that the resource providers
 * connect to the gateway using a comet protocol. <br />
 * The comet procotol functionality is implemented by a gateway client. <br />
 * This is quite different from a normal proxy server where it is the proxy that
 * connects to the resource providers.</p>
 * <p>Schematically, this is how the gateway server works:</p>
 * <pre>
 * External Client       Gateway Server         Gateway Client         Resource Provider
 *                              |                      |
 *                              | &lt;-- comet req. 1 --- |
 *        | --- ext. req. 1 --&gt; |                      |
 *        |                     | --- comet res. 1 --&gt; |
 *        |                     | &lt;-- comet req. 2 --- |
 *        |                                            | --- ext. req. 1 --&gt; |
 *                                                                           |
 *        |                                            | &lt;-- ext. res. 1 --- |
 *        |                     | &lt;-- ext.  res. 1 --- |
 *        | &lt;-- ext. res. 1 --- |
 *
 *        | --- ext. req. 2 --&gt; |
 *        |                     | --- comet res. 2 --&gt; |
 *        .                     .                      .
 * </pre>
 * <p>The gateway server is made of two servlets:
 * <ul>
 * <li>the external servlet, that handles external requests</li>
 * <li>the gateway servlet, that handles the communication with the gateway client</li>
 * </ul>
 * </p>
 * <p>External requests are suspended using Jetty continuations until a response for
 * that request arrives from the resource provider, or a
 * {@link #getExternalTimeout() configurable timeout} expires. <br />
 * Comet requests made by the gateway client also expires after a (different)
 * {@link #getGatewayTimeout() configurable timeout}.</p>
 * <p>External requests are packed into {@link RHTTPRequest} objects, converted into an
 * opaque byte array and sent as the body of the comet reponse to the gateway
 * {@link RHTTPClient}.</p>
 * <p>The gateway client uses a notification mechanism to alert listeners interested
 * in external requests that have been forwarded through the gateway. It is up to the
 * listeners to connect to the resource provider however they like.</p>
 * <p>When the gateway client receives a response from the resource provider, it packs
 * the response into a {@link RHTTPResponse} object, converts it into an opaque byte array
 * and sends it as the body of a normal HTTP request to the gateway server.</p>
 * <p>It is possible to connect more than one gateway client to a gateway server; each
 * gateway client is identified by a unique <em>targetId</em>. <br />
 * External requests must specify a targetId that allows the gateway server to forward
 * the requests to the specific gateway client; how the targetId is retrieved from an
 * external request is handled by {@link TargetIdRetriever} implementations.</p>
 *
 * @version $Revision$ $Date$
 */
public class GatewayServer extends Server
{
    public final static String DFT_EXT_PATH="/gw";
    public final static String DFT_CONNECT_PATH="/__rhttp";
    private final Logger logger = Log.getLogger(getClass().toString());
    private final Gateway gateway;
    private final ServletHolder externalServletHolder;
    private final ServletHolder connectorServletHolder;
    private final ServletContextHandler context;
    
    public GatewayServer()
    {
        this("",DFT_EXT_PATH,DFT_CONNECT_PATH,new StandardTargetIdRetriever());
    }

    public GatewayServer(String contextPath, String externalServletPath,String gatewayServletPath, TargetIdRetriever targetIdRetriever)
    {
        HandlerCollection handlers = new HandlerCollection();
        setHandler(handlers);
        context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);
        
        // Setup the gateway
        gateway = createGateway();
        
        // Setup external servlet
        ExternalServlet externalServlet = new ExternalServlet(gateway, targetIdRetriever);
        externalServletHolder = new ServletHolder(externalServlet);
        context.addServlet(externalServletHolder, externalServletPath + "/*");
        logger.debug("External servlet mapped to {}/*", externalServletPath);

        // Setup gateway servlet
        ConnectorServlet gatewayServlet = new ConnectorServlet(gateway);
        connectorServletHolder = new ServletHolder(gatewayServlet);
        connectorServletHolder.setInitParameter("clientTimeout", "15000");
        context.addServlet(connectorServletHolder, gatewayServletPath + "/*");
        logger.debug("Gateway servlet mapped to {}/*", gatewayServletPath);
    }

    /**
     * Creates and configures a {@link Gateway} object.
     * @return the newly created and configured Gateway object.
     */
    protected Gateway createGateway()
    {
        StandardGateway gateway = new StandardGateway();
        return gateway;
    }
    
    public ServletContextHandler getContext()
    {
        return context;
    }
    
    public Gateway getGateway()
    {
        return gateway;
    }
    
    public ServletHolder getExternalServlet()
    {
        return externalServletHolder;
    }
    
    public ServletHolder getConnectorServlet()
    {
        return connectorServletHolder;
    }

    public void setTargetIdRetriever(TargetIdRetriever retriever)
    {
        ((ExternalServlet)externalServletHolder.getServletInstance()).setTargetIdRetriever(retriever);
    }

    public TargetIdRetriever getTargetIdRetriever()
    {
        return ((ExternalServlet)externalServletHolder.getServletInstance()).getTargetIdRetriever();
    }
    
}
