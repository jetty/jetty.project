//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.session.infinispan;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jetty.session.SessionContext;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.Search;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.infinispan.query.dsl.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.Collectors.toSet;

/**
 * RemoteQueryManager
 *
 * A QueryManager impl that supports doing queries against remote infinispan server.
 */
public class RemoteQueryManager implements QueryManager
{
    private static final Logger LOG = LoggerFactory.getLogger(RemoteQueryManager.class);
    private RemoteCache<String, InfinispanSessionData> _cache;
    private QueryFactory _factory;

    public RemoteQueryManager(RemoteCache<String, InfinispanSessionData> cache)
    {
        _cache = cache;
        _factory = Search.getQueryFactory(_cache);
    }

    @Override
    public Set<String> queryExpiredSessions(SessionContext sessionContext, long time)
    {
        Objects.requireNonNull(sessionContext);

        Query<InfinispanSessionData> expiredQuery = _factory.create("select id from org_eclipse_jetty_session_infinispan.InfinispanSessionData where " +
            " contextPath = :contextPath and expiry <= :expiry and expiry > 0");
        expiredQuery.setParameter("contextPath", sessionContext.getCanonicalContextPath());
        expiredQuery.setParameter("expiry", time);
        
        @SuppressWarnings("rawtypes")
        QueryResult result = expiredQuery.execute();
        List<Object[]> list = result.list();
        Set<String> ids = list.stream().map(a -> (String)a[0]).collect(toSet());
        return ids;
    }

    @Override
    public void deleteOrphanSessions(long time)
    {
        Query<InfinispanSessionData> deleteQuery = _factory.create("select id, contextPath, vhost from org_eclipse_jetty_session_infinispan.InfinispanSessionData where " +
            " expiry <= :expiry and expiry > 0");
        deleteQuery.setParameter("expiry", time);
        
        @SuppressWarnings("rawtypes")
        QueryResult result = deleteQuery.execute();
        List<Object[]> list = result.list();
        list.stream().forEach(a ->
        {
            String key = InfinispanKeyBuilder.build((String)a[1], (String)a[2], (String)a[0]);
            try
            {
                _cache.remove(key);
            }
            catch (Exception e)
            {
                LOG.warn("Error deleting {}", key, e);
            }
        });  
    }

    @Override
    public boolean exists(SessionContext sessionContext, String id)
    {
        Objects.requireNonNull(sessionContext);

        Query<InfinispanSessionData> existQuery = _factory.create("select id from org_eclipse_jetty_session_infinispan.InfinispanSessionData where" +
            " id = :id and contextPath = :contextPath and expiry > :time or expiry <= 0");
        existQuery.setParameter("id", id);
        existQuery.setParameter("contextPath", sessionContext.getCanonicalContextPath());
        existQuery.setParameter("time", System.currentTimeMillis());
        
        @SuppressWarnings("rawtypes")
        QueryResult result = existQuery.execute();
        List<Object[]> list = result.list();
        return !list.isEmpty();
    }
}
