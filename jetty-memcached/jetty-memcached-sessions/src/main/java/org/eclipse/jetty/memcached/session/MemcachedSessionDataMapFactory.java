//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.memcached.session;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.server.session.SessionDataMap;
import org.eclipse.jetty.server.session.SessionDataMapFactory;

/**
 * MemcachedSessionDataMapFactory
 */
public class MemcachedSessionDataMapFactory implements SessionDataMapFactory
{
    protected int _expiry;
    protected boolean _heartbeats = true;
    protected int[] _weights;
    protected List<InetSocketAddress> _addresses;

    /**
     * @param addresses host and port address of memcached servers
     */
    public void setAddresses(InetSocketAddress... addresses)
    {
        if (addresses == null)
            _addresses = null;
        else
        {
            _addresses = new ArrayList<>();
            for (InetSocketAddress a : addresses)
            {
                _addresses.add(a);
            }
        }
    }

    /**
     * @param weights the relative weight to give each server in the list of addresses
     */
    public void setWeights(int[] weights)
    {
        _weights = weights;
    }

    public int getExpirySec()
    {
        return _expiry;
    }

    /**
     * @param expiry time in secs that memcached item remains valid
     */
    public void setExpirySec(int expiry)
    {
        _expiry = expiry;
    }

    public boolean isHeartbeats()
    {
        return _heartbeats;
    }

    public void setHeartbeats(boolean heartbeats)
    {
        _heartbeats = heartbeats;
    }

    /**
     * @see org.eclipse.jetty.server.session.SessionDataMapFactory#getSessionDataMap()
     */
    @Override
    public SessionDataMap getSessionDataMap()
    {
        MemcachedSessionDataMap m = new MemcachedSessionDataMap(_addresses, _weights);
        m.setExpirySec(_expiry);
        m.setHeartbeats(isHeartbeats());
        return m;
    }
}
