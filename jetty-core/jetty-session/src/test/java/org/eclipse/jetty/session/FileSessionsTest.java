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

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.BeforeEach;
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
public class FileSessionsTest
{
    public WorkDir workDir;
    FileTestHelper _helper;
    
    public class TestSessionContext extends SessionContext
    {
        public TestSessionContext(String workerName, String canonicalPath, String vhost)
        {
            _workerName = workerName;
            _canonicalContextPath = canonicalPath;
            _vhost = vhost; 
        }
    }

    @BeforeEach
    public void before() throws Exception
    {
        _helper = new FileTestHelper(workDir.getEmptyPathDir());
    }

    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return _helper.newSessionDataStoreFactory();
    }

    /**
     * Test that passing in a filename that contains ".." chars does not
     * remove a file outside of the store dir.
     */
    @Test
    public void testLoadForeignContext() throws Exception
    {
        //create the SessionDataStore
        TestableSessionManager sessionManager = new TestableSessionManager();
        
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(10);
        FileSessionDataStore store = (FileSessionDataStore)factory.getSessionDataStore(sessionManager);
        store.setDeleteUnrestorableFiles(true);
        SessionContext sessionContext = new TestSessionContext("foo", StringUtil.sanitizeFileSystemName("/test"), "0.0.0.0");
        store.initialize(sessionContext);

        //make a file for foobar context
        _helper.createFile((System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)) + "__foobar_0.0.0.0_1234");

        store.start();

        //test this context can't load it
        assertNull(store.load("1234"));
    }

    @Test
    public void testFilenamesWithContext() throws Exception
    {
        //create the SessionDataStore
        TestableSessionManager sessionManager = new TestableSessionManager();
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(10);
        FileSessionDataStore store = (FileSessionDataStore)factory.getSessionDataStore(sessionManager);
        SessionContext sessionContext = new TestSessionContext("foo", StringUtil.sanitizeFileSystemName("/test"), "0.0.0.0");
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
            store.getExpiryFromFilename("nonnumber__test_0.0.0.0_1234");
            fail("Should be non numeric");
        }
        catch (Exception e)
        {
            //expected
        }

        try
        {
            store.getExpiryFromFilename(null);
            fail("Should throw ISE");
        }
        catch (Exception e)
        {
            //expected;
        }

        try
        {
            store.getExpiryFromFilename("thisisnotavalidsessionfilename");
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
        TestableSessionManager sessionManager = new TestableSessionManager();
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(10);
        FileSessionDataStore store = (FileSessionDataStore)factory.getSessionDataStore(sessionManager);
        //The root context path is translated into "" by Context.getContextPath
        SessionContext sessionContext = new TestSessionContext("foo", "", "0.0.0.0");
        store.initialize(sessionContext);

        String s = store.getIdWithContext("1234");
        assertEquals("_0.0.0.0_1234", s);

        s = store.getIdFromFilename("0__0.0.0.0_1234");
        assertEquals("1234", s);

        long l = store.getExpiryFromFilename("100__0.0.0.0_1234");
        assertEquals(100, l);

        try
        {
            store.getExpiryFromFilename("nonnumber__0.0.0.0_1234");
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
        int gracePeriodSec = 10;
        //create the SessionDataStore
        TestableSessionManager sessionManager = new TestableSessionManager();
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(gracePeriodSec);
        SessionDataStore store = factory.getSessionDataStore(sessionManager);
        SessionContext sessionContext = new TestSessionContext("foo", StringUtil.sanitizeFileSystemName("/foobar"), "0.0.0.0");
        store.initialize(sessionContext);

        store.start();

        //create file not for our context that expired long ago and should be removed by sweep
        _helper.createFile("101__foobar_0.0.0.0_sessiona");
        _helper.assertSessionExists("sessiona", true);

        //create a file not for our context that is not expired and should be ignored
        String nonExpiredForeign = (System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)) + "__foobar_0.0.0.0_sessionb";
        _helper.createFile(nonExpiredForeign);
        _helper.assertFileExists(nonExpiredForeign, true);

        //create a file not for our context that is recently expired, a thus ignored by sweep
        String expiredForeign = (System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(1)) + "__foobar_0.0.0.0_sessionc";
        _helper.createFile(expiredForeign);
        _helper.assertFileExists(expiredForeign, true);

        //create a file that is not a session file, it should be ignored
        _helper.createFile("whatever.txt");
        _helper.assertFileExists("whatever.txt", true);

        //create a file that is not a valid session filename, should be ignored
        _helper.createFile("nonNumber__0.0.0.0_spuriousFile");
        _helper.assertFileExists("nonNumber__0.0.0.0_spuriousFile", true);

        //create a file that is a non-expired session file for our context that should be ignored
        String nonExpired = (System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)) + "__test_0.0.0.0_sessionb";
        _helper.createFile(nonExpired);
        _helper.assertFileExists(nonExpired, true);

        //create a file that is a never-expire session file for our context that should be ignored
        String neverExpired = "0__test_0.0.0.0_sessionc";
        _helper.createFile(neverExpired);
        _helper.assertFileExists(neverExpired, true);

        //create a file that is a never-expire session file for another context that should be ignored
        String foreignNeverExpired = "0__other_0.0.0.0_sessionc";
        _helper.createFile(foreignNeverExpired);
        _helper.assertFileExists(foreignNeverExpired, true);

        //sweep - we're expecting a debug log with exception stacktrace due to file named 
        //nonNumber__0.0.0.0_spuriousFile so suppress it
        try (StacklessLogging ignored = new StacklessLogging(FileSessionsTest.class.getPackage()))
        {
            ((FileSessionDataStore)store).sweepDisk(System.currentTimeMillis() - (10 * TimeUnit.SECONDS.toMillis(gracePeriodSec)));
        }

        //check results
        _helper.assertSessionExists("sessiona", false);
        _helper.assertFileExists("whatever.txt", true);
        _helper.assertFileExists("nonNumber__0.0.0.0_spuriousFile", true);
        _helper.assertFileExists(nonExpired, true);
        _helper.assertFileExists(nonExpiredForeign, true);
        _helper.assertFileExists(expiredForeign, true);
        _helper.assertFileExists(neverExpired, true);
        _helper.assertFileExists(foreignNeverExpired, true);
    }

    /**
     * Test that when it initializes, the FileSessionDataStore deletes old expired sessions.
     */
    @Test
    public void testInitialize()
        throws Exception
    {
        //create the SessionDataStore
        TestableSessionManager sessionManager = new TestableSessionManager();
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(10);
        SessionDataStore store = factory.getSessionDataStore(sessionManager);
        SessionContext sessionContext = new TestSessionContext("foo", StringUtil.sanitizeFileSystemName("/"), "0.0.0.0");
        store.initialize(sessionContext);

        //create file not for our context that expired long ago and should be removed 
        _helper.createFile("101_foobar_0.0.0.0_sessiona");
        _helper.assertSessionExists("sessiona", true);

        //create a file not for our context that is not expired and should be ignored
        String nonExpiredForeign = (System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)) + "_foobar_0.0.0.0_sessionb";
        _helper.createFile(nonExpiredForeign);
        _helper.assertFileExists(nonExpiredForeign, true);

        //create a file not for our context that is recently expired, a thus ignored 
        String expiredForeign = (System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(1)) + "_foobar_0.0.0.0_sessionc";
        _helper.createFile(expiredForeign);
        _helper.assertFileExists(expiredForeign, true);

        //create a file that is not a session file, it should be ignored
        _helper.createFile("whatever.txt");
        _helper.assertFileExists("whatever.txt", true);

        //create a file that is not a valid session filename, should be ignored
        _helper.createFile("nonNumber_0.0.0.0_spuriousFile");
        _helper.assertFileExists("nonNumber_0.0.0.0_spuriousFile", true);

        //create a file that is a non-expired session file for our context that should be ignored
        String nonExpired = (System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)) + "_test_0.0.0.0_sessionb";
        _helper.createFile(nonExpired);
        _helper.assertFileExists(nonExpired, true);

        //create a file that is a never-expire session file for our context that should be ignored
        String neverExpired = "0_test_0.0.0.0_sessionc";
        _helper.createFile(neverExpired);
        _helper.assertFileExists(neverExpired, true);

        //create a file that is a never-expire session file for another context that should be ignored
        String foreignNeverExpired = "0_test_0.0.0.0_sessionc";
        _helper.createFile(foreignNeverExpired);
        _helper.assertFileExists(foreignNeverExpired, true);

        //walk all files in the store
        ((FileSessionDataStore)store).initializeStore();

        //check results
        _helper.assertSessionExists("sessiona", false);
        _helper.assertFileExists("whatever.txt", true);
        _helper.assertFileExists("nonNumber_0.0.0.0_spuriousFile", true);
        _helper.assertFileExists(nonExpired, true);
        _helper.assertFileExists(nonExpiredForeign, true);
        _helper.assertFileExists(expiredForeign, true);
        _helper.assertFileExists(neverExpired, true);
        _helper.assertFileExists(foreignNeverExpired, true);
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
        TestableSessionManager sessionManager = new TestableSessionManager();
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(10);
        SessionDataStore store = factory.getSessionDataStore(sessionManager);
        SessionContext sessionContext = new TestSessionContext("foo", StringUtil.sanitizeFileSystemName("/test"), "0.0.0.0");
        ((FileSessionDataStore)store).setDeleteUnrestorableFiles(true); //invalid file will be removed
        store.initialize(sessionContext);

        String expectedFilename = (System.currentTimeMillis() + 10000) + "__test_0.0.0.0_validFile123";
        _helper.createFile(expectedFilename);
        _helper.assertFileExists(expectedFilename, true);

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

        _helper.assertFileExists(expectedFilename, false);
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
        TestableSessionManager sessionManager = new TestableSessionManager();
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(100);
        SessionDataStore store = factory.getSessionDataStore(sessionManager);
        SessionContext sessionContext = new TestSessionContext("foo", StringUtil.sanitizeFileSystemName("/test"), "0.0.0.0");
        store.initialize(sessionContext);

        long now = System.currentTimeMillis();

        //create a file for session abc that expired 5sec ago
        long exp = now - 5000L;
        String name1 = Long.toString(exp) + "__test_0.0.0.0_abc";
        _helper.createFile(name1);

        //create a file for same session that expired 4 sec ago
        exp = now - 4000L;
        String name2 = Long.toString(exp) + "__test_0.0.0.0_abc";
        _helper.createFile(name2);

        //make a file for same session that expired 3 sec ago
        exp = now - 3000L;
        String name3 = Long.toString(exp) + "__test_0.0.0.0_abc";
        _helper.createFile(name3);

        store.start();

        _helper.assertFileExists(name1, false);
        _helper.assertFileExists(name2, false);
        _helper.assertFileExists(name3, true);
    }
}
