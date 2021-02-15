//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.osgi.httpservice;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;

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
