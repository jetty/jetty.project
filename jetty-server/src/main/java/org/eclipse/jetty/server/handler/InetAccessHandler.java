//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.InetAddressSet;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * InetAddress Access Handler
 * <p>
 * Controls access to the wrapped handler using the real remote IP. Control is
 * provided by and {@link IncludeExcludeSet} over a {@link InetAddressSet}. This
 * handler uses the real internet address of the connection, not one reported in
 * the forwarded for headers, as this cannot be as easily forged.
 * <p>
 * Additionally, there may be times when you want to only apply this handler to
 * a subset of your connectors. In this situation you can use
 * <b>connectorNames</b> to specify the connector names that you want this IP
 * access filter to apply to.
 */
public class InetAccessHandler extends HandlerWrapper
{
    private static final Logger LOG = Log.getLogger(InetAccessHandler.class);

    private final IncludeExcludeSet<String, InetAddress> _addrs = new IncludeExcludeSet<>(InetAddressSet.class);
    private final PathMappings<IncludeExcludeSet<String, InetAddress>> _pathMappings = new PathMappings<>();
    private final IncludeExclude<String> _connectorNames = new IncludeExclude<>();

    /**
     * Clears all the includes, excludes, included connector names and excluded
     * connector names.
     */
    public void clear()
    {
        _addrs.clear();
        _connectorNames.clear();
        _pathMappings.reset();
    }

    /**
     * Includes an InetAddress pattern with an optional URI mapping for the pattern.
     *
     * The InetAddress pattern is separated from the URI pattern using the "|" (pipe)
     * character. URI patterns follow the servlet specification for simple * prefix and
     * suffix wild cards (e.g. /, /foo, /foo/bar, /foo/bar/*, *.baz).
     *
     * @param pattern InetAddress pattern to include
     * @see InetAddressSet
     */
    public void include(String pattern)
    {
        int index = pattern.indexOf('|');
        if (index > 0)
        {
            String addr = pattern.substring(0, index);
            String path = pattern.substring(index + 1);
            ensureMapping(path).include(addr);
        }
        else
        {
            _addrs.include(pattern);
        }
    }

    /**
     * Includes InetAddress patterns
     *
     * @param patterns InetAddress patterns to include
     * @see InetAddressSet
     */
    public void include(String... patterns)
    {
        _addrs.include(patterns);
    }

    /**
     * Excludes an InetAddress pattern with an optional URI mapping for the pattern.

     * The InetAddress pattern is separated from the URI pattern using the "|" (pipe)
     * character. URI patterns follow the servlet specification for simple * prefix and
     * suffix wild cards (e.g. /, /foo, /foo/bar, /foo/bar/*, *.baz).
     *
     * @param pattern InetAddress pattern to exclude
     * @see InetAddressSet
     */
    public void exclude(String pattern)
    {
        int index = pattern.indexOf('|');
        if (index > 0)
        {
            String addr = pattern.substring(0, index);
            String path = pattern.substring(index + 1);
            ensureMapping(path).exclude(addr);
        }
        else
        {
            _addrs.exclude(pattern);
        }
    }

    /**
     * Excludes InetAddress patterns
     *
     * @param patterns InetAddress patterns to exclude
     * @see InetAddressSet
     */
    public void exclude(String... patterns)
    {
        _addrs.exclude(patterns);
    }

    /**
     * Includes a connector name.
     *
     * @param name Connector name to include in this handler.
     */
    public void includeConnector(String name)
    {
        _connectorNames.include(name);
    }

    /**
     * Excludes a connector name.
     *
     * @param name Connector name to exclude in this handler.
     */
    public void excludeConnector(String name)
    {
        _connectorNames.exclude(name);
    }

    /**
     * Includes connector names.
     *
     * @param names Connector names to include in this handler.
     */
    public void includeConnectors(String... names)
    {
        _connectorNames.include(names);
    }

    /**
     * Excludes connector names.
     *
     * @param names Connector names to exclude in this handler.
     */
    public void excludeConnectors(String... names)
    {
        _connectorNames.exclude(names);
    }

