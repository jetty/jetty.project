// ========================================================================
// Copyright (c) Webtide LLC
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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.zip.GZIPOutputStream;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.eclipse.jetty.continuation.Continuation;
import org.eclipse.jetty.continuation.ContinuationListener;
import org.eclipse.jetty.continuation.ContinuationSupport;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.ByteArrayOutputStream2;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;

/* ------------------------------------------------------------ */
/**
 * GZIP Handler This handler will gzip the content of a response if:
 * <ul>
 * <li>The filter is mapped to a matching path</li>
 * <li>The response status code is >=200 and <300
 * <li>The content length is unknown or more than the <code>minGzipSize</code> initParameter or the minGzipSize is 0(default)</li>
 * <li>The content-type is in the comma separated list of mimeTypes set in the <code>mimeTypes</code> initParameter or if no mimeTypes are defined the
 * content-type is not "application/gzip"</li>
 * <li>No content-encoding is specified by the resource</li>
 * </ul>
 * 
 * <p>
 * Compressing the content can greatly improve the network bandwidth usage, but at a cost of memory and CPU cycles. If this handler is used for static content,
 * then use of efficient direct NIO may be prevented, thus use of the gzip mechanism of the {@link org.eclipse.jetty.servlet.DefaultServlet} is advised instead.
 * </p>
 */
public class GzipHandler extends HandlerWrapper
{
    protected Set<String> _mimeTypes;
    protected Set<String> _excluded;
    protected int _bufferSize = 8192;
    protected int _minGzipSize = 256;

