// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.test.support.rawhttp;

import java.io.IOException;

import javax.servlet.http.Cookie;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpVersions;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.SimpleBuffers;
import org.eclipse.jetty.io.View;
import org.eclipse.jetty.io.bio.StringEndPoint;

/**
 * Assist in Generating Proper Raw HTTP Requests. If you want ultimate control
 * over the Raw HTTP Request, to test non-standard behavior, or partial HTTP
 * Requests, do not use this class.
 * 
 * <pre>
 * HttpRequestTester request = new HttpRequestTester();
 * 
 * request.setMethod(&quot;GET&quot;);
 * request.setURI(&quot;/uri&quot;);
 * request.setHost(&quot;fakehost&quot;);
 * request.setConnectionClosed();
 * 
 * String rawRequest = request.generate();
 * 
 * System.out.println(&quot;--raw-request--\n&quot; + rawRequest);
 * </pre>
 * 
 * <pre>
 * --raw-request--
 * GET /uri HTTP/1.1
 * Host: fakehost
 * Connection: close
 * </pre>
 */
public class HttpRequestTester
{
    private HttpFields fields = new HttpFields();
    private String method;
    private String uri;
    private String version;
    private byte[] content;
    private String charset;
    private String defaultCharset;
    private String contentType;

    public HttpRequestTester()
    {
        this("UTF-8");
    }

    public HttpRequestTester(String defCharset)
    {
        this.defaultCharset = defCharset;
    }

    public String getMethod()
    {
        return method;
    }

    public void setHost(String host)
    {
        addHeader("Host",host);
    }

    public void setMethod(String method)
    {
        this.method = method;
    }

    public String getURI()
    {
        return uri;
    }

    public void setURI(String uri)
    {
        this.uri = uri;
    }

    public String getVersion()
    {
        return version;
    }

    public void setVersion(String version)
    {
        this.version = version;
    }

    public String getCharset()
    {
        return charset;
    }

    public void setCharset(String charset)
    {
        this.charset = charset;
    }

    public String getContentType()
    {
        return contentType;
    }

    public void setContentType(String contentType)
    {
        this.contentType = contentType;
    }

    public void setConnectionClosed()
    {
        fields.add("Connection","close");
    }

    /**
     * @param name
     * @param value
     * @throws IllegalArgumentException
     * @see org.eclipse.jetty.http.HttpFields#add(java.lang.String,
     *      java.lang.String)
     */
    public void addHeader(String name, String value) throws IllegalArgumentException
    {
        fields.add(name,value);
    }

    /**
     * @param name
     * @param date
     * @see org.eclipse.jetty.http.HttpFields#addDateField(java.lang.String,
     *      long)
     */
    public void addDateHeader(String name, long date)
    {
        fields.addDateField(name,date);
    }

    /**
     * @param name
     * @param value
     * @see org.eclipse.jetty.http.HttpFields#addLongField(java.lang.String,
     *      long)
     */
    public void addLongHeader(String name, long value)
    {
        fields.addLongField(name,value);
    }

    /**
     * @param cookie
     * @see org.eclipse.jetty.http.HttpFields#addSetCookie(javax.servlet.http.Cookie)
     */
    public void addSetCookie(Cookie cookie)
    {
        fields.addSetCookie(cookie.getName(),cookie.getValue(),cookie.getDomain(),cookie.getPath(),cookie.getMaxAge(),cookie.getComment(),cookie.getSecure(),
                false,cookie.getVersion());
    }

    public String generate() throws IOException
    {
        charset = defaultCharset;
        Buffer contentTypeBuffer = fields.get(HttpHeaders.CONTENT_TYPE_BUFFER);
        if (contentTypeBuffer != null)
        {
            String calcCharset = MimeTypes.getCharsetFromContentType(contentTypeBuffer);
            if (calcCharset != null)
            {
                this.charset = calcCharset;
            }
        }

        Buffer bb = new ByteArrayBuffer(32 * 1024 + (content != null?content.length:0));
        Buffer sb = new ByteArrayBuffer(4 * 1024);
        StringEndPoint endp = new StringEndPoint(charset);
        HttpGenerator generator = new HttpGenerator(new SimpleBuffers(sb,bb),endp);

        if (method != null)
        {
            generator.setRequest(getMethod(),getURI());
            if (version == null)
            {
                generator.setVersion(HttpVersions.HTTP_1_1_ORDINAL);
            }
            else
            {
                generator.setVersion(HttpVersions.CACHE.getOrdinal(HttpVersions.CACHE.lookup(version)));
            }

            generator.completeHeader(fields,false);

            if (content != null)
            {
                generator.addContent(new View(new ByteArrayBuffer(content)),false);
            }
        }

        generator.complete();
        generator.flushBuffer();
        return endp.getOutput();
    }
}
