//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.jupiter.api.Test;

/**
 * NullSessionCacheTest
 */
public class NullSessionCacheTest
{
    @Test
    public void testEvictOnExit() throws Exception
    {
        Server server = new Server();

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);       
        context.setContextPath("/test");
        context.setServer(server);

        NullSessionCacheFactory cacheFactory = new NullSessionCacheFactory();
        
        NullSessionCache cache = (NullSessionCache)cacheFactory.getSessionCache(context.getSessionHandler());

        TestSessionDataStore store = new TestSessionDataStore();
        cache.setSessionDataStore(store);
        context.getSessionHandler().setSessionCache(cache);
        context.start();
        
        //make a session
        long now = System.currentTimeMillis();
        SessionData data = store.newSessionData("1234", now-20, now-10, now-20, TimeUnit.MINUTES.toMillis(10));
        data.setExpiry(now+TimeUnit.DAYS.toMillis(1));
        Session session = cache.newSession(null, data); //mimic a request making a session
        session.complete(); //mimic request leaving session
        cache.put("1234", session); //null cache doesn't store the session
        assertFalse(cache.contains("1234"));
        assertTrue(store.exists("1234"));
        
        session = cache.get("1234"); //get the session again
        session.access(now); //simulate a request
        session.complete(); //simulate a request leaving
        cache.put("1234", session); //finish with the session
        
        assertFalse(session.isResident());
    }

}
