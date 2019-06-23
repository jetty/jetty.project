package org.eclipse.jetty.session.infinispan;

import org.eclipse.jetty.server.session.SessionData;
import org.infinispan.Cache;
import org.infinispan.commons.api.BasicCache;

public class EmbeddedQueryManagerFactory implements QueryManagerFactory
{

    @Override
    public QueryManager getQueryManager(BasicCache<String, SessionData> cache)
    {
        if (!(cache instanceof Cache))
            throw new IllegalArgumentException("Argument was not of type Cache");

        return new EmbeddedQueryManager((Cache<String, SessionData>)cache);
    }
}
