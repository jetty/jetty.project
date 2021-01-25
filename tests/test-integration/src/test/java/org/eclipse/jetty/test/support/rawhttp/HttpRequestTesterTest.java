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

package org.eclipse.jetty.test.support.rawhttp;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HttpRequestTesterTest
{
    @Test
    public void testBasicHttp10Request() throws IOException
    {
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setURI("/uri");
        request.setVersion("HTTP/1.0");
        request.put("Host", "fakehost");

        ByteBuffer bBuff = request.generate();

        StringBuffer expectedRequest = new StringBuffer();
        expectedRequest.append("GET /uri HTTP/1.0\r\n");
        expectedRequest.append("Host: fakehost\r\n");
        expectedRequest.append("\r\n");

        assertEquals(expectedRequest.toString(), BufferUtil.toString(bBuff), "Basic Request");
    }

    @Test
    public void testBasicHttp11Request() throws IOException
    {
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setURI("/uri");
        request.put("Host", "fakehost");
        request.put("Connection", "close");
        request.setContent("aaa");

        ByteBuffer bBuff = request.generate();

        StringBuffer expectedRequest = new StringBuffer();
        expectedRequest.append("GET /uri HTTP/1.1\r\n");
        expectedRequest.append("Host: fakehost\r\n");
        expectedRequest.append("Connection: close\r\n");
        expectedRequest.append("Content-Length: 3\r\n");
        expectedRequest.append("\r\n");
        expectedRequest.append("aaa");

        assertEquals(expectedRequest.toString(), BufferUtil.toString(bBuff), "Basic Request");
    }
}
