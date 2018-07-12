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

package org.eclipse.jetty.util.log;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Properties;

/**
 * Tests for StdErrNoDebugLog
 */
public class StdErrNoDebugLogTest
{
    static
    {
        StdErrLog.setTagPad(0);
    }
    
    @Before
    public void before()
    {
        Thread.currentThread().setName("tname");
    }

    /**
     * we assert isDebugEnabled always return false (even when changing level)
     */
    @Test
    public void testStdErrLogDebug()
    {
        StdErrNoDebugLog log = new StdErrNoDebugLog("xxx",new Properties());
        StdErrCapture output = new StdErrCapture(log);

        log.setLevel(StdErrLog.LEVEL_DEBUG);
        Assert.assertFalse(log.isDebugEnabled());
        log.debug("testing {} {}","test","debug");
        log.info("testing {} {}","test","info");
        log.warn("testing {} {}","test","warn");
        log.setLevel(StdErrLog.LEVEL_INFO);
        log.debug("YOU SHOULD NOT SEE THIS!",null,null);

        // Test for backward compat with old (now deprecated) method
        Logger before = log.getLogger("before");
        log.setDebugEnabled(true);
        Assert.assertFalse(log.isDebugEnabled());
        Logger after = log.getLogger("after");
        before.debug("testing {} {}","test","debug-before");
        log.debug("testing {} {}","test","debug-deprecated");
        after.debug("testing {} {}","test","debug-after");

        log.setDebugEnabled(false);
        before.debug("testing {} {}","test","debug-before-false");
        log.debug("testing {} {}","test","debug-deprecated-false");
        after.debug("testing {} {}","test","debug-after-false");

        output.assertNotContains("DBUG:xxx:tname: testing test debug");
        output.assertContains("INFO:xxx:tname: testing test info");
        output.assertContains("WARN:xxx:tname: testing test warn");
        output.assertNotContains("YOU SHOULD NOT SEE THIS!");
        output.assertNotContains("DBUG:x.before:tname: testing test debug-before");
        output.assertNotContains("DBUG:xxx:tname: testing test debug-deprecated");
        output.assertNotContains("DBUG:x.after:tname: testing test debug-after");
        output.assertNotContains("DBUG:x.before:tname: testing test debug-before-false");
        output.assertNotContains("DBUG:xxx:tname: testing test debug-deprecated-false");
        output.assertNotContains("DBUG:x.after:tname: testing test debug-after-false");
    }

}
