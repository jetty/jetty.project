//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.fcgi.server;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpChannelOverFCGI extends HttpChannel<ByteBuffer>
{
    private static final Logger LOG = Log.getLogger(HttpChannelOverFCGI.class);
    private static final String METHOD_HEADER = "REQUEST_METHOD";
    private static final String URI_HEADER = "REQUEST_URI";
    private static final String VERSION_HEADER = "SERVER_PROTOCOL";

    private String method;
    private String uri;
    private String version;
    private boolean started;
    private List<HttpField> fields;

    public HttpChannelOverFCGI(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport, HttpInput<ByteBuffer> input)
    {
        super(connector, configuration, endPoint, transport, input);
    }

    public void header(HttpField field)
    {
        LOG.debug("FCGI header {}", field);

        if (METHOD_HEADER.equalsIgnoreCase(field.getName()))
        {
            method = field.getValue();
            if (uri != null && version != null)
                startRequest();
        }
        else if (URI_HEADER.equalsIgnoreCase(field.getName()))
        {
            uri = field.getValue();
            if (method != null && version != null)
                startRequest();
        }
        else if (VERSION_HEADER.equalsIgnoreCase(field.getName()))
        {
            version = field.getValue();
            if (method != null && uri != null)
                startRequest();
        }
        else
        {
            if (started)
            {
                resumeHeaders();
                convertHeader(field);
            }
            else
            {
                if (fields == null)
                    fields = new ArrayList<>();
                fields.add(field);
            }
        }
    }

    private void startRequest()
    {
        started = true;
        startRequest(null, method, ByteBuffer.wrap(uri.getBytes(Charset.forName("UTF-8"))), HttpVersion.fromString(version));
        resumeHeaders();
    }

    private void resumeHeaders()
    {
        if (fields != null)
        {
            for (HttpField field : fields)
                convertHeader(field);
            fields = null;
        }
    }

    private void convertHeader(HttpField field)
    {
        String name = field.getName();
        if (name.startsWith("HTTP_"))
        {
            // Converts e.g. "HTTP_ACCEPT_ENCODING" to "Accept-Encoding"
            String[] parts = name.split("_");
            StringBuilder httpName = new StringBuilder();
            for (int i = 1; i < parts.length; ++i)
            {
                if (i > 1)
                    httpName.append("-");
                String part = parts[i];
                httpName.append(Character.toUpperCase(part.charAt(0)));
                httpName.append(part.substring(1).toLowerCase(Locale.ENGLISH));
            }
            field = new HttpField(httpName.toString(), field.getValue());
        }
        LOG.debug("HTTP header {}", field);
        parsedHeader(field);
    }

    public void dispatch()
    {
        getConnector().getExecutor().execute(this);
    }
}
