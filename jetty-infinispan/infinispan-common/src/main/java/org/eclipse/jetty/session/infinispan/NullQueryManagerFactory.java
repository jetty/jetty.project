package org.eclipse.jetty.session.infinispan;

import org.eclipse.jetty.server.session.SessionData;
import org.infinispan.commons.api.BasicCache;

public class NullQueryManagerFactory implements QueryManagerFactory
{
    @Override
    public QueryManager getQueryManager(BasicCache<String, SessionData> cache)
    {
        return null;
    }
}
