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

package org.eclipse.jetty.servlets;

import java.io.IOException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@SuppressWarnings("serial")
public class AsyncTimeoutDispatchWrite extends AbstractFileContentServlet implements AsyncListener
{
    public static class Default extends AsyncTimeoutDispatchWrite
    {
        public Default()
        {
            super(true);
        }
    }

    public static class Passed extends AsyncTimeoutDispatchWrite
    {
        public Passed()
        {
            super(false);
        }
    }

    private final boolean originalReqResp;

    public AsyncTimeoutDispatchWrite(boolean originalReqResp)
    {
        this.originalReqResp = originalReqResp;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        AsyncContext ctx = (AsyncContext)request.getAttribute(AsyncContext.class.getName());
        if (ctx == null)
        {
            // First pass through
            if (originalReqResp)
            {
                // Use Original Request & Response
                ctx = request.startAsync();
            }
            else
            {
                // Pass Request & Response
                ctx = request.startAsync(request, response);
            }
            ctx.addListener(this);
            ctx.setTimeout(200);
            request.setAttribute(AsyncContext.class.getName(), ctx);
        }
        else
        {
            // second pass through, as result of timeout -> dispatch
            String fileName = request.getPathInfo();
            byte[] dataBytes = loadContentFileBytes(fileName);

            response.setContentLength(dataBytes.length);

            ServletOutputStream out = response.getOutputStream();

            if (fileName.endsWith("txt"))
                response.setContentType("text/plain");
            else if (fileName.endsWith("mp3"))
                response.setContentType("audio/mpeg");
            response.setHeader("ETag", "W/etag-" + fileName);

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
