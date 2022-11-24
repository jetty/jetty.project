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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.server.Request;

/**
 * <p>Groups rules that apply only to one or more specific virtual hosts.</p>
 */
public class VirtualHostRuleContainer extends RuleContainer
{
    private final List<String> _virtualHosts = new ArrayList<>();

    /**
     * @return the virtual hosts to match
     */
    public List<String> getVirtualHosts()
    {
        return _virtualHosts;
    }

    /**
     * <p>Sets the virtual hosts to match for the rules within this container to be applied.</p>
     *
     * @param virtualHosts the virtual hosts to match
     */
    public void setVirtualHosts(List<String> virtualHosts)
    {
        _virtualHosts.clear();
        if (virtualHosts != null)
            virtualHosts.forEach(this::addVirtualHost);
    }

    /**
     * @param virtualHost the virtual host to add to the existing list of virtual hosts
     */
    public void addVirtualHost(String virtualHost)
    {
        _virtualHosts.add(normalizeHostName(virtualHost));
    }

    @Override
    public Processor matchAndApply(Processor input) throws IOException
    {
        if (_virtualHosts.isEmpty())
            return super.matchAndApply(input);

        String serverName = Request.getServerName(input);
        for (String virtualHost : getVirtualHosts())
        {
            if (virtualHost == null || virtualHost.equalsIgnoreCase(serverName))
                return super.matchAndApply(input);

            // Handle case-insensitive wildcard host names.
            if (virtualHost.startsWith("*.") &&
                virtualHost.regionMatches(true, 2, serverName, serverName.indexOf(".") + 1, virtualHost.length() - 2))
                return super.matchAndApply(input);
        }

        // No virtual host match, skip the other rules.
        return null;
    }

    private String normalizeHostName(String host)
    {
        if (host == null)
            return null;

        if (host.endsWith("."))
            return host.substring(0, host.length() - 1);

        return host;
    }
}
