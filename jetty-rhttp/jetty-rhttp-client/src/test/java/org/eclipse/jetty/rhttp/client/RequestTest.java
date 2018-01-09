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

package org.eclipse.jetty.rhttp.client;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.rhttp.client.RHTTPRequest;

import junit.framework.TestCase;

/**
 * @version $Revision$ $Date$
 */
public class RequestTest extends TestCase
{
    public void testRequestConversions() throws Exception
    {
        int id = 1;
        String method = "GET";
        String uri = "/test";
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X", "X");
        headers.put("Y", "Y");
        headers.put("Z", "Z");
        byte[] body = "BODY".getBytes("UTF-8");
        headers.put("Content-Length", String.valueOf(body.length));
        RHTTPRequest request1 = new RHTTPRequest(id, method, uri, headers, body);
        byte[] requestBytes1 = request1.getRequestBytes();
        RHTTPRequest request2 = RHTTPRequest.fromRequestBytes(id, requestBytes1);
        assertEquals(id, request2.getId());
        assertEquals(method, request2.getMethod());
        assertEquals(uri, request2.getURI());
        assertEquals(headers, request2.getHeaders());
        assertTrue(Arrays.equals(request2.getBody(), body));

        byte[] requestBytes2 = request2.getRequestBytes();
        assertTrue(Arrays.equals(requestBytes1, requestBytes2));
    }

    public void testFrameConversions() throws Exception
    {
        int id = 1;
        String method = "GET";
        String uri = "/test";
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X", "X");
        headers.put("Y", "Y");
        headers.put("Z", "Z");
        byte[] body = "BODY".getBytes("UTF-8");
        headers.put("Content-Length", String.valueOf(body.length));
        RHTTPRequest request1 = new RHTTPRequest(id, method, uri, headers, body);
        byte[] frameBytes1 = request1.getFrameBytes();
        List<RHTTPRequest> requests = RHTTPRequest.fromFrameBytes(frameBytes1);
        assertNotNull(requests);
        assertEquals(1, requests.size());
        RHTTPRequest request2 = requests.get(0);
        assertEquals(id, request2.getId());
        assertEquals(method, request2.getMethod());
        assertEquals(uri, request2.getURI());
        assertEquals(headers, request2.getHeaders());
        assertTrue(Arrays.equals(request2.getBody(), body));

        byte[] frameBytes2 = request2.getFrameBytes();
        assertTrue(Arrays.equals(frameBytes1, frameBytes2));
    }
}