    /* ------------------------------------------------------------ */
    /**
     */
    public GzipHandler()
    {
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the mime types.
     * 
     * @return mime types to set
     */
    public Set<String> getMimeTypes()
    {
        return _mimeTypes;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the mime types.
     * 
     * @param mimeTypes
     *            the mime types to set
     */
    public void setMimeTypes(Set<String> mimeTypes)
    {
        _mimeTypes = mimeTypes;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the mime types.
     * 
     * @param mimeTypes
     *            the mime types to set
     */
    public void setMimeTypes(String mimeTypes)
    {
        if (mimeTypes != null)
        {
            _mimeTypes = new HashSet<String>();
            StringTokenizer tok = new StringTokenizer(mimeTypes,",",false);
            while (tok.hasMoreTokens())
            {
                _mimeTypes.add(tok.nextToken());
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the excluded user agents.
     * 
     * @return excluded user agents
     */
    public Set<String> getExcluded()
    {
        return _excluded;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the excluded user agents.
     * 
     * @param excluded
     *            excluded user agents to set
     */
    public void setExcluded(Set<String> excluded)
    {
        _excluded = excluded;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the excluded user agents.
     * 
     * @param excluded
     *            excluded user agents to set
     */
    public void setExcluded(String excluded)
    {
        if (excluded != null)
        {
            _excluded = new HashSet<String>();
            StringTokenizer tok = new StringTokenizer(excluded,",",false);
            while (tok.hasMoreTokens())
                _excluded.add(tok.nextToken());
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the buffer size.
     * 
     * @return the buffer size
     */
    public int setBufferSize()
    {
        return _bufferSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the buffer size.
     * 
     * @param bufferSize
     *            buffer size to set
     */
    public void setBufferSize(int bufferSize)
    {
        _bufferSize = bufferSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * Get the minimum reponse size.
     * 
     * @return minimum reponse size
     */
    public int getMinGzipSize()
    {
        return _minGzipSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the minimum reponse size.
     * 
     * @param minGzipSize
     *            minimum reponse size
     */
    public void setMinGzipSize(int minGzipSize)
    {
        _minGzipSize = minGzipSize;
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (_handler!=null && isStarted())
        {
            String ae = request.getHeader("accept-encoding");
            if (ae != null && ae.indexOf("gzip")>=0 && !response.containsHeader("Content-Encoding")
                    && !HttpMethods.HEAD.equalsIgnoreCase(request.getMethod()))
            {
                if (_excluded!=null)
                {
                    String ua = request.getHeader("User-Agent");
                    if (_excluded.contains(ua))
                    {
                        _handler.handle(target,baseRequest, request, response);
                        return;
                    }
                }

                final GZIPResponseWrapper wrappedResponse = newGZIPResponseWrapper(request,response);
                
                boolean exceptional=true;
                try
                {
                    _handler.handle(target, baseRequest, request, wrappedResponse);
                    exceptional=false;
                }
                finally
                {
                    Continuation continuation = ContinuationSupport.getContinuation(request);
                    if (continuation.isSuspended() && continuation.isResponseWrapped())   
                    {
                        continuation.addContinuationListener(new ContinuationListener()
                        {
                            public void onComplete(Continuation continuation)
                            {
                                try
                                {
                                    wrappedResponse.finish();
                                }
                                catch(IOException e)
                                {
                                    Log.warn(e);
                                }
                            }

                            public void onTimeout(Continuation continuation)
                            {}
                        });
                    }
                    else if (exceptional && !response.isCommitted())
                    {
                        wrappedResponse.resetBuffer();
                        wrappedResponse.noGzip();
                    }
                    else
                        wrappedResponse.finish();
                }
            }
            else
            {
                _handler.handle(target,baseRequest, request, response);
            }
        }
    }

    protected GZIPResponseWrapper newGZIPResponseWrapper(HttpServletRequest request, HttpServletResponse response)
    {
        return new GZIPResponseWrapper(request,response);
    }

    /*
     * Allows derived implementations to replace PrintWriter implementation
     */
    protected PrintWriter newWriter(OutputStream out, String encoding) throws UnsupportedEncodingException
    {
        return encoding == null?new PrintWriter(out):new PrintWriter(new OutputStreamWriter(out,encoding));
    }

    public class GZIPResponseWrapper extends HttpServletResponseWrapper
    {
        HttpServletRequest _request;
        boolean _noGzip;
        PrintWriter _writer;
        GzipStream _gzStream;
        long _contentLength = -1;

        public GZIPResponseWrapper(HttpServletRequest request, HttpServletResponse response)
        {
            super(response);
            _request = request;
        }

        public void setContentType(String ct)
        {
            super.setContentType(ct);

            if (ct != null)
            {
                int colon = ct.indexOf(";");
                if (colon > 0)
                    ct = ct.substring(0,colon);
            }

            if ((_gzStream == null || _gzStream._out == null)
                    && (_mimeTypes == null && "application/gzip".equalsIgnoreCase(ct) || _mimeTypes != null
                            && (ct == null || !_mimeTypes.contains(StringUtil.asciiToLowerCase(ct)))))
            {
                noGzip();
            }
        }

        public void setStatus(int sc, String sm)
        {
            super.setStatus(sc,sm);
            if (sc < 200 || sc >= 300)
                noGzip();
        }

        public void setStatus(int sc)
        {
            super.setStatus(sc);
            if (sc < 200 || sc >= 300)
                noGzip();
        }

        public void setContentLength(int length)
        {
            _contentLength = length;
            if (_gzStream != null)
                _gzStream.setContentLength(length);
        }

        public void addHeader(String name, String value)
        {
            if ("content-length".equalsIgnoreCase(name))
            {
                _contentLength = Long.parseLong(value);
                if (_gzStream != null)
                    _gzStream.setContentLength(_contentLength);
            }
            else if ("content-type".equalsIgnoreCase(name))
            {
                setContentType(value);
            }
            else if ("content-encoding".equalsIgnoreCase(name))
            {
                super.addHeader(name,value);
                if (!isCommitted())
                {
                    noGzip();
                }
            }
            else
                super.addHeader(name,value);
        }

        public void setHeader(String name, String value)
        {
            if ("content-length".equalsIgnoreCase(name))
            {
                _contentLength = Long.parseLong(value);
                if (_gzStream != null)
                    _gzStream.setContentLength(_contentLength);
            }
            else if ("content-type".equalsIgnoreCase(name))
            {
                setContentType(value);
            }
            else if ("content-encoding".equalsIgnoreCase(name))
            {
                super.setHeader(name,value);
                if (!isCommitted())
                {
                    noGzip();
                }
            }
            else
                super.setHeader(name,value);
        }

        public void setIntHeader(String name, int value)
        {
            if ("content-length".equalsIgnoreCase(name))
            {
                _contentLength = value;
                if (_gzStream != null)
                    _gzStream.setContentLength(_contentLength);
            }
            else
                super.setIntHeader(name,value);
        }

        public void flushBuffer() throws IOException
        {
            if (_writer != null)
                _writer.flush();
            if (_gzStream != null)
                _gzStream.finish();
            else
                getResponse().flushBuffer();
        }

        public void reset()
        {
            super.reset();
            if (_gzStream != null)
                _gzStream.resetBuffer();
            _writer = null;
            _gzStream = null;
            _noGzip = false;
            _contentLength = -1;
        }

        public void resetBuffer()
        {
            super.resetBuffer();
            if (_gzStream != null)
                _gzStream.resetBuffer();
            _writer = null;
            _gzStream = null;
        }

        public void sendError(int sc, String msg) throws IOException
        {
            resetBuffer();
            super.sendError(sc,msg);
        }

        public void sendError(int sc) throws IOException
        {
            resetBuffer();
            super.sendError(sc);
        }

        public void sendRedirect(String location) throws IOException
        {
            resetBuffer();
            super.sendRedirect(location);
        }

        public ServletOutputStream getOutputStream() throws IOException
        {
            if (_gzStream == null)
            {
                if (getResponse().isCommitted() || _noGzip)
                    return getResponse().getOutputStream();

                _gzStream = newGzipStream(_request,(HttpServletResponse)getResponse(),_contentLength,_bufferSize,_minGzipSize);
            }
            else if (_writer != null)
                throw new IllegalStateException("getWriter() called");

            return _gzStream;
        }

        public PrintWriter getWriter() throws IOException
        {
            if (_writer == null)
            {
                if (_gzStream != null)
                    throw new IllegalStateException("getOutputStream() called");

                if (getResponse().isCommitted() || _noGzip)
                    return getResponse().getWriter();

                _gzStream = newGzipStream(_request,(HttpServletResponse)getResponse(),_contentLength,_bufferSize,_minGzipSize);
                _writer = newWriter(_gzStream,getCharacterEncoding());
            }
            return _writer;
        }

        void noGzip()
        {
            _noGzip = true;
            if (_gzStream != null)
            {
                try
                {
                    _gzStream.doNotGzip();
                }
                catch (IOException e)
                {
                    throw new IllegalStateException(e);
                }
            }
        }

        void finish() throws IOException
        {
            if (_writer != null && !_gzStream._closed)
                _writer.flush();
            if (_gzStream != null)
                _gzStream.finish();
        }

        protected GzipStream newGzipStream(HttpServletRequest request, HttpServletResponse response, long contentLength, int bufferSize, int minGzipSize)
                throws IOException
        {
            return new GzipStream(request,response,contentLength,bufferSize,minGzipSize);
        }
    }

    public static class GzipStream extends ServletOutputStream
    {
        protected HttpServletRequest _request;
        protected HttpServletResponse _response;
        protected OutputStream _out;
        protected ByteArrayOutputStream2 _bOut;
        protected GZIPOutputStream _gzOut;
        protected boolean _closed;
        protected int _bufferSize;
        protected int _minGzipSize;
        protected long _contentLength;

        public GzipStream(HttpServletRequest request, HttpServletResponse response, long contentLength, int bufferSize, int minGzipSize) throws IOException
        {
            _request = request;
            _response = response;
            _contentLength = contentLength;
            _bufferSize = bufferSize;
            _minGzipSize = minGzipSize;
            if (minGzipSize == 0)
                doGzip();
        }

        public void resetBuffer()
        {
            if (_response.isCommitted())
                throw new IllegalStateException("Committed");
            _closed = false;
            _out = null;
            _bOut = null;
            if (_gzOut != null)
                _response.setHeader("Content-Encoding",null);
            _gzOut = null;
        }

        public void setContentLength(long length)
        {
            _contentLength = length;
        }

        public void flush() throws IOException
        {
            if (_out == null || _bOut != null)
            {
                if (_contentLength > 0 && _contentLength < _minGzipSize)
                    doNotGzip();
                else
                    doGzip();
            }

            _out.flush();
        }

        public void close() throws IOException
        {
            if (_closed)
                return;

            if (_request.getAttribute("javax.servlet.include.request_uri") != null)
                flush();
            else
            {
                if (_bOut != null)
                {
                    if (_contentLength < 0)
                        _contentLength = _bOut.getCount();
                    if (_contentLength < _minGzipSize)
                        doNotGzip();
                    else
                        doGzip();
                }
                else if (_out == null)
                {
                    doNotGzip();
                }

                if (_gzOut != null)
                    _gzOut.close();
                else
                    _out.close();
                _closed = true;
            }
        }

        public void finish() throws IOException
        {
            if (!_closed)
            {
                if (_out == null || _bOut != null)
                {
                    if (_contentLength > 0 && _contentLength < _minGzipSize)
                        doNotGzip();
                    else
                        doGzip();
                }

                if (_gzOut != null && !_closed)
                {
                    _closed = true;
                    _gzOut.close();
                }
            }
        }

        public void write(int b) throws IOException
        {
            checkOut(1);
            _out.write(b);
        }

        public void write(byte b[]) throws IOException
        {
            checkOut(b.length);
            _out.write(b);
        }

        public void write(byte b[], int off, int len) throws IOException
        {
            checkOut(len);
            _out.write(b,off,len);
        }

        protected boolean setContentEncodingGzip()
        {
            _response.setHeader("Content-Encoding","gzip");
            return _response.containsHeader("Content-Encoding");
        }

        public void doGzip() throws IOException
        {
            if (_gzOut == null)
            {
                if (_response.isCommitted())
                    throw new IllegalStateException();

                if (setContentEncodingGzip())
                {
                    _out = _gzOut = new GZIPOutputStream(_response.getOutputStream(),_bufferSize);

                    if (_bOut != null)
                    {
                        _out.write(_bOut.getBuf(),0,_bOut.getCount());
                        _bOut = null;
                    }
                }
                else
                    doNotGzip();
            }
        }

        public void doNotGzip() throws IOException
        {
            if (_gzOut != null)
                throw new IllegalStateException();
            if (_out == null || _bOut != null)
            {
                _out = _response.getOutputStream();
                if (_contentLength >= 0)
                {
                    if (_contentLength < Integer.MAX_VALUE)
                        _response.setContentLength((int)_contentLength);
                    else
                        _response.setHeader("Content-Length",Long.toString(_contentLength));
                }

                if (_bOut != null)
                    _out.write(_bOut.getBuf(),0,_bOut.getCount());
                _bOut = null;
            }
        }

        private void checkOut(int length) throws IOException
        {
            if (_closed)
                throw new IOException("CLOSED");

            if (_out == null)
            {
                if (_response.isCommitted() || (_contentLength >= 0 && _contentLength < _minGzipSize))
                    doNotGzip();
                else if (length > _minGzipSize)
                    doGzip();
                else
                    _out = _bOut = new ByteArrayOutputStream2(_bufferSize);
            }
            else if (_bOut != null)
            {
                if (_response.isCommitted() || (_contentLength >= 0 && _contentLength < _minGzipSize))
                    doNotGzip();
                else if (length >= (_bOut.getBuf().length - _bOut.getCount()))
                    doGzip();
            }
        }
    }
}
