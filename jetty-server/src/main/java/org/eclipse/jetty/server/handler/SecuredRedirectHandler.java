//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
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
 * <p>SecuredRedirectHandler redirects from {@code http} to {@code https}.</p>
 * <p>SecuredRedirectHandler uses the information present in {@link HttpConfiguration}
 * attempting to redirect to the {@link HttpConfiguration#getSecureScheme()} and
 * {@link HttpConfiguration#getSecurePort()} for any request that
 * {@link HttpServletRequest#isSecure()} is false.</p>
 */
public class SecuredRedirectHandler extends HandlerWrapper
{
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        HttpChannel channel = baseRequest.getHttpChannel();
        if (baseRequest.isSecure() || channel == null)
        {
            // Nothing to do here.
            super.handle(target, baseRequest, request, response);
            return;
        }

        baseRequest.setHandled(true);

        HttpConfiguration httpConfig = channel.getHttpConfiguration();
        if (httpConfig == null)
        {
            response.sendError(HttpStatus.FORBIDDEN_403, "Missing HttpConfiguration");
            return;
        }

        int securePort = httpConfig.getSecurePort();
        if (securePort > 0)
        {
            String secureScheme = httpConfig.getSecureScheme();
            String url = URIUtil.newURI(secureScheme, baseRequest.getServerName(), securePort, baseRequest.getRequestURI(), baseRequest.getQueryString());
            response.setContentLength(0);
            baseRequest.getResponse().sendRedirect(HttpServletResponse.SC_MOVED_TEMPORARILY, url, true);
        }
        else
        {
            response.sendError(HttpStatus.FORBIDDEN_403, "HttpConfiguration.securePort not configured");
        }
    }
}
