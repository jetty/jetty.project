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

package org.eclipse.jetty.test.support.rawhttp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import javax.servlet.http.Cookie;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.util.BufferUtil;


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
     * @param cookie
     * @see org.eclipse.jetty.http.HttpFields#addSetCookie(org.eclipse.jetty.http.HttpCookie)
     */
    public void addSetCookie(Cookie cookie)
    {
        fields.addSetCookie(cookie.getName(),cookie.getValue(),cookie.getDomain(),cookie.getPath(),cookie.getMaxAge(),cookie.getComment(),cookie.getSecure(),
                false,cookie.getVersion());
    }

    public String generate() throws IOException
    {
        
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = null;
        ByteBuffer chunk = null;
        ByteBuffer content = null;
        HttpVersion httpVersion = null;
        if (version == null)
        {
            httpVersion = HttpVersion.HTTP_1_1;
        }
        else
        {
            httpVersion = httpVersion.fromString(version);
        }
        
        HttpGenerator.RequestInfo info = new HttpGenerator.RequestInfo(httpVersion,fields,0,method,uri);

        HttpGenerator generator = new HttpGenerator();
        loop: while(!generator.isEnd())
        {
            HttpGenerator.Result result =  generator.generateRequest(info, header, chunk, content, true);
            switch(result)
            {
                case NEED_HEADER:
                    header=BufferUtil.allocate(8192);
                    continue;

                case NEED_CHUNK:
                    chunk=BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
                    continue;

                case NEED_INFO:
                    throw new IllegalStateException();

                case FLUSH:
                    if (BufferUtil.hasContent(header))
                    {
                        out.write(BufferUtil.toArray(header));
                        BufferUtil.clear(header);
                    }
                    if (BufferUtil.hasContent(chunk))
                    {
                        out.write(BufferUtil.toArray(chunk));
                        BufferUtil.clear(chunk);
                    }
                    if (BufferUtil.hasContent(content))
                    {
                        out.write(BufferUtil.toArray(content));
                        BufferUtil.clear(content);
                    }
                    break;

                case SHUTDOWN_OUT:
                    break loop;
            }
        }

        return out.toString();
    }
}
