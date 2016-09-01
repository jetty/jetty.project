//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.InetAddressSet;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/**
 * Inet Address Access Handler
 * <p>
 * Controls access to the wrapped handler by the real remote IP. Control is provided
 * by and {@link IncludeExcludeSet} over a {@link InetAddressSet}. This handler
 * uses the real internet address of the connection, not one reported in the forwarded
 * for headers, as this cannot be as easily forged.
 * <p>

 */
public class InetAccessHandler extends HandlerWrapper
{
    private static final Logger LOG = Log.getLogger(InetAccessHandler.class);
    IncludeExcludeSet<String, InetAddress> _set = new IncludeExcludeSet<>(InetAddressSet.class);

    /* ------------------------------------------------------------ */
    /**
     * Creates new handler object
     */
    public InetAccessHandler()
    {
        super();
    }

    /* ------------------------------------------------------------ */
    /**
     * Include a InetAddress pattern
     * @see InetAddressSet
     * @param pattern InetAddress pattern to exclude
     */
    public void include(String pattern)
    {
        _set.include(pattern);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Include a InetAddress pattern
     * @see InetAddressSet
     * @param patterns InetAddress patterns to exclude
     */
    public void include(String... patterns)
    {
        _set.include(patterns);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Exclude a InetAddress pattern
     * @see InetAddressSet
     * @param pattern InetAddress pattern to exclude
     */
    public void exclude(String pattern)
    {
        _set.exclude(pattern);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Include a InetAddress pattern
     * @see InetAddressSet
     * @param patterns InetAddress patterns to exclude
     */
    public void exclude(String... patterns)
    {
        _set.exclude(patterns);
    }


    /* ------------------------------------------------------------ */
    /**
     * Checks the incoming request against the whitelist and blacklist
     *
     * @see org.eclipse.jetty.server.handler.HandlerWrapper#handle(java.lang.String, org.eclipse.jetty.server.Request, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // Get the real remote IP (not the one set by the forwarded headers (which may be forged))
        HttpChannel channel = baseRequest.getHttpChannel();
        if (channel!=null)
        {
            EndPoint endp=channel.getEndPoint();
            if (endp!=null)
            {
                InetSocketAddress address = endp.getRemoteAddress();
                if (address!=null && !isAllowed(address.getAddress()))
                {
                    response.sendError(HttpStatus.FORBIDDEN_403);
                    baseRequest.setHandled(true);
                    return;
                }
            }
        }

        getHandler().handle(target,baseRequest, request, response);
    }

    /* ------------------------------------------------------------ */
    /**
     * Check if specified request is allowed by current IPAccess rules.
     *
     * @param address internet address
     * @return true if address is allowed
     *
     */
    protected boolean isAllowed(InetAddress address)
    {
        return _set.test(address);
    }

    /* ------------------------------------------------------------ */
    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpBeans(out,indent,_set.getIncluded(),_set.getExcluded());
    }
 }
