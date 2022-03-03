//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.test.support.rawhttp;

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
