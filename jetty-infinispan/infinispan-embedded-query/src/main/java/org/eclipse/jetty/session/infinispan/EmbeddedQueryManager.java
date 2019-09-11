package org.eclipse.jetty.session.infinispan;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.server.session.SessionData;
import org.infinispan.Cache;
import org.infinispan.query.Search;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;

public class EmbeddedQueryManager implements QueryManager
{
    private Cache<String, SessionData> _cache;

    public EmbeddedQueryManager(Cache<String, SessionData> cache)
    {
        _cache = cache;
    }

    @Override
    public Set<String> queryExpiredSessions(long time)
    {
        QueryFactory qf = Search.getQueryFactory(_cache);
        Query q = qf.from(SessionData.class).select("id").having("expiry").lte(time).build();

        List<Object[]> list = q.list();
        Set<String> ids = new HashSet<>();
        for (Object[] sl : list)
        {
            ids.add((String)sl[0]);
        }
        return ids;
    }

    @Override
    public Set<String> queryExpiredSessions()
    {
        return queryExpiredSessions(System.currentTimeMillis());
    }
}
