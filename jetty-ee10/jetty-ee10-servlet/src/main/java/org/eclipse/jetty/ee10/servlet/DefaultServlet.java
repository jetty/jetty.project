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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpContent;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ResourceService;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;

public class DefaultServlet extends HttpServlet
{
    private ResourceService _resourceService;

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        ContextHandler contextHandler = initContextHandler(config.getServletContext());

        _resourceService = new ResourceService();
        _resourceService.setResourceBase(contextHandler.getResourceBase());

        // TODO init other settings
    }

    protected ContextHandler initContextHandler(ServletContext servletContext)
    {
        ContextHandler.Context scontext = ContextHandler.getCurrentContext();
        if (scontext == null)
        {
            if (servletContext instanceof ContextHandler.Context)
                return ((ContextHandler.Context)servletContext).getContextHandler();
            else
                throw new IllegalArgumentException("The servletContext " + servletContext + " " +
                    servletContext.getClass().getName() + " is not " + ContextHandler.Context.class.getName());
        }
        else
            return scontext.getContextHandler();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        // TODO This constitutes the fast path, but we should fall back to using the servlet API when the casts below are not possible.
        Callback baseCallback = ((ServletContextRequest.ServletApiRequest)req).getRequest().getCallback();
        Response baseResponse = ((ServletContextResponse.ServletApiResponse)resp).getResponse().getWrapped();
        Request baseRequest = baseResponse.getRequest();

        HttpContent content = _resourceService.getContentFactory().getContent(req.getServletPath(), baseRequest.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize());
        if (content == null)
        {
            // no content
            resp.setStatus(404);
        }
        else
        {
            // serve content
            try
            {
                _resourceService.doGet(baseRequest, baseResponse, baseCallback, content);
            }
            catch (Exception e)
            {
                throw new ServletException(e);
            }
        }
    }

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
    {
        // TODO use service
        super.doHead(req, resp);
    }
}
