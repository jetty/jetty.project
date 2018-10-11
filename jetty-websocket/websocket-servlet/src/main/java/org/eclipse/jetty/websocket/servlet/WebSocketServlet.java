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

package org.eclipse.jetty.websocket.servlet;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.server.Handshaker;
import org.eclipse.jetty.websocket.servlet.internal.WebSocketServletFactoryImpl;
import org.eclipse.jetty.websocket.servlet.internal.WebSocketServletNegotiator;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;

/**
 * Abstract Servlet used to bridge the Servlet API to the WebSocket API.
 * <p>
 * To use this servlet, you will be required to register your websockets with the {@link WebSocketServletFactoryImpl} so that it can create your websockets under the
 * appropriate conditions.
 * <p>
 * The most basic implementation would be as follows.
 * <p>
 * <pre>
 * package my.example;
 *
 * import WebSocketServlet;
 * import WebSocketServletFactory;
 *
 * public class MyEchoServlet extends WebSocketServlet
 * {
 *     &#064;Override
 *     public void configure(WebSocketServletFactory factory)
 *     {
 *         // set a 10 second idle timeout
 *         factory.getPolicy().setIdleTimeout(10000);
 *         // register my socket
 *         factory.register(MyEchoSocket.class);
 *     }
 * }
 * </pre>
 * <p>
 * Note: that only request that conforms to a "WebSocket: Upgrade" handshake request will trigger the {@link WebSocketServletFactoryImpl} handling of creating
 * WebSockets.<br>
 * All other requests are treated as normal servlet requests.
 * <p>
 * <p>
 * <b>Configuration / Init-Parameters:</b><br>
 * <p>
 * <dl>
 * <dt>maxIdleTime</dt>
 * <dd>set the time in ms that a websocket may be idle before closing<br>
 * <p>
 * <dt>maxTextMessageSize</dt>
 * <dd>set the size in UTF-8 bytes that a websocket may be accept as a Text Message before closing<br>
 * <p>
 * <dt>maxBinaryMessageSize</dt>
 * <dd>set the size in bytes that a websocket may be accept as a Binary Message before closing<br>
 * <p>
 * <dt>inputBufferSize</dt>
 * <dd>set the size in bytes of the buffer used to read raw bytes from the network layer<br>
 * </dl>
 */
@SuppressWarnings("serial")
public abstract class WebSocketServlet extends HttpServlet
{
    private static final Logger LOG = Log.getLogger(WebSocketServlet.class);
    private WebSocketServletFactoryImpl factory;
    private final Handshaker handshaker = Handshaker.newInstance();

    public abstract void configure(WebSocketServletFactory factory);

    /**
     * @see javax.servlet.GenericServlet#init()
     */
    @Override
    public void init() throws ServletException
    {
        try
        {
            ServletContext ctx = getServletContext();

            factory = new WebSocketServletFactoryImpl();
            factory.setContextClassLoader(ctx.getClassLoader());
            String max = getInitParameter("maxIdleTime");
            if (max != null)
            {
                factory.setDefaultIdleTimeout(Duration.ofMillis(Long.parseLong(max)));
            }

            max = getInitParameter("maxTextMessageSize");
            if (max != null)
            {
                factory.setDefaultMaxTextMessageSize(Long.parseLong(max));
            }

            max = getInitParameter("maxBinaryMessageSize");
            if (max != null)
            {
                factory.setDefaultMaxBinaryMessageSize(Long.parseLong(max));
            }

            max = getInitParameter("inputBufferSize");
            if (max != null)
            {
                factory.setDefaultInputBufferSize(Integer.parseInt(max));
            }

            max = getInitParameter("outputBufferSize");
            if(max != null)
            {
                factory.setDefaultOutputBufferSize(Integer.parseInt(max));
            }

            max = getInitParameter("maxAllowedFrameSize");
            if(max != null)
            {
                factory.setDefaultMaxAllowedFrameSize(Long.parseLong(max));
            }

            String autoFragment = getInitParameter("autoFragment");
            if(autoFragment != null)
            {
                factory.setAutoFragment(Boolean.parseBoolean(autoFragment));
            }

            configure(factory); // Let user modify factory

            ctx.setAttribute(WebSocketServletFactory.class.getName(), factory);
        }
        catch (Throwable x)
        {
            throw new ServletException(x);
        }
    }

    /**
     * @see javax.servlet.http.HttpServlet#service(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        // Since this is a filter, we need to be smart about determining the target path.
        // We should rely on the Container for stripping path parameters and its ilk before
        // attempting to match a specific mapped websocket creator.
        String target = request.getServletPath();
        if (request.getPathInfo() != null)
        {
            target = target + request.getPathInfo();
        }

        MappedResource<WebSocketServletNegotiator> resource = factory.getMatchedResource(target);
        if (resource != null)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("WebSocket Upgrade detected on {} for endpoint {}", target, resource);
            }

            WebSocketServletNegotiator negotiator = resource.getResource();

            // Store PathSpec resource mapping as request attribute, for WebSocketCreator
            // implementors to use later if they wish
            request.setAttribute(PathSpec.class.getName(), resource.getPathSpec());

            // Attempt to upgrade
            if (handshaker.upgradeRequest(negotiator, request, response))
            {
                // Upgrade was a success, nothing else to do.
                return;
            }

            // If we reach this point, it means we had an incoming request to upgrade
            // but it was either not a proper websocket upgrade, or it was possibly rejected
            // due to incoming request constraints (controlled by WebSocketCreator)
            if (response.isCommitted())
            {
                // not much we can do at this point.
                return;
            }
        }
        else
        {
            if(LOG.isDebugEnabled())
            {
                LOG.debug("No match for WebSocket Upgrade at target: {}", target);
            }
        }

        // All other processing
        super.service(request, response);
    }
}
