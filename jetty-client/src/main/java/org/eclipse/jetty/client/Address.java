//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.net.InetSocketAddress;

/**
 * @version $Revision: 4135 $ $Date: 2008-12-02 11:57:07 +0100 (Tue, 02 Dec 2008) $
 */
public class Address
{
    private final String host;
    private final int port;

    public static Address from(String hostAndPort)
    {
        String host;
        int port;
        int colon = hostAndPort.indexOf(':');
        if (colon >= 0)
        {
            host = hostAndPort.substring(0, colon);
            port = Integer.parseInt(hostAndPort.substring(colon + 1));
        }
        else
        {
            host = hostAndPort;
            port = 0;
        }
        return new Address(host, port);
    }

    public Address(String host, int port)
    {
        if (host == null)
            throw new IllegalArgumentException("Host is null");
        
        this.host = host.trim();
        this.port = port;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Address that = (Address)obj;
        if (!host.equals(that.host)) return false;
        return port == that.port;
    }

    @Override
    public int hashCode()
    {
        int result = host.hashCode();
        result = 31 * result + port;
        return result;
    }

    public String getHost()
    {
        return host;
    }

    public int getPort()
    {
        return port;
    }

    public InetSocketAddress toSocketAddress()
    {
        return new InetSocketAddress(getHost(), getPort());
    }

    @Override
    public String toString()
    {
        return host + ":" + port;
    }
}
