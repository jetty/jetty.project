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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * ScopedHandler.
 *
 * A ScopedHandler is a HandlerWrapper where the wrapped handlers
 * each define a scope.
 *
 * <p>When {@link #handle(String, Request, HttpServletRequest, HttpServletResponse)}
 * is called on the first ScopedHandler in a chain of HandlerWrappers,
 * the {@link #doScope(String, Request, HttpServletRequest, HttpServletResponse)} method is
 * called on all contained ScopedHandlers, before the
 * {@link #doHandle(String, Request, HttpServletRequest, HttpServletResponse)} method
 * is called on all contained handlers.</p>
 *
 * <p>For example if Scoped handlers A, B &amp; C were chained together, then
 * the calling order would be:</p>
 * <pre>
 * A.handle(...)
 *   A.doScope(...)
 *     B.doScope(...)
 *       C.doScope(...)
 *         A.doHandle(...)
 *           B.doHandle(...)
 *              C.doHandle(...)
 * </pre>
 *
 * <p>If non scoped handler X was in the chained A, B, X &amp; C, then
 * the calling order would be:</p>
 * <pre>
 * A.handle(...)
 *   A.doScope(...)
 *     B.doScope(...)
 *       C.doScope(...)
 *         A.doHandle(...)
 *           B.doHandle(...)
 *             X.handle(...)
 *               C.handle(...)
 *                 C.doHandle(...)
 * </pre>
 *
 * <p>A typical usage pattern is:</p>
 * <pre>
 *     private static class MyHandler extends ScopedHandler
 *     {
 *         public void doScope(String target, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
 *         {
 *             try
 *             {
 *                 setUpMyScope();
 *                 super.doScope(target,request,response);
 *             }
 *             finally
 *             {
 *                 tearDownMyScope();
 *             }
 *         }
 *
 *         public void doHandle(String target, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
 *         {
 *             try
 *             {
 *                 doMyHandling();
 *                 super.doHandle(target,request,response);
 *             }
 *             finally
 *             {
 *                 cleanupMyHandling();
 *             }
 *         }
 *     }
 * </pre>
 */
public abstract class ScopedHandler extends HandlerWrapper
{
    private static final ThreadLocal<ScopedHandler> __outerScope = new ThreadLocal<>();
    protected ScopedHandler _outerScope;
    protected ScopedHandler _nextScope;

    @Override
    protected void doStart() throws Exception
    {
        try
        {
            _outerScope = __outerScope.get();
            if (_outerScope == null)
                __outerScope.set(this);

            super.doStart();

            _nextScope = getChildHandlerByClass(ScopedHandler.class);
        }
        finally
        {
            if (_outerScope == null)
                __outerScope.set(null);
        }
    }

    @Override
    public final void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (isStarted())
        {
            if (_outerScope == null)
                doScope(target, baseRequest, request, response);
            else
                doHandle(target, baseRequest, request, response);
        }
    }

    /**
     * Scope the handler
     * <p>Derived implementations should call {@link #nextScope(String, Request, HttpServletRequest, HttpServletResponse)}
     *
     * @param target The target of the request - either a URI or a name.
     * @param baseRequest The original unwrapped request object.
     * @param request The request either as the {@link Request} object or a wrapper of that request.
     * @param response The response as the {@link Response} object or a wrapper of that request.
     * @throws IOException if unable to handle the request or response processing
     * @throws ServletException if unable to handle the request or response due to underlying servlet issue
     */
    public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        nextScope(target, baseRequest, request, response);
    }

    /**
     * Scope the handler
     *
     * @param target The target of the request - either a URI or a name.
     * @param baseRequest The original unwrapped request object.
     * @param request The request either as the {@link Request} object or a wrapper of that request.
     * @param response The response as the {@link Response} object or a wrapper of that request.
     * @throws IOException if unable to handle the request or response processing
     * @throws ServletException if unable to handle the request or response due to underlying servlet issue
     */
    public final void nextScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        if (_nextScope != null)
            _nextScope.doScope(target, baseRequest, request, response);
        else if (_outerScope != null)
            _outerScope.doHandle(target, baseRequest, request, response);
        else
            doHandle(target, baseRequest, request, response);
    }

    /**
     * Do the handler work within the scope.
     * <p>Derived implementations should call {@link #nextHandle(String, Request, HttpServletRequest, HttpServletResponse)}
     *
     * @param target The target of the request - either a URI or a name.
     * @param baseRequest The original unwrapped request object.
     * @param request The request either as the {@link Request} object or a wrapper of that request.
     * @param response The response as the {@link Response} object or a wrapper of that request.
     * @throws IOException if unable to handle the request or response processing
     * @throws ServletException if unable to handle the request or response due to underlying servlet issue
     */
    public abstract void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException;

    /*
     * Do the handler work within the scope.
     * @param target
     *          The target of the request - either a URI or a name.
     * @param baseRequest
     *          The original unwrapped request object.
     * @param request
     *            The request either as the {@link Request} object or a wrapper of that request. The
     *            <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getRequest() getRequest()}</code>
     *            method can be used access the Request object if required.
     * @param response
     *            The response as the {@link Response} object or a wrapper of that request. The
     *            <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getResponse() getResponse()}</code>
     *            method can be used access the Response object if required.
     * @throws IOException
     *             if unable to handle the request or response processing
     * @throws ServletException
     *             if unable to handle the request or response due to underlying servlet issue
     */
    public final void nextHandle(String target, final Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (_nextScope != null && _nextScope == _handler)
            _nextScope.doHandle(target, baseRequest, request, response);
        else if (_handler != null)
            super.handle(target, baseRequest, request, response);
    }
}
