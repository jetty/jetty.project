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

package org.eclipse.jetty.http.gzip;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.Set;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.eclipse.jetty.util.StringUtil;

/*------------------------------------------------------------ */
/**
 */
public abstract class CompressedResponseWrapper extends HttpServletResponseWrapper
{
    
    public static final int DEFAULT_BUFFER_SIZE = 8192;
    public static final int DEFAULT_MIN_COMPRESS_SIZE = 256;
    
    private Set<String> _mimeTypes;
    private int _bufferSize=DEFAULT_BUFFER_SIZE;
    private int _minCompressSize=DEFAULT_MIN_COMPRESS_SIZE;
    protected HttpServletRequest _request;

    private PrintWriter _writer;
    private AbstractCompressedStream _compressedStream;
    private String _etag;
    private long _contentLength=-1;
    private boolean _noCompression;

    /* ------------------------------------------------------------ */
    public CompressedResponseWrapper(HttpServletRequest request, HttpServletResponse response)
    {
        super(response);
        _request = request;
    }


    /* ------------------------------------------------------------ */
    public long getContentLength()
    {
        return _contentLength;
    }

    /* ------------------------------------------------------------ */
    public int getBufferSize()
    {
        return _bufferSize;
    }
    
    /* ------------------------------------------------------------ */
    public int getMinCompressSize()
    {
        return _minCompressSize;
    }
    
    /* ------------------------------------------------------------ */
    public String getETag()
    {
        return _etag;
    }

