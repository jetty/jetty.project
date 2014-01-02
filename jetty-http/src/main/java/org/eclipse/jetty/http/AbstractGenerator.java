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

import java.io.IOException;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.View;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
 * Abstract Generator. Builds HTTP Messages.
 *
 * Currently this class uses a system parameter "jetty.direct.writers" to control
 * two optional writer to byte conversions. buffer.writers=true will probably be
 * faster, but will consume more memory.   This option is just for testing and tuning.
 *
 */
public abstract class AbstractGenerator implements Generator
{
    private static final Logger LOG = Log.getLogger(AbstractGenerator.class);

    // states
    public final static int STATE_HEADER = 0;
    public final static int STATE_CONTENT = 2;
    public final static int STATE_FLUSHING = 3;
    public final static int STATE_END = 4;

    public static final byte[] NO_BYTES = {};

    // data

    protected final Buffers _buffers; // source of buffers
    protected final EndPoint _endp;

    protected int _state = STATE_HEADER;

    protected int _status = 0;
    protected int _version = HttpVersions.HTTP_1_1_ORDINAL;
    protected  Buffer _reason;
    protected  Buffer _method;
    protected  String _uri;

    protected long _contentWritten = 0;
    protected long _contentLength = HttpTokens.UNKNOWN_CONTENT;
    protected boolean _last = false;
    protected boolean _head = false;
    protected boolean _noContent = false;
    protected Boolean _persistent = null;

    protected Buffer _header; // Buffer for HTTP header (and maybe small _content)
    protected Buffer _buffer; // Buffer for copy of passed _content
    protected Buffer _content; // Buffer passed to addContent

    protected Buffer _date;

    private boolean _sendServerVersion;


    /* ------------------------------------------------------------------------------- */
    /**
     * Constructor.
     *
     * @param buffers buffer pool
     * @param io the end point
     */
    public AbstractGenerator(Buffers buffers, EndPoint io)
    {
        this._buffers = buffers;
        this._endp = io;
    }

    /* ------------------------------------------------------------------------------- */
    public abstract boolean isRequest();

    /* ------------------------------------------------------------------------------- */
    public abstract boolean isResponse();

    /* ------------------------------------------------------------------------------- */
    public boolean isOpen()
    {
        return _endp.isOpen();
    }

    /* ------------------------------------------------------------------------------- */
    public void reset()
    {
        _state = STATE_HEADER;
        _status = 0;
        _version = HttpVersions.HTTP_1_1_ORDINAL;
        _reason = null;
        _last = false;
        _head = false;
        _noContent=false;
        _persistent = null;
        _contentWritten = 0;
        _contentLength = HttpTokens.UNKNOWN_CONTENT;
        _date = null;

        _content = null;
        _method=null;
    }

    /* ------------------------------------------------------------------------------- */
    public void returnBuffers()
    {
        if (_buffer!=null && _buffer.length()==0)
        {
            _buffers.returnBuffer(_buffer);
            _buffer=null;
        }

        if (_header!=null && _header.length()==0)
        {
            _buffers.returnBuffer(_header);
            _header=null;
        }
    }

