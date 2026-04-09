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

package org.eclipse.jetty.ee11.servlets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.pathmap.PathSpecSet;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.StringUtil;

/**
 * Include Exclude Based Filter
 * <p>
 * This is an abstract filter which helps with filtering based on include/exclude of paths, mime types, and/or http methods.
 * <p>
 * Use the {@link #shouldFilter(HttpServletRequest, HttpServletResponse)} method to determine if a request/response should be filtered. If mime types are used,
 * it should be called after {@link jakarta.servlet.FilterChain#doFilter(jakarta.servlet.ServletRequest, jakarta.servlet.ServletResponse)} since the mime type may not
 * be written until then.
 * <p>
 * Supported init params:
 * <ul>
 * <li><code>includedPaths</code> - CSV of path specs to include</li>
 * <li><code>excludedPaths</code> - CSV of path specs to exclude</li>
 * <li><code>includedMimeTypes</code> - CSV of mime types to include</li>
 * <li><code>excludedMimeTypes</code> - CSV of mime types to exclude</li>
 * <li><code>includedHttpMethods</code> - CSV of http methods to include</li>
 * <li><code>excludedHttpMethods</code> - CSV of http methods to exclude</li>
 * </ul>
 * <p>
 * Path spec rules:
 * <ul>
 * <li>If the spec starts with <code>'^'</code> the spec is assumed to be a regex based path spec and will match with normal Java regex rules.</li>
 * <li>If the spec starts with <code>'/'</code> the spec is assumed to be a Servlet url-pattern rules path spec for either an exact match or prefix based
 * match.</li>
 * <li>If the spec starts with <code>'*.'</code> the spec is assumed to be a Servlet url-pattern rules path spec for a suffix based match.</li>
 * <li>All other syntaxes are unsupported.</li>
 * </ul>
 * <p>
 * CSVs are parsed with {@link StringUtil#csvSplit(String)}
 *
 * @see PathSpecSet
 * @see IncludeExcludeSet
 */
public abstract class IncludeExcludeBasedFilter extends org.eclipse.jetty.ee.servlets.IncludeExcludeBasedFilter
{
}
