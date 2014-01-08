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

package org.eclipse.jetty.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.HttpGenerator.RequestInfo;
import org.eclipse.jetty.http.HttpGenerator.ResponseInfo;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;

public class HttpTester
{
    private HttpTester()
    {
    }

    public static Request newRequest()
    {
        return new Request();
    }

    public static Request parseRequest(String request)
    {
        Request r=new Request();
        HttpParser parser =new HttpParser(r);
        parser.parseNext(BufferUtil.toBuffer(request));
        return r;
    }

    public static Request parseRequest(ByteBuffer request)
    {
        Request r=new Request();
        HttpParser parser =new HttpParser(r);
        parser.parseNext(request);
        return r;
    }

    public static Response parseResponse(String response)
    {
        Response r=new Response();
        HttpParser parser =new HttpParser(r);
        parser.parseNext(BufferUtil.toBuffer(response));
        return r;
    }

    public static Response parseResponse(ByteBuffer response)
    {
        Response r=new Response();
        HttpParser parser =new HttpParser(r);
        parser.parseNext(response);
        return r;
    }


    public abstract static class Message extends HttpFields implements HttpParser.HttpHandler<ByteBuffer>
    {
        ByteArrayOutputStream _content;
        HttpVersion _version=HttpVersion.HTTP_1_0;

        public HttpVersion getVersion()
        {
            return _version;
        }

        public void setVersion(String version)
        {
            setVersion(HttpVersion.CACHE.get(version));
        }

        public void setVersion(HttpVersion version)
        {
            _version=version;
        }

        public void setContent(byte[] bytes)
        {
            try
            {
                _content=new ByteArrayOutputStream();
                _content.write(bytes);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void setContent(String content)
        {
            try
            {
                _content=new ByteArrayOutputStream();
                _content.write(StringUtil.getBytes(content));
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        public void setContent(ByteBuffer content)
        {
            try
            {
                _content=new ByteArrayOutputStream();
                _content.write(BufferUtil.toArray(content));
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
        @Override
        public boolean parsedHeader(HttpField field)
        {
            put(field.getName(),field.getValue());
            return false;
        }

        @Override
        public boolean messageComplete()
        {
            return true;
        }

        @Override
        public boolean headerComplete()
        {
            _content=new ByteArrayOutputStream();
            return false;
        }

        @Override
        public void earlyEOF()
        {
        }

        @Override
        public boolean content(ByteBuffer ref)
        {
            try
            {
                _content.write(BufferUtil.toArray(ref));
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
            return false;
        }

        @Override
        public void badMessage(int status, String reason)
        {
            throw new RuntimeException(reason);
        }

        public ByteBuffer generate()
        {
            try
            {
                HttpGenerator generator = new HttpGenerator();
                HttpGenerator.Info info = getInfo();
                // System.err.println(info.getClass());
                // System.err.println(info);

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteBuffer header=null;
                ByteBuffer chunk=null;
                ByteBuffer content=_content==null?null:ByteBuffer.wrap(_content.toByteArray());


                loop: while(!generator.isEnd())
                {
                    HttpGenerator.Result result =  info instanceof RequestInfo
                        ?generator.generateRequest((RequestInfo)info,header,chunk,content,true)
                        :generator.generateResponse((ResponseInfo)info,header,chunk,content,true);
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

                return ByteBuffer.wrap(out.toByteArray());
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }

        }
        abstract public HttpGenerator.Info getInfo();

        @Override
        public int getHeaderCacheSize()
        {
            return 0;
        }

    }

    public static class Request extends Message implements HttpParser.RequestHandler<ByteBuffer>
    {
        private String _method;
        private String _uri;

        @Override
        public boolean startRequest(HttpMethod method, String methodString, ByteBuffer uri, HttpVersion version)
        {
            _method=methodString;
            _uri=BufferUtil.toUTF8String(uri);
            _version=version;
            return false;
        }

        public String getMethod()
        {
            return _method;
        }

        public String getUri()
        {
            return _uri;
        }

        public void setMethod(String method)
        {
            _method=method;
        }

        public void setURI(String uri)
        {
            _uri=uri;
        }

        @Override
        public HttpGenerator.RequestInfo getInfo()
        {
            return new HttpGenerator.RequestInfo(_version,this,_content==null?0:_content.size(),_method,_uri);
        }

        @Override
        public String toString()
        {
            return String.format("%s %s %s\n%s\n",_method,_uri,_version,super.toString());
        }

        public void setHeader(String name, String value)
        {
            put(name,value);
        }

        @Override
        public boolean parsedHostHeader(String host,int port)
        {
            return false;
        }
    }

    public static class Response extends Message implements HttpParser.ResponseHandler<ByteBuffer>
    {
        private int _status;
        private String _reason;

        @Override
        public boolean startResponse(HttpVersion version, int status, String reason)
        {
            _version=version;
            _status=status;
            _reason=reason;
            return false;
        }

        public int getStatus()
        {
            return _status;
        }

        public String getReason()
        {
            return _reason;
        }

        public byte[] getContentBytes()
        {
            if (_content==null)
                return null;
            return _content.toByteArray();
        }

        public String getContent()
        {
            if (_content==null)
                return null;
            byte[] bytes=_content.toByteArray();

            String content_type=get(HttpHeader.CONTENT_TYPE);
            String encoding=MimeTypes.getCharsetFromContentType(content_type);
            Charset charset=encoding==null?StandardCharsets.UTF_8:Charset.forName(encoding);

            return new String(bytes,charset);
        }

        @Override
        public HttpGenerator.ResponseInfo getInfo()
        {
            return new HttpGenerator.ResponseInfo(_version,this,_content==null?-1:_content.size(),_status,_reason,false);
        }

        @Override
        public String toString()
        {
            return String.format("%s %s %s\n%s\n",_version,_status,_reason,super.toString());
        }
    }
}
