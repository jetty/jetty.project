//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.test.support.rawhttp;

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

        String expectedRequest = """
            GET /uri HTTP/1.0\r
            Host: fakehost\r
            \r
            """;

        assertEquals(expectedRequest, BufferUtil.toString(bBuff));
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

        String expectedRequest = """
            GET /uri HTTP/1.1\r
            Host: fakehost\r
            Content-Length: 3\r
            Connection: close\r
            \r
            aaa""";

        assertEquals(expectedRequest, BufferUtil.toString(bBuff));
    }
}
