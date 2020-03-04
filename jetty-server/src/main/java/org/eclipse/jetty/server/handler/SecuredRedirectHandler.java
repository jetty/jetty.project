//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
