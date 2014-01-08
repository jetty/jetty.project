//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VersionTest
{
    @Test
    public void testDefaultVersion()
    {
        Version version = new Version();
        assertEquals("Default version difference to 0.0.0",0,version.compare(new Version("0.0.0")));
    }

    @Test
    public void testNewerVersion() {
        assertIsNewer("0.0.0", "0.0.1");
        assertIsNewer("0.1.0", "0.1.1");
        assertIsNewer("1.5.0", "1.6.0");
        // assertIsNewer("1.6.0_12", "1.6.0_16"); // JDK version spec?
    }

    @Test
    public void testOlderVersion() {
        assertIsOlder("0.0.1", "0.0.0");
        assertIsOlder("0.1.1", "0.1.0");
        assertIsOlder("1.6.0", "1.5.0");
    }

    private void assertIsOlder(String basever, String testver)
    {
        Version vbase = new Version(basever);
        Version vtest = new Version(testver);
        assertTrue("Version [" + testver + "] should be older than [" + basever + "]",
                vtest.compare(vbase) == -1);
    }

    private void assertIsNewer(String basever, String testver)
    {
        Version vbase = new Version(basever);
        Version vtest = new Version(testver);
        assertTrue("Version [" + testver + "] should be newer than [" + basever + "]",
                vtest.compare(vbase) == 1);
    }
}
