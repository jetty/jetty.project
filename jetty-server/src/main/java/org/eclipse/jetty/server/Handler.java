//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.component.LifeCycle;

/**
 * A Jetty Server Handler.
 * <p>
 * A Handler instance is required by a {@link Server} to handle incoming
 * HTTP requests.
 * <p>
 * A Handler may:
 * <ul>
 * <li>Completely generate the HTTP Response</li>
 * <li>Examine/modify the request and call another Handler (see {@link HandlerWrapper}).
 * <li>Pass the request to one or more other Handlers (see {@link HandlerCollection}).
 * </ul>
 *
 * Handlers are passed the servlet API request and response object, but are
 * not Servlets.  The servlet container is implemented by handlers for
 * context, security, session and servlet that modify the request object
 * before passing it to the next stage of handling.
 */
@ManagedObject("Jetty Handler")
public interface Handler extends LifeCycle, Destroyable
{
    /**
     * Handle a request.
     *
     * @param target The target of the request - either a URI or a name.
     * @param baseRequest The original unwrapped request object.
     * @param request The request either as the {@link Request} object or a wrapper of that request. The
     * <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getRequest() getRequest()}</code>
     * method can be used access the Request object if required.
     * @param response The response as the {@link Response} object or a wrapper of that request. The
     * <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getResponse() getResponse()}</code>
     * method can be used access the Response object if required.
     * @throws IOException if unable to handle the request or response processing
     * @throws ServletException if unable to handle the request or response due to underlying servlet issue
     */
    void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException;

    void setServer(Server server);

    @ManagedAttribute(value = "the jetty server for this handler", readonly = true)
    Server getServer();

    @ManagedOperation(value = "destroy associated resources", impact = "ACTION")
    @Override
    void destroy();
}

