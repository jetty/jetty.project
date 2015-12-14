//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http.pathmap;

import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests for bad path specs on ServerEndpoint Path Param / URI Template
 */
@RunWith(Parameterized.class)
public class UriTemplatePathSpecBadSpecsTest
{
    private static void bad(List<String[]> data, String str)
    {
        data.add(new String[]
        { str });
    }

    @Parameters
    public static Collection<String[]> data()
    {
        List<String[]> data = new ArrayList<>();
        bad(data,"/a/b{var}"); // bad syntax - variable does not encompass whole path segment
        bad(data,"a/{var}"); // bad syntax - no start slash
        bad(data,"/a/{var/b}"); // path segment separator in variable name
        bad(data,"/{var}/*"); // bad syntax - no globs allowed
        bad(data,"/{var}.do"); // bad syntax - variable does not encompass whole path segment
        bad(data,"/a/{var*}"); // use of glob character not allowed in variable name
        bad(data,"/a/{}"); // bad syntax - no variable name
        // MIGHT BE ALLOWED bad(data,"/a/{---}"); // no alpha in variable name
        bad(data,"{var}"); // bad syntax - no start slash
        bad(data,"/a/{my special variable}"); // bad syntax - space in variable name
        bad(data,"/a/{var}/{var}"); // variable name duplicate
        // MIGHT BE ALLOWED bad(data,"/a/{var}/{Var}/{vAR}"); // variable name duplicated (diff case)
        bad(data,"/a/../../../{var}"); // path navigation not allowed
        bad(data,"/a/./{var}"); // path navigation not allowed
        bad(data,"/a//{var}"); // bad syntax - double path slash (no path segment)
        return data;
    }

    private String pathSpec;

    public UriTemplatePathSpecBadSpecsTest(String pathSpec)
    {
        this.pathSpec = pathSpec;
    }

    @Test
    public void testBadPathSpec()
    {
        try
        {
            new UriTemplatePathSpec(this.pathSpec);
            fail("Expected IllegalArgumentException for a bad PathParam pathspec on: " + pathSpec);
        }
        catch (IllegalArgumentException e)
        {
            // expected path
            System.out.println(e.getMessage());
        }
    }
}
