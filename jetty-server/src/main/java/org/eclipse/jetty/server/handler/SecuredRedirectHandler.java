//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.URIUtil;

/**
 * Secured Redirect Handler
 * <p>
 * Using information present in the {@link HttpConfiguration}, will attempt to redirect to the {@link HttpConfiguration#getSecureScheme()} and
 * {@link HttpConfiguration#getSecurePort()} for any request that {@link HttpServletRequest#isSecure()} == false.
 */
public class SecuredRedirectHandler extends AbstractHandler
{
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        HttpChannel channel = baseRequest.getHttpChannel();
        if (baseRequest.isSecure() || (channel == null))
        {
            // nothing to do
            return;
        }

        HttpConfiguration httpConfig = channel.getHttpConfiguration();
        if (httpConfig == null)
        {
            // no config, show error
            response.sendError(HttpStatus.FORBIDDEN_403, "No http configuration available");
            return;
        }

        if (httpConfig.getSecurePort() > 0)
        {
            String scheme = httpConfig.getSecureScheme();
            int port = httpConfig.getSecurePort();

            String url = URIUtil.newURI(scheme, baseRequest.getServerName(), port, baseRequest.getRequestURI(), baseRequest.getQueryString());
            response.setContentLength(0);
            response.sendRedirect(url);
        }
        else
        {
            response.sendError(HttpStatus.FORBIDDEN_403, "Not Secure");
        }

        baseRequest.setHandled(true);
    }
}
