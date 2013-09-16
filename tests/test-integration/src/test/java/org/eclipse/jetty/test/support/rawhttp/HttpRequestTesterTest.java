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

package org.eclipse.jetty.test.support.rawhttp;

import java.io.IOException;

import org.junit.Assert;
import org.junit.Test;

public class HttpRequestTesterTest
{
    @Test
    public void testBasicHttp10Request() throws IOException
    {
        HttpRequestTester request = new HttpRequestTester();
        request.setMethod("GET");
        request.setURI("/uri");
        request.setVersion("HTTP/1.0");
        request.setHost("fakehost");

        String rawRequest = request.generate();

        StringBuffer expectedRequest = new StringBuffer();
        expectedRequest.append("GET /uri HTTP/1.0\r\n");
        expectedRequest.append("Host: fakehost\r\n");
        expectedRequest.append("\r\n");

        Assert.assertEquals("Basic Request",expectedRequest.toString(),rawRequest);
    }

    @Test
    public void testBasicHttp11Request() throws IOException
    {
        HttpRequestTester request = new HttpRequestTester();
        request.setMethod("GET");
        request.setURI("/uri");
        request.setHost("fakehost");
        request.setConnectionClosed();

        String rawRequest = request.generate();

        StringBuffer expectedRequest = new StringBuffer();
        expectedRequest.append("GET /uri HTTP/1.1\r\n");
        expectedRequest.append("Host: fakehost\r\n");
        expectedRequest.append("Connection: close\r\n");
        expectedRequest.append("Transfer-Encoding: chunked\r\n");
        expectedRequest.append("\r\n");
        expectedRequest.append("0\r\n");
        expectedRequest.append("\r\n");

        Assert.assertEquals("Basic Request",expectedRequest.toString(),rawRequest);
    }
}
