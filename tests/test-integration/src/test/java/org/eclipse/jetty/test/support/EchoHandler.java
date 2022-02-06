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

package org.eclipse.jetty.test.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

public class EchoHandler extends AbstractHandler
{
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        baseRequest.setHandled(true);

        if (request.getContentType() != null)
            response.setContentType(request.getContentType());
        if (request.getParameter("charset") != null)
            response.setCharacterEncoding(request.getParameter("charset"));
        else if (request.getCharacterEncoding() != null)
            response.setCharacterEncoding(request.getCharacterEncoding());

        PrintWriter writer = response.getWriter();
        BufferedReader reader = request.getReader();
        int count = 0;
        String line;

        while ((line = reader.readLine()) != null)
        {
            writer.print(line);
            writer.print("\n");
            count += line.length();
        }

        // just to be difficult
        reader.close();
        writer.close();

        if (reader.read() >= 0)
            throw new IllegalStateException("Not closed");
    }
}
