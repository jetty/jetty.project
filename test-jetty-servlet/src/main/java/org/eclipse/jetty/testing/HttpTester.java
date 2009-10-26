// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.testing;

import java.io.IOException;
import java.util.Enumeration;

import javax.servlet.http.Cookie;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpVersions;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.SimpleBuffers;
import org.eclipse.jetty.io.View;
import org.eclipse.jetty.io.bio.StringEndPoint;
import org.eclipse.jetty.util.ByteArrayOutputStream2;

/* ------------------------------------------------------------ */
/** Test support class.
 * Assist with parsing and generating HTTP requests and responses.
 * 
 * <pre>
 *      HttpTester tester = new HttpTester();
 *      
 *      tester.parse(
 *          "GET /uri HTTP/1.1\r\n"+
 *          "Host: fakehost\r\n"+
 *          "Content-Length: 10\r\n" +
 *          "\r\n");
 *     
 *      System.err.println(tester.getMethod());
 *      System.err.println(tester.getURI());
 *      System.err.println(tester.getVersion());
 *      System.err.println(tester.getHeader("Host"));
 *      System.err.println(tester.getContent());
 * </pre>      
 * 
 * 
 * @see org.eclipse.jetty.testing.ServletTester
 */
public class HttpTester
{
    protected HttpFields _fields=new HttpFields();
    protected String _method;
    protected String _uri;
    protected String _version;
    protected int _status;
    protected String _reason;
    protected ByteArrayOutputStream2 _parsedContent;
    protected byte[] _genContent;
    
    private String _charset, _defaultCharset;
    private Buffer _contentType;
    
    public HttpTester()
    {
        this("UTF-8");
    }
    
    public HttpTester(String charset)
    {
        _defaultCharset = charset;
    }
    
    public void reset()
    {
        _fields.clear();
         _method=null;
         _uri=null;
         _version=null;
         _status=0;
         _reason=null;
         _parsedContent=null;
         _genContent=null;
    }
    
    private String getString(Buffer buffer)
    {
        return getString(buffer.asArray());
    }
    
    private String getString(byte[] b)
    {
        if(_charset==null)
            return new String(b);
        try
        {
            return new String(b, _charset);
        }
        catch(Exception e)
        {
            return new String(b);
        }
    }
    
