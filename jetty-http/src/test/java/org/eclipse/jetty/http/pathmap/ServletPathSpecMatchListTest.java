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

package org.eclipse.jetty.http.pathmap;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests of {@link PathMappings#getMatches(String)}
 */
@RunWith(Parameterized.class)
public class ServletPathSpecMatchListTest
{
    @Parameters(name="{0} = {1}")
    public static List<String[]> testCases()
    {
        String data[][] = new String[][] { 
            // From old PathMapTest
            { "All matches",  "/animal/bird/path.tar.gz", "[/animal/bird/*=birds, /animal/*=animals, *.tar.gz=tarball, *.gz=gzipped, /=default]"},
            { "Dir matches",  "/animal/fish/", "[/animal/fish/*=fishes, /animal/*=animals, /=default]"},
            { "Dir matches",  "/animal/fish", "[/animal/fish/*=fishes, /animal/*=animals, /=default]"},
            { "Root matches", "/", "[=root, /=default]"},
            { "Dir matches",  "", "[/=default]"}
        };
        
        return Arrays.asList(data);
    }

    private static PathMappings<String> mappings;
    
    static {
        mappings = new PathMappings<>();

        // From old PathMapTest
        mappings.put(new ServletPathSpec("/abs/path"),"abspath"); // 1
        mappings.put(new ServletPathSpec("/abs/path/longer"),"longpath"); // 2 
        mappings.put(new ServletPathSpec("/animal/bird/*"),"birds"); // 3
        mappings.put(new ServletPathSpec("/animal/fish/*"),"fishes"); // 4
        mappings.put(new ServletPathSpec("/animal/*"),"animals"); // 5
        mappings.put(new ServletPathSpec("*.tar.gz"),"tarball"); // 6
        mappings.put(new ServletPathSpec("*.gz"),"gzipped"); // 7
        mappings.put(new ServletPathSpec("/"),"default"); // 8
        // 9 was the old Jetty ":" spec delimited case (no longer valid)
        mappings.put(new ServletPathSpec(""),"root"); // 10
        mappings.put(new ServletPathSpec("/\u20ACuro/*"),"money"); // 11
    }
    
    @Parameter(0)
    public String message;
    
    @Parameter(1)
    public String inputPath;
    
    @Parameter(2)
    public String expectedListing;
    
    @Test
    public void testGetMatches()
    {
        List<MappedResource<String>> matches = mappings.getMatches(inputPath);

        StringBuilder actual = new StringBuilder();
        actual.append('[');
        boolean delim = false;
        for (MappedResource<String> res : matches)
        {
            if (delim)
                actual.append(", ");
            actual.append(res.getPathSpec().pathSpec).append('=').append(res.getResource());
            delim = true;
        }
        actual.append(']');

        assertThat(message + " on [" + inputPath + "]",actual.toString(),is(expectedListing));
    }
}
