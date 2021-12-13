//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AbstractHandler.
 * <p>A convenience implementation of {@link Handler} that uses the
 * {@link ContainerLifeCycle} to provide:<ul>
 * <li>start/stop behavior
 * <li>a bean container
 * <li>basic {@link Dumpable} support
 * <li>a {@link Server} reference
 * <li>optional error dispatch handling
 * </ul>
 */
@ManagedObject("Jetty Handler")
public abstract class AbstractHandler extends ContainerLifeCycle implements Handler
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractHandler.class);

    private Server _server;

    public AbstractHandler()
    {
    }

    @Override
    public abstract void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;

    /**
     * Deprecated error page generation
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
    @Deprecated
    protected void doError(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        Object o = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int code = (o instanceof Integer) ? ((Integer)o).intValue() : (o != null ? Integer.parseInt(o.toString()) : 500);
        response.setStatus(code);
        baseRequest.setHandled(true);
    }

    @Override
    protected void doStart() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("starting {}", this);
        if (_server == null)
            LOG.warn("No Server set for {}", this);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("stopping {}", this);
        super.doStop();
    }

    @Override
    public void setServer(Server server)
    {
        if (_server == server)
            return;
        if (isStarted())
            throw new IllegalStateException(getState());
        _server = server;
    }

    @Override
    public Server getServer()
    {
        return _server;
    }

    @Override
    public void destroy()
    {
        if (!isStopped())
            throw new IllegalStateException("!STOPPED");
        super.destroy();
    }

    /**
     * An extension of AbstractHandler that handles {@link DispatcherType#ERROR} dispatches.
     * <p>
     * {@link DispatcherType#ERROR} dispatches are handled by calling the {@link #doError(String, Request, HttpServletRequest, HttpServletResponse)}
     * method.  All other dispatches are passed to the abstract {@link #doNonErrorHandle(String, Request, HttpServletRequest, HttpServletResponse)}
     * method, which should be implemented with specific handler behavior
     * @deprecated This class is no longer required as ERROR dispatch is only done if there is an error page target.
     */
    @Deprecated
    public abstract static class ErrorDispatchHandler extends AbstractHandler
    {
        @Override
        public final void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (baseRequest.getDispatcherType() == DispatcherType.ERROR)
                doError(target, baseRequest, request, response);
            else
                doNonErrorHandle(target, baseRequest, request, response);
        }

        /**
         * Called by {@link #handle(String, Request, HttpServletRequest, HttpServletResponse)}
         * for all non-{@link DispatcherType#ERROR} dispatches.
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
        @Deprecated
        protected abstract void doNonErrorHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;
    }
}
