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

package org.eclipse.jetty.server.handler;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.InetAddressPattern;
import org.eclipse.jetty.util.InetAddressSet;

/**
 * InetAddress Access Handler
 * <p>
 * Controls access to the wrapped handler using the real remote IP. Control is
 * provided by and {@link IncludeExcludeSet} over a {@link InetAddressSet}. This
 * handler uses the real internet address of the connection, not one reported in
 * the forwarded for headers, as this cannot be as easily forged.
 * </p>
 */
public class InetAccessHandler extends ConditionalHandler.Forbidden
{
    public InetAccessHandler()
    {
        this(null);
    }

    public InetAccessHandler(Handler handler)
    {
        super(handler);
    }

    /**
     * Includes an InetAccess pattern with an optional connector name, address and URI mapping.
     *
     * <p>The connector name is separated from the InetAddress pattern with an '@' character,
     * and the InetAddress pattern is separated from the URI pattern using the "|" (pipe)
     * character. URI patterns follow the servlet specification for simple * prefix and
     * suffix wild cards (e.g. /, /foo, /foo/bar, /foo/bar/*, *.baz).</p>
     *
     * <br>Examples:
     * <ul>
     * <li>"connector1@127.0.0.1|/foo"</li>
     * <li>"127.0.0.1|/foo"</li>
     * <li>"connector1@127.0.0.1"</li>
     * <li>"127.0.0.1"</li>
     * </ul>
     *
     * @param pattern InetAccess pattern to include
     * @see InetAddressSet
     */
    public void include(String pattern)
    {
        includeExclude(true, pattern);
    }

    /**
     * Includes InetAccess patterns
     *
     * @param patterns InetAddress patterns to include
     * @see InetAddressSet
     */
    public void include(String... patterns)
    {
        for (String pattern : patterns)
        {
            include(pattern);
        }
    }

    /**
     * Includes an InetAccess entry.
     *
     * @param connectorName optional name of a connector to include.
     * @param addressPattern optional InetAddress pattern to include.
     * @param pathSpec optional pathSpec to include.
     */
    public void include(String connectorName, String addressPattern, PathSpec pathSpec)
    {
        include(from(connectorName, InetAddressPattern.from(addressPattern), null, pathSpec));
    }

    /**
     * Excludes an InetAccess entry pattern with an optional connector name, address and URI mapping.
     *
     * <p>The connector name is separated from the InetAddress pattern with an '@' character,
     * and the InetAddress pattern is separated from the URI pattern using the "|" (pipe)
     * character. A method name is separated from the URI pattern using the ">" character.
     * URI patterns follow the servlet specification for simple * prefix and
     * suffix wild cards (e.g. /, /foo, /foo/bar, /foo/bar/*, *.baz).</p>
     *
     * <br>Examples:
     * <ul>
     * <li>"connector1@127.0.0.1|/foo"</li>
     * <li>"127.0.0.1|/foo"</li>
     * <li>"127.0.0.1>GET|/foo"</li>
     * <li>"127.0.0.1>GET"</li>
     * <li>"connector1@127.0.0.1"</li>
     * <li>"127.0.0.1"</li>
     * </ul>
     *
     * @param pattern InetAddress pattern to exclude
     * @see InetAddressSet
     */
    public void exclude(String pattern)
    {
        includeExclude(false, pattern);
    }

    /**
     * Excludes InetAccess patterns
     *
     * @param patterns InetAddress patterns to exclude
     * @see InetAddressSet
     */
    public void exclude(String... patterns)
    {
        for (String pattern : patterns)
        {
            exclude(pattern);
        }
    }

    /**
     * Excludes an InetAccess entry.
     *
     * @param connectorName optional name of a connector to exclude.
     * @param addressPattern optional InetAddress pattern to exclude.
     * @param pathSpec optional pathSpec to exclude.
     */
    public void exclude(String connectorName, String addressPattern, PathSpec pathSpec)
    {
        exclude(from(connectorName, InetAddressPattern.from(addressPattern), null, pathSpec));
    }

    private void includeExclude(boolean include, String pattern)
    {
        String path = null;
        int pathIndex = pattern.indexOf('|');
        if (pathIndex >= 0)
        {
            path = pattern.substring(pathIndex + 1);
            pattern = pattern.substring(0, pathIndex);
        }

        String method = null;
        int methodIndex = pattern.indexOf('>');
        if (methodIndex >= 0)
        {
            method = pattern.substring(methodIndex + 1);
            pattern = pattern.substring(0, methodIndex);
        }

        String connector = null;
        int connectorIndex = pattern.indexOf('@');
        if (connectorIndex >= 0)
            connector = pattern.substring(0, connectorIndex);

        String addr = null;
        int addrStart = (connectorIndex < 0) ? 0 : connectorIndex + 1;
        int addrEnd = (pathIndex < 0) ? pattern.length() : pathIndex;
        if (addrStart != addrEnd)
            addr = pattern.substring(addrStart, addrEnd);

        if (include)
            include(from(connector, addr, method, path));
        else
            exclude(from(connector, addr, method, path));
    }
}
