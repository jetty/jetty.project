// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.server.handler;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
 * A handler that shuts the server down on a valid request. Used to do "soft" restarts from Java. This handler is a contribution from Johannes Brodwall:
 * https://bugs.eclipse.org/bugs/show_bug.cgi?id=357687
 *
 * Usage:
 *
 * <pre>
 * Server server = new Server(8080);
 * HandlerList handlers = new HandlerList();
 * handlers.setHandlers(new Handler[]
 * { someOtherHandler, new ShutdownHandler(server,&quot;secret password&quot;) });
 * server.setHandler(handlers);
 * server.start();
 * </pre>
 */
public class ShutdownHandler extends AbstractHandler
{
    private static final Logger LOG = Log.getLogger(ShutdownHandler.class);

    private final String shutdownToken;

    private final Server jettyServer;

    private boolean exitJvm = false;

    /**
     * Creates a listener that lets the server be shut down remotely (but only from localhost).
     *
     * @param server
     *            the Jetty instance that should be shut down
     * @param shutdownToken
     *            a secret password to avoid unauthorized shutdown attempts
     */
    public ShutdownHandler(Server server, String shutdownToken)
    {
        this.jettyServer = server;
        this.shutdownToken = shutdownToken;
    }

    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (!target.equals("/shutdown"))
        {
            return;
        }

        if (!request.getMethod().equals("POST"))
        {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        if (!hasCorrectSecurityToken(request))
        {
            LOG.warn("Unauthorized shutdown attempt from " + getRemoteAddr(request));
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (!requestFromLocalhost(request))
        {
            LOG.warn("Unauthorized shutdown attempt from " + getRemoteAddr(request));
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        LOG.info("Shutting down by request from " + getRemoteAddr(request));
        try
        {
            shutdownServer();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Shutting down server",e);
        }
    }

    private boolean requestFromLocalhost(HttpServletRequest request)
    {
        return "127.0.0.1".equals(getRemoteAddr(request));
    }

    protected String getRemoteAddr(HttpServletRequest request)
    {
        return request.getRemoteAddr();
    }

    private boolean hasCorrectSecurityToken(HttpServletRequest request)
    {
        return shutdownToken.equals(request.getParameter("token"));
    }

    void shutdownServer() throws Exception
    {
        jettyServer.stop();
        if (exitJvm)
            System.exit(0);
    }

}
