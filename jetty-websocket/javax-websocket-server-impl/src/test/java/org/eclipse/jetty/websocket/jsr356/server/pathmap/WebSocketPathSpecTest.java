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

package org.eclipse.jetty.websocket.jsr356.server.pathmap;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.util.Map;

import org.eclipse.jetty.websocket.server.pathmap.PathSpec;
import org.eclipse.jetty.websocket.server.pathmap.PathSpecGroup;
import org.junit.Test;

/**
 * Tests for ServerEndpoint Path Param / URI Template Path Specs
 */
public class WebSocketPathSpecTest
{
    private void assertDetectedVars(WebSocketPathSpec spec, String... expectedVars)
    {
        String prefix = String.format("Spec(\"%s\")",spec.getPathSpec());
        assertEquals(prefix + ".variableCount",expectedVars.length,spec.getVariableCount());
        assertEquals(prefix + ".variable.length",expectedVars.length,spec.getVariables().length);
        for (int i = 0; i < expectedVars.length; i++)
        {
            assertEquals(String.format("%s.variable[%d]",prefix,i),expectedVars[i],spec.getVariables()[i]);
        }
    }

    private void assertMatches(PathSpec spec, String path)
    {
        String msg = String.format("Spec(\"%s\").matches(\"%s\")",spec.getPathSpec(),path);
        assertThat(msg,spec.matches(path),is(true));
    }

    private void assertNotMatches(PathSpec spec, String path)
    {
        String msg = String.format("!Spec(\"%s\").matches(\"%s\")",spec.getPathSpec(),path);
        assertThat(msg,spec.matches(path),is(false));
    }

    @Test
    public void testDefaultPathSpec()
    {
        WebSocketPathSpec spec = new WebSocketPathSpec("/");
        assertEquals("Spec.pathSpec","/",spec.getPathSpec());
        assertEquals("Spec.pattern","^/$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",1,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.EXACT,spec.getGroup());

        assertEquals("Spec.variableCount",0,spec.getVariableCount());
        assertEquals("Spec.variable.length",0,spec.getVariables().length);
    }

    @Test
    public void testExactOnePathSpec()
    {
        WebSocketPathSpec spec = new WebSocketPathSpec("/a");
        assertEquals("Spec.pathSpec","/a",spec.getPathSpec());
        assertEquals("Spec.pattern","^/a$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",1,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.EXACT,spec.getGroup());
        
        assertMatches(spec,"/a");
        assertMatches(spec,"/a?type=other");
        assertNotMatches(spec,"/a/b");
        assertNotMatches(spec,"/a/");

        assertEquals("Spec.variableCount",0,spec.getVariableCount());
        assertEquals("Spec.variable.length",0,spec.getVariables().length);
    }
    
    @Test
    public void testExactPathSpec_TestWebapp()
    {
        WebSocketPathSpec spec = new WebSocketPathSpec("/javax.websocket/");
        assertEquals("Spec.pathSpec","/javax.websocket/",spec.getPathSpec());
        assertEquals("Spec.pattern","^/javax\\.websocket/$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",1,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.EXACT,spec.getGroup());
        
        assertMatches(spec,"/javax.websocket/");
        assertNotMatches(spec,"/javax.websocket");

        assertEquals("Spec.variableCount",0,spec.getVariableCount());
        assertEquals("Spec.variable.length",0,spec.getVariables().length);
    }
    
    @Test
    public void testExactTwoPathSpec()
    {
        WebSocketPathSpec spec = new WebSocketPathSpec("/a/b");
        assertEquals("Spec.pathSpec","/a/b",spec.getPathSpec());
        assertEquals("Spec.pattern","^/a/b$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",2,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.EXACT,spec.getGroup());

        assertEquals("Spec.variableCount",0,spec.getVariableCount());
        assertEquals("Spec.variable.length",0,spec.getVariables().length);

        assertMatches(spec,"/a/b");

        assertNotMatches(spec,"/a/b/");
        assertNotMatches(spec,"/a/");
        assertNotMatches(spec,"/a/bb");
    }

    @Test
    public void testMiddleVarPathSpec()
    {
        WebSocketPathSpec spec = new WebSocketPathSpec("/a/{var}/c");
        assertEquals("Spec.pathSpec","/a/{var}/c",spec.getPathSpec());
        assertEquals("Spec.pattern","^/a/([^/]+)/c$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",3,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.MIDDLE_GLOB,spec.getGroup());

        assertDetectedVars(spec,"var");

        assertMatches(spec,"/a/b/c");
        assertMatches(spec,"/a/zz/c");
        assertMatches(spec,"/a/hello+world/c");
        assertNotMatches(spec,"/a/bc");
        assertNotMatches(spec,"/a/b/");
        assertNotMatches(spec,"/a/b");

        Map<String, String> mapped = spec.getPathParams("/a/b/c");
        assertThat("Spec.pathParams",mapped,notNullValue());
        assertThat("Spec.pathParams.size",mapped.size(),is(1));
        assertEquals("Spec.pathParams[var]","b",mapped.get("var"));
    }

