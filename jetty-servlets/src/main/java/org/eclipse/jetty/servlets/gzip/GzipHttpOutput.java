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

package org.eclipse.jetty.servlets.gzip;

import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class GzipHttpOutput extends HttpOutput
{
    public static Logger LOG = Log.getLogger(GzipHttpOutput.class);
    private final static HttpGenerator.CachedHttpField CONTENT_ENCODING_GZIP=new HttpGenerator.CachedHttpField(HttpHeader.CONTENT_ENCODING,"gzip");
    private final static byte[] GZIP_HEADER = new byte[] { (byte)0x1f, (byte)0x8b, Deflater.DEFLATED, 0, 0, 0, 0, 0, 0, 0 };
    
    private enum GZState { NOT_COMPRESSING, MIGHT_COMPRESS, COMMITTING, COMPRESSING, FINISHED};
    private final AtomicReference<GZState> _state = new AtomicReference<>(GZState.NOT_COMPRESSING);
    private final CRC32 _crc = new CRC32();
    
    private Deflater _deflater;
    private GzipFactory _factory;
    private ByteBuffer _buffer;
    
    public GzipHttpOutput(HttpChannel<?> channel)
    {
        super(channel);
    }

    @Override
    public void reset()
    {
        _state.set(GZState.NOT_COMPRESSING);
        super.reset();
    }

    @Override
    protected void write(ByteBuffer content, boolean complete, Callback callback)
    {
        switch (_state.get())
        {
            case NOT_COMPRESSING:
                super.write(content,complete,callback);
                return;

            case MIGHT_COMPRESS:
                commit(content,complete,callback);
                break;
                
            case COMMITTING:
                throw new WritePendingException();

            case COMPRESSING:
                gzip(content,complete,callback);
                break;

            case FINISHED:
                throw new IllegalStateException();
        }
    }

    private void superWrite(ByteBuffer content, boolean complete, Callback callback)
    {
        super.write(content,complete,callback);
    }
    
    private void addTrailer()
    {
        int i=_buffer.limit();
        _buffer.limit(i+8);
        
        int v=(int)_crc.getValue();
        _buffer.put(i++,(byte)(v & 0xFF));
        _buffer.put(i++,(byte)((v>>>8) & 0xFF));
        _buffer.put(i++,(byte)((v>>>16) & 0xFF));
        _buffer.put(i++,(byte)((v>>>24) & 0xFF));
        
        v=_deflater.getTotalIn();
        _buffer.put(i++,(byte)(v & 0xFF));
        _buffer.put(i++,(byte)((v>>>8) & 0xFF));
        _buffer.put(i++,(byte)((v>>>16) & 0xFF));
        _buffer.put(i++,(byte)((v>>>24) & 0xFF));
    }
    
    
    private void gzip(ByteBuffer content, boolean complete, final Callback callback)
    {
        if (content.hasRemaining() || complete)
        {
            if (content.hasArray())
                new GzipArrayCB(content,complete,callback).iterate();
            else
                new GzipBufferCB(content,complete,callback).iterate();
        }
    }

    protected void commit(ByteBuffer content, boolean complete, Callback callback)
    {
        // Are we excluding because of status?
        Response response=getHttpChannel().getResponse();
        int sc = response.getStatus();
        if (sc>0 && (sc<200 || sc==204 || sc==205 || sc>=300))
        {
            LOG.debug("{} exclude by status {}",this,sc);
            noCompression();
            super.write(content,complete,callback);
            return;
        }
        
        // Are we excluding because of mime-type?
        String ct = getHttpChannel().getResponse().getContentType();
        if (ct!=null)
        {
            ct=MimeTypes.getContentTypeWithoutCharset(ct);
            if (_factory.isExcludedMimeType(StringUtil.asciiToLowerCase(ct)))
            {
                LOG.debug("{} exclude by mimeType {}",this,ct);
                noCompression();
                super.write(content,complete,callback);
                return;
            }
        }
        
        // Are we the thread that commits?
        if (_state.compareAndSet(GZState.MIGHT_COMPRESS,GZState.COMMITTING))
        {
            // We are varying the response due to accept encoding header.
            HttpFields fields = response.getHttpFields();
            fields.add(_factory.getVaryField());

            long content_length = response.getContentLength();
            if (content_length<0 && complete)
                content_length=content.remaining();
            
            _deflater = _factory.getDeflater(getHttpChannel().getRequest(),content_length);
            
            if (_deflater==null)
            {
                LOG.debug("{} exclude no deflater",this);
                _state.set(GZState.NOT_COMPRESSING);
                super.write(content,complete,callback);
                return;
            }

            fields.put(CONTENT_ENCODING_GZIP);
            _crc.reset();
            _buffer=getHttpChannel().getByteBufferPool().acquire(_factory.getBufferSize(),false);
            BufferUtil.fill(_buffer,GZIP_HEADER,0,GZIP_HEADER.length);

            // Adjust headers
            response.setContentLength(-1);
            String etag=fields.get(HttpHeader.ETAG);
            if (etag!=null)
                fields.put(HttpHeader.ETAG,etag.substring(0,etag.length()-1)+"--gzip\"");

            LOG.debug("{} compressing {}",this,_deflater);
            _state.set(GZState.COMPRESSING);
            
            gzip(content,complete,callback);
        }
    }

    public void noCompression()
    {
        while (true)
        {
            switch (_state.get())
            {
                case NOT_COMPRESSING:
                    return;

                case MIGHT_COMPRESS:
                    if (_state.compareAndSet(GZState.MIGHT_COMPRESS,GZState.NOT_COMPRESSING))
                        return;
                    break;

                default:
                    throw new IllegalStateException(_state.get().toString());
            }
        }
    }

    public void noCompressionIfPossible()
    {
        while (true)
        {
            switch (_state.get())
            {
                case COMPRESSING:
                case NOT_COMPRESSING:
                    return;

                case MIGHT_COMPRESS:
                    if (_state.compareAndSet(GZState.MIGHT_COMPRESS,GZState.NOT_COMPRESSING))
                        return;
                    break;

                default:
                    throw new IllegalStateException(_state.get().toString());
            }
        }
    }


    public void mightCompress(GzipFactory factory)
    {
        while (true)
        {
            switch (_state.get())
            {
                case NOT_COMPRESSING:
                    _factory=factory;
                    if (_state.compareAndSet(GZState.NOT_COMPRESSING,GZState.MIGHT_COMPRESS))
                    {
                        LOG.debug("{} might compress",this);
                        return;
                    }
                    _factory=null;
                    break;
                    
                default:
                    throw new IllegalStateException(_state.get().toString());
            }
        }
    }
    
    private class GzipArrayCB extends IteratingNestedCallback
    {        
        private final boolean _complete;
        public GzipArrayCB(ByteBuffer content, boolean complete, Callback callback)
        {
            super(callback);
            _complete=complete;

             byte[] array=content.array();
             int off=content.arrayOffset()+content.position();
             int len=content.remaining();
             _crc.update(array,off,len);
             _deflater.setInput(array,off,len);
             if (complete)
                 _deflater.finish();
             content.position(content.limit());
        }

        @Override
        protected Action process() throws Exception
        {
            if (_deflater.needsInput())
            {
                if (_deflater.finished())
                {
                    _factory.recycle(_deflater);
                    _deflater=null;
                    getHttpChannel().getByteBufferPool().release(_buffer);
                    _buffer=null;
                    return Action.SUCCEEDED;
                }

                if (!_complete)
                    return Action.SUCCEEDED;
            }

            BufferUtil.compact(_buffer);
            int off=_buffer.arrayOffset()+_buffer.limit();
            int len=_buffer.capacity()-_buffer.limit()- (_complete?8:0);
            int produced=_deflater.deflate(_buffer.array(),off,len,Deflater.NO_FLUSH);
            _buffer.limit(_buffer.limit()+produced);
            boolean complete=_deflater.finished();
            if (complete)
                addTrailer();
            
            superWrite(_buffer,complete,this);
            return Action.SCHEDULED;
        }
    }
    
    private class GzipBufferCB extends IteratingNestedCallback
    {        
        private final ByteBuffer _input;
        private final ByteBuffer _content;
        private final boolean _complete;
        public GzipBufferCB(ByteBuffer content, boolean complete, Callback callback)
        {
            super(callback);
            _input=getHttpChannel().getByteBufferPool().acquire(Math.min(_factory.getBufferSize(),content.remaining()),false);
            _content=content;
            _complete=complete;
        }

        @Override
        protected Action process() throws Exception
        {
            if (_deflater.needsInput())
            {
                if (BufferUtil.isEmpty(_content))
                {
                    if (_deflater.finished())
                    {
                        _factory.recycle(_deflater);
                        _deflater=null;
                        getHttpChannel().getByteBufferPool().release(_buffer);
                        _buffer=null;
                        return Action.SUCCEEDED;
                    }
                    
                    if (!_complete)
                        return Action.SUCCEEDED;
                }
                else
                {
                    BufferUtil.clearToFill(_input);
                    BufferUtil.put(_content,_input);
                    BufferUtil.flipToFlush(_input,0);

                    byte[] array=_input.array();
                    int off=_input.arrayOffset()+_input.position();
                    int len=_input.remaining();

                    _crc.update(array,off,len);
                    _deflater.setInput(array,off,len);                
                    if (_complete && BufferUtil.isEmpty(_content))
                        _deflater.finish();
                }
            }

            BufferUtil.compact(_buffer);
            int off=_buffer.arrayOffset()+_buffer.limit();
            int len=_buffer.capacity()-_buffer.limit() - (_complete?8:0);
            int produced=_deflater.deflate(_buffer.array(),off,len,Deflater.NO_FLUSH);
            
            _buffer.limit(_buffer.limit()+produced);
            boolean complete=_deflater.finished();
            
            if (complete)
                addTrailer();
                
            superWrite(_buffer,complete,this);
            return Action.SCHEDULED;
        }
        
    }
}
