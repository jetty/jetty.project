//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import javax.servlet.SessionCookieConfig;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Server;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SessionCookieTest
 */
public class SessionCookieTest
{
   
    
    
    public class MockSessionStore extends AbstractSessionCache
    {

        public MockSessionStore(SessionHandler manager)
        {
            super(manager);
        }

      
        /** 
         * @see org.eclipse.jetty.server.session.SessionCache#shutdown()
         */
        @Override
        public void shutdown()
        {
            // TODO Auto-generated method stub
            
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSessionCache#newSession(org.eclipse.jetty.server.session.SessionData)
         */
        @Override
        public Session newSession(SessionData data)
        {
            // TODO Auto-generated method stub
            return null;
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSessionCache#doGet(String)
         */
        @Override
        public Session doGet(String key)
        {
            // TODO Auto-generated method stub
            return null;
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSessionCache#doPutIfAbsent(String, Session)
         */
        @Override
        public Session doPutIfAbsent(String key, Session session)
        {
            return null;
        }

      

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSessionCache#doDelete(String)
         */
        @Override
        public Session doDelete(String key)
        {
            return null;
        }

      

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSessionCache#doReplace(java.lang.String, org.eclipse.jetty.server.session.Session, org.eclipse.jetty.server.session.Session)
         */
        @Override
        public boolean doReplace(String id, Session oldValue, Session newValue)
        {
            // TODO Auto-generated method stub
            return false;
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSessionCache#newSession(javax.servlet.http.HttpServletRequest, org.eclipse.jetty.server.session.SessionData)
         */
        @Override
        public Session newSession(HttpServletRequest request, SessionData data)
        {
            // TODO Auto-generated method stub
            return null;
        }

   
    }

    
    
    public class MockSessionIdManager extends DefaultSessionIdManager
    {
        public MockSessionIdManager(Server server)
        {
            super(server);
        }

        /**
         * @see org.eclipse.jetty.server.SessionIdManager#isIdInUse(java.lang.String)
         */
        @Override
        public boolean isIdInUse(String id)
        {
            return false;
        }

        /**
         * @see org.eclipse.jetty.server.SessionIdManager#expireAll(java.lang.String)
         */
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
    public void testSecureSessionCookie () throws Exception
    {
        Server server = new Server();
        MockSessionIdManager idMgr = new MockSessionIdManager(server);
        idMgr.setWorkerName("node1");
        SessionHandler mgr = new SessionHandler();
        MockSessionStore store = new MockSessionStore(mgr);
        store.setSessionDataStore(new NullSessionDataStore());
        mgr.setSessionCache(store);
        mgr.setSessionIdManager(idMgr);
        
        long now = System.currentTimeMillis();
        
        Session session = new Session(null, new SessionData("123", "_foo", "0.0.0.0", now, now, now, 30)); 

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
