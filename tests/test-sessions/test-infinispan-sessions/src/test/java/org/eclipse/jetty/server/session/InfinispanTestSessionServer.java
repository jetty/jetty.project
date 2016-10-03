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


package org.eclipse.jetty.server.session;

import org.eclipse.jetty.server.SessionIdManager;
import org.eclipse.jetty.session.infinispan.InfinispanSessionDataStore;
import org.eclipse.jetty.webapp.WebAppContext;
import org.infinispan.Cache;
import org.infinispan.commons.api.BasicCache;
import org.infinispan.commons.util.CloseableIteratorSet;

public class InfinispanTestSessionServer extends AbstractTestServer
{
    
  
    
    public InfinispanTestSessionServer(int port, int maxInactivePeriod, int scavengePeriod, int evictionPolicy, BasicCache config) throws Exception
    {
        super(port, maxInactivePeriod, scavengePeriod, evictionPolicy, config);
    }
    
 

    @Override
    public SessionHandler newSessionHandler()
    {
        SessionHandler handler =  new SessionHandler();
        InfinispanSessionDataStore sds = new InfinispanSessionDataStore();
        sds.setCache((BasicCache)_config);
        DefaultSessionCache ss = new DefaultSessionCache(handler);
        ss.setSessionDataStore(sds);
        handler.setSessionCache(ss);
        return handler;
    }

    public boolean exists (WebAppContext context, String id)
    {
        BasicCache cache = (BasicCache)_config;
        if (cache != null)
        {
            return cache.containsKey(((InfinispanSessionDataStore)(context.getSessionHandler().getSessionCache().getSessionDataStore())).getCacheKey(id));      
        }
        
        return false;
    }
    
    public Object get (WebAppContext context, String id)
    {
        BasicCache cache = (BasicCache)_config;
        if (cache != null)
        {
            return cache.get(((InfinispanSessionDataStore)(context.getSessionHandler().getSessionCache().getSessionDataStore())).getCacheKey(id));      
        }
        
        return null;
    }

    public void dumpCache ()
    {
        BasicCache cache = (BasicCache)_config;
        if (cache != null)
        {
            System.err.println(cache.getName()+" contains "+cache.size()+" entries");         
        }
    }

    public void clearCache ()
    {         
        BasicCache cache = (BasicCache)_config;

        if (cache != null)
            cache.clear();
    }

}
