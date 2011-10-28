// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.log;

import static org.hamcrest.Matchers.*;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(Slf4jTestJarsRunner.class)
public class LogTest
{
    private static Logger originalLogger;

    @SuppressWarnings("deprecation")
    @BeforeClass
    public static void rememberOriginalLogger()
    {
        originalLogger = Log.getLog();
    }

    @AfterClass
    public static void restoreOriginalLogger()
    {
        Log.setLog(originalLogger);
    }

    @Test
    public void testNamedLogNamed_StdErrLog()
    {
        Log.setLog(new StdErrLog());

        assertNamedLogging(Red.class);
        assertNamedLogging(Blue.class);
        assertNamedLogging(Green.class);
    }

    @Test
    public void testNamedLogNamed_JUL()
    {
        Log.setLog(new JavaUtilLog());

        assertNamedLogging(Red.class);
        assertNamedLogging(Blue.class);
        assertNamedLogging(Green.class);
    }

    @Test
    @Ignore
    public void testNamedLogNamed_Slf4J() throws Exception
    {
        Log.setLog(new Slf4jLog());

        assertNamedLogging(Red.class);
        assertNamedLogging(Blue.class);
        assertNamedLogging(Green.class);
    }

    @SuppressWarnings("deprecation")
    private void assertNamedLogging(Class<?> clazz)
    {
        Logger lc = Log.getLogger(clazz);
        Assert.assertThat("Named logging (impl=" + Log.getLog().getClass().getName() + ")",lc.getName(),is(clazz.getName()));
    }
}
