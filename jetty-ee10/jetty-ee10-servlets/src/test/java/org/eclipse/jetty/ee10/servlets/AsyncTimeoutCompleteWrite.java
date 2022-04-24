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

package org.eclipse.jetty.ee10.servlets;

import java.io.IOException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;

/**
 * Respond with requested content, but via AsyncContext manipulation.
 * <p>
 *
 * <pre>
 *   1) startAsync
 *   2) AsyncContext.setTimeout()
 *   3) onTimeout()
 *   4) send-response
 *   5) AsyncContext.complete()
 * </pre>
 */
@SuppressWarnings("serial")
public abstract class AsyncTimeoutCompleteWrite extends AbstractFileContentServlet implements AsyncListener
{
    public static class Default extends AsyncTimeoutCompleteWrite
    {
        public Default()
        {
            super(true);
        }
    }

    public static class Passed extends AsyncTimeoutCompleteWrite
    {
        public Passed()
        {
            super(false);
        }
    }

    private final boolean originalReqResp;

    public AsyncTimeoutCompleteWrite(boolean originalReqResp)
    {
        this.originalReqResp = originalReqResp;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        assertThat("'filename' request attribute shouldn't be declared", request.getAttribute("filename"), nullValue());

        AsyncContext ctx = (AsyncContext)request.getAttribute(this.getClass().getName());
        assertThat("AsyncContext (shouldn't be in request attribute)", ctx, nullValue());

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
        String fileName = request.getPathInfo();
        request.setAttribute("filename", fileName);
        ctx.addListener(this);
        ctx.setTimeout(20);

        // Setup indication of a redispatch (which this scenario shouldn't do)
        request.setAttribute(this.getClass().getName(), ctx);
    }

    @Override
    public void onComplete(AsyncEvent event) throws IOException
    {
    }

    @Override
    public void onTimeout(AsyncEvent event) throws IOException
    {
        HttpServletRequest request = (HttpServletRequest)event.getSuppliedRequest();
        HttpServletResponse response = (HttpServletResponse)event.getSuppliedResponse();

        String fileName = (String)request.getAttribute("filename");
        byte[] dataBytes = loadContentFileBytes(fileName);

        response.setContentLength(dataBytes.length);

        ServletOutputStream out = response.getOutputStream();

        if (fileName.endsWith("txt"))
            response.setContentType("text/plain");
        else if (fileName.endsWith("mp3"))
            response.setContentType("audio/mpeg");
        response.setHeader("ETag", "W/etag-" + fileName);

        out.write(dataBytes);

        event.getAsyncContext().complete();
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
