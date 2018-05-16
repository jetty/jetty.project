package org.eclipse.jetty.session.infinispan;

import org.eclipse.jetty.server.session.SessionData;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.commons.api.BasicCache;

public class RemoteQueryManagerFactory implements QueryManagerFactory
{

    @Override
    public QueryManager getQueryManager(BasicCache<String, SessionData> cache)
    {
        if (RemoteCache.class.equals(cache.getClass()))
            throw new IllegalArgumentException("Argument is not of type RemoteCache");
        
        return new RemoteQueryManager((RemoteCache<String, SessionData>)cache);
    }

}
