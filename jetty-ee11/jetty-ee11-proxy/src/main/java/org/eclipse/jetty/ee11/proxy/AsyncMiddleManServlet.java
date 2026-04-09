//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.proxy;

import java.net.URI;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.UnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.server.handler.ConnectHandler;

/**
 * <p>Servlet 3.1 asynchronous proxy servlet with capability
 * to intercept and modify request/response content.</p>
 * <p>Both the request processing and the I/O are asynchronous.</p>
 *
 * @see ProxyServlet
 * @see AsyncProxyServlet
 * @see ConnectHandler
 */
public class AsyncMiddleManServlet extends org.eclipse.jetty.ee.proxy.AsyncMiddleManServlet
{
    /**
     * <p>Convenience extension of {@link AsyncMiddleManServlet} that offers transparent proxy functionalities.</p>
     *
     * @see AbstractProxyServlet.TransparentDelegate
     */
    public static class Transparent extends AsyncMiddleManServlet
    {
        private final TransparentDelegate delegate = new TransparentDelegate(this);

        @Override
        public void init(ServletConfig config) throws ServletException
        {
            super.init(config);
            delegate.init(config);
        }

        @Override
        protected String rewriteTarget(HttpServletRequest request)
        {
            return delegate.rewriteTarget(request);
        }
    }

    /**
     * <p>Utility class that implement transparent proxy functionalities.</p>
     * <p>Configuration parameters:</p>
     * <ul>
     * <li>{@code proxyTo} - a mandatory URI like {@code http://host:80/context} to which the request is proxied.</li>
     * <li>{@code prefix} - an optional URI prefix that is stripped from the start of the forwarded URI.</li>
     * </ul>
     * <p>For example, if a request is received at "/foo/bar", the {@code proxyTo} parameter is
     * {@code http://host:80/context} and the {@code prefix} parameter is "/foo", then the request would
     * be proxied to {@code http://host:80/context/bar}.
     */
    protected static class TransparentDelegate
    {
        private final org.eclipse.jetty.ee.proxy.AbstractProxyServlet proxyServlet;
        private String _proxyTo;
        private String _prefix;

        protected TransparentDelegate(org.eclipse.jetty.ee.proxy.AbstractProxyServlet proxyServlet)
        {
            this.proxyServlet = proxyServlet;
        }

        protected void init(ServletConfig config) throws ServletException
        {
            _proxyTo = config.getInitParameter("proxyTo");
            if (_proxyTo == null)
                throw new UnavailableException("Init parameter 'proxyTo' is required.");

            String prefix = config.getInitParameter("prefix");
            if (prefix != null)
            {
                if (!prefix.startsWith("/"))
                    throw new UnavailableException("Init parameter 'prefix' must start with a '/'.");
                _prefix = prefix;
            }

            // Adjust prefix value to account for context path
            String contextPath = config.getServletContext().getContextPath();
            _prefix = _prefix == null ? contextPath : (contextPath + _prefix);

            if (proxyServlet.getLogger().isDebugEnabled())
                proxyServlet.getLogger().debug("{} @ {} to {}", config.getServletName(), _prefix, _proxyTo);
        }

        protected String rewriteTarget(HttpServletRequest request)
        {
            String path = request.getRequestURI();
            if (!path.startsWith(_prefix))
                return null;

            StringBuilder uri = new StringBuilder(_proxyTo);
            if (_proxyTo.endsWith("/"))
                uri.setLength(uri.length() - 1);
            String rest = path.substring(_prefix.length());
            if (!rest.isEmpty())
            {
                if (!rest.startsWith("/"))
                    uri.append("/");
                uri.append(rest);
            }

            String query = request.getQueryString();
            if (query != null)
            {
                // Is there at least one path segment ?
                String separator = "://";
                if (uri.indexOf("/", uri.indexOf(separator) + separator.length()) < 0)
                    uri.append("/");
                uri.append("?").append(query);
            }
            URI rewrittenURI = URI.create(uri.toString()).normalize();

            if (!proxyServlet.validateDestination(rewrittenURI.getHost(), rewrittenURI.getPort()))
                return null;

            return rewrittenURI.toString();
        }
    }
}
