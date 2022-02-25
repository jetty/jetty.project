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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@SuppressWarnings("serial")
public abstract class AsyncScheduledDispatchWrite extends AbstractFileContentServlet
{
    public static class Default extends AsyncScheduledDispatchWrite
    {
        public Default()
        {
            super(true);
        }
    }

    public static class Passed extends AsyncScheduledDispatchWrite
    {
        public Passed()
        {
            super(false);
        }
    }

    private static class DispatchBack implements Runnable
    {
        private final AsyncContext ctx;

        public DispatchBack(AsyncContext ctx)
        {
            this.ctx = ctx;
        }

        @Override
        public void run()
        {
            ctx.dispatch();
        }
    }

    private final boolean originalReqResp;
    private ScheduledExecutorService scheduler;

    public AsyncScheduledDispatchWrite(boolean originalReqResp)
    {
        this.originalReqResp = originalReqResp;
    }

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        super.init(config);
        scheduler = Executors.newScheduledThreadPool(3);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        Boolean suspended = (Boolean)request.getAttribute("SUSPENDED");
        if (suspended == null || !suspended)
        {
            request.setAttribute("SUSPENDED", Boolean.TRUE);
            AsyncContext ctx;
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
            ctx.setTimeout(0);
            scheduler.schedule(new DispatchBack(ctx), 500, TimeUnit.MILLISECONDS);
        }
        else
        {
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
        }
    }
}
