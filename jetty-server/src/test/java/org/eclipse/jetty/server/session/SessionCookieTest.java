//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Enumeration;
import java.util.Map;
import java.util.Set;

import javax.servlet.SessionCookieConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.http.HttpCookie;
import org.junit.Test;
/**
 * SessionCookieTest
 *
 *
 */
public class SessionCookieTest
{

    public class MockSession extends AbstractSession
    {


        /**
         * @param abstractSessionManager
         * @param created
         * @param accessed
         * @param clusterId
         */
        protected MockSession(AbstractSessionManager abstractSessionManager, long created, long accessed, String clusterId)
        {
            super(abstractSessionManager, created, accessed, clusterId);
        }

        /** 
         * @see javax.servlet.http.HttpSession#getAttribute(java.lang.String)
         */
        @Override
        public Object getAttribute(String name)
        {
            return null;
        }

        /** 
         * @see javax.servlet.http.HttpSession#getAttributeNames()
         */
        @Override
        public Enumeration<String> getAttributeNames()
        {
            return null;
        }

        /** 
         * @see javax.servlet.http.HttpSession#getValueNames()
         */
        @Override
        public String[] getValueNames()
        {
            return null;
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSession#getAttributeMap()
         */
        @Override
        public Map<String, Object> getAttributeMap()
        {
            return null;
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSession#getAttributes()
         */
        @Override
        public int getAttributes()
        {
            return 0;
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSession#getNames()
         */
        @Override
        public Set<String> getNames()
        {
            return null;
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSession#clearAttributes()
         */
        @Override
        public void clearAttributes()
        {
            
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSession#doPutOrRemove(java.lang.String, java.lang.Object)
         */
        @Override
        public Object doPutOrRemove(String name, Object value)
        {
            return null;
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSession#doGet(java.lang.String)
         */
        @Override
        public Object doGet(String name)
        {
            return null;
        }

        /** 
         * @see org.eclipse.jetty.server.session.AbstractSession#doGetAttributeNames()
         */
        @Override
        public Enumeration<String> doGetAttributeNames()
        {
            return null;
        }

    }

    public class MockSessionIdManager extends AbstractSessionIdManager
    {

        /**
         * @see org.eclipse.jetty.server.SessionIdManager#idInUse(java.lang.String)
         */
        @Override
        public boolean idInUse(String id)
        {
            return false;
        }

        /**
         * @see org.eclipse.jetty.server.SessionIdManager#addSession(javax.servlet.http.HttpSession)
         */
        @Override
        public void addSession(HttpSession session)
        {

        }

        /**
         * @see org.eclipse.jetty.server.SessionIdManager#removeSession(javax.servlet.http.HttpSession)
         */
        @Override
        public void removeSession(HttpSession session)
        {

        }

        /**
         * @see org.eclipse.jetty.server.SessionIdManager#invalidateAll(java.lang.String)
         */
        @Override
        public void invalidateAll(String id)
        {

        }

        @Override
        public void renewSessionId(String oldClusterId, String oldNodeId, HttpServletRequest request)
        {
            // TODO Auto-generated method stub
            
        }

    }

    public class MockSessionManager extends AbstractSessionManager
    {

        /**
         * @see org.eclipse.jetty.server.session.AbstractSessionManager#addSession(org.eclipse.jetty.server.session.AbstractSession)
         */
        @Override
        protected void addSession(AbstractSession session)
        {

        }

        /**
         * @see org.eclipse.jetty.server.session.AbstractSessionManager#getSession(java.lang.String)
         */
        @Override
        public AbstractSession getSession(String idInCluster)
        {
            return null;
        }

        /**
         * @see org.eclipse.jetty.server.session.AbstractSessionManager#shutdownSessions()
         */
        @Override
        protected void shutdownSessions() throws Exception
        {

        }

        /**
         * @see org.eclipse.jetty.server.session.AbstractSessionManager#newSession(javax.servlet.http.HttpServletRequest)
         */
        @Override
        protected AbstractSession newSession(HttpServletRequest request)
        {
            return null;
        }

        /**
         * @see org.eclipse.jetty.server.session.AbstractSessionManager#removeSession(java.lang.String)
         */
        @Override
        protected boolean removeSession(String idInCluster)
        {
            return false;
        }

        @Override
        public void renewSessionId(String oldClusterId, String oldNodeId, String newClusterId, String newNodeId)
        {
            // TODO Auto-generated method stub
            
        }

    }

    @Test
    public void testSecureSessionCookie () throws Exception
    {
        MockSessionIdManager idMgr = new MockSessionIdManager();
        idMgr.setWorkerName("node1");
        MockSessionManager mgr = new MockSessionManager();
        mgr.setSessionIdManager(idMgr);
        MockSession session = new MockSession(mgr, System.currentTimeMillis(), System.currentTimeMillis(), "node1123"); //clusterId

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
