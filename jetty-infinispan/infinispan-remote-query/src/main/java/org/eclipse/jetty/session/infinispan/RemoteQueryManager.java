//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.session.infinispan;

import java.util.List;
import java.util.Set;

import org.eclipse.jetty.server.session.SessionData;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.Search;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.infinispan.query.dsl.QueryResult;

import static java.util.stream.Collectors.toSet;

/**
 * RemoteQueryManager
 *
 * A QueryManager impl that supports doing queries against remote infinispan server.
 */
public class RemoteQueryManager implements QueryManager
{
    private RemoteCache<String, SessionData> _cache;
    private QueryFactory _factory;

    public RemoteQueryManager(RemoteCache<String, SessionData> cache)
    {
        _cache = cache;
        _factory = Search.getQueryFactory(_cache);
    }

    @Override
    public Set<String> queryExpiredSessions(long time)
    {
        Query<InfinispanSessionData> expiredQuery = _factory.create("select id from org_eclipse_jetty_session_infinispan.InfinispanSessionData where " +
            "expiry <= :expiry and expiry > 0");
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
