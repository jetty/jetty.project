//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handle;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

/**
 * ScopedHandler.
 *
 * A ScopedHandler is a HandlerWrapper where the wrapped handlers
 * each define a scope.
 *
 * <p>When {@link #handle(String, Request, HttpServletRequest, HttpServletResponse)}
 * is called on the first ScopedHandler in a chain of HandlerWrappers,
 * the {@link #doScope(String, Request, HttpServletRequest, HttpServletResponse, Handle)} method is
 * called on all contained ScopedHandlers, before the
 * {@link #doHandle(String, Request, HttpServletRequest, HttpServletResponse, Handle)} method
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
 *         public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response, Handle next) throws IOException, ServletException
 *         {
 *             try
 *             {
 *                 setUpMyScope();
 *                 next.handle(target,baseRequest,request,response);
 *             }
 *             finally
 *             {
 *                 tearDownMyScope();
 *             }
 *         }
 *
 *         public void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response, Handle next) throws IOException, ServletException
 *         {
 *             try
 *             {
 *                 doMyHandling();
 *                 next.handle(target,baseRequest,request,response);
 *             }
 *             finally
 *             {
 *                 cleanupMyHandling();
 *             }
 *         }
 *     }
 * </pre>
 */
public class ScopedHandler extends HandlerWrapper
{
    private static final ThreadLocal<ScopedHandler> __outerScope = new ThreadLocal<ScopedHandler>();
    private Handle _handle;
    private Handle _nextScope;
    private Handle _nextHandle;

    protected ScopedHandler()
    {
        reset();
    }

    protected void reset()
    {
        _nextHandle = (t,r,rq,rs) -> false;
        _nextScope = (t,r,rq,rs) -> this.doHandle(t, r, rq, rs, _nextHandle);
        _handle = (t,r,rq,rs) -> doScope(t, r, rq, rs, _nextScope);
    }

    @Override
    protected void doStart() throws Exception
    {
        // use ThreadLocal to find or set the outer scope.
        ScopedHandler outerScopedHandler = __outerScope.get();
        if (outerScopedHandler == null)
            __outerScope.set(this);
        try
        {
            super.doStart();
        }
        finally
        {
            if (outerScopedHandler == null)
                __outerScope.set(null);

            // TODO Some MethodHandle magic might work better here, or at least give prettier stacks?
            ScopedHandler nextScopedHandler = getChildHandlerByClass(ScopedHandler.class);

            // What to do after doHandle
            if (nextScopedHandler != null && nextScopedHandler == _handler)
                // The next handler is a scoped handler, so directly call its doHandler
                _nextHandle = (t,r,rq,rs) ->  nextScopedHandler.doHandle(t, r, rq, rs, nextScopedHandler._nextHandle);
            else if (_handler != null)
                // The next handler is a normal handler, so handle normally
                _nextHandle = _handler;
            else
                // There is no next handler
                _nextHandle = (t,r,rq,rs) -> false;

            // What to do after doScope?
            if (nextScopedHandler != null)
                // Scope to next ScoppedHandler
                _nextScope = (t,r,rq,rs) -> nextScopedHandler.doScope(t, r, rq, rs, nextScopedHandler._nextScope);
            else if (outerScopedHandler != null)
                // No more scopped handlers so go back to the outer handler and start handling
                _nextScope = (t,r,rq,rs) -> outerScopedHandler.doHandle(t, r, rq, rs, outerScopedHandler._nextHandle);
            else
                // We must be the outer scope with no inner scopes, so start handling
                _nextScope = (t,r,rq,rs) -> this.doHandle(t, r, rq, rs, _nextHandle);

            // What do we do if handle is called?
            if (outerScopedHandler == null)
                // We are the outer scope so we start scoping the request
                _handle = (t,r,rq,rs) -> doScope(t, r, rq, rs, _nextScope);
            else
                // We are not the outerscope, so we must already be scoped, so we handle
                _handle = (t,r,rq,rs) -> doHandle(t, r, rq, rs, _nextHandle);
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        reset();
        super.doStop();
    }

    @Override
    public final boolean handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        return isStarted() && _handle != null && _handle.handle(target, baseRequest, request, response);
    }

    /**
     * Scope the handler
     *
     * @param target The target of the request - either a URI or a name.
     * @param baseRequest The original unwrapped request object.
     * @param request The request either as the {@link Request} object or a wrapper of that request. The
     * <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getRequest() getRequest()}</code>
     * method can be used access the Request object if required.
     * @param response The response as the {@link Response} object or a wrapper of that request. The
     * <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getResponse() getResponse()}</code>
     * method can be used access the Response object if required.
     * @param next The next scope or handler to call within this scope.
     * @throws IOException if unable to handle the request or response processing
     * @throws ServletException if unable to handle the request or response due to underlying servlet issue
     * @return True if the request has been completely handled.
     */
    public boolean doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response, Handle next)
        throws IOException, ServletException
    {
        return next.handle(target, baseRequest, request, response);
    }

    /**
     * Do the handler work within the scope.
     *
     * @param target The target of the request - either a URI or a name.
     * @param baseRequest The original unwrapped request object.
     * @param request The request either as the {@link Request} object or a wrapper of that request. The
     * <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getRequest() getRequest()}</code>
     * method can be used access the Request object if required.
     * @param response The response as the {@link Response} object or a wrapper of that request. The
     * <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getResponse() getResponse()}</code>
     * method can be used access the Response object if required.
     * @param next The next handler to call within this scope.
     * @throws IOException if unable to handle the request or response processing
     * @throws ServletException if unable to handle the request or response due to underlying servlet issue
     * @return True if the request has been completely handled.
     */
    public boolean doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response, Handle next)
        throws IOException, ServletException
    {
        return next.handle(target, baseRequest, request, response);
    }

}
