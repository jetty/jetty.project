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

package org.eclipse.jetty.util;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class HostPortTest
{
    @Parameters(name="{0}")
    public static List<String[]> testCases()
    {
        String data[][] = new String[][] { 
            {"","",null},
            {":80","","80"},
            {"host","host",null},
            {"host:80","host","80"},
            {"10.10.10.1","10.10.10.1",null},
            {"10.10.10.1:80","10.10.10.1","80"},
            {"[0::0::0::1]","[0::0::0::1]",null},
            {"[0::0::0::1]:80","[0::0::0::1]","80"},

            {null,null,null},
            {"host:",null,null},
            {"127.0.0.1:",null,null},
            {"[0::0::0::0::1]:",null,null},
            {"host:xxx",null,null},
            {"127.0.0.1:xxx",null,null},
            {"[0::0::0::0::1]:xxx",null,null},
            {"host:-80",null,null},
            {"127.0.0.1:-80",null,null},
            {"[0::0::0::0::1]:-80",null,null},
        };
        return Arrays.asList(data);
    }
    
    @Parameter(0)
    public String _authority;
    
    @Parameter(1)
    public String _expectedHost;
    
    @Parameter(2)
    public String _expectedPort;

    
    @Test
    public void test()
    {
        try
        {
            HostPort hostPort = new HostPort(_authority);
            assertThat(_authority,hostPort.getHost(),is(_expectedHost));
            
            if (_expectedPort==null)
                assertThat(_authority,hostPort.getPort(),is(0));
            else
                assertThat(_authority,hostPort.getPort(),is(Integer.valueOf(_expectedPort)));
        }
        catch (Exception e)
        {
            if (_expectedHost!=null)
                e.printStackTrace();
            assertNull(_authority,_expectedHost);
        }
    }

}
