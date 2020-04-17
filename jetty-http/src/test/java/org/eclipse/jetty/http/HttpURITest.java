//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class HttpURITest
{
    @Test
    public void testExample() throws Exception
    {
        HttpURI uri = new HttpURI("http://user:password@host:8888/ignored/../p%61th;ignored/info;param?query=value#fragment");

        assertThat(uri.getScheme(), is("http"));
        assertThat(uri.getUser(), is("user:password"));
        assertThat(uri.getHost(), is("host"));
        assertThat(uri.getPort(), is(8888));
        assertThat(uri.getPath(), is("/ignored/../p%61th;ignored/info;param"));
        assertThat(uri.getDecodedPath(), is("/path/info"));
        assertThat(uri.getParam(), is("param"));
        assertThat(uri.getQuery(), is("query=value"));
        assertThat(uri.getFragment(), is("fragment"));
        assertThat(uri.getAuthority(), is("host:8888"));
    }

    @Test
    public void testInvalidAddress() throws Exception
    {
        assertInvalidURI("http://[ffff::1:8080/", "Invalid URL; no closing ']' -- should throw exception");
        assertInvalidURI("**", "only '*', not '**'");
        assertInvalidURI("*/", "only '*', not '*/'");
    }

    private void assertInvalidURI(String invalidURI, String message)
    {
        try
        {
            new HttpURI.Builder(invalidURI).build();
            fail(message);
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(true);
        }
    }

    @Test
    public void testParse()
    {
        HttpURI.Builder builder = new HttpURI.Builder();
        HttpURI uri;

        builder.uri("*");
        uri = builder.build();
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("*"));

        builder.uri("/foo/bar");
        uri = builder.build();
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("/foo/bar"));

        builder.uri("//foo/bar");
        uri = builder.build();
        assertThat(uri.getHost(), is("foo"));
        assertThat(uri.getPath(), is("/bar"));

        builder.uri("http://foo/bar");
        uri = builder.build();
        assertThat(uri.getHost(), is("foo"));
        assertThat(uri.getPath(), is("/bar"));
    }

    @Test
    public void testParseRequestTarget()
    {
        HttpURI uri;

        uri = new HttpURI.Builder("GET", "*").build();
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("*"));

        uri = new HttpURI.Builder("GET", "/foo/bar").build();
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("/foo/bar"));

        uri = new HttpURI.Builder("GET", "//foo/bar").build();
        assertThat(uri.getHost(), nullValue());
        assertThat(uri.getPath(), is("//foo/bar"));

        uri = new HttpURI.Builder("GET", "http://foo/bar").build();
        assertThat(uri.getHost(), is("foo"));
        assertThat(uri.getPath(), is("/bar"));
    }

    @Test
    public void testAt() throws Exception
    {
        HttpURI uri = new HttpURI("/@foo/bar");
        assertEquals("/@foo/bar", uri.getPath());
    }

    @Test
    public void testParams() throws Exception
    {
        HttpURI uri = new HttpURI("/foo/bar");
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertEquals(null, uri.getParam());

        uri = new HttpURI("/foo/bar;jsessionid=12345");
        assertEquals("/foo/bar;jsessionid=12345", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertEquals("jsessionid=12345", uri.getParam());

        uri = new HttpURI("/foo;abc=123/bar;jsessionid=12345");
        assertEquals("/foo;abc=123/bar;jsessionid=12345", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertEquals("jsessionid=12345", uri.getParam());

        uri = new HttpURI("/foo;abc=123/bar;jsessionid=12345?name=value");
        assertEquals("/foo;abc=123/bar;jsessionid=12345", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertEquals("jsessionid=12345", uri.getParam());

        uri = new HttpURI("/foo;abc=123/bar;jsessionid=12345#target");
        assertEquals("/foo;abc=123/bar;jsessionid=12345", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());
        assertEquals("jsessionid=12345", uri.getParam());
    }

    @Test
    public void testMutableURIBuilder()
    {
        HttpURI.Builder builder = new HttpURI.Builder("/foo/bar");
        HttpURI uri = builder.build();
        assertEquals("/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());

        uri = builder.scheme("http").build();
        assertEquals("http:/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());

        uri = builder.authority("host", 0).build();
        assertEquals("http://host/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());

        uri = builder.authority("host", 8888).build();
        assertEquals("http://host:8888/foo/bar", uri.toString());
        assertEquals("/foo/bar", uri.getPath());
        assertEquals("/foo/bar", uri.getDecodedPath());

        uri = builder.pathQuery("/f%30%30;p0/bar;p1;p2").build();
        assertEquals("http://host:8888/f%30%30;p0/bar;p1;p2", uri.toString());
        assertEquals("/f%30%30;p0/bar;p1;p2", uri.getPath());
        assertEquals("/f00/bar", uri.getDecodedPath());
        assertEquals("p2", uri.getParam());
        assertEquals(null, uri.getQuery());

        uri = builder.pathQuery("/f%30%30;p0/bar;p1;p2?name=value").build();
        assertEquals("http://host:8888/f%30%30;p0/bar;p1;p2?name=value", uri.toString());
        assertEquals("/f%30%30;p0/bar;p1;p2", uri.getPath());
        assertEquals("/f00/bar", uri.getDecodedPath());
        assertEquals("p2", uri.getParam());
        assertEquals("name=value", uri.getQuery());

        uri = builder.query("other=123456").build();
        assertEquals("http://host:8888/f%30%30;p0/bar;p1;p2?other=123456", uri.toString());
        assertEquals("/f%30%30;p0/bar;p1;p2", uri.getPath());
        assertEquals("/f00/bar", uri.getDecodedPath());
        assertEquals("p2", uri.getParam());
        assertEquals("other=123456", uri.getQuery());
    }

    @Test
    public void testSchemeAndOrAuthority() throws Exception
    {
        HttpURI.Builder builder = new HttpURI.Builder("/path/info");
        HttpURI uri = builder.build();
        assertEquals("/path/info", uri.toString());

        uri = builder.authority("host", 0).build();
        assertEquals("//host/path/info", uri.toString());

        uri = builder.authority("host", 8888).build();
        assertEquals("//host:8888/path/info", uri.toString());

        uri = builder.scheme("http").build();
        assertEquals("http://host:8888/path/info", uri.toString());

        uri = builder.authority(null, 0).build();
        assertEquals("http:/path/info", uri.toString());
    }

    @Test
    public void testBasicAuthCredentials() throws Exception
    {
        HttpURI uri = new HttpURI("http://user:password@example.com:8888/blah");
        assertEquals("http://user:password@example.com:8888/blah", uri.toString());
        assertEquals(uri.getAuthority(), "example.com:8888");
        assertEquals(uri.getUser(), "user:password");
    }

    @Test
    public void testCanonicalDecoded() throws Exception
    {
        HttpURI uri = new HttpURI("/path/.info");
        assertEquals("/path/.info", uri.getDecodedPath());

        uri = new HttpURI("/path/./info");
        assertEquals("/path/info", uri.getDecodedPath());

        uri = new HttpURI("/path/../info");
        assertEquals("/info", uri.getDecodedPath());

        uri = new HttpURI("/./path/info.");
        assertEquals("/path/info.", uri.getDecodedPath());

        uri = new HttpURI("./path/info/.");
        assertEquals("path/info/", uri.getDecodedPath());

        uri = new HttpURI("http://host/path/.info");
        assertEquals("/path/.info", uri.getDecodedPath());

        uri = new HttpURI("http://host/path/./info");
        assertEquals("/path/info", uri.getDecodedPath());

        uri = new HttpURI("http://host/path/../info");
        assertEquals("/info", uri.getDecodedPath());

        uri = new HttpURI("http://host/./path/info.");
        assertEquals("/path/info.", uri.getDecodedPath());

        uri = new HttpURI("http:./path/info/.");
        assertEquals("path/info/", uri.getDecodedPath());
    }
}
