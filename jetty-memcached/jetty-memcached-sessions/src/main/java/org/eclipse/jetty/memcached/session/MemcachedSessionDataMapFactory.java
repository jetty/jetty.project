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

package org.eclipse.jetty.memcached.session;

import org.eclipse.jetty.server.session.SessionDataMap;
import org.eclipse.jetty.server.session.SessionDataMapFactory;

/**
 * MemcachedSessionDataMapFactory
 *
 *
 */
public class MemcachedSessionDataMapFactory implements SessionDataMapFactory
{
    protected String _host = "localhost";
    protected String _port = "11211";
    protected int _expiry;
    
    
    
    
    public String getHost()
    {
        return _host;
    }


    public void setHost(String host)
    {
        _host = host;
    }


    public String getPort()
    {
        return _port;
    }


    public void setPort(String port)
    {
        _port = port;
    }



    public int getExpiry()
    {
        return _expiry;
    }


    public void setExpiry(int expiry)
    {
        _expiry = expiry;
    }
    
    /** 
     * @see org.eclipse.jetty.server.session.SessionDataMapFactory#getSessionDataMap()
     */
    @Override
    public SessionDataMap getSessionDataMap()
    {
        MemcachedSessionDataMap m = new MemcachedSessionDataMap(_host, _port);
        m.setExpirySec(_expiry);
        return m;
    }


}
