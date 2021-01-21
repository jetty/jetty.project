//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.server.session.SessionData;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.Search;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;

/**
 * RemoteQueryManager
 *
 * A QueryManager impl that supports doing queries against remote infinispan server.
 */
public class RemoteQueryManager implements QueryManager
{
    private RemoteCache<String, SessionData> _cache;

    public RemoteQueryManager(RemoteCache<String, SessionData> cache)
    {
        _cache = cache;
    }

    @Override
    public Set<String> queryExpiredSessions(long time)
    {
        // TODO can the QueryFactory be created only once
        QueryFactory qf = Search.getQueryFactory(_cache);
        Query q = qf.from(InfinispanSessionData.class).select("id").having("expiry").lte(time).build();

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
