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

import org.eclipse.jetty.server.session.SessionData;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.commons.api.BasicCache;

public class RemoteQueryManagerFactory implements QueryManagerFactory
{

    @Override
    public QueryManager getQueryManager(BasicCache<String, SessionData> cache)
    {
        if (!RemoteCache.class.isAssignableFrom(cache.getClass()))
            throw new IllegalArgumentException("Argument is not of type RemoteCache");

        return new RemoteQueryManager((RemoteCache<String, SessionData>)cache);
    }
}
