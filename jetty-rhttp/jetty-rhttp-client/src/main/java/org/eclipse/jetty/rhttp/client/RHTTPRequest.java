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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;

/**
 * <p>Represents the external request information that is carried over the comet protocol.</p>
 * <p>Instances of this class are converted into an opaque byte array of the form:</p>
 * <pre>
 * &lt;request-id&gt; SPACE &lt;request-length&gt; CRLF
 * &lt;external-request&gt;
 * </pre>
 * <p>The byte array form is carried as body of a normal HTTP response returned by the gateway server
 * to the gateway client.</p>
 * @see RHTTPResponse
 * @version $Revision$ $Date$
 */
public class RHTTPRequest
{
    private static final String CRLF = "\r\n";
    private static final byte[] CRLF_BYTES = CRLF.getBytes();

    private final int id;
    private final byte[] requestBytes;
    private final byte[] frameBytes;
    private volatile String method;
    private volatile String uri;
    private volatile Map<String, String> headers;
    private volatile byte[] body;

    public static List<RHTTPRequest> fromFrameBytes(byte[] bytes)
    {
        List<RHTTPRequest> result = new ArrayList<RHTTPRequest>();
        int start = 0;
        while (start < bytes.length)
        {
            // Scan until we find the space
            int end = start;
            while (bytes[end] != ' ') ++end;
            int requestId = Integer.parseInt(new String(bytes, start, end - start));
            start = end + 1;

            // Scan until end of line
            while (bytes[end] != '\n') ++end;
            int length = Integer.parseInt(new String(bytes, start, end - start - 1));
            start = end + 1;

            byte[] requestBytes = new byte[length];
            System.arraycopy(bytes, start, requestBytes, 0, length);
            RHTTPRequest request = fromRequestBytes(requestId, requestBytes);
            result.add(request);
            start += length;
        }
        return result;
    }

    public static RHTTPRequest fromRequestBytes(int requestId, byte[] requestBytes)
    {
        return new RHTTPRequest(requestId, requestBytes);
    }

    public RHTTPRequest(int id, String method, String uri, Map<String, String> headers, byte[] body)
    {
        this.id = id;
        this.method = method;
        this.uri = uri;
        this.headers = headers;
        this.body = body;
        this.requestBytes = toRequestBytes();
        this.frameBytes = toFrameBytes(requestBytes);
    }

    private RHTTPRequest(int id, byte[] requestBytes)
    {
        this.id = id;
        this.requestBytes = requestBytes;
        this.frameBytes = toFrameBytes(requestBytes);
        // Other fields are lazily initialized
    }

    private void initialize()
    {
        try
        {
            final ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpParser parser = new HttpParser(new ByteArrayBuffer(requestBytes), new HttpParser.EventHandler()
            {
                @Override
                public void startRequest(Buffer method, Buffer uri, Buffer httpVersion) throws IOException
                {
                    RHTTPRequest.this.method = method.toString("UTF-8");
                    RHTTPRequest.this.uri = uri.toString("UTF-8");
                    RHTTPRequest.this.headers = new LinkedHashMap<String, String>();
                }

                @Override
                public void startResponse(Buffer httpVersion, int statusCode, Buffer statusMessage) throws IOException
                {
                }

                @Override
                public void parsedHeader(Buffer name, Buffer value) throws IOException
                {
                    RHTTPRequest.this.headers.put(name.toString("UTF-8"), value.toString("UTF-8"));
                }

                @Override
                public void content(Buffer content) throws IOException
                {
                    content.writeTo(body);
                }
            });
            parser.parse();
            this.body = body.toByteArray();
        }
        catch (IOException x)
        {
            // Cannot happen: we're parsing from a byte[], not from an I/O stream
            throw new AssertionError(x);
        }
    }

    public int getId()
    {
        return id;
    }

    public byte[] getRequestBytes()
    {
        return requestBytes;
    }

    public byte[] getFrameBytes()
    {
        return frameBytes;
    }

    public String getMethod()
    {
        if (method == null)
            initialize();
        return method;
    }

    public String getURI()
    {
        if (uri == null)
            initialize();
        return uri;
    }

    public Map<String, String> getHeaders()
    {
        if (headers == null)
            initialize();
        return headers;
    }

    public byte[] getBody()
    {
        if (body == null)
            initialize();
        return body;
    }

    private byte[] toRequestBytes()
    {
        try
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(method.getBytes("UTF-8"));
            bytes.write(' ');
            bytes.write(uri.getBytes("UTF-8"));
            bytes.write(' ');
            bytes.write("HTTP/1.1".getBytes("UTF-8"));
            bytes.write(CRLF_BYTES);
            for (Map.Entry<String, String> entry : headers.entrySet())
            {
                bytes.write(entry.getKey().getBytes("UTF-8"));
                bytes.write(':');
                bytes.write(' ');
                bytes.write(entry.getValue().getBytes("UTF-8"));
                bytes.write(CRLF_BYTES);
            }
            bytes.write(CRLF_BYTES);
            bytes.write(body);
            bytes.close();
            return bytes.toByteArray();
        }
        catch (IOException x)
        {
            throw new AssertionError(x);
        }
    }

    private byte[] toFrameBytes(byte[] requestBytes)
    {
        try
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(String.valueOf(id).getBytes("UTF-8"));
            bytes.write(' ');
            bytes.write(String.valueOf(requestBytes.length).getBytes("UTF-8"));
            bytes.write(CRLF_BYTES);
            bytes.write(requestBytes);
            bytes.close();
            return bytes.toByteArray();
        }
        catch (IOException x)
        {
            throw new AssertionError(x);
        }
    }

    @Override
    public String toString()
    {
        // Use fields to avoid initialization
        StringBuilder builder = new StringBuilder();
        builder.append(id).append(" ");
        builder.append(method).append(" ");
        builder.append(uri).append(" ");
        builder.append(requestBytes.length).append("/");
        builder.append(frameBytes.length);
        return builder.toString();
    }

    public String toLongString()
    {
        // Use getters to trigger initialization
        StringBuilder builder = new StringBuilder();
        builder.append(id).append(" ");
        builder.append(getMethod()).append(" ");
        builder.append(getURI()).append(CRLF);
        for (Map.Entry<String, String> header : getHeaders().entrySet())
            builder.append(header.getKey()).append(": ").append(header.getValue()).append(CRLF);
        builder.append(getBody().length).append(" body bytes").append(CRLF);
        return builder.toString();
    }
}
