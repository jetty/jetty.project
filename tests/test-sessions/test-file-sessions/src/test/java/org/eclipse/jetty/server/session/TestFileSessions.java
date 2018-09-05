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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * TestFileSessions
 *
 *
 */
public class TestFileSessions extends AbstractTestBase
{
    @Before
    public void before() throws Exception
    {
       FileTestHelper.setup();
    }
    
    @After 
    public void after()
    {
       FileTestHelper.teardown();
    }
 

   
    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return FileTestHelper.newSessionDataStoreFactory();
    }
    
    /**
     * Test that passing in a filename that contains ".." chars does not
     * remove a file outside of the store dir.
     * 
     * @throws Exception
     */
    @Test
    public void testLoadForeignContext() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(10);
        FileSessionDataStore store = (FileSessionDataStore)factory.getSessionDataStore(context.getSessionHandler());
        store.setDeleteUnrestorableFiles(true);
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
 
        //make a file for foobar context
        FileTestHelper.createFile((System.currentTimeMillis()+TimeUnit.DAYS.toMillis(1))+"__foobar_0.0.0.0_1234");
        
        store.start();
        
        //test this context can't load it
        assertNull(store.load("1234"));
    }
    

    
    @Test
    public void testFilenamesWithContext() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(10);
        FileSessionDataStore store = (FileSessionDataStore)factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        String s = store.getIdWithContext("1234");
        assertEquals("_test_0.0.0.0_1234", s);
        
        s = store.getIdFromFilename("0__test_0.0.0.0_1234");
        assertEquals("1234", s);
        
        s = store.getIdFromFilename(null);
        assertNull(s);  
        
        long l = store.getExpiryFromFilename("100__test_0.0.0.0_1234");
        assertEquals(100, l);
        
        
        try
        {
            long ll = store.getExpiryFromFilename("nonnumber__test_0.0.0.0_1234");
            fail ("Should be non numeric");
        }
        catch (Exception e)
        {
            //expected
        }
        
        try
        {
            long ll = store.getExpiryFromFilename(null);
            fail("Should throw ISE");
        }
        catch (Exception e)
        {
            //expected;
        }
        
        try
        {
            long ll = store.getExpiryFromFilename("thisisnotavalidsessionfilename");
            fail("Should throw ISE");
        }
        catch (IllegalStateException e)
        {
            //expected;
        }
        
        s = store.getContextFromFilename("100__test_0.0.0.0_1234");
        assertEquals("_test_0.0.0.0", s);

        assertNull (store.getContextFromFilename(null));

        try
        {
            s = store.getContextFromFilename("thisisnotavalidfilename");
            fail("Should throw exception");
        }
        catch (StringIndexOutOfBoundsException e)
        {
            //expected;
        }
        
        s = store.getIdWithContextFromFilename("100__test_0.0.0.0_1234");
        assertEquals("_test_0.0.0.0_1234",s);
        
        assertNull(store.getIdWithContextFromFilename(null));
        assertNull(store.getIdWithContextFromFilename("thisisnotavalidfilename"));
        
        assertTrue(store.isOurContextSessionFilename("100__test_0.0.0.0_1234"));
        assertFalse(store.isOurContextSessionFilename("100__other_0.0.0.0_1234"));
    }




    @Test
    public void testFilenamesWithDefaultContext() throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(10);
        FileSessionDataStore store = (FileSessionDataStore)factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        String s = store.getIdWithContext("1234");
        assertEquals("_0.0.0.0_1234", s);
        
        s = store.getIdFromFilename("0__0.0.0.0_1234");
        assertEquals("1234", s);
        
        long l = store.getExpiryFromFilename("100__0.0.0.0_1234");
        assertEquals(100, l);
        
        try
        {
            long ll = store.getExpiryFromFilename("nonnumber__0.0.0.0_1234");
            fail ("Should be non numeric");
        }
        catch (Exception e)
        {
            //expected
        }
        
        s = store.getContextFromFilename("100__0.0.0.0_1234");
        assertEquals("_0.0.0.0", s);
        
        s = store.getIdWithContextFromFilename("100__0.0.0.0_1234");
        assertEquals("_0.0.0.0_1234",s);
        
        assertTrue(store.isOurContextSessionFilename("100__0.0.0.0_1234"));
        assertFalse(store.isOurContextSessionFilename("100__other_0.0.0.0_1234"));
    }
   
    
    
    /**
     * Test the FileSessionDataStore sweeper function
     * 
     * @throws Exception
     */
    @Test
    public void testSweep () throws Exception
    {

        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(10);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);

        store.start();

        //create file not for our context that expired long ago and should be removed by sweep
        FileTestHelper.createFile("101__foobar_0.0.0.0_sessiona");
        FileTestHelper.assertSessionExists("sessiona", true);

        //create a file not for our context that is not expired and should be ignored
        String nonExpiredForeign = (System.currentTimeMillis()+TimeUnit.DAYS.toMillis(1))+"__foobar_0.0.0.0_sessionb";
        FileTestHelper.createFile(nonExpiredForeign);
        FileTestHelper.assertFileExists(nonExpiredForeign, true);

        //create a file not for our context that is recently expired, a thus ignored by sweep
        String expiredForeign = (System.currentTimeMillis()-TimeUnit.SECONDS.toMillis(1))+"__foobar_0.0.0.0_sessionc";
        FileTestHelper.createFile(expiredForeign);
        FileTestHelper.assertFileExists(expiredForeign, true);

        //create a file that is not a session file, it should be ignored
        FileTestHelper.createFile("whatever.txt");
        FileTestHelper.assertFileExists("whatever.txt", true);
        
        //create a file that is not a valid session filename, should be ignored
        FileTestHelper.createFile("nonNumber__0.0.0.0_spuriousFile");
        FileTestHelper.assertFileExists("nonNumber__0.0.0.0_spuriousFile", true);
        
        //create a file that is a non-expired session file for our context that should be ignored
        String nonExpired = (System.currentTimeMillis()+TimeUnit.DAYS.toMillis(1))+"__test_0.0.0.0_sessionb";
        FileTestHelper.createFile(nonExpired);
        FileTestHelper.assertFileExists(nonExpired, true);

        //create a file that is a never-expire session file for our context that should be ignored
        String neverExpired = "0__test_0.0.0.0_sessionc";
        FileTestHelper.createFile(neverExpired);
        FileTestHelper.assertFileExists(neverExpired, true);

        //create a file that is a never-expire session file for another context that should be ignored
        String foreignNeverExpired = "0__other_0.0.0.0_sessionc";
        FileTestHelper.createFile(foreignNeverExpired);
        FileTestHelper.assertFileExists(foreignNeverExpired, true);

        //sweep
        ((FileSessionDataStore)store).sweepDisk();

        //check results
        FileTestHelper.assertSessionExists("sessiona", false);
        FileTestHelper.assertFileExists("whatever.txt", true);
        FileTestHelper.assertFileExists("nonNumber__0.0.0.0_spuriousFile", true);
        FileTestHelper.assertFileExists(nonExpired, true);
        FileTestHelper.assertFileExists(nonExpiredForeign, true);
        FileTestHelper.assertFileExists(expiredForeign, true);
        FileTestHelper.assertFileExists(neverExpired, true);
        FileTestHelper.assertFileExists(foreignNeverExpired, true);
    }
    
    
    /**
     * Test that when it initializes, the FileSessionDataStore deletes old expired sessions.
     * 
     * @throws Exception
     */
    @Test
    public void testInitialize ()
    throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(10);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);

        //create file not for our context that expired long ago and should be removed 
        FileTestHelper.createFile("101_foobar_0.0.0.0_sessiona");
        FileTestHelper.assertSessionExists("sessiona", true);

        //create a file not for our context that is not expired and should be ignored
        String nonExpiredForeign = (System.currentTimeMillis()+TimeUnit.DAYS.toMillis(1))+"_foobar_0.0.0.0_sessionb";
        FileTestHelper.createFile(nonExpiredForeign);
        FileTestHelper.assertFileExists(nonExpiredForeign, true);

        //create a file not for our context that is recently expired, a thus ignored 
        String expiredForeign = (System.currentTimeMillis()-TimeUnit.SECONDS.toMillis(1))+"_foobar_0.0.0.0_sessionc";
        FileTestHelper.createFile(expiredForeign);
        FileTestHelper.assertFileExists(expiredForeign, true);

        //create a file that is not a session file, it should be ignored
        FileTestHelper.createFile("whatever.txt");
        FileTestHelper.assertFileExists("whatever.txt", true);
        
        //create a file that is not a valid session filename, should be ignored
        FileTestHelper.createFile("nonNumber_0.0.0.0_spuriousFile");
        FileTestHelper.assertFileExists("nonNumber_0.0.0.0_spuriousFile", true);

        //create a file that is a non-expired session file for our context that should be ignored
        String nonExpired = (System.currentTimeMillis()+TimeUnit.DAYS.toMillis(1))+"_test_0.0.0.0_sessionb";
        FileTestHelper.createFile(nonExpired);
        FileTestHelper.assertFileExists(nonExpired, true);

        //create a file that is a never-expire session file for our context that should be ignored
        String neverExpired = "0_test_0.0.0.0_sessionc";
        FileTestHelper.createFile(neverExpired);
        FileTestHelper.assertFileExists(neverExpired, true);

        //create a file that is a never-expire session file for another context that should be ignored
        String foreignNeverExpired = "0_test_0.0.0.0_sessionc";
        FileTestHelper.createFile(foreignNeverExpired);
        FileTestHelper.assertFileExists(foreignNeverExpired, true);

        //walk all files in the store
        ((FileSessionDataStore)store).initializeStore();
        
        //check results
        FileTestHelper.assertSessionExists("sessiona", false);
        FileTestHelper.assertFileExists("whatever.txt", true);
        FileTestHelper.assertFileExists("nonNumber_0.0.0.0_spuriousFile", true);
        FileTestHelper.assertFileExists(nonExpired, true);
        FileTestHelper.assertFileExists(nonExpiredForeign, true);
        FileTestHelper.assertFileExists(expiredForeign, true);
        FileTestHelper.assertFileExists(neverExpired, true);
        FileTestHelper.assertFileExists(foreignNeverExpired, true);
    }


    /**
     * If deleteUnrestorableFiles option is true, a damaged or unrestorable
     * file should be deleted.
     * 
     * @throws Exception
     */
    @Test
    public void testDeleteUnrestorableFiles ()
    throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(10);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        ((FileSessionDataStore)store).setDeleteUnrestorableFiles(true); //invalid file will be removed
        store.initialize(sessionContext);
  
        String expectedFilename = (System.currentTimeMillis() + 10000)+"__test_0.0.0.0_validFile123";
        FileTestHelper.createFile(expectedFilename);
        FileTestHelper.assertFileExists(expectedFilename, true);

        store.start();
        
        try
        {
            store.load("validFile123");
            fail("Load should fail");
        }
        catch (Exception e)
        {
            //expected exception
        }
        
        FileTestHelper.assertFileExists(expectedFilename, false);
    }
    
    
    /**
     * Tests that only the most recent file will be
     * loaded into the cache, even if it is already
     * expired. Other recently expired files for
     * same session should be deleted.
     * @throws Exception
     */
    @Test
    public void testLoadOnlyMostRecentFiles ()
    throws Exception
    {
        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/test");       
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(100);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler());
        SessionContext sessionContext = new SessionContext("foo", context.getServletContext());
        store.initialize(sessionContext);
        
        long now = System.currentTimeMillis();
        
        //create a file for session abc that expired 5sec ago
        long exp = now - 5000L; 
        String name1 =  Long.toString(exp)+"__test_0.0.0.0_abc"; 
        FileTestHelper.createFile(name1);

        
        //create a file for same session that expired 4 sec ago
        exp = now - 4000L;
        String name2 = Long.toString(exp)+"__test_0.0.0.0_abc"; 
        FileTestHelper.createFile(name2);


        //make a file for same session that expired 3 sec ago
        exp = now - 3000L;
        String name3 = Long.toString(exp)+"__test_0.0.0.0_abc";
        FileTestHelper.createFile(name3);
        
        store.start();
        
        FileTestHelper.assertFileExists(name1, false);
        FileTestHelper.assertFileExists(name2, false);
        FileTestHelper.assertFileExists(name3, true);        
    }

}
