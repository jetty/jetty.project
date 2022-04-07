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

package org.eclipse.jetty.server.session;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.AbstractSessionCache;
import org.eclipse.jetty.session.DefaultSessionIdManager;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionCookieTest
 */
public class SessionCookieTest
{

    public class MockSessionCache extends AbstractSessionCache
    {

        public MockSessionCache(SessionManager manager)
        {
            super(manager);
        }

        @Override
        public void shutdown()
        {
        }

        @Override
        public Session newSession(SessionData data)
        {
            return null;
        }

        @Override
        public Session doGet(String key)
        {
            return null;
        }

        @Override
        public Session doPutIfAbsent(String key, Session session)
        {
            return null;
        }

        @Override
        public Session doDelete(String key)
        {
            return null;
        }

        @Override
        public boolean doReplace(String id, Session oldValue, Session newValue)
        {
            return false;
        }

        @Override
        public Session newSession(HttpServletRequest request, SessionData data)
        {
            return null;
        }

        @Override
        protected Session doComputeIfAbsent(String id, Function<String, Session> mappingFunction)
        {
            return mappingFunction.apply(id);
        }
    }

    public class MockSessionIdManager extends DefaultSessionIdManager
    {
        public MockSessionIdManager(Server server)
        {
            super(server);
        }

        @Override
        public boolean isIdInUse(String id)
        {
            return false;
        }

        @Override
        public void expireAll(String id)
        {

        }

        @Override
        public String renewSessionId(String oldClusterId, String oldNodeId, HttpServletRequest request)
        {
            return "";
        }
    }

    @Test
    public void testSecureSessionCookie() throws Exception
    {
        Server server = new Server();
        MockSessionIdManager idMgr = new MockSessionIdManager(server);
        idMgr.setWorkerName("node1");
        SessionHandler mgr = new SessionHandler();
        MockSessionCache cache = new MockSessionCache(mgr);
        cache.setSessionDataStore(new NullSessionDataStore());
        mgr.setSessionCache(cache);
        mgr.setSessionIdManager(idMgr);

        long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

        Session session = new Session(mgr, new SessionData("123", "_foo", "0.0.0.0", now, now, now, 30));

        SessionCookieConfig sessionCookieConfig = mgr.getSessionCookieConfig();
        sessionCookieConfig.setSecure(true);

        //sessionCookieConfig.secure == true, always mark cookie as secure, irrespective of if requestIsSecure
        HttpCookie cookie = mgr.getSessionCookie(session, "/foo", true);
        assertTrue(cookie.isSecure());
        //sessionCookieConfig.secure == true, always mark cookie as secure, irrespective of if requestIsSecure
        cookie = mgr.getSessionCookie(session, "/foo", false);
        assertTrue(cookie.isSecure());

        //sessionCookieConfig.secure==false, setSecureRequestOnly==true, requestIsSecure==true
        //cookie should be secure: see SessionCookieConfig.setSecure() javadoc
        sessionCookieConfig.setSecure(false);
        cookie = mgr.getSessionCookie(session, "/foo", true);
        assertTrue(cookie.isSecure());

        //sessionCookieConfig.secure=false, setSecureRequestOnly==true, requestIsSecure==false
        //cookie is not secure: see SessionCookieConfig.setSecure() javadoc
        cookie = mgr.getSessionCookie(session, "/foo", false);
        assertFalse(cookie.isSecure());

        //sessionCookieConfig.secure=false, setSecureRequestOnly==false, requestIsSecure==false
        //cookie is not secure: not a secure request
        mgr.setSecureRequestOnly(false);
        cookie = mgr.getSessionCookie(session, "/foo", false);
        assertFalse(cookie.isSecure());

        //sessionCookieConfig.secure=false, setSecureRequestOnly==false, requestIsSecure==true
        //cookie is not secure: not on secured requests and request is secure
        cookie = mgr.getSessionCookie(session, "/foo", true);
        assertFalse(cookie.isSecure());
    }
}
