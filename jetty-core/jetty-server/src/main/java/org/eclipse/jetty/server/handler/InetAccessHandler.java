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

import java.io.IOException;

import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.InetAddressPattern;
import org.eclipse.jetty.util.InetAddressSet;
import org.eclipse.jetty.util.component.DumpableCollection;

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

    private final IncludeExcludeSet<PatternTuple, Request> _set = new IncludeExcludeSet<>(InetAccessSet.class);

    public InetAccessHandler()
    {
        this(null);
    }

    public InetAccessHandler(Handler handler)
    {
        super(handler);
    }

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
        include(connectorName, addressPattern, null, pathSpec);
    }

    /**
     * Includes an InetAccess entry.
     *
     * @param connectorName optional name of a connector to include or {@code null}.
     * @param addressPattern optional InetAddress pattern to include or {@code null}.
     * @param method optional method to include or {@code null}.
     * @param pathSpec optional pathSpec to include or {@code null}.
     */
    public void include(String connectorName, String addressPattern, String method, PathSpec pathSpec)
    {
        _set.include(new PatternTuple(connectorName, InetAddressPattern.from(addressPattern), method, pathSpec));
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
        exclude(connectorName, addressPattern, null, pathSpec);
    }

    /**
     * Excludes an InetAccess entry.
     *
     * @param connectorName optional name of a connector to exclude or {@code null}.
     * @param addressPattern optional InetAddress pattern to exclude or {@code null}.
     * @param method optional method to exclude or {@code null}.
     * @param pathSpec optional pathSpec to exclude or {@code null}.
     */
    public void exclude(String connectorName, String addressPattern, String method, PathSpec pathSpec)
    {
        _set.exclude(new PatternTuple(connectorName, InetAddressPattern.from(addressPattern), method, pathSpec));
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (!isAllowed(request))
            return false;
        return super.handle(request, response, callback);
    }

    /**
     * Checks if specified address and request are allowed by current InetAddress rules.
     *
     * @param request the HttpServletRequest request to check
     * @return true if inetAddress and request are allowed
     */
    protected boolean isAllowed(Request request)
    {
        return _set.test(request);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            new DumpableCollection("included", _set.getIncluded()),
            new DumpableCollection("excluded", _set.getExcluded()));
    }
}