    @Test
    public void testOneVarPathSpec()
    {
        WebSocketPathSpec spec = new WebSocketPathSpec("/a/{foo}");
        assertEquals("Spec.pathSpec","/a/{foo}",spec.getPathSpec());
        assertEquals("Spec.pattern","^/a/([^/]+)$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",2,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.PREFIX_GLOB,spec.getGroup());

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
    public void testOneVarSuffixPathSpec()
    {
        WebSocketPathSpec spec = new WebSocketPathSpec("/{var}/b/c");
        assertEquals("Spec.pathSpec","/{var}/b/c",spec.getPathSpec());
        assertEquals("Spec.pattern","^/([^/]+)/b/c$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",3,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.SUFFIX_GLOB,spec.getGroup());

        assertDetectedVars(spec,"var");

        assertMatches(spec,"/a/b/c");
        assertMatches(spec,"/az/b/c");
        assertMatches(spec,"/hello+world/b/c");
        assertNotMatches(spec,"/a/bc");
        assertNotMatches(spec,"/a/b/");
        assertNotMatches(spec,"/a/b");

        Map<String, String> mapped = spec.getPathParams("/a/b/c");
        assertThat("Spec.pathParams",mapped,notNullValue());
        assertThat("Spec.pathParams.size",mapped.size(),is(1));
        assertEquals("Spec.pathParams[var]","a",mapped.get("var"));
    }

    @Test
    public void testTwoVarComplexInnerPathSpec()
    {
        WebSocketPathSpec spec = new WebSocketPathSpec("/a/{var1}/c/{var2}/e");
        assertEquals("Spec.pathSpec","/a/{var1}/c/{var2}/e",spec.getPathSpec());
        assertEquals("Spec.pattern","^/a/([^/]+)/c/([^/]+)/e$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",5,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.MIDDLE_GLOB,spec.getGroup());

        assertDetectedVars(spec,"var1","var2");

        assertMatches(spec,"/a/b/c/d/e");
        assertNotMatches(spec,"/a/bc/d/e");
        assertNotMatches(spec,"/a/b/d/e");
        assertNotMatches(spec,"/a/b//d/e");

        Map<String, String> mapped = spec.getPathParams("/a/b/c/d/e");
        assertThat("Spec.pathParams",mapped,notNullValue());
        assertThat("Spec.pathParams.size",mapped.size(),is(2));
        assertEquals("Spec.pathParams[var1]","b",mapped.get("var1"));
        assertEquals("Spec.pathParams[var2]","d",mapped.get("var2"));
    }

    @Test
    public void testTwoVarComplexOuterPathSpec()
    {
        WebSocketPathSpec spec = new WebSocketPathSpec("/{var1}/b/{var2}/{var3}");
        assertEquals("Spec.pathSpec","/{var1}/b/{var2}/{var3}",spec.getPathSpec());
        assertEquals("Spec.pattern","^/([^/]+)/b/([^/]+)/([^/]+)$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",4,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.MIDDLE_GLOB,spec.getGroup());

        assertDetectedVars(spec,"var1","var2","var3");

        assertMatches(spec,"/a/b/c/d");
        assertNotMatches(spec,"/a/bc/d/e");
        assertNotMatches(spec,"/a/c/d/e");
        assertNotMatches(spec,"/a//d/e");

        Map<String, String> mapped = spec.getPathParams("/a/b/c/d");
        assertThat("Spec.pathParams",mapped,notNullValue());
        assertThat("Spec.pathParams.size",mapped.size(),is(3));
        assertEquals("Spec.pathParams[var1]","a",mapped.get("var1"));
        assertEquals("Spec.pathParams[var2]","c",mapped.get("var2"));
        assertEquals("Spec.pathParams[var3]","d",mapped.get("var3"));
    }

    @Test
    public void testTwoVarPrefixPathSpec()
    {
        WebSocketPathSpec spec = new WebSocketPathSpec("/a/{var1}/{var2}");
        assertEquals("Spec.pathSpec","/a/{var1}/{var2}",spec.getPathSpec());
        assertEquals("Spec.pattern","^/a/([^/]+)/([^/]+)$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",3,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.PREFIX_GLOB,spec.getGroup());

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
        WebSocketPathSpec spec = new WebSocketPathSpec("/{var1}");
        assertEquals("Spec.pathSpec","/{var1}",spec.getPathSpec());
        assertEquals("Spec.pattern","^/([^/]+)$",spec.getPattern().pattern());
        assertEquals("Spec.pathDepth",1,spec.getPathDepth());
        assertEquals("Spec.group",PathSpecGroup.PREFIX_GLOB,spec.getGroup());

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