    /**
     * Checks the incoming request against the whitelist and blacklist
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        // Get the real remote IP (not the one set by the forwarded headers (which may be forged))
        HttpChannel channel = baseRequest.getHttpChannel();
        if (channel != null)
        {
            EndPoint endp = channel.getEndPoint();
            if (endp != null)
            {
                InetSocketAddress address = endp.getRemoteAddress();
                if (address != null && !isAllowed(address.getAddress(), baseRequest, request))
                {
                    response.sendError(HttpStatus.FORBIDDEN_403);
                    baseRequest.setHandled(true);
                    return;
                }
            }
        }

        getHandler().handle(target, baseRequest, request, response);
    }

    /**
     * Checks if specified address and request are allowed by current InetAddress rules.
     *
     * @param addr the inetAddress to check
     * @param baseRequest the base request to check
     * @param request the HttpServletRequest request to check
     * @return true if inetAddress and request are allowed
     */
    protected boolean isAllowed(InetAddress addr, Request baseRequest, HttpServletRequest request)
    {
        // If this connector is not in the subset of connectors to apply this filter to, the request is simply allowed through.
        Connector connector = baseRequest.getHttpChannel().getConnector();
        String connectorName = connector.getName();
        boolean filterAppliesToConnector = _connectorNames.test(connectorName);
        if (!filterAppliesToConnector)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Handler does not apply to connector {}", connector);
            return true;
        }

        MappingResult allowedByPath = matchMappings(baseRequest.getPathInfo(), addr);
        Boolean allowedByAddr = _addrs.isIncludedAndNotExcluded(addr);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("connectorName = {}/{} addr={}/{} allowedByAddr={} allowedByPath={}",
                connectorName, _connectorNames, addr, _addrs, allowedByAddr, allowedByPath);
        }

        // Not allowed if it was specifically excluded anywhere.
        if (Boolean.FALSE.equals(allowedByAddr) || MappingResult.FALSE.equals(allowedByPath))
            return false;

        // If either set has any includes, then we must be included by one of them.
        if (_addrs.hasIncludes() || allowedByPath.hasIncludes())
            return Boolean.TRUE.equals(allowedByAddr) || MappingResult.TRUE.equals(allowedByPath);

        return true;
    }

    private MappingResult matchMappings(String path, InetAddress addr)
    {
        List<MappedResource<IncludeExcludeSet<String, InetAddress>>> matches = _pathMappings.getMatches(path);

        boolean hasIncludes = false;
        boolean isIncluded = false;
        for (MappedResource<IncludeExcludeSet<String, InetAddress>> resource : matches)
        {
            IncludeExcludeSet<String, InetAddress> set = resource.getResource();
            if (set.hasIncludes())
                hasIncludes = true;

            Boolean b = set.isIncludedAndNotExcluded(addr);
            if (Boolean.FALSE.equals(b))
                return MappingResult.FALSE;

            if (Boolean.TRUE.equals(b))
                isIncluded = true;
        }

        return isIncluded ? MappingResult.TRUE : (hasIncludes ? MappingResult.HAS_INCLUDES : MappingResult.NO_INCLUDES);
    }

    private IncludeExcludeSet<String, InetAddress> ensureMapping(String path)
    {
        ServletPathSpec pathSpec = new ServletPathSpec(path);
        IncludeExcludeSet<String, InetAddress> names = _pathMappings.get(pathSpec);
        if (names == null)
        {
            names = new IncludeExcludeSet<>(InetAddressSet.class);
            _pathMappings.put(pathSpec, names);
        }
        return names;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            new DumpableCollection("included", _addrs.getIncluded()),
            new DumpableCollection("excluded", _addrs.getExcluded()),
            new DumpableCollection("includedConnector", _connectorNames.getIncluded()),
            new DumpableCollection("excludedConnector", _connectorNames.getExcluded()));
    }

    private enum MappingResult
    {
        TRUE,
        FALSE,
        NO_INCLUDES,
        HAS_INCLUDES;

        boolean hasIncludes()
        {
            return TRUE.equals(this) || HAS_INCLUDES.equals(this);
        }
    }
}
