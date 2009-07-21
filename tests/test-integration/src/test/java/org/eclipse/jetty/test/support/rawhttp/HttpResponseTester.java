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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import junit.framework.Assert;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.View;
import org.eclipse.jetty.test.StringAssert;
import org.eclipse.jetty.test.support.StringUtil;
import org.eclipse.jetty.util.ByteArrayOutputStream2;

/**
 * Assists in testing of HTTP Responses.
 */
public class HttpResponseTester
{
    private class PH extends HttpParser.EventHandler
    {
        @Override
        public void content(Buffer ref) throws IOException
        {
            if (content == null)
                content = new ByteArrayOutputStream2();
            content.write(ref.asArray());
        }

        @Override
        public void headerComplete() throws IOException
        {
            contentType = fields.get(HttpHeaders.CONTENT_TYPE_BUFFER);
            if (contentType != null)
            {
                String calcCharset = MimeTypes.getCharsetFromContentType(contentType);
                if (calcCharset != null)
                {
                    charset = calcCharset;
                }
            }
        }

        @Override
        public void messageComplete(long contextLength) throws IOException
        {
        }

        @Override
        public void parsedHeader(Buffer name, Buffer value) throws IOException
        {
            fields.add(name,value);
        }

        @Override
        public void startRequest(Buffer method, Buffer url, Buffer version) throws IOException
        {
            reset();
            HttpResponseTester.this.method = getString(method);
            HttpResponseTester.this.uri = getString(url);
            HttpResponseTester.this.version = getString(version);
        }

        @Override
        public void startResponse(Buffer version, int status, Buffer reason) throws IOException
        {
            reset();
            HttpResponseTester.this.version = getString(version);
            HttpResponseTester.this.status = status;
            HttpResponseTester.this.reason = getString(reason);
        }
    }

    public static List<HttpResponseTester> parseMulti(CharSequence rawHTTP) throws IOException
    {
        List<HttpResponseTester> responses = new ArrayList<HttpResponseTester>();
        String parse = rawHTTP.toString();
        while (StringUtil.isNotBlank(parse))
        {
            HttpResponseTester response = new HttpResponseTester();
            parse = response.parse(parse);
            responses.add(response);
        }

        return responses;
    }

    private HttpFields fields = new HttpFields();
    private CharSequence rawResponse;
    private String method;
    private String uri;
    private String version;
    private int status;
    private String reason;
    private Buffer contentType;
    private ByteArrayOutputStream2 content;
    private String charset;
    private String defaultCharset;

    public HttpResponseTester()
    {
        this("UTF-8");
    }

    public HttpResponseTester(String defCharset)
    {
        this.defaultCharset = defCharset;
    }

    public String getMethod()
    {
        return method;
    }

    public String getURI()
    {
        return uri;
    }

    public String getVersion()
    {
        return version;
    }

    public int getStatus()
    {
        return status;
    }

    public CharSequence getRawResponse()
    {
        return rawResponse;
    }

    public String getReason()
    {
        return reason;
    }

    public String getContentType()
    {
        if (contentType == null)
        {
            return null;
        }
        return contentType.toString();
    }

    public ByteArrayOutputStream2 getContentBytes()
    {
        return content;
    }

    public String getContent()
    {
        return content.toString();
    }

    public String getBody()
    {
        return content.toString();
    }

    private byte[] getByteArray(CharSequence str)
    {
        if (charset == null)
        {
            return str.toString().getBytes();
        }

        try
        {
            return str.toString().getBytes(charset);
        }
        catch (Exception e)
        {
            return str.toString().getBytes();
        }
    }

    private String getString(Buffer buffer)
    {
        return getString(buffer.asArray());
    }

    private String getString(byte[] b)
    {
        if (charset == null)
        {
            return new String(b);
        }

        try
        {
            return new String(b,charset);
        }
        catch (Exception e)
        {
            return new String(b);
        }
    }

    /**
     * @param name
     * @return
     * @see org.eclipse.jetty.http.HttpFields#getDateField(java.lang.String)
     */
    public long getDateHeader(String name)
    {
        return fields.getDateField(name);
    }

    /**
     * @param name
     * @return
     * @throws NumberFormatException
     * @see org.eclipse.jetty.http.HttpFields#getLongField(java.lang.String)
     */
    public long getLongHeader(String name) throws NumberFormatException
    {
        return fields.getLongField(name);
    }

    /**
     * @param name
     * @return
     * @see org.eclipse.jetty.http.HttpFields#getStringField(java.lang.String)
     */
    public String getHeader(String name)
    {
        return fields.getStringField(name);
    }

    public boolean hasHeader(String headerKey)
    {
        return fields.containsKey(headerKey);
    }

    /**
     * Parse on HTTP Response
     * 
     * @param rawHTTP
     *            Raw HTTP to parse
     * @return Any unparsed data in the rawHTTP (eg pipelined requests)
     * @throws IOException
     */
    public String parse(CharSequence rawHTTP) throws IOException
    {
        this.charset = defaultCharset;
        this.rawResponse = rawHTTP;
        ByteArrayBuffer buf = new ByteArrayBuffer(getByteArray(rawHTTP));
        View view = new View(buf);
        HttpParser parser = new HttpParser(view,new PH());
        parser.parse();
        return getString(view.asArray());
    }

