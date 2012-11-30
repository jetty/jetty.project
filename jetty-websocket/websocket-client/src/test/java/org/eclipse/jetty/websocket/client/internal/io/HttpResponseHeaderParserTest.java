//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.internal.io;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class HttpResponseHeaderParserTest
{
    @Rule
    public TestTracker tt = new TestTracker();

    private void appendUtf8(ByteBuffer buf, String line)
    {
        buf.put(ByteBuffer.wrap(StringUtil.getBytes(line,StringUtil.__UTF8)));
    }

    @Test
    public void testParseRealWorldResponse()
    {
        // Arbitrary Http Response Headers seen in the wild.
        // Request URI -> https://ssl.google-analytics.com/__utm.gif
        List<String> expected = new ArrayList<>();
        expected.add("HTTP/1.0 200 OK");
        expected.add("Date: Thu, 09 Aug 2012 16:16:39 GMT");
        expected.add("Content-Length: 35");
        expected.add("X-Content-Type-Options: nosniff");
        expected.add("Pragma: no-cache");
        expected.add("Expires: Wed, 19 Apr 2000 11:43:00 GMT");
        expected.add("Last-Modified: Wed, 21 Jan 2004 19:51:30 GMT");
        expected.add("Content-Type: image/gif");
        expected.add("Cache-Control: private, no-cache, no-cache=Set-Cookie, proxy-revalidate");
        expected.add("Age: 518097");
        expected.add("Server: GFE/2.0");
        expected.add("Connection: Keep-Alive");
        expected.add("");

        // Prepare Buffer
        ByteBuffer buf = ByteBuffer.allocate(512);
        for (String line : expected)
        {
            appendUtf8(buf,line + "\r\n");
        }

        BufferUtil.flipToFlush(buf,0);

        // Parse Buffer
        HttpResponseHeaderParser parser = new HttpResponseHeaderParser();
        UpgradeResponse response = parser.parse(buf);
        Assert.assertThat("Response",response,notNullValue());

        Assert.assertThat("Response.statusCode",response.getStatusCode(),is(200));
        Assert.assertThat("Response.statusReason",response.getStatusReason(),is("OK"));

        Assert.assertThat("Response.header[age]",response.getHeader("age"),is("518097"));
    }

    @Test
    public void testParseRealWorldResponse_SmallBuffers()
    {
        // Arbitrary Http Response Headers seen in the wild.
        // Request URI -> https://ssl.google-analytics.com/__utm.gif
        List<String> expected = new ArrayList<>();
        expected.add("HTTP/1.0 200 OK");
        expected.add("Date: Thu, 09 Aug 2012 16:16:39 GMT");
        expected.add("Content-Length: 35");
        expected.add("X-Content-Type-Options: nosniff");
        expected.add("Pragma: no-cache");
        expected.add("Expires: Wed, 19 Apr 2000 11:43:00 GMT");
        expected.add("Last-Modified: Wed, 21 Jan 2004 19:51:30 GMT");
        expected.add("Content-Type: image/gif");
        expected.add("Cache-Control: private, no-cache, no-cache=Set-Cookie, proxy-revalidate");
        expected.add("Age: 518097");
        expected.add("Server: GFE/2.0");
        expected.add("Connection: Keep-Alive");
        expected.add("");

        // Prepare Buffer
        ByteBuffer buf = ByteBuffer.allocate(512);
        for (String line : expected)
        {
            appendUtf8(buf,line + "\r\n");
        }
        BufferUtil.flipToFlush(buf,0);

        // Prepare small buffers to simulate a slow read/fill/parse from the network
        ByteBuffer small1 = buf.slice();
        ByteBuffer small2 = buf.slice();
        ByteBuffer small3 = buf.slice();

        small1.limit(50);
        small2.position(50);
        small2.limit(70);
        small3.position(70);

        // Parse Buffer
        HttpResponseHeaderParser parser = new HttpResponseHeaderParser();
        UpgradeResponse response;

        // Parse small 1
        response = parser.parse(small1);
        Assert.assertThat("Small 1",response,nullValue());

        // Parse small 2
        response = parser.parse(small2);
        Assert.assertThat("Small 2",response,nullValue());

        // Parse small 3
        response = parser.parse(small3);
        Assert.assertThat("Small 3",response,notNullValue());

        Assert.assertThat("Response.statusCode",response.getStatusCode(),is(200));
        Assert.assertThat("Response.statusReason",response.getStatusReason(),is("OK"));

        Assert.assertThat("Response.header[age]",response.getHeader("age"),is("518097"));
    }
}
