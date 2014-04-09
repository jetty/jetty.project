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

import static org.hamcrest.Matchers.*;

import java.nio.file.Path;

import org.eclipse.jetty.toolchain.test.OS;
import org.junit.Assert;
import org.junit.Test;

public class PathMatchersTest
{
    private void assertIsAbsolute(String pattern, boolean expected)
    {
        Assert.assertThat("isAbsolute(\"" + pattern + "\")",PathMatchers.isAbsolute(pattern),is(expected));
    }

    @Test
    public void testIsAbsolute()
    {
        if (OS.IS_UNIX)
        {
            assertIsAbsolute("/opt/app",true);
            assertIsAbsolute("/opt/florb",true);
            assertIsAbsolute("/home/user/benfranklin",true);
            assertIsAbsolute("glob:/home/user/benfranklin/*.jar",true);
            assertIsAbsolute("glob:/**/*.jar",true);
            assertIsAbsolute("regex:/*-[^dev].ini",true);
        }

        if (OS.IS_WINDOWS)
        {
            assertIsAbsolute("C:\\\\System32",true);
            assertIsAbsolute("C:\\\\Program Files",true);
        }
    }

    @Test
    public void testIsNotAbsolute()
    {
        assertIsAbsolute("etc",false);
        assertIsAbsolute("lib",false);
        assertIsAbsolute("${user.dir}",false);
        assertIsAbsolute("**/*.jar",false);
        assertIsAbsolute("glob:*.ini",false);
        assertIsAbsolute("regex:*-[^dev].ini",false);
    }

    private void assertSearchRoot(String pattern, String expectedSearchRoot)
    {
        Path actual = PathMatchers.getSearchRoot(pattern);
        String expectedNormal = FS.separators(expectedSearchRoot);
        Assert.assertThat(".getSearchRoot(\"" + pattern + "\")",actual.toString(),is(expectedNormal));
    }

    @Test
    public void testGetSearchRoot()
    {
        if (OS.IS_UNIX)
        {
            // absolute first
            assertSearchRoot("/opt/app/*.jar","/opt/app");
            assertSearchRoot("/lib/jvm/**/jre/lib/*.jar","/lib/jvm");
            assertSearchRoot("glob:/var/lib/*.xml","/var/lib");
            assertSearchRoot("glob:/var/lib/*.{xml,java}","/var/lib");
            assertSearchRoot("glob:/opt/corporate/lib-{dev,prod}/*.ini","/opt/corporate");
            assertSearchRoot("regex:/opt/jetty/.*/lib-(dev|prod)/*.ini","/opt/jetty");

            assertSearchRoot("/*.ini","/");
            assertSearchRoot("/etc/jetty.conf","/etc");
            assertSearchRoot("/common.conf","/");
        }

        if (OS.IS_WINDOWS)
        {
            // absolute patterns (complete with required windows slash escaping)
            assertSearchRoot("C:\\\\corp\\\\lib\\\\*.jar","C:\\corp\\lib");
            assertSearchRoot("D:\\\\lib\\\\**\\\\jre\\\\lib\\\\*.jar","C:\\lib");
        }

        // some relative paths
        assertSearchRoot("lib/*.jar","lib");
        assertSearchRoot("etc/jetty.xml","etc");
        assertSearchRoot("start.ini",".");
        assertSearchRoot("start.d/",".");
    }
}
