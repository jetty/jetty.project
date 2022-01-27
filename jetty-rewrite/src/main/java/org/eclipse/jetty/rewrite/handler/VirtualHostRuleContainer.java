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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.util.ArrayUtil;

/**
 * Groups rules that apply only to a specific virtual host
 * or sets of virtual hosts
 */

public class VirtualHostRuleContainer extends RuleContainer
{
    private String[] _virtualHosts;

    /**
     * Set the virtual hosts that the rules within this container will apply to
     *
     * @param virtualHosts Array of virtual hosts that the rules within this container are applied to.
     * A null hostname or null/empty array means any hostname is acceptable.
     */
    public void setVirtualHosts(String[] virtualHosts)
    {
        if (virtualHosts == null)
        {
            _virtualHosts = virtualHosts;
        }
        else
        {
            _virtualHosts = new String[virtualHosts.length];
            for (int i = 0; i < virtualHosts.length; i++)
            {
                _virtualHosts[i] = normalizeHostname(virtualHosts[i]);
            }
        }
    }

    /**
     * Get the virtual hosts that the rules within this container will apply to
     *
     * @return Array of virtual hosts that the rules within this container are applied to.
     * A null hostname or null/empty array means any hostname is acceptable.
     */
    public String[] getVirtualHosts()
    {
        return _virtualHosts;
    }

    /**
     * @param virtualHost add a virtual host to the existing list of virtual hosts
     * A null hostname means any hostname is acceptable
     */
    public void addVirtualHost(String virtualHost)
    {
        _virtualHosts = ArrayUtil.addToArray(_virtualHosts, virtualHost, String.class);
    }

    /**
     * Process the contained rules if the request is applicable to the virtual hosts of this rule
     *
     * @param target target field to pass on to the contained rules
     * @param request request object to pass on to the contained rules
     * @param response response object to pass on to the contained rules
     */
    @Override
    public String matchAndApply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        if (_virtualHosts != null && _virtualHosts.length > 0)
        {
            String requestHost = normalizeHostname(request.getServerName());
            for (String ruleHost : _virtualHosts)
            {
                if (ruleHost == null ||
                    ruleHost.equalsIgnoreCase(requestHost) ||
                    (ruleHost.startsWith("*.") && ruleHost.regionMatches(true, 2, requestHost, requestHost.indexOf(".") + 1, ruleHost.length() - 2))
                )
                {
                    return apply(target, request, response);
                }
            }
        }
        else
        {
            return apply(target, request, response);
        }
        return null;
    }

    private String normalizeHostname(String host)
    {
        if (host == null)
            return null;

        if (host.endsWith("."))
            return host.substring(0, host.length() - 1);

        return host;
    }
}
