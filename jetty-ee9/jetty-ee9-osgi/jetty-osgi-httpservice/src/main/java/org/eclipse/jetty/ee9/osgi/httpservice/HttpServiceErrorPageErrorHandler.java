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

package org.eclipse.jetty.ee9.osgi.httpservice;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.server.Request;

/**
 * Extended error page handler.
 * Makes it easy to plug a servlet to handle errors thrown by the HttpService or
 * to use Jetty's ErrorPageErrorHandler API to plug custom error pages.
 */
public class HttpServiceErrorPageErrorHandler extends ErrorPageErrorHandler
{

    private static HttpServiceErrorPageErrorHandler INSTANCE;

    public static HttpServiceErrorPageErrorHandler getInstance()
    {
        return INSTANCE;
    }

    public HttpServiceErrorPageErrorHandler()
    {
        INSTANCE = this;
    }

    @Override
    public void handle(String target, Request baseRequest,
                       HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        if (HttpServiceErrorHandlerHelper.getCustomErrorHandler() != null)
        {
            try
            {
                HttpServiceErrorHandlerHelper.getCustomErrorHandler().service(request, response);
            }
            catch (ServletException e)
            {
                //well
            }
        }
        if (!response.isCommitted())
        {
            super.handle(target, baseRequest, request, response);
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        INSTANCE = null;
        super.doStop();
    }
}