    private byte[] getByteArray(String str)
    {
        if(_charset==null)
            return str.getBytes();
        try
        {
            return str.getBytes(_charset);
        }
        catch(Exception e)
        {
            return str.getBytes();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Parse one HTTP request or response
     * @param rawHTTP Raw HTTP to parse
     * @return Any unparsed data in the rawHTTP (eg pipelined requests)
     * @throws IOException
     */
    public String parse(String rawHTTP) throws IOException
    {
        _charset = _defaultCharset;
        ByteArrayBuffer buf = new ByteArrayBuffer(getByteArray(rawHTTP));
        View view = new View(buf);
        HttpParser parser = new HttpParser(view,new PH());
        parser.parse();
        return getString(view.asArray());
    }

    /* ------------------------------------------------------------ */
    public String generate() throws IOException
    {
        _charset = _defaultCharset;
        _contentType = _fields.get(HttpHeaders.CONTENT_TYPE_BUFFER);
        if(_contentType!=null)
        {
            String charset = MimeTypes.getCharsetFromContentType(_contentType);
            if(charset!=null)
                _charset = charset;
        }
        Buffer bb=new ByteArrayBuffer(32*1024 + (_genContent!=null?_genContent.length:0));
        Buffer sb=new ByteArrayBuffer(4*1024);
        StringEndPoint endp = new StringEndPoint(_charset);
        HttpGenerator generator = new HttpGenerator(new SimpleBuffers(sb,bb),endp);
        
        if (_method!=null)
        {
            generator.setRequest(getMethod(),getURI());
            if (_version==null)
                generator.setVersion(HttpVersions.HTTP_1_1_ORDINAL);
            else
                generator.setVersion(HttpVersions.CACHE.getOrdinal(HttpVersions.CACHE.lookup(_version)));
            generator.completeHeader(_fields,false);
            if (_genContent!=null)
                generator.addContent(new View(new ByteArrayBuffer(_genContent)),false);
            else if (_parsedContent!=null)
                generator.addContent(new ByteArrayBuffer(_parsedContent.toByteArray()),false);
        }
        
        generator.complete();
        generator.flushBuffer();
        return endp.getOutput();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return the method
     */
    public String getMethod()
    {
        return _method;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param method the method to set
     */
    public void setMethod(String method)
    {
        _method=method;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the reason
     */
    public String getReason()
    {
        return _reason;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param reason the reason to set
     */
    public void setReason(String reason)
    {
        _reason=reason;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the status
     */
    public int getStatus()
    {
        return _status;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param status the status to set
     */
    public void setStatus(int status)
    {
        _status=status;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the uri
     */
    public String getURI()
    {
        return _uri;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param uri the uri to set
     */
    public void setURI(String uri)
    {
        _uri=uri;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the version
     */
    public String getVersion()
    {
        return _version;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param version the version to set
     */
    public void setVersion(String version)
    {
        _version=version;
    }
    
    /* ------------------------------------------------------------ */
    public String getContentType()
    {
        return getString(_contentType);
    }
    
    /* ------------------------------------------------------------ */
    public String getCharacterEncoding()
    {
        return _charset;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @param value
     * @throws IllegalArgumentException
     * @see org.eclipse.jetty.http.HttpFields#add(java.lang.String, java.lang.String)
     */
    public void addHeader(String name, String value) throws IllegalArgumentException
    {
        _fields.add(name,value);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @param date
     * @see org.eclipse.jetty.http.HttpFields#addDateField(java.lang.String, long)
     */
    public void addDateHeader(String name, long date)
    {
        _fields.addDateField(name,date);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @param value
     * @see org.eclipse.jetty.http.HttpFields#addLongField(java.lang.String, long)
     */
    public void addLongHeader(String name, long value)
    {
        _fields.addLongField(name,value);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param cookie
     * @see org.eclipse.jetty.http.HttpFields#addSetCookie(javax.servlet.http.Cookie)
     */
    public void addSetCookie(Cookie cookie)
    {
        _fields.addSetCookie(
                cookie.getName(),
                cookie.getValue(),
                cookie.getDomain(),
                cookie.getPath(),
                cookie.getMaxAge(),
                cookie.getComment(),
                cookie.getSecure(),
                false,
                cookie.getVersion());
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @return
     * @see org.eclipse.jetty.http.HttpFields#getDateField(java.lang.String)
     */
    public long getDateHeader(String name)
    {
        return _fields.getDateField(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return
     * @see org.eclipse.jetty.http.HttpFields#getFieldNames()
     */
    public Enumeration getHeaderNames()
    {
        return _fields.getFieldNames();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @return
     * @throws NumberFormatException
     * @see org.eclipse.jetty.http.HttpFields#getLongField(java.lang.String)
     */
    public long getLongHeader(String name) throws NumberFormatException
    {
        return _fields.getLongField(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @return
     * @see org.eclipse.jetty.http.HttpFields#getStringField(java.lang.String)
     */
    public String getHeader(String name)
    {
        return _fields.getStringField(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @return
     * @see org.eclipse.jetty.http.HttpFields#getValues(java.lang.String)
     */
    public Enumeration getHeaderValues(String name)
    {
        return _fields.getValues(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @param value
     * @see org.eclipse.jetty.http.HttpFields#put(java.lang.String, java.lang.String)
     */
    public void setHeader(String name, String value)
    {
        _fields.put(name,value);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @param date
     * @see org.eclipse.jetty.http.HttpFields#putDateField(java.lang.String, long)
     */
    public void setDateHeader(String name, long date)
    {
        _fields.putDateField(name,date);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @param value
     * @see org.eclipse.jetty.http.HttpFields#putLongField(java.lang.String, long)
     */
    public void setLongHeader(String name, long value)
    {
        _fields.putLongField(name,value);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param name
     * @see org.eclipse.jetty.http.HttpFields#remove(java.lang.String)
     */
    public void removeHeader(String name)
    {
        _fields.remove(name);
    }
    
    /* ------------------------------------------------------------ */
    public String getContent()
    {
        if (_parsedContent!=null)
            return getString(_parsedContent.toByteArray());
        if (_genContent!=null)
            return getString(_genContent);
        return null;
    }
    
    /* ------------------------------------------------------------ */
    public void setContent(String content)
    {
        _parsedContent=null;
        if (content!=null)
        {
            _genContent=getByteArray(content);
            setLongHeader(HttpHeaders.CONTENT_LENGTH,_genContent.length);
        }
        else
        {
            removeHeader(HttpHeaders.CONTENT_LENGTH);
            _genContent=null;
        }
    }

    /* ------------------------------------------------------------ */
    private class PH extends HttpParser.EventHandler
    {
        @Override
        public void startRequest(Buffer method, Buffer url, Buffer version) throws IOException
        {
            reset();
            _method=getString(method);
            _uri=getString(url);
            _version=getString(version);
        }

        @Override
        public void startResponse(Buffer version, int status, Buffer reason) throws IOException
        {
            reset();
            _version=getString(version);
            _status=status;
            _reason=getString(reason);
        }
        
        @Override
        public void parsedHeader(Buffer name, Buffer value) throws IOException
        {
            _fields.add(name,value);
        }

        @Override
        public void headerComplete() throws IOException
        {
            _contentType = _fields.get(HttpHeaders.CONTENT_TYPE_BUFFER);
            if(_contentType!=null)
            {
                String charset = MimeTypes.getCharsetFromContentType(_contentType);
                if(charset!=null)
                    _charset = charset;
            }
        }

        @Override
        public void messageComplete(long contextLength) throws IOException
        {
        }
        
        @Override
        public void content(Buffer ref) throws IOException
        {
            if (_parsedContent==null)
                _parsedContent=new ByteArrayOutputStream2();
            _parsedContent.write(ref.asArray());
        }
    }

}
