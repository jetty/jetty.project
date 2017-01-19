//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;

/**
 * <p>Represents the resource provider response information that is carried over the comet protocol.</p>
 * <p>Instances of this class are converted into an opaque byte array of the form:</p>
 * <pre>
 * &lt;request-id&gt; SPACE &lt;response-length&gt; CRLF
 * &lt;resource-response&gt;
 * </pre>
 * <p>The byte array form is carried as body of a normal HTTP request made by the gateway client to
 * the gateway server.</p>
 * @see RHTTPRequest
 * @version $Revision$ $Date$
 */
public class RHTTPResponse
{
    private static final String CRLF = "\r\n";
    private static final byte[] CRLF_BYTES = CRLF.getBytes();

    private final int id;
    private final byte[] responseBytes;
    private final byte[] frameBytes;
    private volatile int code;
    private volatile String message;
    private volatile Map<String, String> headers;
    private volatile byte[] body;

    public static RHTTPResponse fromFrameBytes(byte[] bytes)
    {
        int start = 0;
        // Scan until we find the space
        int end = start;
        while (bytes[end] != ' ') ++end;
        int responseId = Integer.parseInt(new String(bytes, start, end - start));
        start = end + 1;

        // Scan until end of line
        while (bytes[end] != '\n') ++end;
        int length = Integer.parseInt(new String(bytes, start, end - start - 1));
        start = end + 1;

        byte[] responseBytes = new byte[length];
        System.arraycopy(bytes, start, responseBytes, 0, length);
        return fromResponseBytes(responseId, responseBytes);
    }

    public static RHTTPResponse fromResponseBytes(int id, byte[] responseBytes)
    {
        return new RHTTPResponse(id, responseBytes);
    }

    public RHTTPResponse(int id, int code, String message, Map<String, String> headers, byte[] body)
    {
        this.id = id;
        this.code = code;
        this.message = message;
        this.headers = headers;
        this.body = body;
        this.responseBytes = toResponseBytes();
        this.frameBytes = toFrameBytes(responseBytes);
    }

    private RHTTPResponse(int id, byte[] responseBytes)
    {
        this.id = id;
        this.responseBytes = responseBytes;
        this.frameBytes = toFrameBytes(responseBytes);
        // Other fields are lazily initialized
    }

    private void initialize()
    {
        try
        {
            final ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpParser parser = new HttpParser(new ByteArrayBuffer(responseBytes), new HttpParser.EventHandler()
            {
                @Override
                public void startRequest(Buffer method, Buffer uri, Buffer httpVersion) throws IOException
                {
                }

                @Override
                public void startResponse(Buffer httpVersion, int statusCode, Buffer statusMessage) throws IOException
                {
                    RHTTPResponse.this.code = statusCode;
                    RHTTPResponse.this.message = statusMessage.toString("UTF-8");
                    RHTTPResponse.this.headers = new LinkedHashMap<String, String>();
                }

                @Override
                public void parsedHeader(Buffer name, Buffer value) throws IOException
                {
                    RHTTPResponse.this.headers.put(name.toString("UTF-8"), value.toString("UTF-8"));
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

    public byte[] getResponseBytes()
    {
        return responseBytes;
    }

    public byte[] getFrameBytes()
    {
        return frameBytes;
    }

    public int getStatusCode()
    {
        if (code == 0)
            initialize();
        return code;
    }

    public String getStatusMessage()
    {
        if (message == null)
            initialize();
        return message;
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

    private byte[] toResponseBytes()
    {
        try
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write("HTTP/1.1".getBytes("UTF-8"));
            bytes.write(' ');
            bytes.write(String.valueOf(code).getBytes("UTF-8"));
            bytes.write(' ');
            bytes.write(message.getBytes("UTF-8"));
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

    private byte[] toFrameBytes(byte[] responseBytes)
    {
        try
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(String.valueOf(id).getBytes("UTF-8"));
            bytes.write(' ');
            bytes.write(String.valueOf(responseBytes.length).getBytes("UTF-8"));
            bytes.write(CRLF_BYTES);
            bytes.write(responseBytes);
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
        builder.append(code).append(" ");
        builder.append(message).append(" ");
        builder.append(responseBytes.length).append("/");
        builder.append(frameBytes.length);
        return builder.toString();
    }

    public String toLongString()
    {
        // Use getters to trigger initialization
        StringBuilder builder = new StringBuilder();
        builder.append(id).append(" ");
        builder.append(getStatusCode()).append(" ");
        builder.append(getStatusMessage()).append(CRLF);
        for (Map.Entry<String, String> header : getHeaders().entrySet())
            builder.append(header.getKey()).append(": ").append(header.getValue()).append(CRLF);
        builder.append(getBody().length).append(" body bytes").append(CRLF);
        return builder.toString();
    }
}
