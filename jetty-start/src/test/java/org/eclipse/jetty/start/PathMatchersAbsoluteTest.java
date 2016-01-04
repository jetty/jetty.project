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
public class PathMatchersAbsoluteTest
{
    @Parameters(name="{0} -> {1}")
    public static List<Object[]> data()
    {
        List<Object[]> cases = new ArrayList<>();
        
        if(OS.IS_UNIX)
        {
            cases.add(new Object[]{"/opt/app",true});
            cases.add(new Object[]{"/opt/app",true});
            cases.add(new Object[]{"/opt/florb",true});
            cases.add(new Object[]{"/home/user/benfranklin",true});
            cases.add(new Object[]{"glob:/home/user/benfranklin/*.jar",true});
            cases.add(new Object[]{"glob:/**/*.jar",true});
            cases.add(new Object[]{"regex:/*-[^dev].ini",true});
        }
        
        if(OS.IS_WINDOWS)
        {
            // normal declaration
            cases.add(new Object[]{"D:\\code\\jetty\\jetty-start\\src\\test\\resources\\extra-libs\\example.jar",true});
            // escaped declaration
            cases.add(new Object[]{"C:\\\\System32",true});
            cases.add(new Object[]{"C:\\\\Program Files",true});
        }
        
        cases.add(new Object[]{"etc",false});
        cases.add(new Object[]{"lib",false});
        cases.add(new Object[]{"${user.dir}",false});
        cases.add(new Object[]{"**/*.jar",false});
        cases.add(new Object[]{"glob:*.ini",false});
        cases.add(new Object[]{"regex:*-[^dev].ini",false});

        return cases;
    }
    
    @Parameter(value=0)
    public String pattern;
    @Parameter(value=1)
    public boolean expected;

    @Test
    public void testIsAbsolute()
    {
        Assert.assertThat("isAbsolute(\""+pattern+"\")",PathMatchers.isAbsolute(pattern),is(expected));
    }
}
