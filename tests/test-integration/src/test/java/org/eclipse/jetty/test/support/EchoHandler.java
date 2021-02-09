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
