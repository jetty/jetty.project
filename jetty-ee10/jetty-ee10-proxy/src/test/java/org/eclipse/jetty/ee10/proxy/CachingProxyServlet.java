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

package org.eclipse.jetty.ee10.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Don't do this at home: this example is not concurrent, not complete,
 * it is only used for this test and to verify that ProxyServlet can be
 * subclassed enough to write your own caching servlet
 */
public class CachingProxyServlet extends ProxyServlet
{
    public static final String CACHE_HEADER = "X-Cached";
    private Map<String, ContentResponse> cache = new HashMap<>();
    private Map<String, ByteArrayOutputStream> temp = new HashMap<>();

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        ContentResponse cachedResponse = cache.get(request.getRequestURI());
        if (cachedResponse != null)
        {
            response.setStatus(cachedResponse.getStatus());
            // Should copy headers too, but keep it simple
            response.addHeader(CACHE_HEADER, "true");
            response.getOutputStream().write(cachedResponse.getContent());
        }
        else
        {
            super.service(request, response);
        }
    }

    @Override
    protected void onResponseContent(HttpServletRequest request, HttpServletResponse response, Response proxyResponse, byte[] buffer, int offset, int length, Callback callback)
    {
        // Accumulate the response content
        ByteArrayOutputStream baos = temp.get(request.getRequestURI());
        if (baos == null)
        {
            baos = new ByteArrayOutputStream();
            temp.put(request.getRequestURI(), baos);
        }
        baos.write(buffer, offset, length);
        super.onResponseContent(request, response, proxyResponse, buffer, offset, length, callback);
    }

    @Override
    protected void onProxyResponseSuccess(HttpServletRequest request, HttpServletResponse response, Response proxyResponse)
    {
        byte[] content = temp.remove(request.getRequestURI()).toByteArray();
        ContentResponse cached = ContentResponse.from(proxyResponse, content, null, null);
        cache.put(request.getRequestURI(), cached);
        super.onProxyResponseSuccess(request, response, proxyResponse);
    }
}
