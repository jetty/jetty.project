package org.eclipse.jetty.websocket.servlet;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;

public class WebSocketUriMappingTest
{
    private PathMappings<String> mapping = new PathMappings<>();

    private String getMatch(String pathSpecString)
    {
        MappedResource<String> resource = mapping.getMatch(pathSpecString);
        return resource == null ? null : resource.getResource();
    }

    @Test
    public void testJsrExampleI()
    {
        mapping.put("/a/b", "endpointA");

        assertThat(getMatch("/a/b"), is("endpointA"));
        assertNull(getMatch("/a/c"));
    }

    @Test
    public void testJsrExampleII()
    {
        mapping.put(new UriTemplatePathSpec("/a/{var}"), "endpointA");

        assertThat(getMatch("/a/b"), is("endpointA"));
        assertThat(getMatch("/a/apple"), is("endpointA"));
        assertNull(getMatch("/a"));
        assertNull(getMatch("/a/b/c"));
    }

    @Test
    public void testJsrExampleIII()
    {
        mapping.put(new UriTemplatePathSpec("/a/{var}/c"), "endpointA");
        mapping.put(new UriTemplatePathSpec("/a/b/c"), "endpointB");
        mapping.put(new UriTemplatePathSpec("/a/{var1}/{var2}"), "endpointC");

        assertThat(getMatch("/a/b/c"), is("endpointB"));
        assertThat(getMatch("/a/d/c"), is("endpointA"));
        assertThat(getMatch("/a/x/y"), is("endpointC"));
    }

    @Test
    public void testJsrExampleIV()
    {
        mapping.put(new UriTemplatePathSpec("/{var1}/d"), "endpointA");
        mapping.put(new UriTemplatePathSpec("/b/{var2}"), "endpointB");

        assertThat(getMatch("/b/d"), is("endpointB"));
    }

    @Test
    public void testPrefixVsSuffix()
    {
        mapping.put(new UriTemplatePathSpec("/{a}/b"), "suffix");
        mapping.put(new UriTemplatePathSpec("/{a}/{b}"), "prefix");

        assertThat(getMatch("/a/b"), is("suffix"));
    }

    @Test
    public void testMiddleVsSuffix()
    {
        mapping.put(new UriTemplatePathSpec("/a/{b}/c"), "middle");
        mapping.put(new UriTemplatePathSpec("/a/b/{c}"), "suffix");

        assertThat(getMatch("/a/b/c"), is("suffix"));
    }

    @Test
    public void testMiddleVsSuffix2()
    {
        mapping.put(new UriTemplatePathSpec("/{a}/b/{c}"), "middle");
        mapping.put(new UriTemplatePathSpec("/{a}/b/c"), "suffix");

        assertThat(getMatch("/a/b/c"), is("suffix"));
    }

    @Test
    public void testMiddleVsPrefix()
    {
        mapping.put(new UriTemplatePathSpec("/a/{b}/{c}/d"), "middle");
        mapping.put(new UriTemplatePathSpec("/a/b/c/{d}"), "prefix");

        assertThat(getMatch("/a/b/c/d"), is("prefix"));
    }

    @Test
    public void testMiddleVsMiddle()
    {
        // This works but only because its an alphabetical check and '{' > 'c'.
        mapping.put(new UriTemplatePathSpec("/a/{b}/{c}/d"), "middle1");
        mapping.put(new UriTemplatePathSpec("/a/{b}/c/d"), "middle2");

        assertThat(getMatch("/a/b/c/d"), is("middle2"));
    }

    @Test
    public void testMiddleVsMiddle2()
    {
        mapping.put(new UriTemplatePathSpec("/{a}/{bz}/c/{d}"), "middle1");
        mapping.put(new UriTemplatePathSpec("/{a}/{ba}/{c}/d"), "middle2");

        assertThat(getMatch("/a/b/c/d"), is("middle1"));
    }

    @Test
    public void testMiddleVsMiddle3()
    {
        mapping.put(new UriTemplatePathSpec("/{a}/{ba}/c/{d}"), "middle1");
        mapping.put(new UriTemplatePathSpec("/{a}/{bz}/{c}/d"), "middle2");

        assertThat(getMatch("/a/b/c/d"), is("middle1"));
    }

    @Test
    public void testPrefixVsPrefix()
    {
        // This works but only because its an alphabetical check and '{' > 'b'.
        mapping.put(new UriTemplatePathSpec("/a/{b}/{c}"), "prefix1");
        mapping.put(new UriTemplatePathSpec("/a/b/{c}"), "prefix2");

        assertThat(getMatch("/a/b/c"), is("prefix2"));
    }

    @Test
    public void testSuffixVsSuffix()
    {
        // This works but only because its an alphabetical check and '{' > 'b'.
        mapping.put(new UriTemplatePathSpec("/{a}/{b}/c"), "suffix1");
        mapping.put(new UriTemplatePathSpec("/{a}/b/c"), "suffix2");

        assertThat(getMatch("/a/b/c"), is("suffix2"));
    }
}
