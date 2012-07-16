package org.eclipse.jetty.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;

public class HttpTester
{
    private HttpTester(){};
    
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
    
    
    public abstract static class Message extends HttpFields implements HttpParser.HttpHandler
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

        public void setContent(String content) 
        {
            try
            {
                _content=new ByteArrayOutputStream();
                _content.write(StringUtil.getBytes(content));
            }
            catch (IOException e)
            {
                throw new RuntimeIOException(e);
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
                throw new RuntimeIOException(e);
            }
        }
        @Override
        public boolean parsedHeader(HttpHeader header, String name, String value)
        {
            put(name,value);
            return false;
        }
        
        @Override
        public boolean messageComplete(long contentLength)
        {
            return true;
        }
        
        @Override
        public boolean headerComplete(boolean hasBody, boolean persistent)
        {
            if (hasBody)
                _content=new ByteArrayOutputStream();
            return false;
        }
        
        @Override
        public boolean earlyEOF()
        {
            return true;
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
                throw new RuntimeIOException(e);
            }
            return false;
        }
        
        @Override
        public void badMessage(int status, String reason)
        {
            throw new RuntimeIOException(reason);
        }

        public ByteBuffer generate() 
        {
            try
            {
                HttpGenerator generator = new HttpGenerator();
                HttpGenerator.Info info = getInfo();
                System.err.println(info.getClass());
                System.err.println(info);

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteBuffer header=BufferUtil.allocate(8192);
                ByteBuffer buffer=BufferUtil.allocate(8192);
                ByteBuffer chunk=BufferUtil.allocate(16);
                ByteBuffer content=_content==null?null:ByteBuffer.wrap(_content.toByteArray());


                loop: while(true)
                {
                    HttpGenerator.Result result = generator.generate(info,header,chunk,buffer,content,HttpGenerator.Action.COMPLETE);
                    switch(result)
                    {
                        case NEED_BUFFER:
                        case NEED_HEADER:
                        case NEED_CHUNK:
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
                            if (BufferUtil.hasContent(buffer))
                            {
                                out.write(BufferUtil.toArray(buffer));
                                BufferUtil.clear(buffer);
                            }
                            break;

                        case FLUSH_CONTENT:
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
                        case OK:
                        case SHUTDOWN_OUT:
                            break loop;
                    }
                }
                
                return ByteBuffer.wrap(out.toByteArray());
            }
            catch (IOException e)
            {
                throw new RuntimeIOException(e);
            }

        }
        abstract public HttpGenerator.Info getInfo();
       
    }
    
    public static class Request extends Message implements HttpParser.RequestHandler
    {
        private String _method;
        private String _uri;
       
        @Override
        public boolean startRequest(HttpMethod method, String methodString, String uri, HttpVersion version)
        {
            _method=methodString;
            _uri=uri;
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
        
        public String toString()
        {
            return String.format("%s %s %s\n%s\n",_method,_uri,_version,super.toString());
        }

        public void setHeader(String name, String value)
        {
            put(name,value);
        }
    }
    
    public static class Response extends Message implements HttpParser.ResponseHandler
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
            Charset charset=encoding==null?StringUtil.__UTF8_CHARSET:Charset.forName(encoding);
       
            return new String(bytes,charset);
        }
       
        @Override
        public HttpGenerator.ResponseInfo getInfo()
        {
            return new HttpGenerator.ResponseInfo(_version,this,_content==null?-1:_content.size(),_status,_reason,false);
        }

        public String toString()
        {
            return String.format("%s %s %s\n%s\n",_version,_status,_reason,super.toString());
        }
    }
}
