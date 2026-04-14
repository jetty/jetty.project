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

package org.eclipse.jetty.ee10.fcgi.proxy;

import jakarta.servlet.ServletContext;

/**
 * Inspired by nginx's try_files functionality.
 * <p>
 * This filter accepts the {@code files} init-param as a list of space-separated
 * file URIs. The special token {@code $path} represents the current request URL's
 * path (the portion after the context path).
 * <p>
 * Typical example of how this filter can be configured is the following:
 * <pre>
 * &lt;filter&gt;
 *     &lt;filter-name&gt;try_files&lt;/filter-name&gt;
 *     &lt;filter-class&gt;org.eclipse.jetty.fcgi.server.proxy.TryFilesFilter&lt;/filter-class&gt;
 *     &lt;init-param&gt;
 *         &lt;param-name&gt;files&lt;/param-name&gt;
 *         &lt;param-value&gt;/maintenance.html $path /index.php?p=$path&lt;/param-value&gt;
 *     &lt;/init-param&gt;
 * &lt;/filter&gt;
 * </pre>
 * For a request such as {@code /context/path/to/resource.ext}, this filter will
 * try to serve the {@code /maintenance.html} file if it finds it; failing that,
 * it will try to serve the {@code /path/to/resource.ext} file if it finds it;
 * failing that it will forward the request to {@code /index.php?p=/path/to/resource.ext}.
 * The last file URI specified in the list is therefore the "fallback" to which the request
 * is forwarded to in case no previous files can be found.
 * <p>
 * The files are resolved using {@link ServletContext#getResource(String)} to make sure
 * that only files visible to the application are served.
 *
 * @see FastCGIProxyServlet
 */
public class TryFilesFilter extends org.eclipse.jetty.ee.fcgi.proxy.TryFilesFilter
{
}
