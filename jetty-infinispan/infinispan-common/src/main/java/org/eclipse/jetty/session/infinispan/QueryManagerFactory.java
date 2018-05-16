package org.eclipse.jetty.session.infinispan;

import org.eclipse.jetty.server.session.SessionData;
import org.infinispan.commons.api.BasicCache;

public interface QueryManagerFactory
{
    public QueryManager getQueryManager(BasicCache<String, SessionData> cache);
}
