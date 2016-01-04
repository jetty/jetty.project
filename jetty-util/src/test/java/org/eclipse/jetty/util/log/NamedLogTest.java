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

import org.junit.Test;

public class NamedLogTest
{
    @Test
    public void testNamedLogging()
    {
        Red red = new Red();
        Green green = new Green();
        Blue blue = new Blue();

        StdErrCapture output = new StdErrCapture();

        setLoggerOptions(Red.class,output);
        setLoggerOptions(Green.class,output);
        setLoggerOptions(Blue.class,output);

        red.generateLogs();
        green.generateLogs();
        blue.generateLogs();
        
        output.assertContains(Red.class.getName());
        output.assertContains(Green.class.getName());
        output.assertContains(Blue.class.getName());
    }

    private void setLoggerOptions(Class<?> clazz, StdErrCapture output)
    {
        Logger logger = Log.getLogger(clazz);
        logger.setDebugEnabled(true);

        if (logger instanceof StdErrLog)
        {
            StdErrLog sel = (StdErrLog)logger;
            sel.setPrintLongNames(true);
            output.capture(sel);
        }
    }
}
