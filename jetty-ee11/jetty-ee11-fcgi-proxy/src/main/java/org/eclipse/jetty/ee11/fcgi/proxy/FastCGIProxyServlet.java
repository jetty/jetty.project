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

package org.eclipse.jetty.ee11.fcgi.proxy;

/**
 * Specific implementation of {@link org.eclipse.jetty.ee11.proxy.AsyncProxyServlet.Transparent} for FastCGI.
 * <p>
 * This servlet accepts an HTTP request and transforms it into a FastCGI request
 * that is sent to the FastCGI server specified in the {@code proxyTo}
 * init-param.
 * <p>
 * This servlet accepts these additional {@code init-param}s:
 * <ul>
 * <li>{@code scriptRoot}, mandatory, that must be set to the directory where
 * the application that must be served via FastCGI is installed and corresponds to
 * the FastCGI DOCUMENT_ROOT parameter</li>
 * <li>{@code scriptPattern}, optional, defaults to {@code (.+?\.php)},
 * that specifies a regular expression with at least 1 and at most 2 groups that specify
 * respectively:
 * <ul>
 * <li>the FastCGI SCRIPT_NAME parameter</li>
 * <li>the FastCGI PATH_INFO parameter</li>
 * </ul></li>
 * <li>{@code fastCGI.HTTPS}, optional, defaults to false, that specifies whether
 * to force the FastCGI {@code HTTPS} parameter to the value {@code on}</li>
 * <li>{@code fastCGI.envNames}, optional, a comma separated list of environment variable
 * names read via {@link System#getenv(String)} that are forwarded as FastCGI parameters.</li>
 * <li>{@code unixDomainPath}, optional, that specifies the Unix-Domain path the FastCGI
 * server listens to.</li>
 * </ul>
 *
 * @see TryFilesFilter
 */
public class FastCGIProxyServlet extends org.eclipse.jetty.ee.fcgi.proxy.FastCGIProxyServlet
{
}
