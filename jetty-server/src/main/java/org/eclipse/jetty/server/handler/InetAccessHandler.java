//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.IncludeExclude;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.InetAddressSet;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * InetAddress Access Handler
 * <p>
 * Controls access to the wrapped handler using the real remote IP. Control is provided
 * by and {@link IncludeExcludeSet} over a {@link InetAddressSet}. This handler
 * uses the real internet address of the connection, not one reported in the forwarded
 * for headers, as this cannot be as easily forged.
 * <p>
 * Additionally, there may be times when you want to only apply this handler to a subset
 * of your connectors. For example maybe you want to have HTTPS wide open but have HTTP
 * restricted. In this situation you can use <b>connectorNames</b> to specify the connector
 * names that you want this IP access filter to apply to.
 */
public class InetAccessHandler extends HandlerWrapper
{
    private static final Logger LOG = Log.getLogger(InetAccessHandler.class);

    private final IncludeExcludeSet<String, InetAddress> _set = new IncludeExcludeSet<>(InetAddressSet.class);
    private final IncludeExclude<String> _connectorNames = new IncludeExclude<>();

    /**
     * Includes an InetAddress pattern
     *
     * @param pattern InetAddress pattern to include
     * @see InetAddressSet
     */
    public void include(String pattern)
    {
        _set.include(pattern);
    }

    /**
     * Includes InetAddress patterns
     *
     * @param patterns InetAddress patterns to include
     * @see InetAddressSet
     */
    public void include(String... patterns)
    {
        _set.include(patterns);
    }

    /**
     * Excludes an InetAddress pattern
     *
     * @param pattern InetAddress pattern to exclude
     * @see InetAddressSet
     */
    public void exclude(String pattern)
    {
        _set.exclude(pattern);
    }

    /**
     * Excludes InetAddress patterns
     *
     * @param patterns InetAddress patterns to exclude
     * @see InetAddressSet
     */
    public void exclude(String... patterns)
    {
        _set.exclude(patterns);
    }

    /**
     * Includes a connector name
     *
     * @param name Connector name to include in this handler
     */
     public void includeConnectorName(String name) {
         _connectorNames.include(name);
     }

     /**
     * Excludes a connector name.
     *
     * @param name Connector name to exclude in this handler.
     */
     public void excludeConnectorName(String name) {
         _connectorNames.exclude(name);
     }

    /**
     * Checks the incoming request against the whitelist and blacklist
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
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
     * @param address the inetAddress to check
     * @param baseRequest the base request to check
     * @param request the HttpServletRequest request to check
     * @return true if inetAddress and request are allowed
     */
    protected boolean isAllowed(InetAddress address, Request baseRequest, HttpServletRequest request)
    {
        String connectorName = baseRequest.getHttpChannel().getConnector().getName();

        boolean allowed = (connectorName != null &&
          (!_connectorNames.getIncluded().isEmpty() || !_connectorNames.getExcluded().isEmpty()) &&
          !_connectorNames.test(connectorName)) || _set.test(address);
        if (LOG.isDebugEnabled())
            LOG.debug("{} {} {} for {}", this, allowed ? "allowed" : "denied", address, request);
        return allowed;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpBeans(out, indent, _set.getIncluded(), _set.getExcluded(), _connectorNames.getIncluded(), _connectorNames.getExcluded());
    }
}