    public void reset()
    {
        fields.clear();
        method = null;
        uri = null;
        version = null;
        status = 0;
        reason = null;
        content = null;
    }

    /**
     * Make sure that status code is "OK"
     */
    public void assertStatusOK()
    {
        assertStatus(HttpStatus.OK_200,"OK");
    }

    public void assertStatusOK(String msg)
    {
        assertStatus(msg,HttpStatus.OK_200,"OK");
    }

    public void assertStatus(int expectedStatus, String expectedReason)
    {
        Assert.assertEquals("Response.status",expectedStatus,this.status);
        Assert.assertEquals("Response.reason",expectedReason,this.reason);
    }

    public void assertStatus(String msg, int expectedStatus, String expectedReason)
    {
        Assert.assertEquals(msg + ": Response.status",expectedStatus,this.status);
        Assert.assertEquals(msg + ": Response.reason",expectedReason,this.reason);
    }

    public void assertStatus(String msg, int expectedStatus)
    {
        assertStatus(msg,expectedStatus,HttpStatus.getMessage(expectedStatus));
    }

    public void assertContentType(String expectedType)
    {
        assertHeader("Content-Type",expectedType);
    }

    private void assertHeader(String headerKey, String expectedValue)
    {
        String actual = fields.getStringField(headerKey);
        Assert.assertNotNull("Response[" + headerKey + "] should not be null",actual);
        Assert.assertEquals("Response[" + headerKey + "]",expectedValue,actual);
    }

    public void assertHeader(String msg, String headerKey, String expectedValue)
    {
        String actual = fields.getStringField(headerKey);
        Assert.assertNotNull(msg + ": Response[" + headerKey + "] should not be null, expecting <" + expectedValue + ">",actual);
        Assert.assertEquals(msg + ": Response[" + headerKey + "]",expectedValue,actual);
    }

    public void assertBody(String expected)
    {
        Assert.assertNotNull("Response.content should not be null",this.content);
        String actual = this.content.toString();
        Assert.assertEquals("Response.content",expected,actual);
    }

    public void assertBody(String msg, String expected)
    {
        Assert.assertNotNull(msg + ": Response.content should not be null",this.content);
        String actual = this.content.toString();
        Assert.assertEquals(msg + ": Response.content",expected,actual);
    }
    
    public void assertNoBody(String msg)
    {
        Assert.assertNull(msg + ": Response.content should be null",this.content);
    }

    public void assertBodyContains(String msg, String expectedNeedle)
    {
        StringAssert.assertContains(msg + ": Response Content",this.content.toString(),expectedNeedle);
    }

    public void assertHeaderExists(String msg, String expectedHeaderKey)
    {
        Assert.assertTrue(msg + ": header <" + expectedHeaderKey + "> should exist",fields.containsKey(expectedHeaderKey));
    }

    public void assertHeaderNotPresent(String msg, String headerKey)
    {
        Assert.assertFalse(msg + ": header <" + headerKey + "> should NOT exist",fields.containsKey(headerKey));
    }
    
    public List<HttpResponseTester> findBodyMultiparts(String boundary) throws IOException
    {
        List<HttpResponseTester> multiparts = new ArrayList<HttpResponseTester>();

        BufferedReader buf = new BufferedReader(new StringReader(getBody()));
        String line;
        String startBoundary = "--" + boundary;
        String endBoundary = "--" + boundary + "--";
        HttpResponseTester resp = null;
        boolean parsingHeader = true;
        boolean previousBodyLine = false;

        while ((line = buf.readLine()) != null)
        {
            if (line.equals(startBoundary))
            {
                // end of multipart, start a new one.
                if (resp != null)
                {
                    multiparts.add(resp);
                }
                resp = new HttpResponseTester();
                parsingHeader = true;
                previousBodyLine = false;
                continue;
            }

            if (line.equals(endBoundary))
            {
                if (resp != null)
                {
                    multiparts.add(resp);
                }
                break;
            }

            if (parsingHeader)
            {
                if (line.equals(""))
                {
                    parsingHeader = false;
                    continue;
                }

                resp.parseHeader(line);
            }
            else
            {
                if (previousBodyLine)
                {
                    resp.appendBody("\n");
                }
                resp.appendBody(line);
                previousBodyLine = true;
            }
        }

        return multiparts;
    }

    public void parseHeader(String line)
    {
        int idx = line.indexOf(":");
        String key = line.substring(0,idx).trim();
        String val = line.substring(idx + 1).trim();

        fields.add(key,val);
    }

    public void appendBody(String s) throws IOException
    {
        appendBody(s.getBytes());
    }

    public void appendBody(byte buf[]) throws IOException
    {
        if (content == null)
        {
            content = new ByteArrayOutputStream2();
        }

        content.write(buf);
    }
}
