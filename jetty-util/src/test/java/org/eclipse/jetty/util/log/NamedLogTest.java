//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.log;

import org.junit.jupiter.api.Test;

public class NamedLogTest
{
    @Test
    public void testNamedLogging()
    {
        Red red = new Red();
        Green green = new Green();
        Blue blue = new Blue();

        StdErrCapture output = new StdErrCapture();

        setLoggerOptions(Red.class, output);
        setLoggerOptions(Green.class, output);
        setLoggerOptions(Blue.class, output);

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
