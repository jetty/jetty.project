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

package org.eclipse.jetty.servlets.gzip;

import java.io.IOException;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@SuppressWarnings("serial")
public class AsyncTimeoutDispatchBasedWrite extends TestDirContentServlet implements AsyncListener
{
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        AsyncContext ctx = (AsyncContext)request.getAttribute(AsyncContext.class.getName());
        if (ctx == null)
        {
            // First pass through
            ctx = request.startAsync();
            ctx.addListener(this);
            ctx.setTimeout(200);
            request.setAttribute(AsyncContext.class.getName(),ctx);
        }
        else
        {
            // second pass through, as result of timeout -> dispatch
            String fileName = request.getServletPath();
            byte[] dataBytes = loadContentFileBytes(fileName);

            response.setContentLength(dataBytes.length);

            ServletOutputStream out = response.getOutputStream();

            if (fileName.endsWith("txt"))
                response.setContentType("text/plain");
            else if (fileName.endsWith("mp3"))
                response.setContentType("audio/mpeg");
            response.setHeader("ETag","W/etag-" + fileName);

            out.write(dataBytes);
            // no need to call AsyncContext.complete() from here
            // in fact, it will cause an IllegalStateException if we do
            // ctx.complete();
        }
    }

    @Override
    public void onComplete(AsyncEvent event) throws IOException
    {
    }

    @Override
    public void onTimeout(AsyncEvent event) throws IOException
    {
        event.getAsyncContext().dispatch();
    }

    @Override
    public void onError(AsyncEvent event) throws IOException
    {
    }

    @Override
    public void onStartAsync(AsyncEvent event) throws IOException
    {
    }
}
