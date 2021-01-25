//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNull;

public class WebSocketUriMappingTest
{
    private PathMappings<String> mapping = new PathMappings<>();

    private String getBestMatch(String uriPath)
    {
        List<MappedResource<String>> resources = mapping.getMatches(uriPath);
        assertThat("Matches on " + uriPath, resources, is(not(nullValue())));
        if (resources.isEmpty())
            return null;
        return resources.get(0).getResource();
    }

    @Test
    public void testJsrExampleI()
    {
        mapping.put("/a/b", "endpointA");

        assertThat(getBestMatch("/a/b"), is("endpointA"));
        assertNull(getBestMatch("/a/c"));
    }

    @Test
    public void testJsrExampleII()
    {
        mapping.put(new UriTemplatePathSpec("/a/{var}"), "endpointA");

        assertThat(getBestMatch("/a/b"), is("endpointA"));
        assertThat(getBestMatch("/a/apple"), is("endpointA"));
        assertNull(getBestMatch("/a"));
        assertNull(getBestMatch("/a/b/c"));
    }

    @Test
    public void testJsrExampleIII()
    {
        mapping.put(new UriTemplatePathSpec("/a/{var}/c"), "endpointA");
        mapping.put(new UriTemplatePathSpec("/a/b/c"), "endpointB");
        mapping.put(new UriTemplatePathSpec("/a/{var1}/{var2}"), "endpointC");

        assertThat(getBestMatch("/a/b/c"), is("endpointB"));
        assertThat(getBestMatch("/a/d/c"), is("endpointA"));
        assertThat(getBestMatch("/a/x/y"), is("endpointC"));
    }

    @Test
    public void testJsrExampleIV()
    {
        mapping.put(new UriTemplatePathSpec("/{var1}/d"), "endpointA");
        mapping.put(new UriTemplatePathSpec("/b/{var2}"), "endpointB");

        assertThat(getBestMatch("/b/d"), is("endpointB"));
    }

    @Test
    public void testPrefixVsSuffix()
    {
        mapping.put(new UriTemplatePathSpec("/{a}/b"), "suffix");
        mapping.put(new UriTemplatePathSpec("/{a}/{b}"), "prefix");

        List<MappedResource<String>> matches = mapping.getMatches("/a/b");

        assertThat(getBestMatch("/a/b"), is("suffix"));
    }

    @Test
    public void testMiddleVsSuffix()
    {
        mapping.put(new UriTemplatePathSpec("/a/{b}/c"), "middle");
        mapping.put(new UriTemplatePathSpec("/a/b/{c}"), "suffix");

        assertThat(getBestMatch("/a/b/c"), is("suffix"));
    }

    @Test
    public void testMiddleVsSuffix2()
    {
        mapping.put(new UriTemplatePathSpec("/{a}/b/{c}"), "middle");
        mapping.put(new UriTemplatePathSpec("/{a}/b/c"), "suffix");

        assertThat(getBestMatch("/a/b/c"), is("suffix"));
    }

    @Test
    public void testMiddleVsPrefix()
    {
        mapping.put(new UriTemplatePathSpec("/a/{b}/{c}/d"), "middle");
        mapping.put(new UriTemplatePathSpec("/a/b/c/{d}"), "prefix");

        assertThat(getBestMatch("/a/b/c/d"), is("prefix"));
    }

    @Test
    public void testMiddleVsMiddle()
    {
        // This works but only because its an alphabetical check and '{' > 'c'.
        mapping.put(new UriTemplatePathSpec("/a/{b}/{c}/d"), "middle1");
        mapping.put(new UriTemplatePathSpec("/a/{b}/c/d"), "middle2");

        assertThat(getBestMatch("/a/b/c/d"), is("middle2"));
    }

    @Test
    public void testMiddleVsMiddle2()
    {
        mapping.put(new UriTemplatePathSpec("/{a}/{bz}/c/{d}"), "middle1");
        mapping.put(new UriTemplatePathSpec("/{a}/{ba}/{c}/d"), "middle2");

        assertThat(getBestMatch("/a/b/c/d"), is("middle1"));
    }

    @Test
    public void testMiddleVsMiddle3()
    {
        mapping.put(new UriTemplatePathSpec("/{a}/{ba}/c/{d}"), "middle1");
        mapping.put(new UriTemplatePathSpec("/{a}/{bz}/{c}/d"), "middle2");

        assertThat(getBestMatch("/a/b/c/d"), is("middle1"));
    }

    @Test
    public void testPrefixVsPrefix()
    {
        // This works but only because its an alphabetical check and '{' > 'b'.
        mapping.put(new UriTemplatePathSpec("/a/{b}/{c}"), "prefix1");
        mapping.put(new UriTemplatePathSpec("/a/b/{c}"), "prefix2");

        assertThat(getBestMatch("/a/b/c"), is("prefix2"));
    }

    @Test
    public void testSuffixVsSuffix()
    {
        // This works but only because its an alphabetical check and '{' > 'b'.
        mapping.put(new UriTemplatePathSpec("/{a}/{b}/c"), "suffix1");
        mapping.put(new UriTemplatePathSpec("/{a}/b/c"), "suffix2");

        assertThat(getBestMatch("/a/b/c"), is("suffix2"));
    }

    @Test
    public void testDifferentLengths()
    {
        mapping.put(new UriTemplatePathSpec("/a/{var}/c"), "endpointA");
        mapping.put(new UriTemplatePathSpec("/a/{var}/c/d"), "endpointB");
        mapping.put(new UriTemplatePathSpec("/a/{var1}/{var2}/d/e"), "endpointC");

        assertThat(getBestMatch("/a/b/c"), is("endpointA"));
        assertThat(getBestMatch("/a/d/c/d"), is("endpointB"));
        assertThat(getBestMatch("/a/x/y/d/e"), is("endpointC"));
    }
}
