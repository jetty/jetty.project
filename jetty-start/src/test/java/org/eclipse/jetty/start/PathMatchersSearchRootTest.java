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

package org.eclipse.jetty.start;

import static org.hamcrest.Matchers.is;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.OS;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class PathMatchersSearchRootTest
{
    @Parameters(name="{0}")
    public static List<String[]> data()
    {
        List<String[]> cases = new ArrayList<>();
        
        if (OS.IS_UNIX)
        {
            // absolute first
            cases.add(new String[]{"/opt/app/*.jar","/opt/app"});
            cases.add(new String[]{"/lib/jvm/**/jre/lib/*.jar","/lib/jvm"});
            cases.add(new String[]{"glob:/var/lib/*.xml","/var/lib"});
            cases.add(new String[]{"glob:/var/lib/*.{xml,java}","/var/lib"});
            cases.add(new String[]{"glob:/opt/corporate/lib-{dev,prod}/*.ini","/opt/corporate"});
            cases.add(new String[]{"regex:/opt/jetty/.*/lib-(dev|prod)/*.ini","/opt/jetty"});

            cases.add(new String[]{"/*.ini","/"});
            cases.add(new String[]{"/etc/jetty.conf","/etc"});
            cases.add(new String[]{"/common.conf","/"});
        }

        if (OS.IS_WINDOWS)
        {
            // absolute declaration
            cases.add(new String[]{"D:\\code\\jetty\\jetty-start\\src\\test\\resources\\extra-libs\\example.jar",
                    "D:\\code\\jetty\\jetty-start\\src\\test\\resources\\extra-libs"});
            // escaped declaration
            // absolute patterns (complete with required windows slash escaping)
            cases.add(new String[]{"C:\\\\corp\\\\lib\\\\*.jar","C:\\corp\\lib"});
            cases.add(new String[]{"D:\\\\lib\\\\**\\\\jre\\\\lib\\\\*.jar","D:\\lib"});
        }

        // some relative paths
        cases.add(new String[]{"lib/*.jar","lib"});
        cases.add(new String[]{"etc/jetty.xml","etc"});
        cases.add(new String[]{"start.ini","."});
        cases.add(new String[]{"start.d/","start.d"});
        return cases;
    }
    
    @Parameter(value=0)
    public String pattern;
    @Parameter(value=1)
    public String expectedSearchRoot;
    
    @Test
    public void testSearchRoot()
    {
        Path actual = PathMatchers.getSearchRoot(pattern);
        String expectedNormal = FS.separators(expectedSearchRoot);
        Assert.assertThat(".getSearchRoot(\"" + pattern + "\")",actual.toString(),is(expectedNormal));
    }
}
