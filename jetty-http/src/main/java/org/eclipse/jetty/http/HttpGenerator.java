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

package org.eclipse.jetty.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;

import javax.swing.text.View;

import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/**
 * HttpGenerator. Builds HTTP Messages.
 *
 *
 *
 */
public class HttpGenerator 
{

    // Build cache of response lines for status
    private static class Status
    {
        byte[] _reason;
        byte[] _schemeCode;
        byte[] _responseLine;
    }
    private static final Status[] __status = new Status[HttpStatus.MAX_CODE+1];
    static
    {
        int versionLength=HttpVersion.HTTP_1_1.toString().length();

        for (int i=0;i<__status.length;i++)
        {
            HttpStatus.Code code = HttpStatus.getCode(i);
            if (code==null)
                continue;
            String reason=code.getMessage();
            byte[] line=new byte[versionLength+5+reason.length()+2];
            HttpVersion.HTTP_1_1.toBuffer().get(line,0,versionLength);
            line[versionLength+0]=' ';
            line[versionLength+1]=(byte)('0'+i/100);
            line[versionLength+2]=(byte)('0'+(i%100)/10);
            line[versionLength+3]=(byte)('0'+(i%10));
            line[versionLength+4]=' ';
            for (int j=0;j<reason.length();j++)
                line[versionLength+5+j]=(byte)reason.charAt(j);
            line[versionLength+5+reason.length()]=HttpTokens.CARRIAGE_RETURN;
            line[versionLength+6+reason.length()]=HttpTokens.LINE_FEED;

            __status[i] = new Status();
            __status[i]._reason=new byte[line.length-versionLength-7] ;
            System.arraycopy(line,versionLength+5,__status[i]._reason,0,line.length-versionLength-7);
            __status[i]._schemeCode=new byte[versionLength+5];
            System.arraycopy(line,0,__status[i]._schemeCode,0,versionLength+5);
            __status[i]._responseLine=line;
        }
    }


    private static final Logger LOG = Log.getLogger(HttpGenerator.class);

    // states
    
    enum State { HEADER, CONTENT, FLUSHING, END };

    public static final byte[] NO_BYTES = {};

    // data

    protected State _state = State.HEADER;

    protected int _status = 0;
    protected HttpVersion _version = HttpVersion.HTTP_1_1;
    protected  byte[] _reason;
    protected  byte[] _method;
    protected  byte[] _uri;

    protected long _contentWritten = 0;
    protected long _contentLength = HttpTokens.UNKNOWN_CONTENT;
    protected boolean _last = false;
    protected boolean _head = false;
    protected boolean _noContent = false;
    protected Boolean _persistent = null;


    protected ByteBuffer _date;

    private boolean _sendServerVersion;
    


    // common _content
    private static final byte[] LAST_CHUNK =
    { (byte) '0', (byte) '\015', (byte) '\012', (byte) '\015', (byte) '\012'};
    private static final byte[] CONTENT_LENGTH_0 = StringUtil.getBytes("Content-Length: 0\015\012");
    private static final byte[] CONNECTION_KEEP_ALIVE = StringUtil.getBytes("Connection: keep-alive\015\012");
    private static final byte[] CONNECTION_CLOSE = StringUtil.getBytes("Connection: close\015\012");
    private static final byte[] CONNECTION_ = StringUtil.getBytes("Connection: ");
    private static final byte[] HTTP_1_1_SPACE = StringUtil.getBytes(HttpVersion.HTTP_1_1+" ");
    private static final byte[] CRLF = StringUtil.getBytes("\015\012");
    private static final byte[] TRANSFER_ENCODING_CHUNKED = StringUtil.getBytes("Transfer-Encoding: chunked\015\012");
    private static byte[] SERVER = StringUtil.getBytes("Server: Jetty(7.0.x)\015\012");

    // other statics
    private static final int CHUNK_SPACE = 12;

    public static void setServerVersion(String version)
    {
        SERVER=StringUtil.getBytes("Server: Jetty("+version+")\015\012");
    }

