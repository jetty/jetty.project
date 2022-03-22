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

package org.eclipse.jetty.session;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 */
public class DefaultSessionIdManagerTest
{
    private class TestSessionHandler extends TestableSessionHandler
    {
        private boolean _idInUse;
        
        public void setIdInUse(boolean idInUse)
        {
            _idInUse = idInUse;
        }

        @Override
        public boolean isIdInUse(String id) throws Exception
        {
            return _idInUse;
        }
    }
    
    private class TestRequest implements Request
    {
        @Override
        public Object removeAttribute(String name)
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public Object setAttribute(String name, Object attribute)
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public Object getAttribute(String name)
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public Set<String> getAttributeNameSet()
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public void clearAttributes()
        {
            // TODO Auto-generated method stub
            
        }

        @Override
        public String getId()
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public HttpChannel getHttpChannel()
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public String getMethod()
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public HttpURI getHttpURI()
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public Context getContext()
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public String getPathInContext()
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public HttpFields getHeaders()
        {
            // TODO Auto-generated method stub
            return null;
        }

        public List<HttpCookie> getCookies()
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public long getTimeStamp()
        {
            return 0;
        }

        @Override
        public boolean isSecure()
        {
            // TODO Auto-generated method stub
            return false;
        }

        @Override
        public long getContentLength()
        {
            // TODO Auto-generated method stub
            return 0;
        }

        @Override
        public Content readContent()
        {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public void demandContent(Runnable onContentAvailable)
        {
            // TODO Auto-generated method stub
            
        }

        @Override
        public boolean addErrorListener(Predicate<Throwable> onError)
        {
            // TODO Auto-generated method stub
            return false;
        }

        @Override
        public void addCompletionListener(Callback onComplete)
        {
            // TODO Auto-generated method stub

        }

        @Override
        public void push(org.eclipse.jetty.http.MetaData.Request request)
        {
            // TODO Auto-generated method stub
        }
    }
    
    @Test
    public void testNewSessionId() throws Exception
    {
        //Test that we will create a new session id if there
        //is no request
        Server server = new Server();
        DefaultSessionIdManager sessionIdManager = new DefaultSessionIdManager(server);
        server.addBean(sessionIdManager, true);
        server.start();
        String id = sessionIdManager.newSessionId(null, "1234", System.currentTimeMillis());
        //check we got an id
        assertNotNull(id);
        //check that it cannot be the requested id
        assertNotSame("1234", id);
    }
    
    @Test
    public void testIsIdInUse() throws Exception
    {
        Server server = new Server();
        DefaultSessionIdManager sessionIdManager = new DefaultSessionIdManager(server);
        server.addBean(sessionIdManager, true);

        
        //test something that is not in use
        TestSessionHandler tsh1 = new TestSessionHandler();
        tsh1.setIdInUse(false);
        tsh1.setServer(server);
        server.addBean(tsh1);
        server.start();
        assertFalse(sessionIdManager.isIdInUse("1234"));
        //test something that _is_ in use
        server.stop();
        TestSessionHandler tsh2 = new TestSessionHandler();
        tsh2.setIdInUse(true);
        tsh2.setServer(server);
        server.addBean(tsh2);
        server.start();
        assertTrue(sessionIdManager.isIdInUse("1234"));
    }
    
    @Test
    public void testRequestedSessionIdNotReused() throws Exception
    {
        //Test that we do not use the suggested requested id because
        //it is not _already_ in use.
        Server server = new Server();
        DefaultSessionIdManager sessionIdManager = new DefaultSessionIdManager(server);
        server.addBean(sessionIdManager, true);
        server.start();

        String id = sessionIdManager.newSessionId(new TestRequest(), "1234", System.currentTimeMillis());
        assertNotNull(id);
        assertNotEquals("1234", id);
    }

    @Test
    public void testRequestedSessionIdReused() throws Exception
    {
        //Test that we do use the suggested requested id because
        //it _is_ in use.
        Server server = new Server();
        DefaultSessionIdManager sessionIdManager = new DefaultSessionIdManager(server);
        TestSessionHandler tsh = new TestSessionHandler();
        tsh.setIdInUse(true);
        server.setHandler(tsh);
        server.start();
        
        String id = sessionIdManager.newSessionId(new TestRequest(), "1234", System.currentTimeMillis());
        assertNotNull(id);
        assertEquals("1234", id);
    }
}
