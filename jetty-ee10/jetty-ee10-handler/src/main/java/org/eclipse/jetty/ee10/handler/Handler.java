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

package org.eclipse.jetty.ee10.handler;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.internal.HttpConnection;
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
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException;

    public void setServer(Server server);

    @ManagedAttribute(value = "the jetty server for this handler", readonly = true)
    public Server getServer();

    @ManagedOperation(value = "destroy associated resources", impact = "ACTION")
    @Override
    public void destroy();
}

