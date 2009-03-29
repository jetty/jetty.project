// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.servlet;

import junit.framework.TestCase;

public abstract class AbstractSessionTest extends TestCase
{
    public static final String __host1 = "localhost";
    public static final String __host2 = __host1;
    public static final String __port1 = "8010";
    public static final String __port2 = "8011";
    SessionTestServer _server1;
    SessionTestServer _server2;

    public abstract SessionTestServer newServer1 ();
    
    public abstract SessionTestServer newServer2();
    
    public void setUp () throws Exception
    {
        _server1 = newServer1();
        _server2 = newServer2();
        _server1.start();      
        _server2.start();
    }
    
    public void tearDown () throws Exception
    {
        if (_server1 != null)
            _server1.stop();
        if (_server2 != null)
            _server2.stop();
        
        _server1=null;
        _server2=null;
    }
    
    
    public void testSessions () throws Exception
    {
        SessionTestClient client1 = new SessionTestClient("http://"+__host1+":"+__port1);
        SessionTestClient client2 = new SessionTestClient("http://"+__host2+":"+__port2);
        // confirm that user has no session
        assertFalse(client1.send("/contextA", null));
        String cookie1 = client1.newSession("/contextA");
        assertNotNull(cookie1);
        System.err.println("cookie1: " + cookie1);
        
        // confirm that client2 has the same session attributes as client1
        assertTrue(client1.setAttribute("/contextA", cookie1, "foo", "bar"));        
        assertTrue(client2.hasAttribute("/contextA", cookie1, "foo", "bar"));
        
        // confirm that /contextA would share same sessionId as /contextB        
        assertTrue(client1.send("/contextA/dispatch/forward/contextB", cookie1));        
        assertTrue(client2.send("/contextA/dispatch/forward/contextB", cookie1));
        assertTrue(client1.send("/contextB", cookie1));
        
        // verify that session attributes on /contextA is different from /contextB
        assertFalse(client1.hasAttribute("/contextB/action", cookie1, "foo", "bar"));
        
        // add new session attributes on /contextB
        client1.setAttribute("/contextB/action", cookie1, "zzzzz", "yyyyy");
        assertTrue(client1.hasAttribute("/contextB/action", cookie1, "zzzzz", "yyyyy"));
        
        // verify that client2 has same sessionAttributes on /contextB
        // client1's newly added attribute "zzzzz" needs to be flushed to the database first
        // saveInterval is configured at 10s... to test, uncomment the 2 lines below.
        //Thread.sleep(10000);
        //assertTrue(client2.hasAttribute("/contextB/action", cookie1, "zzzzz", "yyyyy"));        
        
        String cookie2 = client2.newSession("/contextA");
        assertNotNull(cookie2);
        System.err.println("cookie2: " + cookie2);
        
        // confirm that client1 has same session attributes as client2
        assertTrue(client2.setAttribute("/contextA", cookie2, "hello", "world"));
        assertTrue(client1.hasAttribute("/contextA", cookie2, "hello", "world"));
                
        // confirm that /contextA would share same sessionId as /contextB        
        assertTrue(client1.send("/contextA/dispatch/forward/contextB", cookie2));        
        assertTrue(client2.send("/contextA/dispatch/forward/contextB", cookie2));
        assertTrue(client1.send("/contextB", cookie2));
        
        // Session invalidate on contextA
        assertTrue(client1.invalidate("/contextA", cookie1));
        
        // confirm that session on contextB has not been invalidated after contextA has been invalidated
        assertTrue(client1.send("/contextB", cookie1));       
        
        // confirm that session on contextA has been deleted
        assertFalse(client1.send("/contextA", cookie1));
        
        // Session invalidate on contextB
        assertTrue(client1.invalidate("/contextB/action", cookie1)); 
        
        // confirm that session on contextB has been deleted
        assertFalse(client1.send("/contextB/action", cookie1));
        
        // session will reflect after 10s, so node2 still would not be deleted.
        assertTrue(client2.send("/contextB/action", cookie1));
        
        // wait for saveInterval and check if the session invalidation has been reflected to the other node
        // to test, uncomment 3 lines below
        //Thread.sleep(10000);
        //assertFalse(client2.send("/contextA", cookie1));
        //assertFalse(client2.send("/contextB/action", cookie1));        
    }
    
    public void testSessionManagerStop() throws Exception
    {     
        SessionTestClient client1 = new SessionTestClient("http://"+__host1+":"+__port1);
        SessionTestClient client2 = new SessionTestClient("http://"+__host2+":"+__port2);
        // confirm that user has no session
        assertFalse(client1.send("/contextA", null));
        String cookie1 = client1.newSession("/contextA");
        assertNotNull(cookie1);
        System.err.println("cookie1: " + cookie1);
        
        // creates a session for contextB
        assertTrue(client1.send("/contextB", cookie1));
        
        // confirm that /contextA and /contextB sessions are available        
        assertTrue(client1.send("/contextA", cookie1));
        assertTrue(client1.send("/contextB/action", cookie1));
        assertTrue(client1.setAttribute("/contextA", cookie1, "a", "b"));
        assertTrue(client1.setAttribute("/contextB/action", cookie1, "c", "d"));
        
        // confirm that /contextA and /contextB sessions from client2 are available   
        assertTrue(client2.send("/contextA", cookie1));
        assertTrue(client2.send("/contextB/action", cookie1));
        assertTrue(client2.hasAttribute("/contextA", cookie1, "a", "b"));
        assertTrue(client2.hasAttribute("/contextB/action", cookie1, "c", "d"));
        
        // stop sessionManager from node1
        _server1._sessionMgr1.stop();
        
        // verify session still exists for contextB
        assertTrue(client1.send("/contextB/action", cookie1));
        assertTrue(client1.hasAttribute("/contextB/action", cookie1, "c", "d"));
        
        // stop sessionManager from node2
        _server2._sessionMgr2.stop();
        
        // verfiy session still exists for contextA
        assertTrue(client2.send("/contextA", cookie1));
        assertTrue(client2.hasAttribute("/contextA", cookie1, "a", "b"));
    }
    
    public void testFailover() throws Exception
    {
        SessionTestClient client1 = new SessionTestClient("http://"+__host1+":"+__port1);
        SessionTestClient client2 = new SessionTestClient("http://"+__host2+":"+__port2);
        // confirm that user has no session
        assertFalse(client1.send("/contextA", null));
        String cookie1 = client1.newSession("/contextA");
        
        assertNotNull(cookie1);
        System.err.println("cookie1: " + cookie1);
        
        assertTrue(client1.setAttribute("/contextA", cookie1, "a", "b"));
        
        assertTrue(client2.hasAttribute("/contextA", cookie1, "a", "b"));
    }
}
