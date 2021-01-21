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

package org.eclipse.jetty.server.session;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * TestFileSessions
 */
@ExtendWith(WorkDirExtension.class)
public class TestFileSessions extends AbstractTestBase
{
    public WorkDir workDir;

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return FileTestHelper.newSessionDataStoreFactory(workDir);
    }

    /**
     * Test that passing in a filename that contains ".." chars does not
     * remove a file outside of the store dir.
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
        FileTestHelper.createFile(workDir, (System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)) + "__foobar_0.0.0.0_1234");

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
            fail("Should be non numeric");
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

        assertNull(store.getContextFromFilename(null));

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
        assertEquals("_test_0.0.0.0_1234", s);

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
            fail("Should be non numeric");
        }
        catch (Exception e)
        {
            //expected
        }

        s = store.getContextFromFilename("100__0.0.0.0_1234");
        assertEquals("_0.0.0.0", s);

        s = store.getIdWithContextFromFilename("100__0.0.0.0_1234");
        assertEquals("_0.0.0.0_1234", s);

        assertTrue(store.isOurContextSessionFilename("100__0.0.0.0_1234"));
        assertFalse(store.isOurContextSessionFilename("100__other_0.0.0.0_1234"));
    }

    /**
     * Test the FileSessionDataStore sweeper function
     */
    @Test
    public void testSweep() throws Exception
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
        FileTestHelper.createFile(workDir, "101__foobar_0.0.0.0_sessiona");
        FileTestHelper.assertSessionExists(workDir, "sessiona", true);

        //create a file not for our context that is not expired and should be ignored
        String nonExpiredForeign = (System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)) + "__foobar_0.0.0.0_sessionb";
        FileTestHelper.createFile(workDir, nonExpiredForeign);
        FileTestHelper.assertFileExists(workDir, nonExpiredForeign, true);

        //create a file not for our context that is recently expired, a thus ignored by sweep
        String expiredForeign = (System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(1)) + "__foobar_0.0.0.0_sessionc";
        FileTestHelper.createFile(workDir, expiredForeign);
        FileTestHelper.assertFileExists(workDir, expiredForeign, true);

        //create a file that is not a session file, it should be ignored
        FileTestHelper.createFile(workDir, "whatever.txt");
        FileTestHelper.assertFileExists(workDir, "whatever.txt", true);

        //create a file that is not a valid session filename, should be ignored
        FileTestHelper.createFile(workDir, "nonNumber__0.0.0.0_spuriousFile");
        FileTestHelper.assertFileExists(workDir, "nonNumber__0.0.0.0_spuriousFile", true);

        //create a file that is a non-expired session file for our context that should be ignored
        String nonExpired = (System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)) + "__test_0.0.0.0_sessionb";
        FileTestHelper.createFile(workDir, nonExpired);
        FileTestHelper.assertFileExists(workDir, nonExpired, true);

        //create a file that is a never-expire session file for our context that should be ignored
        String neverExpired = "0__test_0.0.0.0_sessionc";
        FileTestHelper.createFile(workDir, neverExpired);
        FileTestHelper.assertFileExists(workDir, neverExpired, true);

        //create a file that is a never-expire session file for another context that should be ignored
        String foreignNeverExpired = "0__other_0.0.0.0_sessionc";
        FileTestHelper.createFile(workDir, foreignNeverExpired);
        FileTestHelper.assertFileExists(workDir, foreignNeverExpired, true);

        //sweep - we're expecting a debug log with exception stacktrace due to file named 
        //nonNumber__0.0.0.0_spuriousFile so suppress it
        try (StacklessLogging stackless = new StacklessLogging(Log.getLogger("org.eclipse.jetty.server.session")))
        {
            ((FileSessionDataStore)store).sweepDisk();
        }

        //check results
        FileTestHelper.assertSessionExists(workDir, "sessiona", false);
        FileTestHelper.assertFileExists(workDir, "whatever.txt", true);
        FileTestHelper.assertFileExists(workDir, "nonNumber__0.0.0.0_spuriousFile", true);
        FileTestHelper.assertFileExists(workDir, nonExpired, true);
        FileTestHelper.assertFileExists(workDir, nonExpiredForeign, true);
        FileTestHelper.assertFileExists(workDir, expiredForeign, true);
        FileTestHelper.assertFileExists(workDir, neverExpired, true);
        FileTestHelper.assertFileExists(workDir, foreignNeverExpired, true);
    }

    /**
     * Test that when it initializes, the FileSessionDataStore deletes old expired sessions.
     */
    @Test
    public void testInitialize()
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
        FileTestHelper.createFile(workDir, "101_foobar_0.0.0.0_sessiona");
        FileTestHelper.assertSessionExists(workDir, "sessiona", true);

        //create a file not for our context that is not expired and should be ignored
        String nonExpiredForeign = (System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)) + "_foobar_0.0.0.0_sessionb";
        FileTestHelper.createFile(workDir, nonExpiredForeign);
        FileTestHelper.assertFileExists(workDir, nonExpiredForeign, true);

        //create a file not for our context that is recently expired, a thus ignored 
        String expiredForeign = (System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(1)) + "_foobar_0.0.0.0_sessionc";
        FileTestHelper.createFile(workDir, expiredForeign);
        FileTestHelper.assertFileExists(workDir, expiredForeign, true);

        //create a file that is not a session file, it should be ignored
        FileTestHelper.createFile(workDir, "whatever.txt");
        FileTestHelper.assertFileExists(workDir, "whatever.txt", true);

        //create a file that is not a valid session filename, should be ignored
        FileTestHelper.createFile(workDir, "nonNumber_0.0.0.0_spuriousFile");
        FileTestHelper.assertFileExists(workDir, "nonNumber_0.0.0.0_spuriousFile", true);

        //create a file that is a non-expired session file for our context that should be ignored
        String nonExpired = (System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)) + "_test_0.0.0.0_sessionb";
        FileTestHelper.createFile(workDir, nonExpired);
        FileTestHelper.assertFileExists(workDir, nonExpired, true);

        //create a file that is a never-expire session file for our context that should be ignored
        String neverExpired = "0_test_0.0.0.0_sessionc";
        FileTestHelper.createFile(workDir, neverExpired);
        FileTestHelper.assertFileExists(workDir, neverExpired, true);

        //create a file that is a never-expire session file for another context that should be ignored
        String foreignNeverExpired = "0_test_0.0.0.0_sessionc";
        FileTestHelper.createFile(workDir, foreignNeverExpired);
        FileTestHelper.assertFileExists(workDir, foreignNeverExpired, true);

        //walk all files in the store
        ((FileSessionDataStore)store).initializeStore();

        //check results
        FileTestHelper.assertSessionExists(workDir, "sessiona", false);
        FileTestHelper.assertFileExists(workDir, "whatever.txt", true);
        FileTestHelper.assertFileExists(workDir, "nonNumber_0.0.0.0_spuriousFile", true);
        FileTestHelper.assertFileExists(workDir, nonExpired, true);
        FileTestHelper.assertFileExists(workDir, nonExpiredForeign, true);
        FileTestHelper.assertFileExists(workDir, expiredForeign, true);
        FileTestHelper.assertFileExists(workDir, neverExpired, true);
        FileTestHelper.assertFileExists(workDir, foreignNeverExpired, true);
    }

    /**
     * If deleteUnrestorableFiles option is true, a damaged or unrestorable
     * file should be deleted.
     */
    @Test
    public void testDeleteUnrestorableFiles()
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

        String expectedFilename = (System.currentTimeMillis() + 10000) + "__test_0.0.0.0_validFile123";
        FileTestHelper.createFile(workDir, expectedFilename);
        FileTestHelper.assertFileExists(workDir, expectedFilename, true);

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

        FileTestHelper.assertFileExists(workDir, expectedFilename, false);
    }

    /**
     * Tests that only the most recent file will be
     * loaded into the cache, even if it is already
     * expired. Other recently expired files for
     * same session should be deleted.
     */
    @Test
    public void testLoadOnlyMostRecentFiles()
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
        String name1 = Long.toString(exp) + "__test_0.0.0.0_abc";
        FileTestHelper.createFile(workDir, name1);

        //create a file for same session that expired 4 sec ago
        exp = now - 4000L;
        String name2 = Long.toString(exp) + "__test_0.0.0.0_abc";
        FileTestHelper.createFile(workDir, name2);

        //make a file for same session that expired 3 sec ago
        exp = now - 3000L;
        String name3 = Long.toString(exp) + "__test_0.0.0.0_abc";
        FileTestHelper.createFile(workDir, name3);

        store.start();

        FileTestHelper.assertFileExists(workDir, name1, false);
        FileTestHelper.assertFileExists(workDir, name2, false);
        FileTestHelper.assertFileExists(workDir, name3, true);
    }
}