    // data
    private boolean _bypass = false; // True if _content buffer can be written directly to endp and bypass the content buffer
    private boolean _needCRLF = false;
    private boolean _needEOC = false;
    private boolean _bufferChunked = false;


    
    
    
    /* ------------------------------------------------------------------------------- */
    public void reset()
    {
        _state = State.HEADER;
        _status = 0;
        _version = HttpVersion.HTTP_1_1;
        _reason = null;
        _last = false;
        _head = false;
        _noContent=false;
        _persistent = null;
        _contentWritten = 0;
        _contentLength = HttpTokens.UNKNOWN_CONTENT;
        _date = null;

        _method=null;
    
        _needCRLF = false;
        _needEOC = false;
        _bufferChunked=false;
        _uri=null;
        _noContent=false;
    }
    
    /* ------------------------------------------------------------------------------- */
    public void resetBuffer()
    {
        if(_state.ordinal()>=State.FLUSHING.ordinal())
            throw new IllegalStateException("Flushed");

        _last = false;
        _persistent=null;
        _contentWritten = 0;
        _contentLength = HttpTokens.UNKNOWN_CONTENT;
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
    public State getState()
    {
        return _state;
    }

    /* ------------------------------------------------------------ */
    public boolean isState(State state)
    {
        return _state == state;
    }

    /* ------------------------------------------------------------ */
    public boolean isComplete()
    {
        return _state == State.END;
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return _state == State.HEADER && _method==null && _status==0;
    }

    /* ------------------------------------------------------------ */
    public boolean isCommitted()
    {
        return _state != State.HEADER;
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
        :(isRequest()?true:_version.ordinal()>HttpVersion.HTTP_1_0.ordinal());
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
    public void setVersion(HttpVersion version)
    {
        if (_state != State.HEADER)
            throw new IllegalStateException("STATE!=START "+_state);
        _version = version;
        if (_version==HttpVersion.HTTP_0_9 && _method!=null)
            _noContent=true;
    }

    /* ------------------------------------------------------------ */
    public HttpVersion getVersion()
    {
        return _version;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.http.Generator#setDate(org.eclipse.jetty.io.ByteBuffer)
     */
    public void setDate(ByteBuffer timeStampBuffer)
    {
        _date=timeStampBuffer;
    }

    /* ------------------------------------------------------------ */
    /**
     */
    public void setRequest(String method, String uri)
    {
        if (_state != State.HEADER)
            throw new IllegalStateException("STATE!=START "+_state);
        _method=StringUtil.getBytes(method);
        _uri=StringUtil.getUtf8Bytes(uri);
    }
    
    /* ------------------------------------------------------------ */
    /**
     */
    public void setRequest(HttpMethod method, String uri,HttpVersion version)
    {
        if (_state != State.HEADER)
            throw new IllegalStateException("STATE!=START "+_state);
        _method=method.toBytes();
        _uri=StringUtil.getUtf8Bytes(uri);
        setVersion(version);
    }
    

    /* ------------------------------------------------------------ */
    /**
     * @param status The status code to send.
     * @param reason the status message to send.
     */
    public void setResponse(int status, String reason)
    {
        if (_state != State.HEADER) throw new IllegalStateException("STATE!=START");
        _method=null;
        _status = status;
        if (reason==null)
            _reason=null;
        else
        {
            if (reason.length()>1024)
                reason=reason.substring(0,1024);
            _reason=StringUtil.getBytes(reason);
            for (int i=_reason.length;i-->0;)
                if (_reason[i]=='\r' || _reason[i]=='\n')
                    _reason[i]='?';
        }
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
    /**
     * Complete the message.
     *
     * @throws IOException
     */
    public void complete() throws IOException
    {
        if (_state == State.END)
            return;

        if (_state == State.HEADER)
        {
            throw new IllegalStateException("State==HEADER");
        }

        if (_contentLength >= 0 && _contentLength != _contentWritten && !_head)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ContentLength written=="+_contentWritten+" != contentLength=="+_contentLength);
            _persistent = false;
        }

        if (_state.ordinal() < State.FLUSHING.ordinal())
        {
            _state = State.FLUSHING;
            if (_contentLength == HttpTokens.CHUNKED_CONTENT)
                _needEOC = true;
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
                addContent(new View(BufferUtil.allocate(content)), Generator.LAST);
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
   

    /* ------------------------------------------------------------------------------- */
    public static byte[] getReasonBuffer(int code)
    {
        Status status = code<__status.length?__status[code]:null;
        if (status!=null)
            return status._reason;
        return null;
    }


    /* ------------------------------------------------------------ */
    /**
     * Add content.
     *
     * @param content
     * @param last
     * @throws IllegalArgumentException if <code>content</code> is {@link ByteBuffer#isImmutable immutable}.
     * @throws IllegalStateException If the request is not expecting any more content,
     *   or if the buffers are full and cannot be flushed.
     * @throws IOException if there is a problem flushing the buffers.
     */
    public void addContent(ByteBuffer content, boolean last) throws IOException
    {
        if (_noContent)
            throw new IllegalStateException("NO CONTENT");

        if (_last || _state==State.END)
        {
            LOG.warn("Ignoring extra content {}",content);
            content.clear();
            return;
        }
        _last = last;

        // Handle any unfinished business?
        if (_content!=null && _content.hasRemaining() || _bufferChunked)
        {
            if (_endp.isOutputShutdown())
                throw new EofException();
            flushBuffer();
            if (_content != null && _content.hasRemaining())
            {
                ByteBuffer nc=_buffers.getBuffer(_content.remaining()+content.remaining());
                nc.put(_content);
                nc.put(content);
                content=nc;
            }
        }

        _content = content;
        _contentWritten += content.remaining();

        // Handle the _content
        if (_head)
        {
            content.clear();
            _content=null;
        }
        else if (_endp != null && (_buffer==null || _buffer.remaining()==0) && _content.remaining() > 0 && (_last || isCommitted() && _content.remaining()>1024))
        {
            _bypass = true;
        }
        else if (!_bufferChunked)
        {
            // Yes - so we better check we have a buffer
            if (_buffer == null)
                _buffer = _buffers.getBuffer();

            // Copy _content to buffer;
            int len=_buffer.put(_content);
            _content.skip(len);
            if (_content.remaining() == 0)
                _content = null;
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * send complete response.
     *
     * @param response
     */
    public void sendResponse(ByteBuffer response) throws IOException
    {
        if (_noContent || _state!=State.HEADER || _content!=null && _content.hasRemaining() || _bufferChunked || _head )
            throw new IllegalStateException();

        _last = true;

        _content = response;
        _bypass = true;
        _state = State.FLUSHING;

        // TODO this is not exactly right, but should do.
        _contentLength =_contentWritten = response.length();

    }

    /* ------------------------------------------------------------ */
    /**
     * Add content.
     *
     * @param b byte
     * @return true if the buffers are full
     * @throws IOException
     */
    public boolean addContent(byte b) throws IOException
    {
        if (_noContent)
            throw new IllegalStateException("NO CONTENT");

        if (_last || _state==State.END)
        {
            LOG.warn("Ignoring extra content {}",Byte.valueOf(b));
            return false;
        }

        // Handle any unfinished business?
        if (_content != null && _content.hasRemaining() || _bufferChunked)
        {
            flushBuffer();
            if (_content != null && _content.hasRemaining() || _bufferChunked)
                throw new IllegalStateException("FULL");
        }

        _contentWritten++;

        // Handle the _content
        if (_head)
            return false;

        // we better check we have a buffer
        if (_buffer == null)
            _buffer = _buffers.getBuffer();

        // Copy _content to buffer;
        _buffer.put(b);

        return _buffer.space()<=(_contentLength == HttpTokens.CHUNKED_CONTENT?CHUNK_SPACE:0);
    }

    /* ------------------------------------------------------------ */
    public void send1xx(int code) throws IOException
    {
        if (_state != State.HEADER)
            return;

        if (code<100||code>199)
            throw new IllegalArgumentException("!1xx");
        Status status=__status[code];
        if (status==null)
            throw new IllegalArgumentException(code+"?");

        // get a header buffer
        if (_header == null)
            _header = _buffers.getHeader();

        _header.put(status._responseLine);
        _header.put(HttpTokens.CRLF);

        try
        {
            // nasty semi busy flush!
            while(_header.remaining()>0)
            {
                int len = _endp.flush(_header);
                if (len<0)
                    throw new EofException();
                if (len==0)
                    Thread.sleep(100);
            }
        }
        catch(InterruptedException e)
        {
            LOG.debug(e);
            throw new InterruptedIOException(e.toString());
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isRequest()
    {
        return _method!=null;
    }

    /* ------------------------------------------------------------ */
    public boolean isResponse()
    {
        return _method==null;
    }

    /* ------------------------------------------------------------ */
    public void completeHeader(ByteBuffer buffer, HttpFields fields, boolean allContentAdded) throws IOException
    {
        if (_state != State.HEADER)
            return;

        // handle a reset
        if (isResponse() && _status==0)
            throw new EofException();

        if (_last && !allContentAdded)
            throw new IllegalStateException("last?");
        _last = _last | allContentAdded;


        if (isRequest() && _version==HttpVersion.HTTP_0_9)
            _noContent=true;
        
        boolean has_server = false;

        try
        {
            if (isRequest())
            {
                _persistent=true;

                if (_version == HttpVersion.HTTP_0_9)
                {
                    _contentLength = HttpTokens.NO_CONTENT;
                    buffer.put(_method);
                    buffer.put((byte)' ');
                    buffer.put(_uri); 
                    buffer.put(HttpTokens.CRLF);
                    _state = State.FLUSHING;
                    _noContent=true;
                    return;
                }
                else
                {
                    buffer.put(_method);
                    buffer.put((byte)' ');
                    buffer.put(_uri); 
                    buffer.put((byte)' ');
                    buffer.put((_version==HttpVersion.HTTP_1_0?HttpVersion.HTTP_1_0:HttpVersion.HTTP_1_1).toBytes());
                    buffer.put(HttpTokens.CRLF);
                }
            }
            else
            {
                // Responses
                if (_version == HttpVersion.HTTP_0_9)
                {
                    _persistent = false;
                    _contentLength = HttpTokens.EOF_CONTENT;
                    _state = State.CONTENT;
                    return;
                }
                else
                {
                    if (_persistent==null)
                        _persistent= (_version.ordinal() > HttpVersion.HTTP_1_0.ordinal());

                    // add response line
                    Status status = _status<__status.length?__status[_status]:null;

                    if (status==null)
                    {
                        buffer.put(HTTP_1_1_SPACE);
                        buffer.put((byte) ('0' + _status / 100));
                        buffer.put((byte) ('0' + (_status % 100) / 10));
                        buffer.put((byte) ('0' + (_status % 10)));
                        buffer.put((byte) ' ');
                        if (_reason==null)
                        {
                            buffer.put((byte) ('0' + _status / 100));
                            buffer.put((byte) ('0' + (_status % 100) / 10));
                            buffer.put((byte) ('0' + (_status % 10)));
                        }
                        else
                            buffer.put(_reason);
                        buffer.put(HttpTokens.CRLF);
                    }
                    else
                    {
                        if (_reason==null)
                            buffer.put(status._responseLine);
                        else
                        {
                            buffer.put(status._schemeCode);
                            buffer.put(_reason);
                            buffer.put(HttpTokens.CRLF);
                        }
                    }

                    if (_status<200 && _status>=100 )
                    {
                        _noContent=true;

                        if (_status!=101 )
                        {
                            buffer.put(HttpTokens.CRLF);
                            _state = State.CONTENT;
                            return;
                        }
                    }
                    else if (_status==204 || _status==304)
                    {
                        _noContent=true;
                    }
                }
            }

            // Add headers
            if (_status>=200 && _date!=null)
            {
                buffer.put(HttpHeader.DATE.toBytesColonSpace());
                buffer.put(_date);
                buffer.put(CRLF);
            }

            // key field values
            HttpFields.Field content_length = null;
            HttpFields.Field transfer_encoding = null;
            boolean keep_alive = false;
            boolean close=false;
            boolean content_type=false;
            StringBuilder connection = null;

            if (fields != null)
            {
                int s=fields.size();
                for (int f=0;f<s;f++)
                {
                    HttpFields.Field field = fields.getField(f);
                    if (field==null)
                        continue;

                    HttpHeader header = HttpHeader.CACHE.get(field.getName());
                    HttpHeaderValue value = null;
                    
                    switch (header==null?HttpHeader.UNKNOWN:header)
                      {
                        case CONTENT_LENGTH:
                            content_length = field;
                            _contentLength = field.getLongValue();

                            if (_contentLength < _contentWritten || _last && _contentLength != _contentWritten)
                                content_length = null;

                            // write the field to the header buffer
                            field.putTo(buffer);
                            break;

                        case CONTENT_TYPE:
                            if (field.getValue().startsWith(MimeTypes.Type.MULTIPART_BYTERANGES.toString()))
                                _contentLength = HttpTokens.SELF_DEFINING_CONTENT;

                            // write the field to the header buffer
                            content_type=true;
                            field.putTo(buffer);
                            break;

                        case TRANSFER_ENCODING:
                            if (_version == HttpVersion.HTTP_1_1)
                                transfer_encoding = field;
                            // Do NOT add yet!
                            break;

                        case CONNECTION:
                            if (isRequest())
                                field.putTo(buffer);

                            value = HttpHeaderValue.CACHE.get(field.getValue());
                            
                            switch (value==null?HttpHeaderValue.UNKNOWN:value)
                            {
                                case UPGRADE:
                                {
                                    // special case for websocket connection ordering
                                    if (isResponse())
                                    {
                                        field.putTo(buffer);
                                        continue;
                                    }
                                }
                                case CLOSE:
                                {
                                    close=true;
                                    if (isResponse())
                                        _persistent=false;
                                    if (!_persistent && isResponse() && _contentLength == HttpTokens.UNKNOWN_CONTENT)
                                        _contentLength = HttpTokens.EOF_CONTENT;
                                    break;
                                }
                                case KEEP_ALIVE:
                                {
                                    if (_version == HttpVersion.HTTP_1_0)
                                    {
                                        keep_alive = true;
                                        if (isResponse())
                                            _persistent=true;
                                    }
                                    break;
                                }

                                default:
                                {
                                    String[] values = field.getValue().split(",");
                                    for  (int i=0;values!=null && i<values.length;i++)
                                    {
                                        HttpHeaderValue v = HttpHeaderValue.CACHE.get(field.getValue());
                                        if (v!=null)
                                        {
                                            switch(v)
                                            {
                                                case CLOSE:
                                                    close=true;
                                                    if (isResponse())
                                                        _persistent=false;
                                                    keep_alive=false;
                                                    if (!_persistent && isResponse() && _contentLength == HttpTokens.UNKNOWN_CONTENT)
                                                        _contentLength = HttpTokens.EOF_CONTENT;
                                                    break;

                                                case KEEP_ALIVE:
                                                    if (_version == HttpVersion.HTTP_1_0)
                                                    {
                                                        keep_alive = true;
                                                        if (isResponse())
                                                            _persistent = true;
                                                    }
                                                    break;

                                                default:
                                                    if (connection==null)
                                                        connection=new StringBuilder();
                                                    else
                                                        connection.append(',');
                                                    connection.append(values[i]);
                                            }
                                        }
                                        else
                                        {
                                            if (connection==null)
                                                connection=new StringBuilder();
                                            else
                                                connection.append(',');
                                            connection.append(values[i]);
                                        }
                                    }

                                    break;
                                }
                            }

                            // Do NOT add yet!
                            break;

                        case SERVER:
                            if (getSendServerVersion())
                            {
                                has_server=true;
                                field.putTo(buffer);
                            }
                            break;

                        case UNKNOWN:
                            // write the field to the header buffer
                            field.putTo(buffer);
                            break;
                            
                        default:
                            buffer.put(header.toBytesColonSpace());
                            field.putValueTo(buffer);
                            buffer.put(CRLF);
                            
                    }
                }
            }

            // Calculate how to end _content and connection, _content length and transfer encoding
            // settings.
            // From RFC 2616 4.4:
            // 1. No body for 1xx, 204, 304 & HEAD response
            // 2. Force _content-length?
            // 3. If Transfer-Encoding!=identity && HTTP/1.1 && !HttpConnection==close then chunk
            // 4. Content-Length
            // 5. multipart/byteranges
            // 6. close
            switch ((int) _contentLength)
            {
                case HttpTokens.UNKNOWN_CONTENT:
                    // It may be that we have no _content, or perhaps _content just has not been
                    // written yet?

                    // Response known not to have a body
                    if (_contentWritten == 0 && isResponse() && (_status < 200 || _status == 204 || _status == 304))
                        _contentLength = HttpTokens.NO_CONTENT;
                    else if (_last)
                    {
                        // we have seen all the _content there is
                        _contentLength = _contentWritten;
                        if (content_length == null && (isResponse() || _contentLength>0 || content_type ) && !_noContent)
                        {
                            // known length but not actually set.
                            buffer.put(HttpHeader.CONTENT_LENGTH.toBytes());
                            buffer.put(HttpTokens.COLON);
                            buffer.put((byte) ' ');
                            BufferUtil.putDecLong(buffer, _contentLength);
                            buffer.put(HttpTokens.CRLF);
                        }
                    }
                    else
                    {
                        // No idea, so we must assume that a body is coming
                        _contentLength = (!_persistent || _version.ordinal() < HttpVersion.HTTP_1_1.ordinal() ) ? HttpTokens.EOF_CONTENT : HttpTokens.CHUNKED_CONTENT;
                        if (isRequest() && _contentLength==HttpTokens.EOF_CONTENT)
                        {
                            _contentLength=HttpTokens.NO_CONTENT;
                            _noContent=true;
                        }
                    }
                    break;

                case HttpTokens.NO_CONTENT:
                    if (content_length == null && isResponse() && _status >= 200 && _status != 204 && _status != 304)
                        buffer.put(CONTENT_LENGTH_0);
                    break;

                case HttpTokens.EOF_CONTENT:
                    _persistent = isRequest();
                    break;

                case HttpTokens.CHUNKED_CONTENT:
                    break;

                default:
                    // TODO - maybe allow forced chunking by setting te ???
                    break;
            }

            // Add transfer_encoding if needed
            if (_contentLength == HttpTokens.CHUNKED_CONTENT)
            {
                // try to use user supplied encoding as it may have other values.
                if (transfer_encoding != null && !HttpHeaderValue.CHUNKED.toString().equalsIgnoreCase(transfer_encoding.getValue()))
                {
                    String c = transfer_encoding.getValue();
                    if (c.endsWith(HttpHeaderValue.CHUNKED.toString()))
                        transfer_encoding.putTo(buffer);
                    else
                        throw new IllegalArgumentException("BAD TE");
                }
                else
                    buffer.put(TRANSFER_ENCODING_CHUNKED);
            }

            // Handle connection if need be
            if (_contentLength==HttpTokens.EOF_CONTENT)
            {
                keep_alive=false;
                _persistent=false;
            }

            if (isResponse())
            {
                if (!_persistent && (close || _version.ordinal() > HttpVersion.HTTP_1_0.ordinal()))
                {
                    if (connection==null)
                        buffer.put(CONNECTION_CLOSE);
                    else
                    {
                        buffer.put(CONNECTION_CLOSE,0,CONNECTION_CLOSE.length-2);
                        buffer.put((byte)',');
                        buffer.put(connection.toString().getBytes());
                        buffer.put(CRLF);
                    }
                }
                else if (keep_alive)
                {
                    if (connection==null)
                        buffer.put(CONNECTION_KEEP_ALIVE);
                    else
                    {
                        buffer.put(CONNECTION_KEEP_ALIVE,0,CONNECTION_CLOSE.length-2);
                        buffer.put((byte)',');
                        buffer.put(connection.toString().getBytes());
                        buffer.put(CRLF);
                    }
                }
                else if (connection!=null)
                {
                    buffer.put(CONNECTION_);
                    buffer.put(connection.toString().getBytes());
                    buffer.put(CRLF);
                }
            }

            if (!has_server && _status>199 && getSendServerVersion())
                buffer.put(SERVER);

            // end the header.
            buffer.put(HttpTokens.CRLF);
            _state = State.CONTENT;

        }
        catch(ArrayIndexOutOfBoundsException e)
        {
            throw new RuntimeException("Header>"+buffer.capacity(),e);
        }
    }
    /* ------------------------------------------------------------ */
    private void prepareBuffers()
    {
        // if we are not flushing an existing chunk
        if (!_bufferChunked)
        {
            // Refill buffer if possible
            if (!_bypass && _content != null && _content.remaining() > 0 && _buffer != null && _buffer.space() > 0)
            {
                int len = _buffer.put(_content);
                _content.skip(len);
                if (_content.remaining() == 0)
                    _content = null;
            }

            // Chunk buffer if need be
            if (_contentLength == HttpTokens.CHUNKED_CONTENT)
            {
                if (_bypass && (_buffer==null||_buffer.remaining()==0) && _content!=null)
                {
                    // this is a bypass write
                    int size = _content.remaining();
                    _bufferChunked = true;

                    if (_header == null)
                        _header = _buffers.getHeader();

                    // if we need CRLF add this to header
                    if (_needCRLF)
                    {
                        if (_header.remaining() > 0) throw new IllegalStateException("EOC");
                        _header.put(HttpTokens.CRLF);
                        _needCRLF = false;
                    }
                    // Add the chunk size to the header
                    BufferUtil.putHexInt(_header, size);
                    _header.put(HttpTokens.CRLF);

                    // Need a CRLF after the content
                    _needCRLF=true;
                }
                else if (_buffer!=null)
                {
                    int size = _buffer.remaining();
                    if (size > 0)
                    {
                        // Prepare a chunk!
                        _bufferChunked = true;

                        // Did we leave space at the start of the buffer.
                        //noinspection ConstantConditions
                        if (_buffer.getIndex() == CHUNK_SPACE)
                        {
                            // Oh yes, goodie! let's use it then!
                            _buffer.poke(_buffer.getIndex() - 2, HttpTokens.CRLF, 0, 2);
                            _buffer.setGetIndex(_buffer.getIndex() - 2);
                            BufferUtil.prependHexInt(_buffer, size);

                            if (_needCRLF)
                            {
                                _buffer.poke(_buffer.getIndex() - 2, HttpTokens.CRLF, 0, 2);
                                _buffer.setGetIndex(_buffer.getIndex() - 2);
                                _needCRLF = false;
                            }
                        }
                        else
                        {
                            // No space so lets use a header buffer.
                            if (_header == null)
                                _header = _buffers.getHeader();

                            if (_needCRLF)
                            {
                                if (_header.remaining() > 0) throw new IllegalStateException("EOC");
                                _header.put(HttpTokens.CRLF);
                                _needCRLF = false;
                            }
                            BufferUtil.putHexInt(_header, size);
                            _header.put(HttpTokens.CRLF);
                        }

                        // Add end chunk trailer.
                        if (_buffer.space() >= 2)
                            _buffer.put(HttpTokens.CRLF);
                        else
                            _needCRLF = true;
                    }
                }

                // If we need EOC and everything written
                if (_needEOC && (_content == null || _content.remaining() == 0))
                {
                    if (_needCRLF)
                    {
                        if (_buffer == null && _header.space() >= 2)
                        {
                            _header.put(HttpTokens.CRLF);
                            _needCRLF = false;
                        }
                        else if (_buffer!=null && _buffer.space() >= 2)
                        {
                            _buffer.put(HttpTokens.CRLF);
                            _needCRLF = false;
                        }
                    }

                    if (!_needCRLF && _needEOC)
                    {
                        if (_buffer == null && _header.space() >= LAST_CHUNK.length)
                        {
                            if (!_head)
                            {
                                _header.put(LAST_CHUNK);
                                _bufferChunked=true;
                            }
                            _needEOC = false;
                        }
                        else if (_buffer!=null && _buffer.space() >= LAST_CHUNK.length)
                        {
                            if (!_head)
                            {
                                _buffer.put(LAST_CHUNK);
                                _bufferChunked=true;
                            }
                            _needEOC = false;
                        }
                    }
                }
            }
        }

        if (_content != null && _content.remaining() == 0)
            _content = null;

    }

    @Override
    public String toString()
    {
        return String.format("%s{s=%d}",
                getClass().getSimpleName(),
                _state);
    }
}
