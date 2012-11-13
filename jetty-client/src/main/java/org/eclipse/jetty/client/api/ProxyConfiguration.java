//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.api;

import java.util.HashSet;
import java.util.Set;

public class ProxyConfiguration
{
    private final Set<String> excluded = new HashSet<>();
    private final String host;
    private final int port;

    public ProxyConfiguration(String host, int port)
    {
        this.host = host;
        this.port = port;
    }

    public String getHost()
    {
        return host;
    }

    public int getPort()
    {
        return port;
    }

    public boolean matches(String host, int port)
    {
        String hostPort = host + ":" + port;
        return !getExcludedHosts().contains(hostPort);
    }

    public Set<String> getExcludedHosts()
    {
        return excluded;
    }
}
