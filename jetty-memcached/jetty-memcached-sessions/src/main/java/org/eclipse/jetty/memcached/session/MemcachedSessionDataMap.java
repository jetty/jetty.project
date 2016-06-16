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

import java.io.IOException;

import org.eclipse.jetty.server.session.SessionContext;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionDataMap;

import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.XMemcachedClientBuilder;



/**
 * MemcachedSessionDataMap
 *
 * Uses memcached as a cache for SessionData.
 */
public class MemcachedSessionDataMap implements SessionDataMap
{
    public static final String DEFAULT_HOST = "localhost";
    public static final String DEFAULT_PORT = "11211";
    protected MemcachedClient _client;
    protected int _expirySec = 0;
    protected XMemcachedClientBuilder _builder;

    
    

    /**
     * @param host address of memcache server
     * @param port address of memcache server
     */
    public MemcachedSessionDataMap(String host, String port)
    {
        if (host == null || port == null)
            throw new IllegalArgumentException("Host: "+host+" port: "+port);
        _builder = new XMemcachedClientBuilder(host+":"+port);
    }
    
    
    
    /**
     * @return the builder
     */
    public XMemcachedClientBuilder getBuilder()
    {
        return _builder;
    }

 
    
    
    /**
     * @param sec
     */
    public void setExpirySec (int sec)
    {
        _expirySec = sec;
    }
    


    /** 
     * @see org.eclipse.jetty.server.session.SessionDataMap#initialize(org.eclipse.jetty.server.session.SessionContext)
     */
    @Override
    public void initialize(SessionContext context)
    {
        try
        {
            _client = _builder.build();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            throw new IllegalStateException(e);
        }
    }

    /** 
     * @see org.eclipse.jetty.server.session.SessionDataMap#load(java.lang.String)
     */
    @Override
    public SessionData load(String id) throws Exception
    {
        SessionData data = _client.get(id);
        return data;
    }

    
    /** 
     * @see org.eclipse.jetty.server.session.SessionDataMap#store(java.lang.String, org.eclipse.jetty.server.session.SessionData)
     */
    @Override
    public void store(String id, SessionData data) throws Exception
    {
        _client.set(id, _expirySec, data);
    }        

    
    /** 
     * @see org.eclipse.jetty.server.session.SessionDataMap#delete(java.lang.String)
     */
    @Override
    public boolean delete(String id) throws Exception
    {
        _client.delete(id);
        return true; //delete returns false if the value didn't exist
    }
}
