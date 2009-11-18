// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.test.support.rawhttp;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.util.List;

import org.eclipse.jetty.test.AbstractJettyTestCase;
import org.junit.Test;

public class HttpResponseTesterTest extends AbstractJettyTestCase
{
    @Test
    public void testHttp11Response() throws IOException
    {
        StringBuffer rawResponse = new StringBuffer();
        rawResponse.append("HTTP/1.1 200 OK\n");
        rawResponse.append("Date: Mon, 08 Jun 2009 22:56:04 GMT\n");
        rawResponse.append("Content-Type: text/plain\n");
        rawResponse.append("Content-Length: 28\n");
        rawResponse.append("Last-Modified: Mon, 08 Jun 2009 17:06:22 GMT\n");
        rawResponse.append("Connection: close\n");
        rawResponse.append("Server: Jetty(7.0.y.z-SNAPSHOT\n");
        rawResponse.append("\n");
        rawResponse.append("ABCDEFGHIJKLMNOPQRSTTUVWXYZ\n");
        rawResponse.append("\n");

        HttpResponseTester response = new HttpResponseTester();
        response.parse(rawResponse);

        assertEquals("Response.version","HTTP/1.1",response.getVersion());
        assertEquals("Response.status",200,response.getStatus());
        assertEquals("Response.reason","OK",response.getReason());

        assertEquals("Response[Content-Type]","text/plain",response.getContentType());
        assertEquals("Response[Content-Length]",28,response.getLongHeader("Content-Length"));
        assertEquals("Response[Connection]","close",response.getHeader("Connection"));

        String expected = "ABCDEFGHIJKLMNOPQRSTTUVWXYZ\n";

        assertEquals("Response.content",expected,response.getContent().toString());
    }

    @Test
    public void testMultiHttp11Response() throws IOException
    {
        StringBuffer rawResponse = new StringBuffer();
        rawResponse.append("HTTP/1.1 200 OK\n");
        rawResponse.append("Date: Mon, 08 Jun 2009 23:05:26 GMT\n");
        rawResponse.append("Content-Type: text/plain\n");
        rawResponse.append("Content-Length: 28\n");
        rawResponse.append("Last-Modified: Mon, 08 Jun 2009 17:06:22 GMT\n");
        rawResponse.append("Server: Jetty(7.0.y.z-SNAPSHOT)\n");
        rawResponse.append("\n");
        rawResponse.append("ABCDEFGHIJKLMNOPQRSTTUVWXYZ\n");
        rawResponse.append("HTTP/1.1 200 OK\n");
        rawResponse.append("Date: Mon, 08 Jun 2009 23:05:26 GMT\n");
        rawResponse.append("Content-Type: text/plain\n");
        rawResponse.append("Content-Length: 25\n");
        rawResponse.append("Last-Modified: Mon, 08 Jun 2009 17:06:22 GMT\n");
        rawResponse.append("Server: Jetty(7.0.y.z-SNAPSHOT)\n");
        rawResponse.append("\n");
        rawResponse.append("Host=Default\n");
        rawResponse.append("Resource=R1\n");
        rawResponse.append("HTTP/1.1 200 OK\n");
        rawResponse.append("Date: Mon, 08 Jun 2009 23:05:26 GMT\n");
        rawResponse.append("Content-Type: text/plain\n");
        rawResponse.append("Content-Length: 25\n");
        rawResponse.append("Last-Modified: Mon, 08 Jun 2009 17:06:22 GMT\n");
        rawResponse.append("Connection: close\n");
        rawResponse.append("Server: Jetty(7.0.y.z-SNAPSHOT)\n");
        rawResponse.append("\n");
        rawResponse.append("Host=Default\n");
        rawResponse.append("Resource=R2\n");
        rawResponse.append("\n");

        List<HttpResponseTester> responses = HttpResponseTester.parseMulti(rawResponse);
        assertNotNull("Responses should not be null",responses);
        assertEquals("Responses.size",3,responses.size());

        HttpResponseTester resp1 = responses.get(0);
        resp1.assertStatusOK();
        resp1.assertContentType("text/plain");
        resp1.assertBody("ABCDEFGHIJKLMNOPQRSTTUVWXYZ\n");
        assertThat(resp1.getHeader("Connection"),is(not("close")));

        HttpResponseTester resp2 = responses.get(1);
        resp2.assertStatusOK();
        resp2.assertContentType("text/plain");
        resp2.assertBody("Host=Default\nResource=R1\n");
        assertThat(resp2.getHeader("Connection"),is(not("close")));

        HttpResponseTester resp3 = responses.get(2);
        resp3.assertStatusOK();
        resp3.assertContentType("text/plain");
        resp3.assertBody("Host=Default\nResource=R2\n");
        assertThat(resp3.getHeader("Connection"),is("close"));
    }
}
