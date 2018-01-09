//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.test.support.rawhttp;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.util.List;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.junit.Assert;
import org.junit.Test;

public class HttpResponseTesterTest
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

        HttpTester.Response response = HttpTester.parseResponse(rawResponse.toString());

        Assert.assertEquals("Response.version","HTTP/1.1",response.getVersion().asString());
        Assert.assertEquals("Response.status",200,response.getStatus());
        Assert.assertEquals("Response.reason","OK",response.getReason());

        Assert.assertEquals("Response[Content-Type]","text/plain",response.get(HttpHeader.CONTENT_TYPE));
        Assert.assertEquals("Response[Content-Length]",28,response.getLongField("Content-Length"));
        Assert.assertEquals("Response[Connection]","close",response.get("Connection"));

        String expected = "ABCDEFGHIJKLMNOPQRSTTUVWXYZ\n";

        Assert.assertEquals("Response.content",expected,response.getContent().toString());
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
        rawResponse.append("\n");

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

        List<HttpTester.Response> responses = HttpTesting.readResponses(rawResponse.toString());

        Assert.assertNotNull("Responses should not be null",responses);
        Assert.assertEquals("Responses.size",3,responses.size());

        HttpTester.Response resp1 = responses.get(0);
        // System.err.println(resp1.toString());
        Assert.assertEquals(HttpStatus.OK_200, resp1.getStatus());
        Assert.assertEquals("text/plain", resp1.get("Content-Type"));
        Assert.assertTrue(resp1.getContent().contains("ABCDEFGHIJKLMNOPQRSTTUVWXYZ\n"));
        assertThat(resp1.get("Connection"),is(not("close")));

        HttpTester.Response resp2 = responses.get(1);
        // System.err.println(resp2.toString());
        Assert.assertEquals(HttpStatus.OK_200, resp2.getStatus());
        Assert.assertEquals("text/plain", resp2.get("Content-Type"));
        Assert.assertTrue(resp2.getContent().contains("Host=Default\nResource=R1\n"));
        assertThat(resp2.get("Connection"),is(not("close")));

        HttpTester.Response resp3 = responses.get(2);
        // System.err.println(resp3.toString());
        Assert.assertEquals(HttpStatus.OK_200, resp3.getStatus());
        Assert.assertEquals("text/plain", resp3.get("Content-Type"));
        Assert.assertTrue(resp3.getContent().contains("Host=Default\nResource=R2\n"));
        assertThat(resp3.get("Connection"),is("close"));
    }
}
