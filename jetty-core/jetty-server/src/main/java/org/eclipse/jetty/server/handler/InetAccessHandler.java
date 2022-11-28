//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.InetAddressPattern;
import org.eclipse.jetty.util.InetAddressSet;
import org.eclipse.jetty.util.component.DumpableCollection;

import static org.eclipse.jetty.server.handler.InetAccessSet.AccessTuple;
import static org.eclipse.jetty.server.handler.InetAccessSet.PatternTuple;

/**
 * InetAddress Access Handler
 * <p>
 * Controls access to the wrapped handler using the real remote IP. Control is
 * provided by and {@link IncludeExcludeSet} over a {@link InetAddressSet}. This
 * handler uses the real internet address of the connection, not one reported in
 * the forwarded for headers, as this cannot be as easily forged.
 * </p>
 */
public class InetAccessHandler extends Handler.Wrapper
{
    // TODO replace this handler with a general conditional handler wrapper.

    private final IncludeExcludeSet<PatternTuple, AccessTuple> _set = new IncludeExcludeSet<>(InetAccessSet.class);

    /**
     * Clears all the includes, excludes, included connector names and excluded
     * connector names.
     */
    public void clear()
    {
        _set.clear();
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
        _set.include(PatternTuple.from(pattern));
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
        _set.include(new PatternTuple(connectorName, InetAddressPattern.from(addressPattern), pathSpec));
    }

    /**
     * Excludes an InetAccess entry pattern with an optional connector name, address and URI mapping.
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
     * @param pattern InetAddress pattern to exclude
     * @see InetAddressSet
     */
    public void exclude(String pattern)
    {
        _set.exclude(PatternTuple.from(pattern));
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
        _set.exclude(new PatternTuple(connectorName, InetAddressPattern.from(addressPattern), pathSpec));
    }

    /**
     * Includes a connector name.
     *
     * @param name Connector name to include in this handler.
     * @deprecated use {@link InetAccessHandler#include(String)} instead.
     */
    @Deprecated
    public void includeConnector(String name)
    {
        throw new UnsupportedOperationException();
    }

    /**
     * Excludes a connector name.
     *
     * @param name Connector name to exclude in this handler.
     * @deprecated use {@link InetAccessHandler#include(String)} instead.
     */
    @Deprecated
    public void excludeConnector(String name)
    {
        _set.exclude(new PatternTuple(name, null, null));
    }

    /**
     * Includes connector names.
     *
     * @param names Connector names to include in this handler.
     * @deprecated use {@link InetAccessHandler#include(String)} instead.
     */
    @Deprecated
    public void includeConnectors(String... names)
    {
        throw new UnsupportedOperationException();
    }

    /**
     * Excludes connector names.
     *
     * @param names Connector names to exclude in this handler.
     * @deprecated use {@link InetAccessHandler#include(String)} instead.
     */
    @Deprecated
    public void excludeConnectors(String... names)
    {
        for (String name : names)
        {
            excludeConnector(name);
        }
    }

    @Override
    public void process(Request request, Response response, Callback callback) throws Exception
    {
        SocketAddress socketAddress = request.getConnectionMetaData().getRemoteSocketAddress();
        if (socketAddress instanceof InetSocketAddress inetSocketAddress &&
            !isAllowed(inetSocketAddress.getAddress(), request))
            return;
        super.process(request, response, callback);
    }

    /**
     * Checks if specified address and request are allowed by current InetAddress rules.
     *
     * @param addr the inetAddress to check
     * @param request the HttpServletRequest request to check
     * @return true if inetAddress and request are allowed
     */
    protected boolean isAllowed(InetAddress addr, Request request)
    {
        String connectorName = request.getConnectionMetaData().getConnector().getName();
        String path = Request.getPathInContext(request);
        return _set.test(new AccessTuple(connectorName, addr, path));
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            new DumpableCollection("included", _set.getIncluded()),
            new DumpableCollection("excluded", _set.getExcluded()));
    }
}
