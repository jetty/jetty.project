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

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RegexPathSpecTest
{
    public static void assertMatches(PathSpec spec, String path)
    {
        String msg = String.format("Spec(\"%s\").matches(\"%s\")", spec.getDeclaration(), path);
        assertNotNull(spec.matched(path), msg);
    }

    public static void assertNotMatches(PathSpec spec, String path)
    {
        String msg = String.format("!Spec(\"%s\").matches(\"%s\")", spec.getDeclaration(), path);
        assertNull(spec.matched(path), msg);
    }

    @Test
    public void testExactSpec()
    {
        RegexPathSpec spec = new RegexPathSpec("^/a$");
        assertEquals("^/a$", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/a$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(1, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.EXACT, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/a");

        assertNotMatches(spec, "/aa");
        assertNotMatches(spec, "/a/");
    }

    @Test
    public void testMiddleSpec()
    {
        RegexPathSpec spec = new RegexPathSpec("^/rest/([^/]*)/list$");
        assertEquals("^/rest/([^/]*)/list$", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/rest/([^/]*)/list$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(3, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.MIDDLE_GLOB, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/rest/api/list");
        assertMatches(spec, "/rest/1.0/list");
        assertMatches(spec, "/rest/2.0/list");
        assertMatches(spec, "/rest/accounts/list");

        assertNotMatches(spec, "/a");
        assertNotMatches(spec, "/aa");
        assertNotMatches(spec, "/aa/bb");
        assertNotMatches(spec, "/rest/admin/delete");
        assertNotMatches(spec, "/rest/list");
    }

    @Test
    public void testPathInfo()
    {
        RegexPathSpec spec = new RegexPathSpec("^/test(/.*)?$");
        assertTrue(spec.matches("/test/info"));
        assertThat(spec.getPathMatch("/test/info"), equalTo("/test"));
        assertThat(spec.getPathInfo("/test/info"), equalTo("/info"));

        spec = new RegexPathSpec("^/[Tt]est(/.*)?$");
        assertTrue(spec.matches("/test/info"));
        assertThat(spec.getPathMatch("/test/info"), equalTo("/test/info"));
        assertThat(spec.getPathInfo("/test/info"), nullValue());
    }

    @Test
    public void testMiddleSpecNoGrouping()
    {
        RegexPathSpec spec = new RegexPathSpec("^/rest/[^/]+/list$");
        assertEquals("^/rest/[^/]+/list$", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/rest/[^/]+/list$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(3, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.MIDDLE_GLOB, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/rest/api/list");
        assertMatches(spec, "/rest/1.0/list");
        assertMatches(spec, "/rest/2.0/list");
        assertMatches(spec, "/rest/accounts/list");

        assertNotMatches(spec, "/a");
        assertNotMatches(spec, "/aa");
        assertNotMatches(spec, "/aa/bb");
        assertNotMatches(spec, "/rest/admin/delete");
        assertNotMatches(spec, "/rest/list");
    }

    @Test
    public void testPrefixSpec()
    {
        RegexPathSpec spec = new RegexPathSpec("^/a/(.*)$");
        assertEquals("^/a/(.*)$", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^/a/(.*)$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(2, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.PREFIX_GLOB, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/a/");
        assertMatches(spec, "/a/b");
        assertMatches(spec, "/a/b/c/d/e");

        assertNotMatches(spec, "/a");
        assertNotMatches(spec, "/aa");
        assertNotMatches(spec, "/aa/bb");
    }

    @Test
    public void testSuffixSpec()
    {
        RegexPathSpec spec = new RegexPathSpec("^(.*).do$");
        assertEquals("^(.*).do$", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^(.*).do$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(0, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.SUFFIX_GLOB, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/a.do");
        assertMatches(spec, "/a/b/c.do");
        assertMatches(spec, "/abcde.do");
        assertMatches(spec, "/abc/efg.do");

        assertNotMatches(spec, "/a");
        assertNotMatches(spec, "/aa");
        assertNotMatches(spec, "/aa/bb");
        assertNotMatches(spec, "/aa/bb.do/more");
    }

    @Test
    public void testSuffixSpecMiddle()
    {
        RegexPathSpec spec = new RegexPathSpec("^.*/middle/.*$");
        assertEquals("^.*/middle/.*$", spec.getDeclaration(), "Spec.pathSpec");
        assertEquals("^.*/middle/.*$", spec.getPattern().pattern(), "Spec.pattern");
        assertEquals(2, spec.getPathDepth(), "Spec.pathDepth");
        assertEquals(PathSpecGroup.SUFFIX_GLOB, spec.getGroup(), "Spec.group");

        assertMatches(spec, "/a/middle/c.do");
        assertMatches(spec, "/a/b/c/d/middle/e/f");
        assertMatches(spec, "/middle/");

        assertNotMatches(spec, "/a.do");
        assertNotMatches(spec, "/a");
        assertNotMatches(spec, "/aa");
        assertNotMatches(spec, "/aa/bb");
        assertNotMatches(spec, "/aa/bb.do/more");
    }

    @Test
    public void testEquals()
    {
        assertThat(new RegexPathSpec("^(.*).do$"), equalTo(new RegexPathSpec("^(.*).do$")));
        assertThat(new RegexPathSpec("/foo"), equalTo(new RegexPathSpec("/foo")));
        assertThat(new RegexPathSpec("^(.*).do$"), not(equalTo(new RegexPathSpec("^(.*).gz$"))));
        assertThat(new RegexPathSpec("^(.*).do$"), not(equalTo(new RegexPathSpec("^.*.do$"))));
        assertThat(new RegexPathSpec("/foo"), not(equalTo(new ServletPathSpec("/foo"))));
    }
}
