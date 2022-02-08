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

    @Override
    public SessionDataMap getSessionDataMap()
    {
        MemcachedSessionDataMap m = new MemcachedSessionDataMap(_addresses, _weights);
        m.setExpirySec(_expiry);
        m.setHeartbeats(isHeartbeats());
        return m;
    }
}
