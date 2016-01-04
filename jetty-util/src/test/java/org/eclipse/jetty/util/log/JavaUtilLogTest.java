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

package org.eclipse.jetty.util.log;

import static org.hamcrest.Matchers.is;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class JavaUtilLogTest
{
    private static Handler[] originalHandlers;
    private static CapturingJULHandler jul;

    @BeforeClass
    public static void setJUL()
    {
        LogManager lmgr = LogManager.getLogManager();
        java.util.logging.Logger root = lmgr.getLogger("");
        // Remember original handlers
        originalHandlers = root.getHandlers();
        // Remove original handlers
        for (Handler existing : originalHandlers)
        {
            root.removeHandler(existing);
        }
        // Set test/capturing handler
        jul = new CapturingJULHandler();
        root.addHandler(jul);
    }

    @AfterClass
    public static void restoreJUL()
    {
        LogManager lmgr = LogManager.getLogManager();
        java.util.logging.Logger root = lmgr.getLogger("");
        // Remove test handlers
        for (Handler existing : root.getHandlers())
        {
            root.removeHandler(existing);
        }
        // Restore original handlers
        for (Handler original : originalHandlers)
        {
            root.addHandler(original);
        }
    }

    @Test
    public void testNamedLogger()
    {
        jul.clear();
        JavaUtilLog log = new JavaUtilLog("test");
        log.info("Info test");

        jul.assertContainsLine("INFO|test|Info test");

        JavaUtilLog loglong = new JavaUtilLog("test.a.long.name");
        loglong.info("Long test");

        jul.assertContainsLine("INFO|test.a.long.name|Long test");
    }

    @Test
    public void testDebugOutput()
    {
        jul.clear();

        // Common Throwable (for test)
        Throwable th = new Throwable("Message");

        // Capture raw string form
        StringWriter tout = new StringWriter();
        th.printStackTrace(new PrintWriter(tout));
        String ths = tout.toString();

        // Tests
        JavaUtilLog log = new JavaUtilLog("test.de.bug");
        setJulLevel("test.de.bug",Level.FINE);

        log.debug("Simple debug");
        log.debug("Debug with {} parameter",1);
        log.debug("Debug with {} {} parameters", 2, "spiffy");
        log.debug("Debug with throwable", th);
        log.debug(th);

        // jul.dump();

        jul.assertContainsLine("FINE|test.de.bug|Simple debug");
        jul.assertContainsLine("FINE|test.de.bug|Debug with 1 parameter");
        jul.assertContainsLine("FINE|test.de.bug|Debug with 2 spiffy parameters");
        jul.assertContainsLine("FINE|test.de.bug|Debug with throwable");
        jul.assertContainsLine(ths);
    }

    @Test
    public void testInfoOutput()
    {
        jul.clear();

        // Common Throwable (for test)
        Throwable th = new Throwable("Message");

        // Capture raw string form
        StringWriter tout = new StringWriter();
        th.printStackTrace(new PrintWriter(tout));
        String ths = tout.toString();

        // Tests
        JavaUtilLog log = new JavaUtilLog("test.in.fo");
        setJulLevel("test.in.fo",Level.INFO);

        log.info("Simple info");
        log.info("Info with {} parameter",1);
        log.info("Info with {} {} parameters", 2, "spiffy");
        log.info("Info with throwable", th);
        log.info(th);

        // jul.dump();

        jul.assertContainsLine("INFO|test.in.fo|Simple info");
        jul.assertContainsLine("INFO|test.in.fo|Info with 1 parameter");
        jul.assertContainsLine("INFO|test.in.fo|Info with 2 spiffy parameters");
        jul.assertContainsLine("INFO|test.in.fo|Info with throwable");
        jul.assertContainsLine(ths);
    }

    @Test
    public void testWarnOutput()
    {
        jul.clear();

        // Common Throwable (for test)
        Throwable th = new Throwable("Message");

        // Capture raw string form
        StringWriter tout = new StringWriter();
        th.printStackTrace(new PrintWriter(tout));
        String ths = tout.toString();

        // Tests
        JavaUtilLog log = new JavaUtilLog("test.wa.rn");
        setJulLevel("test.wa.rn",Level.WARNING);

        log.warn("Simple warn");
        log.warn("Warn with {} parameter",1);
        log.warn("Warn with {} {} parameters", 2, "spiffy");
        log.warn("Warn with throwable", th);
        log.warn(th);

        // jul.dump();

        jul.assertContainsLine("WARNING|test.wa.rn|Simple warn");
        jul.assertContainsLine("WARNING|test.wa.rn|Warn with 1 parameter");
        jul.assertContainsLine("WARNING|test.wa.rn|Warn with 2 spiffy parameters");
        jul.assertContainsLine("WARNING|test.wa.rn|Warn with throwable");
        jul.assertContainsLine(ths);
    }

    @Test
    public void testFormattingWithNulls()
    {
        jul.clear();

        JavaUtilLog log = new JavaUtilLog("test.nu.ll");
        setJulLevel("test.nu.ll",Level.INFO);

        log.info("Testing info(msg,null,null) - {} {}","arg0","arg1");
        log.info("Testing info(msg,null,null) - {}/{}",null,null);
        log.info("Testing info(msg,null,null) > {}",null,null);
        log.info("Testing info(msg,null,null)",null,null);
        log.info(null,"Testing","info(null,arg0,arg1)");
        log.info(null,null,null);

        //jul.dump();

        jul.assertContainsLine("INFO|test.nu.ll|Testing info(msg,null,null) - null/null");
        jul.assertContainsLine("INFO|test.nu.ll|Testing info(msg,null,null) > null null");
        jul.assertContainsLine("INFO|test.nu.ll|Testing info(msg,null,null) null null");
        jul.assertContainsLine("INFO|test.nu.ll|null Testing info(null,arg0,arg1)");
        jul.assertContainsLine("INFO|test.nu.ll|null null null");
    }

    @Test
    public void testIsDebugEnabled() {
        JavaUtilLog log = new JavaUtilLog("test.legacy");

        setJulLevel("test.legacy",Level.ALL);
        Assert.assertThat("log.level(all).isDebugEnabled", log.isDebugEnabled(), is(true));

        setJulLevel("test.legacy",Level.FINEST);
        Assert.assertThat("log.level(finest).isDebugEnabled", log.isDebugEnabled(), is(true));

        setJulLevel("test.legacy",Level.FINER);
        Assert.assertThat("log.level(finer).isDebugEnabled", log.isDebugEnabled(), is(true));

        setJulLevel("test.legacy",Level.FINE);
        Assert.assertThat("log.level(fine).isDebugEnabled", log.isDebugEnabled(), is(true));

        setJulLevel("test.legacy",Level.INFO);
        Assert.assertThat("log.level(info).isDebugEnabled", log.isDebugEnabled(), is(false));

        setJulLevel("test.legacy",Level.WARNING);
        Assert.assertThat("log.level(warn).isDebugEnabled", log.isDebugEnabled(), is(false));

        log.setDebugEnabled(true);
        Assert.assertThat("log.isDebugEnabled", log.isDebugEnabled(), is(true));

        log.setDebugEnabled(false);
        Assert.assertThat("log.isDebugEnabled", log.isDebugEnabled(), is(false));
    }

    private void setJulLevel(String name, Level lvl)
    {
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(name);
        log.setLevel(lvl);
    }
}
