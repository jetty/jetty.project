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
 * Tests of {@link PathMappings#getMatch(String)}, with a focus on correct mapping selection order
 */
@RunWith(Parameterized.class)
public class ServletPathSpecOrderTest
{
    @Parameters(name="{0} = {1}")
    public static List<String[]> testCases()
    {
        String data[][] = new String[][] { 
            // From old PathMapTest
            {"/abs/path","abspath"},
            {"/abs/path/xxx","default"},
            {"/abs/pith","default"},
            {"/abs/path/longer","longpath"},
            {"/abs/path/","default"},
            {"/abs/path/foo","default"},
            {"/animal/bird/eagle/bald","birds"},
            {"/animal/fish/shark/hammerhead","fishes"},
            {"/animal/insect/ladybug","animals"},
            {"/animal","animals"},
            {"/animal/","animals"},
            {"/animal/other","animals"},
            {"/animal/*","animals"},
            {"/downloads/distribution.tar.gz","tarball"},
            {"/downloads/script.gz","gzipped"},
            {"/animal/arhive.gz","animals"},
            {"/Other/path","default"},
            {"/\u20ACuro/path","money"},
            {"/","root"},
        
            // Extra tests
            {"/downloads/readme.txt","default"},
            {"/downloads/logs.tgz","default"},
            {"/main.css","default"}
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
    public String inputPath;
    
    @Parameter(1)
    public String expectedResource;
    
    @Test
    public void testMatch()
    {
        assertThat("Match on ["+ inputPath+ "]", mappings.getMatch(inputPath).getResource(), is(expectedResource));
    }
}
