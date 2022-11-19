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

package org.eclipse.jetty.server.handler;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dump request handler.
 * Dumps GET and POST requests.
 * Useful for testing and debugging.
 */
public class DumpHandler extends Handler.Processor.Blocking
{
    private static final Logger LOG = LoggerFactory.getLogger(DumpHandler.class);

    private final Blocker.Shared _blocker = new Blocker.Shared();
    private final String _label;

    public DumpHandler()
    {
        this("Dump Handler");
    }

    public DumpHandler(String label)
    {
        _label = label;
    }

    @Override
    public void process(Request request, Response response, Callback callback) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("dump {}", request);
        HttpURI httpURI = request.getHttpURI();

        Fields params = Request.extractQueryParameters(request);

        if (Boolean.parseBoolean(params.getValue("flush")))
        {
            try (Blocker.Callback blocker = _blocker.callback())
            {
                response.write(false, null, blocker);
                blocker.block();
            }
        }

        if (Boolean.parseBoolean(params.getValue("empty")))
        {
            response.setStatus(200);
            callback.succeeded();
            return;
        }

        Utf8StringBuilder read = null;
        if (params.getValue("read") != null)
        {
            read = new Utf8StringBuilder();
            int len = Integer.parseInt(params.getValue("read"));
            byte[] buffer = new byte[8192];

            Content.Chunk chunk = null;
            while (len > 0)
            {
                if (chunk == null)
                {
                    chunk = request.read();
                    if (chunk == null)
                    {
                        try (Blocker.Runnable blocker = _blocker.runnable())
                        {
                            request.demand(blocker);
                            blocker.block();
                        }
                        continue;
                    }
                }

                if (chunk instanceof Content.Chunk.Error error)
                {
                    callback.failed(error.getCause());
                    return;
                }

                int l = Math.min(buffer.length, Math.min(len, chunk.remaining()));
                int r = chunk.get(buffer, 0, l);
                read.append(buffer, 0, r);
                len -= r;

                if (!chunk.hasRemaining())
                {
                    chunk.release();
                    if (chunk.isLast())
                        break;
                    chunk = null;
                }
            }
            if (chunk != null)
                chunk.release();
        }

        if (params.getValue("date") != null)
            response.getHeaders().put("Date", params.getValue("date"));

        if (params.getValue("ISE") != null)
            throw new IllegalStateException("Testing ISE");

        if (params.getValue("error") != null)
        {
            response.setStatus(Integer.parseInt(params.getValue("error")));
            callback.succeeded();
            return;
        }

        response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Known.TEXT_HTML.asString());

        ByteArrayOutputStream buf = new ByteArrayOutputStream(2048);
        Writer writer = new OutputStreamWriter(buf, StandardCharsets.ISO_8859_1);
        writer.write("<html><h1>" + _label + "</h1>\n");
        writer.write("<pre>httpURI=" + httpURI + "</pre><br/>\n");
        writer.write("<pre>httpURI.path=" + httpURI.getPath() + "</pre><br/>\n");
        writer.write("<pre>httpURI.query=" + httpURI.getQuery() + "</pre><br/>\n");
        writer.write("<pre>httpURI.pathQuery=" + httpURI.getPathQuery() + "</pre><br/>\n");
        writer.write("<pre>pathInContext=" + Request.getPathInContext(request) + "</pre><br/>\n");
        writer.write("<pre>contentType=" + request.getHeaders().get(HttpHeader.CONTENT_TYPE) + "</pre><br/>\n");
        writer.write("<pre>servername=" + Request.getServerName(request) + "</pre><br/>\n");
        writer.write("<pre>local=" + Request.getLocalAddr(request) + ":" + Request.getLocalPort(request) + "</pre><br/>\n");
        writer.write("<pre>remote=" + Request.getRemoteAddr(request) + ":" + Request.getRemotePort(request) + "</pre><br/>\n");
        writer.write("<h3>Header:</h3><pre>");
        writer.write(String.format("%4s %s %s\n", request.getMethod(), httpURI.getPathQuery(), request.getConnectionMetaData().getProtocol()));
        for (HttpField field : request.getHeaders())
        {
            String name = field.getName();
            writer.write(name);
            writer.write(": ");
            String value = field.getValue();
            writer.write(value == null ? "" : value);
            writer.write("\n");
        }

        writer.write("</pre>\n<h3>Attributes:</h3>\n<pre>");
        for (String attr : request.getAttributeNameSet())
        {
            writer.write(attr);
            writer.write("=");
            writer.write(request.getAttribute(attr).toString());
            writer.write("\n");
        }

        writer.write("</pre>\n<h3>Content:</h3>\n<pre>");
        if (read != null)
            writer.write(read.toString());
        else
            writer.write(Content.Source.asString(request));

        writer.write("</pre>\n");
        writer.write("</html>\n");
        writer.flush();

        // commit now
        if (!Boolean.parseBoolean(params.getValue("no-content-length")))
            response.getHeaders().putLongField(HttpHeader.CONTENT_LENGTH, buf.size() + 1000);

        response.getHeaders().add("Before-Flush", response.isCommitted() ? "Committed???" : "Not Committed");

        try (Blocker.Callback blocker = _blocker.callback())
        {
            response.write(false, BufferUtil.toBuffer(buf.toByteArray()), blocker);
            blocker.block();
        }
        response.getHeaders().add("After-Flush", "These headers should not be seen in the response!!!");
        String value = response.isCommitted() ? "Committed" : "Not Committed?";
        response.getHeaders().add("After-Flush", value);

        // write remaining content after commit
        String padding = "ABCDEFGHIJ".repeat(99) + "ABCDEFGH\r\n";

        try (Blocker.Callback blocker = _blocker.callback())
        {
            response.write(true, BufferUtil.toBuffer(padding.getBytes(StandardCharsets.ISO_8859_1)), blocker);
            blocker.block();
        }

        callback.succeeded();
    }
}
