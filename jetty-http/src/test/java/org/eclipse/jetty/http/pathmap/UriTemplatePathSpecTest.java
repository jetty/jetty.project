//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for URI Template Path Specs
 */
public class UriTemplatePathSpecTest
{
    private void assertDetectedVars(UriTemplatePathSpec spec, String... expectedVars)
    {
        String prefix = String.format("Spec(\"%s\")", spec.getDeclaration());
        assertEquals(expectedVars.length, spec.getVariableCount(), prefix + ".variableCount");
        assertEquals(expectedVars.length, spec.getVariables().length, prefix + ".variable.length");
        for (int i = 0; i < expectedVars.length; i++)
        {
            assertThat(String.format("%s.variable[%d]", prefix, i), spec.getVariables()[i], is(expectedVars[i]));
        }
    }

    private void assertMatches(PathSpec spec, String path)
    {
        String msg = String.format("Spec(\"%s\").matches(\"%s\")", spec.getDeclaration(), path);
        assertThat(msg, spec.matches(path), is(true));
    }

    private void assertNotMatches(PathSpec spec, String path)
    {
        String msg = String.format("!Spec(\"%s\").matches(\"%s\")", spec.getDeclaration(), path);
        assertThat(msg, spec.matches(path), is(false));
    }

    @Test
    public void testDefaultPathSpec()
    {
        UriTemplatePathSpec spec = new UriTemplatePathSpec("/");
        assertEquals("/", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(1, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.EXACT, spec.getGroup(), "Spec.group");

        assertEquals(0, spec.getVariableCount(), "Spec.variableCount");
        assertEquals(0, spec.getVariables().length, "Spec.variable.length");
    }

    @Test
    public void testExactOnePathSpec()
    {
        UriTemplatePathSpec spec = new UriTemplatePathSpec("/a");
        assertEquals("/a", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/a$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(1, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.EXACT, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/a");
        assertMatches(spec, "/a?type=other");
        assertNotMatches(spec, "/a/b");
        assertNotMatches(spec, "/a/");

        assertEquals(0, spec.getVariableCount(), "Spec.variableCount");
        assertEquals(0, spec.getVariables().length, "Spec.variable.length");
    }

    @Test
    public void testExactPathSpecTestWebapp()
    {
        UriTemplatePathSpec spec = new UriTemplatePathSpec("/deep.thought/");
        assertEquals("/deep.thought/", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/deep\\.thought/$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(1, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.EXACT, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/deep.thought/");
        assertNotMatches(spec, "/deep.thought");

        assertEquals(0, spec.getVariableCount(), "Spec.variableCount");
        assertEquals(0, spec.getVariables().length, "Spec.variable.length");
    }

    @Test
    public void testExactTwoPathSpec()
    {
        UriTemplatePathSpec spec = new UriTemplatePathSpec("/a/b");
        assertEquals("/a/b", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/a/b$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(2, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.EXACT, spec.getGroup(), "Spec.group");

        assertEquals(0, spec.getVariableCount(), "Spec.variableCount");
        assertEquals(0, spec.getVariables().length, "Spec.variable.length");

        assertMatches(spec, "/a/b");

        assertNotMatches(spec, "/a/b/");
        assertNotMatches(spec, "/a/");
        assertNotMatches(spec, "/a/bb");
    }

    @Test
    public void testMiddleVarPathSpec()
    {
        UriTemplatePathSpec spec = new UriTemplatePathSpec("/a/{var}/c");
        assertEquals("/a/{var}/c", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/a/([^/]+)/c$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(3, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.MIDDLE_GLOB, spec.getGroup(), "Spec.group");

        assertDetectedVars(spec, "var");

        assertMatches(spec, "/a/b/c");
        assertMatches(spec, "/a/zz/c");
        assertMatches(spec, "/a/hello+world/c");
        assertNotMatches(spec, "/a/bc");
        assertNotMatches(spec, "/a/b/");
        assertNotMatches(spec, "/a/b");

        Map<String, String> mapped = spec.getPathParams("/a/b/c");
        assertThat("Spec.pathParams", mapped, notNullValue());
        assertThat("Spec.pathParams.size", mapped.size(), is(1));
        assertEquals("b", mapped.get("var"), "Spec.pathParams[var]");
    }

    @Test
    public void testPathInfo()
    {
        UriTemplatePathSpec spec = new UriTemplatePathSpec("/test/{var}");
        assertTrue(spec.matches("/test/info"));
        assertThat(spec.getPathMatch("/test/info"), equalTo("/test"));
        assertThat(spec.getPathInfo("/test/info"), equalTo("info"));

        spec = new UriTemplatePathSpec("/{x}/test/{y}");
        assertTrue(spec.matches("/try/test/info"));
        assertThat(spec.getPathMatch("/try/test/info"), equalTo("/try/test/info"));
        assertThat(spec.getPathInfo("/try/test/info"), nullValue());
    }

    @Test
    public void testOneVarPathSpec()
    {
        UriTemplatePathSpec spec = new UriTemplatePathSpec("/a/{foo}");
        assertEquals("/a/{foo}", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/a/([^/]+)$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(2, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.PREFIX_GLOB, spec.getGroup(), "Spec.group");

        assertDetectedVars(spec, "foo");

        assertMatches(spec, "/a/b");
        assertNotMatches(spec, "/a/");
        assertNotMatches(spec, "/a");

        Map<String, String> mapped = spec.getPathParams("/a/b");
        assertThat("Spec.pathParams", mapped, notNullValue());
        assertThat("Spec.pathParams.size", mapped.size(), is(1));
        assertEquals("b", mapped.get("foo"), "Spec.pathParams[foo]");
    }

    @Test
    public void testOneVarSuffixPathSpec()
    {
        UriTemplatePathSpec spec = new UriTemplatePathSpec("/{var}/b/c");
        assertEquals("/{var}/b/c", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/([^/]+)/b/c$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(3, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.SUFFIX_GLOB, spec.getGroup(), "Spec.group");

        assertDetectedVars(spec, "var");

        assertMatches(spec, "/a/b/c");
        assertMatches(spec, "/az/b/c");
        assertMatches(spec, "/hello+world/b/c");
        assertNotMatches(spec, "/a/bc");
        assertNotMatches(spec, "/a/b/");
        assertNotMatches(spec, "/a/b");

        Map<String, String> mapped = spec.getPathParams("/a/b/c");
        assertThat("Spec.pathParams", mapped, notNullValue());
        assertThat("Spec.pathParams.size", mapped.size(), is(1));
        assertEquals("a", mapped.get("var"), "Spec.pathParams[var]");
    }

    @Test
    public void testTwoVarComplexInnerPathSpec()
    {
        UriTemplatePathSpec spec = new UriTemplatePathSpec("/a/{var1}/c/{var2}/e");
        assertEquals("/a/{var1}/c/{var2}/e", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/a/([^/]+)/c/([^/]+)/e$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(5, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.MIDDLE_GLOB, spec.getGroup(), "Spec.group");

        assertDetectedVars(spec, "var1", "var2");

        assertMatches(spec, "/a/b/c/d/e");
        assertNotMatches(spec, "/a/bc/d/e");
        assertNotMatches(spec, "/a/b/d/e");
        assertNotMatches(spec, "/a/b//d/e");

        Map<String, String> mapped = spec.getPathParams("/a/b/c/d/e");
        assertThat("Spec.pathParams", mapped, notNullValue());
        assertThat("Spec.pathParams.size", mapped.size(), is(2));
        assertEquals("b", mapped.get("var1"), "Spec.pathParams[var1]");
        assertEquals("d", mapped.get("var2"), "Spec.pathParams[var2]");
    }

    @Test
    public void testTwoVarComplexOuterPathSpec()
    {
        UriTemplatePathSpec spec = new UriTemplatePathSpec("/{var1}/b/{var2}/{var3}");
        assertEquals("/{var1}/b/{var2}/{var3}", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/([^/]+)/b/([^/]+)/([^/]+)$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(4, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.MIDDLE_GLOB, spec.getGroup(), "Spec.group");

        assertDetectedVars(spec, "var1", "var2", "var3");

        assertMatches(spec, "/a/b/c/d");
        assertNotMatches(spec, "/a/bc/d/e");
        assertNotMatches(spec, "/a/c/d/e");
        assertNotMatches(spec, "/a//d/e");

        Map<String, String> mapped = spec.getPathParams("/a/b/c/d");
        assertThat("Spec.pathParams", mapped, notNullValue());
        assertThat("Spec.pathParams.size", mapped.size(), is(3));
        assertEquals("a", mapped.get("var1"), "Spec.pathParams[var1]");
        assertEquals("c", mapped.get("var2"), "Spec.pathParams[var2]");
        assertEquals("d", mapped.get("var3"), "Spec.pathParams[var3]");
    }

    @Test
    public void testTwoVarPrefixPathSpec()
    {
        UriTemplatePathSpec spec = new UriTemplatePathSpec("/a/{var1}/{var2}");
        assertEquals("/a/{var1}/{var2}", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/a/([^/]+)/([^/]+)$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(3, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.PREFIX_GLOB, spec.getGroup(), "Spec.group");

        assertDetectedVars(spec, "var1", "var2");

        assertMatches(spec, "/a/b/c");
        assertNotMatches(spec, "/a/bc");
        assertNotMatches(spec, "/a/b/");
        assertNotMatches(spec, "/a/b");

        Map<String, String> mapped = spec.getPathParams("/a/b/c");
        assertThat("Spec.pathParams", mapped, notNullValue());
        assertThat("Spec.pathParams.size", mapped.size(), is(2));
        assertEquals("b", mapped.get("var1"), "Spec.pathParams[var1]");
        assertEquals("c", mapped.get("var2"), "Spec.pathParams[var2]");
    }

    @Test
    public void testVarOnlyPathSpec()
    {
        UriTemplatePathSpec spec = new UriTemplatePathSpec("/{var1}");
        assertEquals("/{var1}", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/([^/]+)$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(1, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.PREFIX_GLOB, spec.getGroup(), "Spec.group");

        assertDetectedVars(spec, "var1");

        assertMatches(spec, "/a");
        assertNotMatches(spec, "/");
        assertNotMatches(spec, "/a/b");
        assertNotMatches(spec, "/a/b/c");

        Map<String, String> mapped = spec.getPathParams("/a");
        assertThat("Spec.pathParams", mapped, notNullValue());
        assertThat("Spec.pathParams.size", mapped.size(), is(1));
        assertEquals("a", mapped.get("var1"), "Spec.pathParams[var1]");
    }

    @Test
    public void testEquals()
    {
        assertThat(new UriTemplatePathSpec("/{var1}"), equalTo(new UriTemplatePathSpec("/{var1}")));
        assertThat(new UriTemplatePathSpec("/{var1}"), equalTo(new UriTemplatePathSpec("/{var2}")));
        assertThat(new UriTemplatePathSpec("/{var1}/{var2}"), equalTo(new UriTemplatePathSpec("/{var2}/{var1}")));
        assertThat(new UriTemplatePathSpec("/{var1}"), not(equalTo(new UriTemplatePathSpec("/{var1}/{var2}"))));
        assertThat(new UriTemplatePathSpec("/a/b/c"), not(equalTo(new UriTemplatePathSpec("/a/{var}/c"))));
        assertThat(new UriTemplatePathSpec("/foo"), not(equalTo(new ServletPathSpec("/foo"))));
    }
}