    /* ------------------------------------------------------------ */
    public HttpServletRequest getRequest()
    {
        return _request;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#setMimeTypes(java.util.Set)
     */
    public void setMimeTypes(Set<String> mimeTypes)
    {
        _mimeTypes = mimeTypes;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#setBufferSize(int)
     */
    @Override
    public void setBufferSize(int bufferSize)
    {
        _bufferSize = bufferSize;
        if (_compressedStream!=null)
            _compressedStream.setBufferSize(bufferSize);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#setMinCompressSize(int)
     */
    public void setMinCompressSize(int minCompressSize)
    {
        _minCompressSize = minCompressSize;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#setContentType(java.lang.String)
     */
    @Override
    public void setContentType(String ct)
    {
        super.setContentType(ct);
    
        if (!_noCompression)
        {
            if (ct!=null)
            {
                int colon=ct.indexOf(";");
                if (colon>0)
                    ct=ct.substring(0,colon);
            }

            if ((_compressedStream==null || _compressedStream.getOutputStream()==null) && 
                    (_mimeTypes==null && ct!=null && ct.contains("gzip") ||
                    _mimeTypes!=null && (ct==null||!_mimeTypes.contains(StringUtil.asciiToLowerCase(ct)))))
            {
                noCompression();
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#setStatus(int, java.lang.String)
     */
    @Override
    public void setStatus(int sc, String sm)
    {
        super.setStatus(sc,sm);
        if (sc<200 || sc==204 || sc==205 || sc>=300)
            noCompression();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#setStatus(int)
     */
    @Override
    public void setStatus(int sc)
    {
        super.setStatus(sc);
        if (sc<200 || sc==204 || sc==205 || sc>=300)
            noCompression();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#setContentLength(int)
     */
    @Override
    public void setContentLength(int length)
    {
        if (_noCompression)
            super.setContentLength(length);
        else
            setContentLength((long)length);
    }
    
    /* ------------------------------------------------------------ */
    protected void setContentLength(long length)
    {
        _contentLength=length;
        if (_compressedStream!=null)
            _compressedStream.setContentLength();
        else if (_noCompression && _contentLength>=0)
        {
            HttpServletResponse response = (HttpServletResponse)getResponse();
            if(_contentLength<Integer.MAX_VALUE)
            {
                response.setContentLength((int)_contentLength);
            }
            else
            {
                response.setHeader("Content-Length", Long.toString(_contentLength));
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#addHeader(java.lang.String, java.lang.String)
     */
    @Override
    public void addHeader(String name, String value)
    {
        if ("content-length".equalsIgnoreCase(name))
        {
            _contentLength=Long.parseLong(value);
            if (_compressedStream!=null)
                _compressedStream.setContentLength();
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
                noCompression();
            }
        }
        else if ("etag".equalsIgnoreCase(name))
            _etag=value;
        else
            super.addHeader(name,value);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#flushBuffer()
     */
    @Override
    public void flushBuffer() throws IOException
    {
        if (_writer!=null)
            _writer.flush();
        if (_compressedStream!=null)
            _compressedStream.flush();
        else
            getResponse().flushBuffer();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#reset()
     */
    @Override
    public void reset()
    {
        super.reset();
        if (_compressedStream!=null)
            _compressedStream.resetBuffer();
        _writer=null;
        _compressedStream=null;
        _noCompression=false;
        _contentLength=-1;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#resetBuffer()
     */
    @Override
    public void resetBuffer()
    {
        super.resetBuffer();
        if (_compressedStream!=null)
            _compressedStream.resetBuffer();
        _writer=null;
        _compressedStream=null;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#sendError(int, java.lang.String)
     */
    @Override
    public void sendError(int sc, String msg) throws IOException
    {
        resetBuffer();
        super.sendError(sc,msg);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#sendError(int)
     */
    @Override
    public void sendError(int sc) throws IOException
    {
        resetBuffer();
        super.sendError(sc);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#sendRedirect(java.lang.String)
     */
    @Override
    public void sendRedirect(String location) throws IOException
    {
        resetBuffer();
        super.sendRedirect(location);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#noCompression()
     */
    public void noCompression()
    {
        if (!_noCompression)
            setDeferredHeaders();
        _noCompression=true;
        if (_compressedStream!=null)
        {
            try
            {
                _compressedStream.doNotCompress(false);
            }
            catch (IOException e)
            {
                throw new IllegalStateException(e);
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#finish()
     */
    public void finish() throws IOException
    {
        if (_writer!=null && !_compressedStream.isClosed())
            _writer.flush();
        if (_compressedStream!=null)
            _compressedStream.finish();
        else 
            setDeferredHeaders();
    }

    /* ------------------------------------------------------------ */
    private void setDeferredHeaders()
    {
        if (!isCommitted())
        {
            if (_contentLength>=0)
            {
                if (_contentLength < Integer.MAX_VALUE)
                    super.setContentLength((int)_contentLength);
                else
                    super.setHeader("Content-Length",Long.toString(_contentLength));
            }
            if(_etag!=null)
                super.setHeader("ETag",_etag);
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#setHeader(java.lang.String, java.lang.String)
     */
    @Override
    public void setHeader(String name, String value)
    {
        if (_noCompression)
            super.setHeader(name,value);
        else if ("content-length".equalsIgnoreCase(name))
        {
            setContentLength(Long.parseLong(value));
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
                noCompression();
            }
        }
        else if ("etag".equalsIgnoreCase(name))
            _etag=value;
        else
            super.setHeader(name,value);
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean containsHeader(String name)
    {
        if (!_noCompression && "etag".equalsIgnoreCase(name) && _etag!=null)
            return true;
        return super.containsHeader(name);
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#getOutputStream()
     */
    @Override
    public ServletOutputStream getOutputStream() throws IOException
    {
        if (_compressedStream==null)
        {
            if (getResponse().isCommitted() || _noCompression)
                return getResponse().getOutputStream();
            
            _compressedStream=newCompressedStream(_request,(HttpServletResponse)getResponse());
        }
        else if (_writer!=null)
            throw new IllegalStateException("getWriter() called");
        
        return _compressedStream;   
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#getWriter()
     */
    @Override
    public PrintWriter getWriter() throws IOException
    {
        if (_writer==null)
        { 
            if (_compressedStream!=null)
                throw new IllegalStateException("getOutputStream() called");
            
            if (getResponse().isCommitted() || _noCompression)
                return getResponse().getWriter();
            
            _compressedStream=newCompressedStream(_request,(HttpServletResponse)getResponse());
            _writer=newWriter(_compressedStream,getCharacterEncoding());
        }
        return _writer;   
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.gzip.CompressedResponseWrapper#setIntHeader(java.lang.String, int)
     */
    @Override
    public void setIntHeader(String name, int value)
    {
        if ("content-length".equalsIgnoreCase(name))
        {
            _contentLength=value;
            if (_compressedStream!=null)
                _compressedStream.setContentLength();
        }
        else
            super.setIntHeader(name,value);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Allows derived implementations to replace PrintWriter implementation.
     *
     * @param out the out
     * @param encoding the encoding
     * @return the prints the writer
     * @throws UnsupportedEncodingException the unsupported encoding exception
     */
    protected PrintWriter newWriter(OutputStream out,String encoding) throws UnsupportedEncodingException
    {
        return encoding==null?new PrintWriter(out):new PrintWriter(new OutputStreamWriter(out,encoding));
    }
    
    /* ------------------------------------------------------------ */
    /**
     *@return the underlying CompressedStream implementation 
     */
    protected abstract AbstractCompressedStream newCompressedStream(HttpServletRequest _request, HttpServletResponse response) throws IOException;

}
