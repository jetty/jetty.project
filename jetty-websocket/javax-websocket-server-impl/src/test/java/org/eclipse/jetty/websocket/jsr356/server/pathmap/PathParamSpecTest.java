//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server.pathmap;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.Map;

import org.junit.Test;

/**
 * Tests for ServerEndpoint Path Param / URI Template Path Specs
 */
public class PathParamSpecTest
{
    private void assertDetectedVars(PathParamSpec spec, String... expectedVars)
    {
        String prefix = String.format("Spec(\"%s\")",spec.getPathSpec());
        assertEquals(prefix + ".variableCount",expectedVars.length,spec.getVariableCount());
        assertEquals(prefix + ".variable.length",expectedVars.length,spec.getVariables().length);
        for (int i = 0; i < expectedVars.length; i++)
        {
            assertEquals(String.format("%s.variable[%d]",prefix,i),expectedVars[i],spec.getVariables()[i]);
        }
    }

    private void assertMatches(PathParamSpec spec, String path)
    {
        String msg = String.format("Spec(\"%s\").matches(\"%s\")",spec.getPathSpec(),path);
        assertThat(msg,spec.matches(path),is(true));
    }

    private void assertNotMatches(PathParamSpec spec, String path)
    {
        String msg = String.format("!Spec(\"%s\").matches(\"%s\")",spec.getPathSpec(),path);
        assertThat(msg,spec.matches(path),is(false));
    }

    @Test
    public void testDefaultPathSpec()
    {
        PathParamSpec spec = new PathParamSpec("/");
        assertEquals("Spec.pathSpec","/",spec.getPathSpec());
        assertEquals("Spec.pattern","^/$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",1,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.EXACT,spec.group);

        assertEquals("Spec.variableCount",0,spec.getVariableCount());
        assertEquals("Spec.variable.length",0,spec.getVariables().length);
    }

    @Test
    public void testExactOnePathSpec()
    {
        PathParamSpec spec = new PathParamSpec("/a");
        assertEquals("Spec.pathSpec","/a",spec.getPathSpec());
        assertEquals("Spec.pattern","^/a$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",1,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.EXACT,spec.group);

        assertEquals("Spec.variableCount",0,spec.getVariableCount());
        assertEquals("Spec.variable.length",0,spec.getVariables().length);
    }

    @Test
    public void testExactTwoPathSpec()
    {
        PathParamSpec spec = new PathParamSpec("/a/b");
        assertEquals("Spec.pathSpec","/a/b",spec.getPathSpec());
        assertEquals("Spec.pattern","^/a/b$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",2,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.EXACT,spec.group);

        assertEquals("Spec.variableCount",0,spec.getVariableCount());
        assertEquals("Spec.variable.length",0,spec.getVariables().length);

        assertMatches(spec,"/a/b");

        assertNotMatches(spec,"/a/b/");
        assertNotMatches(spec,"/a/");
        assertNotMatches(spec,"/a/bb");
    }

    @Test
    public void testOneVarPathSpec()
    {
        PathParamSpec spec = new PathParamSpec("/a/{foo}");
        assertEquals("Spec.pathSpec","/a/{foo}",spec.getPathSpec());
        assertEquals("Spec.pattern","^/a/([^/]+)$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",2,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.PREFIX_GLOB,spec.group);

        assertDetectedVars(spec,"foo");

        assertMatches(spec,"/a/b");
        assertNotMatches(spec,"/a/");
        assertNotMatches(spec,"/a");

        Map<String, String> mapped = spec.getPathParams("/a/b");
        assertThat("Spec.pathParams",mapped,notNullValue());
        assertThat("Spec.pathParams.size",mapped.size(),is(1));
        assertEquals("Spec.pathParams[foo]","b",mapped.get("foo"));
    }

    @Test
    public void testTwoVarPrefixPathSpec()
    {
        PathParamSpec spec = new PathParamSpec("/a/{var1}/{var2}");
        assertEquals("Spec.pathSpec","/a/{var1}/{var2}",spec.getPathSpec());
        assertEquals("Spec.pattern","^/a/([^/]+)/([^/]+)$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",3,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.PREFIX_GLOB,spec.group);

        assertDetectedVars(spec,"var1","var2");

        assertMatches(spec,"/a/b/c");
        assertNotMatches(spec,"/a/bc");
        assertNotMatches(spec,"/a/b/");
        assertNotMatches(spec,"/a/b");

        Map<String, String> mapped = spec.getPathParams("/a/b/c");
        assertThat("Spec.pathParams",mapped,notNullValue());
        assertThat("Spec.pathParams.size",mapped.size(),is(2));
        assertEquals("Spec.pathParams[var1]","b",mapped.get("var1"));
        assertEquals("Spec.pathParams[var2]","c",mapped.get("var2"));
    }

    @Test
    public void testVarOnlyPathSpec()
    {
        PathParamSpec spec = new PathParamSpec("/{var1}");
        assertEquals("Spec.pathSpec","/{var1}",spec.getPathSpec());
        assertEquals("Spec.pattern","^/([^/]+)$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",1,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.PREFIX_GLOB,spec.group);

        assertDetectedVars(spec,"var1");

        assertMatches(spec,"/a");
        assertNotMatches(spec,"/");
        assertNotMatches(spec,"/a/b");
        assertNotMatches(spec,"/a/b/c");

        Map<String, String> mapped = spec.getPathParams("/a");
        assertThat("Spec.pathParams",mapped,notNullValue());
        assertThat("Spec.pathParams.size",mapped.size(),is(1));
        assertEquals("Spec.pathParams[var1]","a",mapped.get("var1"));
    }
}
