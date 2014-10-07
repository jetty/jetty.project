//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.server.http;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.spdy.api.DataInfo;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpChannelOverSPDY extends HttpChannel
{
    private static final Logger LOG = Log.getLogger(HttpChannelOverSPDY.class);

    private final Stream stream;

    public HttpChannelOverSPDY(Connector connector, HttpConfiguration configuration, EndPoint endPoint, HttpTransport transport, HttpInputOverSPDY input, Stream stream)
    {
        super(connector, configuration, endPoint, transport, input);
        this.stream = stream;
    }

    public long getIdleTimeout()
    {
        return stream.getIdleTimeout();
    }
    
    public void requestStart(final Fields headers, final boolean endRequest)
    {
        if (!headers.isEmpty())
            requestHeaders(headers, endRequest);
    }

    public void requestHeaders(Fields headers, boolean endRequest)
    {
        boolean proceed = performRequest(headers);
        if (!proceed)
            return;

        if (endRequest)
            onRequestComplete();

        execute(this);
    }

    public void requestContent(final DataInfo dataInfo, boolean endRequest)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("HTTP > {} bytes of content", dataInfo.length());

        // We need to copy the dataInfo since we do not know when its bytes
        // will be consumed. When the copy is consumed, we consume also the
        // original, so the implementation can send a window update.
        ByteBuffer copyByteBuffer = dataInfo.asByteBuffer(false);

        HttpInputOverSPDY.ContentOverSPDY content = new HttpInputOverSPDY.ContentOverSPDY(copyByteBuffer, dataInfo);

        onContent(content);

        if (endRequest)
            onRequestComplete();
    }
    
    private boolean performRequest(Fields headers)
    {
        short version = stream.getSession().getVersion();
        Fields.Field methodHeader = headers.get(HTTPSPDYHeader.METHOD.name(version));
        Fields.Field uriHeader = headers.get(HTTPSPDYHeader.URI.name(version));
        Fields.Field versionHeader = headers.get(HTTPSPDYHeader.VERSION.name(version));

        if (methodHeader == null || uriHeader == null || versionHeader == null)
        {
            onBadMessage(400, "Missing required request line elements");
            return false;
        }

        HttpMethod httpMethod = HttpMethod.fromString(methodHeader.getValue());
        HttpVersion httpVersion = HttpVersion.fromString(versionHeader.getValue());


        if (LOG.isDebugEnabled())
            LOG.debug("HTTP > {} {} {}", httpMethod, uriHeader.getValue(), httpVersion);


        HostPortHttpField hostPort = null;
        HttpFields fields = new HttpFields();
        for (Fields.Field header : headers)
        {
            String name = header.getName();

            // Skip special SPDY headers, unless it's the "host" header
            HTTPSPDYHeader specialHeader = HTTPSPDYHeader.from(stream.getSession().getVersion(), name);
            if (specialHeader != null)
            {
                if (specialHeader != HTTPSPDYHeader.HOST)
                    continue;
                name = "host";
            }

            switch (name)
            {
                case "connection":
                case "keep-alive":
                case "proxy-connection":
                case "transfer-encoding":
                {
                    // Spec says to ignore these headers.
                    break;
                }
                case "host":
                {
                    hostPort = new HostPortHttpField(header.getValue());
                    break;
                }
                default:
                {
                    // Spec says headers must be single valued
                    String value = header.getValue();
                    if (LOG.isDebugEnabled())
                        LOG.debug("HTTP > {}: {}", name, value);
                    fields.add(new HttpField(name, value));
                    break;
                }
            }
        }

        if (hostPort == null)
        {
            onBadMessage(400, "Missing Host header");
            return false;
        }

        // At last, add the Host header.
        if (hostPort!=null)
            fields.add(hostPort);

        Fields.Field schemeHeader = headers.get(HTTPSPDYHeader.SCHEME.name(version));
        
        HttpURI uri = new HttpURI(uriHeader.getValue());
        if (uri.getScheme()==null && schemeHeader!=null)
            uri.setScheme(schemeHeader.getValue());
        if (uri.getHost()==null && hostPort!=null)
            uri.setAuthority(hostPort.getHost(),hostPort.getPort());
        
        MetaData.Request request = new MetaData.Request(httpMethod==null?methodHeader.getValue():httpMethod.asString(), uri, httpVersion, fields);
        onRequest(request);
        return true;
    }
}
