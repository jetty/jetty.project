package org.eclipse.jetty.session.infinispan;

import java.util.List;
import java.util.Set;

import org.eclipse.jetty.server.session.SessionData;
import org.infinispan.Cache;
import org.infinispan.query.Search;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.infinispan.query.dsl.QueryResult;

import static java.util.stream.Collectors.toSet;

public class EmbeddedQueryManager implements QueryManager
{
    private Cache<String, SessionData> _cache;
    private QueryFactory _factory;

    public EmbeddedQueryManager(Cache<String, SessionData> cache)
    {
        _cache = cache;
        _factory = Search.getQueryFactory(_cache);
    }

    @Override
    public Set<String> queryExpiredSessions(long time)
    {
        Query<InfinispanSessionData> expiredQuery = _factory.create("select id from org.eclipse.jetty.server.session.SessionData where " +
            " expiry <= :expiry and expiry > 0");
        expiredQuery.setParameter("expiry", time);
        
        @SuppressWarnings("rawtypes")
        QueryResult result = expiredQuery.execute();
        List<Object[]> list = result.list();
        Set<String> ids = list.stream().map(a -> (String)a[0]).collect(toSet());
        return ids;
    }

    @Override
    public Set<String> queryExpiredSessions()
    {
        return queryExpiredSessions(System.currentTimeMillis());
    }
}
