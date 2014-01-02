//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;


/* ------------------------------------------------------------ */
/** ScopedHandler.
 * 
 * A ScopedHandler is a HandlerWrapper where the wrapped handlers
 * each define a scope.   When {@link #handle(String, Request, HttpServletRequest, HttpServletResponse)}
 * is called on the first ScopedHandler in a chain of HandlerWrappers,
 * the {@link #doScope(String, Request, HttpServletRequest, HttpServletResponse)} method is 
 * called on all contained ScopedHandlers, before the 
 * {@link #doHandle(String, Request, HttpServletRequest, HttpServletResponse)} method 
 * is called on all contained handlers.
 * 
 * <p>For example if Scoped handlers A, B & C were chained together, then 
 * the calling order would be:<pre>
 * A.handle(...)
 *   A.doScope(...)
 *     B.doScope(...)
 *       C.doScope(...)
 *         A.doHandle(...)
 *           B.doHandle(...)
 *              C.doHandle(...)   
 * <pre>
 * 
 * <p>If non scoped handler X was in the chained A, B, X & C, then 
 * the calling order would be:<pre>
 * A.handle(...)
 *   A.doScope(...)
 *     B.doScope(...)
 *       C.doScope(...)
 *         A.doHandle(...)
 *           B.doHandle(...)
 *             X.handle(...)
 *               C.handle(...)
 *                 C.doHandle(...)   
 * <pre>
 * 
 * <p>A typical usage pattern is:<pre>
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
    private static final ThreadLocal<ScopedHandler> __outerScope= new ThreadLocal<ScopedHandler>();
    protected ScopedHandler _outerScope;
    protected ScopedHandler _nextScope;
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        try
        {
            _outerScope=__outerScope.get();
            if (_outerScope==null)
                __outerScope.set(this);
            
            super.doStart();
            
            _nextScope= (ScopedHandler)getChildHandlerByClass(ScopedHandler.class);
            
        }
        finally
        {
            if (_outerScope==null)
                __outerScope.set(null);
        }
    }


    /* ------------------------------------------------------------ */
    /* 
     */
    @Override
    public final void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (_outerScope==null)  
            doScope(target,baseRequest,request, response);
        else 
            doHandle(target,baseRequest,request, response);
    }
    
    /* ------------------------------------------------------------ */
    /* 
     * Scope the handler
     */
    public abstract void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) 
        throws IOException, ServletException;
    
    /* ------------------------------------------------------------ */
    /* 
     * Scope the handler
     */
    public final void nextScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) 
        throws IOException, ServletException
    {
        // this method has been manually inlined in several locations, but
        // is called protected by an if(never()), so your IDE can find those
        // locations if this code is changed.
        if (_nextScope!=null)
            _nextScope.doScope(target,baseRequest,request, response);
        else if (_outerScope!=null)
            _outerScope.doHandle(target,baseRequest,request, response);
        else 
            doHandle(target,baseRequest,request, response);
    }

    /* ------------------------------------------------------------ */
    /* 
     * Do the handler work within the scope.
     */
    public abstract void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) 
        throws IOException, ServletException;
    
    /* ------------------------------------------------------------ */
    /* 
     * Do the handler work within the scope.
     */
    public final void nextHandle(String target, final Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // this method has been manually inlined in several locations, but
        // is called protected by an if(never()), so your IDE can find those
        // locations if this code is changed.
        if (_nextScope!=null && _nextScope==_handler)
            _nextScope.doHandle(target,baseRequest,request, response);
        else if (_handler!=null)
            _handler.handle(target,baseRequest, request, response);
    }
    
    /* ------------------------------------------------------------ */
    protected boolean never()
    {
        return false;
    }
    
}
