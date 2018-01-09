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
import java.util.Map;

import org.eclipse.jetty.rhttp.client.RHTTPResponse;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.StdErrLog;

import junit.framework.TestCase;

/**
 * @version $Revision$ $Date$
 */
public class ResponseTest extends TestCase
{
    {
        ((StdErrLog)Log.getLog()).setHideStacks(!Log.getLog().isDebugEnabled());
    }
    
    public void testResponseConversions() throws Exception
    {
        int id = 1;
        int statusCode = 200;
        String statusMessage = "OK";
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X", "X");
        headers.put("Y", "Y");
        headers.put("Z", "Z");
        byte[] body = "BODY".getBytes("UTF-8");
        RHTTPResponse response1 = new RHTTPResponse(id, statusCode, statusMessage, headers, body);
        byte[] responseBytes1 = response1.getResponseBytes();
        RHTTPResponse response2 = RHTTPResponse.fromResponseBytes(id, responseBytes1);
        assertEquals(id, response2.getId());
        assertEquals(statusCode, response2.getStatusCode());
        assertEquals(statusMessage, response2.getStatusMessage());
        assertEquals(headers, response2.getHeaders());
        assertTrue(Arrays.equals(response2.getBody(), body));

        byte[] responseBytes2 = response2.getResponseBytes();
        assertTrue(Arrays.equals(responseBytes1, responseBytes2));
    }

    public void testFrameConversions() throws Exception
    {
        int id = 1;
        int statusCode = 200;
        String statusMessage = "OK";
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X", "X");
        headers.put("Y", "Y");
        headers.put("Z", "Z");
        byte[] body = "BODY".getBytes("UTF-8");
        RHTTPResponse response1 = new RHTTPResponse(id, statusCode, statusMessage, headers, body);
        byte[] frameBytes1 = response1.getFrameBytes();
        RHTTPResponse response2 = RHTTPResponse.fromFrameBytes(frameBytes1);
        assertEquals(id, response2.getId());
        assertEquals(statusCode, response2.getStatusCode());
        assertEquals(response2.getStatusMessage(), statusMessage);
        assertEquals(headers, response2.getHeaders());
        assertTrue(Arrays.equals(response2.getBody(), body));

        byte[] frameBytes2 = response2.getFrameBytes();
        assertTrue(Arrays.equals(frameBytes1, frameBytes2));
    }
}
