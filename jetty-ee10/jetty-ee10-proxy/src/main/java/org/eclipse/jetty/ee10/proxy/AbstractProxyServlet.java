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

package org.eclipse.jetty.ee10.proxy;

import jakarta.servlet.ServletContext;
import org.eclipse.jetty.client.HttpClient;

/**
 * <p>Abstract base class for proxy servlets.</p>
 * <p>Forwards requests to another server either as a standard web reverse
 * proxy or as a transparent reverse proxy (as defined by RFC 7230).</p>
 * <p>To facilitate JMX monitoring, the {@link HttpClient} instance is set
 * as ServletContext attribute, prefixed with this servlet's name and
 * exposed by the mechanism provided by
 * {@link ServletContext#setAttribute(String, Object)}.</p>
 * <p>The following init parameters may be used to configure the servlet:</p>
 * <ul>
 * <li>preserveHost - the host header specified by the client is forwarded to the server</li>
 * <li>hostHeader - forces the host header to a particular value</li>
 * <li>viaHost - the name to use in the Via header: Via: http/1.1 &lt;viaHost&gt;</li>
 * <li>whiteList - comma-separated list of allowed proxy hosts</li>
 * <li>blackList - comma-separated list of forbidden proxy hosts</li>
 * </ul>
 * <p>In addition, see {@link #createHttpClient()} for init parameters
 * used to configure the {@link HttpClient} instance.</p>
 * <p>NOTE: By default the Host header sent to the server by this proxy
 * servlet is the server's host name. However, this breaks redirects.
 * Set {@code preserveHost} to {@code true} to make redirects working,
 * although this may break server's virtual host selection.</p>
 * <p>The default behavior of not preserving the Host header mimics
 * the default behavior of Apache httpd and Nginx, which both have
 * a way to be configured to preserve the Host header.</p>
 */
public abstract class AbstractProxyServlet extends org.eclipse.jetty.ee.proxy.AbstractProxyServlet
{
}
