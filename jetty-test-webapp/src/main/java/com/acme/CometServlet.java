// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package com.acme;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.ajax.Continuation;
import org.eclipse.jetty.util.ajax.ContinuationSupport;

/** CometServlet
 * This servlet implements the Comet API from tc6.x with the exception of the read method.
 * 
 * 
 *
 */
public class CometServlet extends HttpServlet
{
    public void begin(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        request.setAttribute("org.apache.tomcat.comet",Boolean.TRUE);
    }

    public void end(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        synchronized(request)
        {
            request.removeAttribute("org.apache.tomcat.comet");
            
            Continuation continuation=ContinuationSupport.getContinuation(request,request);
            if (continuation.isPending())
                continuation.resume();
        }
    }

    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        end(request,response);
    }

    public boolean read(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        throw new UnsupportedOperationException();
    }

    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        synchronized(request)
        {
            // TODO: wrap response so we can reset timeout on writes.
            
            Continuation continuation=ContinuationSupport.getContinuation(request,request);
            
            if (!continuation.isPending())
                begin(request,response);
            
            Integer timeout=(Integer)request.getAttribute("org.apache.tomcat.comet.timeout");
            boolean resumed=continuation.suspend(timeout==null?60000:timeout.intValue());
            
            if (!resumed)
                error(request,response);
        }
    }

    public void setTimeout(HttpServletRequest request, HttpServletResponse response, int timeout) throws IOException, ServletException,
            UnsupportedOperationException
    {
        request.setAttribute("org.apache.tomcat.comet.timeout",new Integer(timeout));
    }
}