    /* ------------------------------------------------------------------------------- */
    public void resetBuffer()
    {
        if(_state>=STATE_FLUSHING)
            throw new IllegalStateException("Flushed");

        _last = false;
        _persistent=null;
        _contentWritten = 0;
        _contentLength = HttpTokens.UNKNOWN_CONTENT;
        _content=null;
        if (_buffer!=null)
            _buffer.clear();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the contentBufferSize.
     */
    public int getContentBufferSize()
    {
        if (_buffer==null)
            _buffer=_buffers.getBuffer();
        return _buffer.capacity();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param contentBufferSize The contentBufferSize to set.
     */
    public void increaseContentBufferSize(int contentBufferSize)
    {
        if (_buffer==null)
            _buffer=_buffers.getBuffer();
        if (contentBufferSize > _buffer.capacity())
        {
            Buffer nb = _buffers.getBuffer(contentBufferSize);
            nb.put(_buffer);
            _buffers.returnBuffer(_buffer);
            _buffer = nb;
        }
    }

    /* ------------------------------------------------------------ */
    public Buffer getUncheckedBuffer()
    {
        return _buffer;
    }

    /* ------------------------------------------------------------ */
    public boolean getSendServerVersion ()
    {
        return _sendServerVersion;
    }

    /* ------------------------------------------------------------ */
    public void setSendServerVersion (boolean sendServerVersion)
    {
        _sendServerVersion = sendServerVersion;
    }

    /* ------------------------------------------------------------ */
    public int getState()
    {
        return _state;
    }

    /* ------------------------------------------------------------ */
    public boolean isState(int state)
    {
        return _state == state;
    }

    /* ------------------------------------------------------------ */
    public boolean isComplete()
    {
        return _state == STATE_END;
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return _state == STATE_HEADER && _method==null && _status==0;
    }

    /* ------------------------------------------------------------ */
    public boolean isCommitted()
    {
        return _state != STATE_HEADER;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the head.
     */
    public boolean isHead()
    {
        return _head;
    }

    /* ------------------------------------------------------------ */
    public void setContentLength(long value)
    {
        if (value<0)
            _contentLength=HttpTokens.UNKNOWN_CONTENT;
        else
            _contentLength=value;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param head The head to set.
     */
    public void setHead(boolean head)
    {
        _head = head;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return <code>false</code> if the connection should be closed after a request has been read,
     * <code>true</code> if it should be used for additional requests.
     */
    public boolean isPersistent()
    {
        return _persistent!=null
        ?_persistent.booleanValue()
        :(isRequest()?true:_version>HttpVersions.HTTP_1_0_ORDINAL);
    }

    /* ------------------------------------------------------------ */
    public void setPersistent(boolean persistent)
    {
        _persistent=persistent;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param version The version of the client the response is being sent to (NB. Not the version
     *            in the response, which is the version of the server).
     */
    public void setVersion(int version)
    {
        if (_state != STATE_HEADER)
            throw new IllegalStateException("STATE!=START "+_state);
        _version = version;
        if (_version==HttpVersions.HTTP_0_9_ORDINAL && _method!=null)
            _noContent=true;
    }

    /* ------------------------------------------------------------ */
    public int getVersion()
    {
        return _version;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.Generator#setDate(org.eclipse.jetty.io.Buffer)
     */
    public void setDate(Buffer timeStampBuffer)
    {
        _date=timeStampBuffer;
    }

    /* ------------------------------------------------------------ */
    /**
     */
    public void setRequest(String method, String uri)
    {
        if (method==null || HttpMethods.GET.equals(method) )
            _method=HttpMethods.GET_BUFFER;
        else
            _method=HttpMethods.CACHE.lookup(method);
        _uri=uri;
        if (_version==HttpVersions.HTTP_0_9_ORDINAL)
            _noContent=true;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param status The status code to send.
     * @param reason the status message to send.
     */
    public void setResponse(int status, String reason)
    {
        if (_state != STATE_HEADER) throw new IllegalStateException("STATE!=START");
        _method=null;
        _status = status;
        if (reason!=null)
        {
            int len=reason.length();

            // TODO don't hard code
            if (len>1024)
                len=1024;
            _reason=new ByteArrayBuffer(len);
            for (int i=0;i<len;i++)
            {
                char ch = reason.charAt(i);
                if (ch!='\r'&&ch!='\n')
                    _reason.put((byte)ch);
                else
                    _reason.put((byte)' ');
            }
        }
    }

    /* ------------------------------------------------------------ */
    /** Prepare buffer for unchecked writes.
     * Prepare the generator buffer to receive unchecked writes
     * @return the available space in the buffer.
     * @throws IOException
     */
    public abstract int prepareUncheckedAddContent() throws IOException;

    /* ------------------------------------------------------------ */
    void uncheckedAddContent(int b)
    {
        _buffer.put((byte)b);
    }

    /* ------------------------------------------------------------ */
    public void completeUncheckedAddContent()
    {
        if (_noContent)
        {
            if(_buffer!=null)
                _buffer.clear();
        }
        else
        {
            _contentWritten+=_buffer.length();
            if (_head)
                _buffer.clear();
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isBufferFull()
    {
        if (_buffer != null && _buffer.space()==0)
        {
            if (_buffer.length()==0 && !_buffer.isImmutable())
                _buffer.compact();
            return _buffer.space()==0;
        }

        return _content!=null && _content.length()>0;
    }

    /* ------------------------------------------------------------ */
    public boolean isWritten()
    {
        return _contentWritten>0;
    }

    /* ------------------------------------------------------------ */
    public boolean isAllContentWritten()
    {
        return _contentLength>=0 && _contentWritten>=_contentLength;
    }

    /* ------------------------------------------------------------ */
    public abstract void completeHeader(HttpFields fields, boolean allContentAdded) throws IOException;

    /* ------------------------------------------------------------ */
    /**
     * Complete the message.
     *
     * @throws IOException
     */
    public void complete() throws IOException
    {
        if (_state == STATE_HEADER)
        {
            throw new IllegalStateException("State==HEADER");
        }

        if (_contentLength >= 0 && _contentLength != _contentWritten && !_head)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ContentLength written=="+_contentWritten+" != contentLength=="+_contentLength);
            _persistent = false;
        }
    }

    /* ------------------------------------------------------------ */
    public abstract int flushBuffer() throws IOException;


    /* ------------------------------------------------------------ */
    public void flush(long maxIdleTime) throws IOException
    {
        // block until everything is flushed
        long now=System.currentTimeMillis();
        long end=now+maxIdleTime;
        Buffer content = _content;
        Buffer buffer = _buffer;
        if (content!=null && content.length()>0 || buffer!=null && buffer.length()>0 || isBufferFull())
        {
            flushBuffer();

            while (now<end && (content!=null && content.length()>0 ||buffer!=null && buffer.length()>0) && _endp.isOpen()&& !_endp.isOutputShutdown())
            {
                blockForOutput(end-now);
                now=System.currentTimeMillis();
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Utility method to send an error response. If the builder is not committed, this call is
     * equivalent to a setResponse, addContent and complete call.
     *
     * @param code The error code
     * @param reason The error reason
     * @param content Contents of the error page
     * @param close True if the connection should be closed
     * @throws IOException if there is a problem flushing the response
     */
    public void sendError(int code, String reason, String content, boolean close) throws IOException
    {
        if (close)
            _persistent=false;
        if (isCommitted())
        {
            LOG.debug("sendError on committed: {} {}",code,reason);
        }
        else
        {
            LOG.debug("sendError: {} {}",code,reason);
            setResponse(code, reason);
            if (content != null)
            {
                completeHeader(null, false);
                addContent(new View(new ByteArrayBuffer(content)), Generator.LAST);
            }
            else if (code>=400)
            {
                completeHeader(null, false);
                addContent(new View(new ByteArrayBuffer("Error: "+(reason==null?(""+code):reason))), Generator.LAST);
            }
            else
            {
                completeHeader(null, true);
            }
            complete();
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the contentWritten.
     */
    public long getContentWritten()
    {
        return _contentWritten;
    }



    /* ------------------------------------------------------------ */
    public void  blockForOutput(long maxIdleTime) throws IOException
    {
        if (_endp.isBlocking())
        {
            try
            {
                flushBuffer();
            }
            catch(IOException e)
            {
                _endp.close();
                throw e;
            }
        }
        else
        {
            if (!_endp.blockWritable(maxIdleTime))
            {
                _endp.close();
                throw new EofException("timeout");
            }

            flushBuffer();
        }
    }

}
