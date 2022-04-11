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

package org.eclipse.jetty.ee9.handler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dump request handler.
 * Dumps GET and POST requests.
 * Useful for testing and debugging.
 */
public class DumpHandler extends AbstractHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(DumpHandler.class);

    String label = "Dump HttpHandler";

    public DumpHandler()
    {
    }

    public DumpHandler(String label)
    {
        this.label = label;
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (!isStarted())
            return;

        if (Boolean.parseBoolean(request.getParameter("flush")))
            response.flushBuffer();

        if (Boolean.parseBoolean(request.getParameter("empty")))
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            return;
        }

        StringBuilder read = null;
        if (request.getParameter("read") != null)
        {
            read = new StringBuilder();
            int len = Integer.parseInt(request.getParameter("read"));
            Reader in = request.getReader();
            for (int i = len; i-- > 0; )
            {
                read.append((char)in.read());
            }
        }

        if (request.getParameter("date") != null)
            response.setHeader("Date", request.getParameter("date"));

        if (request.getParameter("ISE") != null)
        {
            throw new IllegalStateException("Testing ISE");
        }

        if (request.getParameter("error") != null)
        {
            response.sendError(Integer.parseInt(request.getParameter("error")));
            return;
        }

        baseRequest.setHandled(true);
        response.setHeader(HttpHeader.CONTENT_TYPE.asString(), MimeTypes.Type.TEXT_HTML.asString());

        OutputStream out = response.getOutputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream(2048);
        Writer writer = new OutputStreamWriter(buf, StandardCharsets.ISO_8859_1);
        writer.write("<html><h1>" + label + "</h1>");
        writer.write("<pre>\ncontextPath=" + request.getContextPath() + "\n</pre>\n");
        writer.write("<pre>\npathInfo=" + request.getPathInfo() + "\n</pre>\n");
        writer.write("<pre>\ncontentType=" + request.getContentType() + "\n</pre>\n");
        writer.write("<pre>\nencoding=" + request.getCharacterEncoding() + "\n</pre>\n");
        writer.write("<pre>\nservername=" + request.getServerName() + "\n</pre>\n");
        writer.write("<pre>\nserverport=" + request.getServerPort() + "\n</pre>\n");
        writer.write("<pre>\nlocalname=" + request.getLocalName() + "\n</pre>\n");
        writer.write("<pre>\nlocal=" + request.getLocalAddr() + ":" + request.getLocalPort() + "\n</pre>\n");
        writer.write("<pre>\nremote=" + request.getRemoteAddr() + ":" + request.getRemotePort() + "\n</pre>\n");
        writer.write("<h3>Header:</h3><pre>");
        writer.write(String.format("%4s %s %s\n", request.getMethod(), request.getRequestURI(), request.getProtocol()));
        Enumeration<String> headers = request.getHeaderNames();
        while (headers.hasMoreElements())
        {
            String name = headers.nextElement();
            writer.write(name);
            writer.write(": ");
            String value = request.getHeader(name);
            writer.write(value == null ? "" : value);
            writer.write("\n");
        }
        writer.write("</pre>\n<h3>Parameters:</h3>\n<pre>");
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements())
        {
            String name = names.nextElement();
            String[] values = request.getParameterValues(name);
            if (values == null || values.length == 0)
            {
                writer.write(name);
                writer.write("=\n");
            }
            else if (values.length == 1)
            {
                writer.write(name);
                writer.write("=");
                writer.write(values[0]);
                writer.write("\n");
            }
            else
            {
                for (int i = 0; i < values.length; i++)
                {
                    writer.write(name);
                    writer.write("[" + i + "]=");
                    writer.write(values[i]);
                    writer.write("\n");
                }
            }
        }

        String cookieName = request.getParameter("CookieName");
        if (cookieName != null && cookieName.trim().length() > 0)
        {
            String cookieAction = request.getParameter("Button");
            try
            {
                String val = request.getParameter("CookieVal");
                val = val.replaceAll("[ \n\r=<>]", "?");
                Cookie cookie =
                    new Cookie(cookieName.trim(), val);
                if ("Clear Cookie".equals(cookieAction))
                    cookie.setMaxAge(0);
                response.addCookie(cookie);
            }
            catch (IllegalArgumentException e)
            {
                writer.write("</pre>\n<h3>BAD Set-Cookie:</h3>\n<pre>");
                writer.write(e.toString());
            }
        }

        writer.write("</pre>\n<h3>Cookies:</h3>\n<pre>");
        Cookie[] cookies = request.getCookies();
        if (cookies != null && cookies.length > 0)
        {
            for (int c = 0; c < cookies.length; c++)
            {
                Cookie cookie = cookies[c];
                writer.write(cookie.getName());
                writer.write("=");
                writer.write(cookie.getValue());
                writer.write("\n");
            }
        }

        writer.write("</pre>\n<h3>Attributes:</h3>\n<pre>");
        Enumeration<String> attributes = request.getAttributeNames();
        if (attributes != null && attributes.hasMoreElements())
        {
            while (attributes.hasMoreElements())
            {
                String attr = attributes.nextElement().toString();
                writer.write(attr);
                writer.write("=");
                writer.write(request.getAttribute(attr).toString());
                writer.write("\n");
            }
        }

        writer.write("</pre>\n<h3>Content:</h3>\n<pre>");

        if (baseRequest.getContentRead() == 0)
        {
            if (read != null)
            {
                writer.write(read.toString());
            }
            else
            {
                char[] content = new char[4096];
                int len;
                try
                {
                    Reader in = request.getReader();
                    while ((len = in.read(content)) >= 0)
                    {
                        writer.write(new String(content, 0, len));
                    }
                }
                catch (IOException e)
                {
                    LOG.warn("Failed to copy request content", e);
                    writer.write(e.toString());
                }
            }
        }

        writer.write("</pre>\n");
        writer.write("</html>\n");
        writer.flush();

        // commit now
        if (!Boolean.parseBoolean(request.getParameter("no-content-length")))
            response.setContentLength(buf.size() + 1000);
        response.addHeader("Before-Flush", response.isCommitted() ? "Committed???" : "Not Committed");
        buf.writeTo(out);
        out.flush();
        response.addHeader("After-Flush", "These headers should not be seen in the response!!!");
        response.addHeader("After-Flush", response.isCommitted() ? "Committed" : "Not Committed?");

        // write remaining content after commit
        try
        {
            buf.reset();
            writer.flush();
            for (int pad = 998; pad-- > 0; )
            {
                writer.write(" ");
            }
            writer.write("\r\n");
            writer.flush();
            buf.writeTo(out);
        }
        catch (Exception e)
        {
            LOG.trace("IGNORED", e);
        }
    }
}
