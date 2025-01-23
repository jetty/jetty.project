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

package org.eclipse.jetty.demo.simple;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.stream.Collectors;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@MultipartConfig
public class EchoServlet extends HttpServlet
{
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        response.setContentType(request.getContentType());
        ServletOutputStream output = response.getOutputStream();
        String pathInfo = request.getPathInfo();
        switch (pathInfo)
        {
            case "/form" ->
            {
                String content = request.getParameterMap().entrySet().stream()
                    .map(e -> "%s=%s".formatted(e.getKey(), String.join(", ", e.getValue())))
                    .collect(Collectors.joining("&"));
                output.print(content);
            }
            case "/multipart" ->
            {
                MultipartConfigElement config = new MultipartConfigElement("");
                request.setAttribute(MultipartConfigElement.class.getName(), config);

                String content = request.getParts().stream()
                    .map(part -> "name=%s&length=%d".formatted(part.getName(), part.getSize()))
                    .collect(Collectors.joining(","));
                output.print(content);
            }
            default ->
            {
                // echo using servlet 6.0 ByteBuffer APIs
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                ServletInputStream input = request.getInputStream();
                while (input.read(buffer) >= 0)
                {
                    buffer.flip();
                    output.write(buffer);
                    buffer.clear();
                }
            }
        }
    }
}
