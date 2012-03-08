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

import java.nio.ByteBuffer;

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

    enum Action { FLUSH, COMPLETE, PREPARE };
    enum State { START, COMMITTING, COMMITTING_COMPLETING, COMMITTED, COMPLETING, END };
    enum Result { NEED_CHUNK,NEED_HEADER,NEED_BUFFER,FLUSH,FLUSH_CONTENT,OK,SHUTDOWN_OUT};

    public static final byte[] NO_BYTES = {};

    // data

    private State _state = State.START;

    private int _status = 0;

    private final HttpFields _fields;
    private HttpVersion _version = HttpVersion.HTTP_1_1;
    private  byte[] _reason;
    private  byte[] _method;
    private  byte[] _uri;

    private int _largeContent=4096;
    private long _contentPrepared = 0;
    private long _contentLength = HttpTokens.UNKNOWN_CONTENT;
    private boolean _head = false;
    private boolean _noContent = false;
    private Boolean _persistent = null;


    private ByteBuffer _date;

    private boolean _sendServerVersion;



    // common _content
    private static final byte[] LAST_CHUNK =    { (byte) '0', (byte) '\015', (byte) '\012', (byte) '\015', (byte) '\012'};
    private static final byte[] CONTENT_LENGTH_0 = StringUtil.getBytes("Content-Length: 0\015\012");
    private static final byte[] CONNECTION_KEEP_ALIVE = StringUtil.getBytes("Connection: keep-alive\015\012");
    private static final byte[] CONNECTION_CLOSE = StringUtil.getBytes("Connection: close\015\012");
    private static final byte[] CONNECTION_ = StringUtil.getBytes("Connection: ");
    private static final byte[] HTTP_1_1_SPACE = StringUtil.getBytes(HttpVersion.HTTP_1_1+" ");
    private static final byte[] CRLF = StringUtil.getBytes("\015\012");
    private static final byte[] TRANSFER_ENCODING_CHUNKED = StringUtil.getBytes("Transfer-Encoding: chunked\015\012");
    private static byte[] SERVER = StringUtil.getBytes("Server: Jetty(7.0.x)\015\012");

    // other statics
    public static final int CHUNK_SIZE = 12;

    public static void setServerVersion(String version)
    {
        SERVER=StringUtil.getBytes("Server: Jetty("+version+")\015\012");
    }

    // data
    private boolean _needCRLF = false;

    /* ------------------------------------------------------------------------------- */
    public HttpGenerator(HttpFields fields)
    {
        _fields=fields;
    }

    /* ------------------------------------------------------------------------------- */
    public void reset()
    {
        _state = State.START;
        _status = 0;
        _version = HttpVersion.HTTP_1_1;
        _reason = null;
        _head = false;
        _noContent=false;
        _persistent = null;
        _contentPrepared = 0;
        _contentLength = HttpTokens.UNKNOWN_CONTENT;
        _date = null;

        _method=null;

        _needCRLF = false;
        _uri=null;
        _noContent=false;
        _fields.clear();
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
        return _state == State.START && _method==null && _status==0;
    }

    /* ------------------------------------------------------------ */
    public boolean isCommitted()
    {
        return _state.ordinal() >= State.COMMITTED.ordinal();
    }

    /* ------------------------------------------------------------ */
    public boolean isChunking()
    {
        return _contentLength==HttpTokens.CHUNKED_CONTENT;
    }

    /* ------------------------------------------------------------ */
    public int getLargeContent()
    {
        return _largeContent;
    }

    /* ------------------------------------------------------------ */
    public void setLargeContent(int largeContent)
    {
        _largeContent = largeContent;
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
        if (_state != State.START)
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
        if (_state != State.START)
            throw new IllegalStateException("STATE!=START "+_state);
        _method=StringUtil.getBytes(method);
        _uri=StringUtil.getUtf8Bytes(uri);
    }

    /* ------------------------------------------------------------ */
    /**
     */
    public void setRequest(HttpMethod method, String uri,HttpVersion version)
    {
        if (_state != State.START)
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
        if (_state != State.START)
            throw new IllegalStateException("STATE!=START");
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
        return _contentPrepared>0;
    }

    /* ------------------------------------------------------------ */
    public boolean isAllContentWritten()
    {
        return _contentLength>=0 && _contentPrepared>=_contentLength;
    }


    /* ------------------------------------------------------------ */
    public long getContentWritten()
    {
        return _contentPrepared;
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
    public Result generate(ByteBuffer header, ByteBuffer chunk, ByteBuffer buffer, ByteBuffer content, Action action)
    {
        Result result = Result.OK;
        if (_state==State.END)
            return result;
        if (action==null)
            action=Action.PREPARE;

        // Do we have content to handle
        if (BufferUtil.hasContent(content))
        {
            // Do we have too much content?
            if (_contentLength>0 && content.remaining()>(_contentLength-_contentPrepared))
            {
                LOG.warn("Content truncated at {}",new Throwable());
                content.limit(content.position()+(int)(_contentLength-_contentPrepared));
            }

            // Can we do a direct flush
            if (BufferUtil.isEmpty(buffer) && content.remaining()>_largeContent)
            {
                if (isCommitted())
                {
                    if (isChunking())
                    {
                        if (chunk==null)
                            return Result.NEED_CHUNK;
                        BufferUtil.clearToFill(chunk);
                        prepareChunk(chunk,content.remaining());
                        BufferUtil.flipToFlush(chunk,0);
                    }
                    _contentPrepared+=content.remaining();
                    return Result.FLUSH_CONTENT;
                }

                _state=action==Action.COMPLETE?State.COMMITTING_COMPLETING:State.COMMITTING;
                result=Result.FLUSH_CONTENT;
            }
            else
            {
                // we copy content to buffer
                // if we don't have one, we need one
                if (buffer==null)
                    return Result.NEED_BUFFER;

                // Copy the content
                _contentPrepared+=BufferUtil.flipPutFlip(content,buffer);

                // are we full?
                if (BufferUtil.isFull(buffer))
                {
                    if (isCommitted())
                    {
                        if (isChunking())
                        {
                            if (chunk==null)
                                return Result.NEED_CHUNK;
                            BufferUtil.clearToFill(chunk);
                            prepareChunk(chunk,buffer.remaining());
                            BufferUtil.flipToFlush(chunk,0);
                        }
                        return Result.FLUSH;
                    }
                    _state=action==Action.COMPLETE?State.COMMITTING_COMPLETING:State.COMMITTING;
                    result=Result.FLUSH;
                }
            }
        }

        // Handle the actions
        if (result==Result.OK)
        {
            switch(action)
            {
                case COMPLETE:
                    if (!isCommitted())
                        _state=State.COMMITTING_COMPLETING;
                    else if (_state==State.COMMITTED)
                        _state=State.COMPLETING;
                    result=BufferUtil.hasContent(buffer)?Result.FLUSH:Result.OK;
                    break;
                case FLUSH:
                    if (!isCommitted())
                        _state=State.COMMITTING;
                    result=BufferUtil.hasContent(buffer)?Result.FLUSH:Result.OK;
                    break;
            }
        }

        // flip header if we have one
        final int pos=header==null?-1:BufferUtil.flipToFill(header);
        try
        {
            // handle by state
            switch (_state)
            {
                case START:
                    return Result.OK;

                case COMMITTING:
                case COMMITTING_COMPLETING:
                {
                    if (isRequest())
                    {
                        if (header==null || header.capacity()<=CHUNK_SIZE)
                            return Result.NEED_HEADER;

                        if(_version==HttpVersion.HTTP_0_9)
                        {
                            _noContent=true;
                            generateRequestLine(header);
                            _state = State.END;
                            return Result.OK;
                        }
                        _persistent=true;
                        generateRequestLine(header);
                    }
                    else
                    {
                        // Responses

                        // Do we need a response header?
                        if (_version == HttpVersion.HTTP_0_9)
                        {
                            _persistent = false;
                            _contentLength = HttpTokens.EOF_CONTENT;
                            _state = State.COMMITTED;
                            if (result==Result.FLUSH_CONTENT)
                                _contentPrepared+=content.remaining();
                            return result;
                        }

                        // yes we need a response header
                        if (header==null || header.capacity()<=CHUNK_SIZE)
                            return Result.NEED_HEADER;

                        // Are we persistent by default?
                        if (_persistent==null)
                            _persistent=(_version.ordinal() > HttpVersion.HTTP_1_0.ordinal());

                        generateResponseLine(header);

                        // Handle 1xx
                        if (_status>=100 && _status<200 )
                        {
                            _noContent=true;

                            if (_status!=101 )
                            {
                                header.put(HttpTokens.CRLF);
                                _state = State.START;
                                return Result.OK;
                            }
                        }
                        else if (_status==204 || _status==304)
                        {
                            _noContent=true;
                        }
                    }

                    boolean completing=action==Action.COMPLETE||_state==State.COMMITTING_COMPLETING;
                    generateHeaders(header,content,completing);
                    _state = completing?State.COMPLETING:State.COMMITTED;

                    // handle result
                    switch(result)
                    {
                        case FLUSH:
                            if (isChunking())
                                prepareChunk(header,buffer.remaining());
                            break;
                        case FLUSH_CONTENT:
                            if (isChunking())
                                prepareChunk(header,content.remaining());
                            _contentPrepared+=content.remaining();
                            break;
                        case OK:
                            if (BufferUtil.hasContent(buffer))
                            {
                                if (isChunking())
                                    prepareChunk(header,buffer.remaining());
                            }
                            result=Result.FLUSH;
                    }

                    return result;
                }


                case COMMITTED:
                    return Result.OK;


                case COMPLETING:
                    // handle content with commit

                    if (isChunking())
                    {
                        if (chunk==null)
                            return Result.NEED_CHUNK;
                        BufferUtil.clearToFill(chunk);

                        switch(result)
                        {
                            case FLUSH:
                                prepareChunk(chunk,buffer.remaining());
                                break;
                            case FLUSH_CONTENT:
                                prepareChunk(chunk,content.remaining());
                            case OK:
                                if (BufferUtil.hasContent(buffer))
                                {
                                    result=Result.FLUSH;
                                    prepareChunk(chunk,buffer.remaining());
                                }
                                else
                                {
                                    result=Result.FLUSH;
                                    _state=State.END;
                                    prepareChunk(chunk,0);
                                }
                        }
                        BufferUtil.flipToFlush(chunk,0);
                    }
                    else if (result==Result.OK)
                    {
                        if (BufferUtil.hasContent(buffer))
                            result=Result.FLUSH;
                        else
                        {
                            if (!_persistent)
                                result=Result.SHUTDOWN_OUT;
                            _state=State.END;
                        }
                    }

                    return result;

                case END:
                    if (!_persistent)
                        result=Result.SHUTDOWN_OUT;
                    return Result.OK;

                default:
                    throw new IllegalStateException();
            }
        }
        finally
        {
            if (pos>=0)
                BufferUtil.flipToFlush(header,pos);
        }
    }

    /* ------------------------------------------------------------ */
    private void prepareChunk(ByteBuffer chunk, int remaining)
    {
        // if we need CRLF add this to header
        if (_needCRLF)
            BufferUtil.putCRLF(chunk);

        // Add the chunk size to the header
        if (remaining>0)
        {
            BufferUtil.putHexInt(chunk, remaining);
            BufferUtil.putCRLF(chunk);
            _needCRLF=true;
        }
        else
        {
            chunk.put(LAST_CHUNK);
            _needCRLF=false;
        }
    }


    /* ------------------------------------------------------------ */
    private void generateRequestLine(ByteBuffer header)
    {
        header.put(_method);
        header.put((byte)' ');
        header.put(_uri);
        switch(_version)
        {
            case HTTP_1_0:
            case HTTP_1_1:
                header.put((byte)' ');
                header.put(_version.toBytes());
        }
        header.put(HttpTokens.CRLF);
    }

    /* ------------------------------------------------------------ */
    private void generateResponseLine(ByteBuffer header)
    {
        // Look for prepared response line
        Status status = _status<__status.length?__status[_status]:null;
        if (status!=null)
        {
            if (_reason==null)
                header.put(status._responseLine);
            else
            {
                header.put(status._schemeCode);
                header.put(_reason);
                header.put(HttpTokens.CRLF);
            }
        }
        else // generate response line
        {
            header.put(HTTP_1_1_SPACE);
            header.put((byte) ('0' + _status / 100));
            header.put((byte) ('0' + (_status % 100) / 10));
            header.put((byte) ('0' + (_status % 10)));
            header.put((byte) ' ');
            if (_reason==null)
            {
                header.put((byte) ('0' + _status / 100));
                header.put((byte) ('0' + (_status % 100) / 10));
                header.put((byte) ('0' + (_status % 10)));
            }
            else
                header.put(_reason);
            header.put(HttpTokens.CRLF);
        }
    }

    /* ------------------------------------------------------------ */
    private void generateHeaders(ByteBuffer header,ByteBuffer content,boolean last)
    {

        // Add Date header
        if (_status>=200 && _date!=null)
        {
            header.put(HttpHeader.DATE.toBytesColonSpace());
            header.put(_date);
            header.put(CRLF);
        }

        // default field values
        boolean has_server = false;
        HttpFields.Field transfer_encoding=null;
        boolean keep_alive=false;
        boolean close=false;
        boolean content_type=false;
        boolean content_length=false;
        StringBuilder connection = null;

        // Generate fields
        if (_fields != null)
        {
            for (HttpFields.Field field : _fields)
            {
                HttpHeader name = HttpHeader.CACHE.get(field.getName());

                switch (name==null?HttpHeader.UNKNOWN:name)
                {
                    case CONTENT_LENGTH:
                    {
                        long length = field.getLongValue();
                        if (length>=0)
                        {
                            long prepared=_contentPrepared+BufferUtil.length(content);
                            if (length < prepared || last && length != prepared)
                            {
                                LOG.warn("Incorrect ContentLength: "+length+"!="+prepared);
                                if (LOG.isDebugEnabled())
                                    LOG.debug(new Throwable());
                                _contentLength=HttpTokens.UNKNOWN_CONTENT;
                            }
                            else
                            {
                                // write the field to the header
                                header.put(HttpHeader.CONTENT_LENGTH.toBytesColonSpace());
                                BufferUtil.putDecLong(header,length);
                                BufferUtil.putCRLF(header);
                                _contentLength=length;
                                content_length=true;
                            }
                        }
                        break;
                    }

                    case CONTENT_TYPE:
                    {
                        if (field.getValue().startsWith(MimeTypes.Type.MULTIPART_BYTERANGES.toString()))
                            _contentLength = HttpTokens.SELF_DEFINING_CONTENT;

                        // write the field to the header
                        content_type=true;
                        field.putTo(header);
                        break;
                    }

                    case TRANSFER_ENCODING:
                    {
                        if (_version == HttpVersion.HTTP_1_1)
                            transfer_encoding = field;
                        // Do NOT add yet!
                        break;
                    }

                    case CONNECTION:
                    {
                        if (isRequest())
                            field.putTo(header);

                        // Lookup and/or split connection value field
                        HttpHeaderValue[] values = new HttpHeaderValue[]{HttpHeaderValue.CACHE.get(field.getValue())};
                        String[] split = null;

                        if (values[0]==null)
                        {
                            split = field.getValue().split("\\s*,\\s*");
                            if (split.length>0)
                            {
                                values=new HttpHeaderValue[split.length];
                                for (int i=0;i<split.length;i++)
                                    values[i]=HttpHeaderValue.CACHE.get(split[i]);
                            }
                        }

                        // Handle connection values
                        for (int i=0;i<values.length;i++)
                        {
                            HttpHeaderValue value=values[i];
                            switch (value==null?HttpHeaderValue.UNKNOWN:value)
                            {
                                case UPGRADE:
                                {
                                    // special case for websocket connection ordering
                                    header.put(HttpHeader.CONNECTION.toBytesColonSpace()).put(HttpHeader.UPGRADE.toBytes());
                                    break;
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
                                    if (connection==null)
                                        connection=new StringBuilder();
                                    else
                                        connection.append(',');
                                    connection.append(split==null?field.getValue():split[i]);
                                }
                            }

                        }

                        // Do NOT add yet!
                        break;
                    }

                    case SERVER:
                    {
                        if (getSendServerVersion())
                        {
                            has_server=true;
                            field.putTo(header);
                        }
                        break;
                    }

                    default:
                        if (name==null)
                            field.putTo(header);
                        else
                        {
                            header.put(name.toBytesColonSpace());
                            field.putValueTo(header);
                            header.put(CRLF);
                        }

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
                if (_contentPrepared == 0 && isResponse() && (_status < 200 || _status == 204 || _status == 304))
                    _contentLength = HttpTokens.NO_CONTENT;
                else if (last)
                {
                    // we have seen all the _content there is
                    _contentLength = _contentPrepared+BufferUtil.length(content);
                    if (!content_length && (isResponse() || _contentLength>0 || content_type ) && !_noContent)
                    {
                        // known length but not actually set.
                        header.put(HttpHeader.CONTENT_LENGTH.toBytesColonSpace());
                        BufferUtil.putDecLong(header, _contentLength);
                        header.put(HttpTokens.CRLF);
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
                if (!content_length && isResponse() && _status >= 200 && _status != 204 && _status != 304)
                    header.put(CONTENT_LENGTH_0);
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
        if (isChunking())
        {
            // try to use user supplied encoding as it may have other values.
            if (transfer_encoding != null && !HttpHeaderValue.CHUNKED.toString().equalsIgnoreCase(transfer_encoding.getValue()))
            {
                String c = transfer_encoding.getValue();
                if (c.endsWith(HttpHeaderValue.CHUNKED.toString()))
                    transfer_encoding.putTo(header);
                else
                    throw new IllegalArgumentException("BAD TE");
            }
            else
                header.put(TRANSFER_ENCODING_CHUNKED);
        }

        // Handle connection if need be
        if (_contentLength==HttpTokens.EOF_CONTENT)
        {
            keep_alive=false;
            _persistent=false;
        }

        // If this is a response, work out persistence
        if (isResponse())
        {
            if (!_persistent && (close || _version.ordinal() > HttpVersion.HTTP_1_0.ordinal()))
            {
                if (connection==null)
                    header.put(CONNECTION_CLOSE);
                else
                {
                    header.put(CONNECTION_CLOSE,0,CONNECTION_CLOSE.length-2);
                    header.put((byte)',');
                    header.put(StringUtil.getBytes(connection.toString()));
                    header.put(CRLF);
                }
            }
            else if (keep_alive)
            {
                if (connection==null)
                    header.put(CONNECTION_KEEP_ALIVE);
                else
                {
                    header.put(CONNECTION_KEEP_ALIVE,0,CONNECTION_CLOSE.length-2);
                    header.put((byte)',');
                    header.put(StringUtil.getBytes(connection.toString()));
                    header.put(CRLF);
                }
            }
            else if (connection!=null)
            {
                header.put(CONNECTION_);
                header.put(StringUtil.getBytes(connection.toString()));
                header.put(CRLF);
            }
        }

        if (!has_server && _status>199 && getSendServerVersion())
            header.put(SERVER);

        // end the header.
        header.put(HttpTokens.CRLF);

    }


    /* ------------------------------------------------------------------------------- */
    public static byte[] getReasonBuffer(int code)
    {
        Status status = code<__status.length?__status[code]:null;
        if (status!=null)
            return status._reason;
        return null;
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public String toString()
    {
        return String.format("%s{s=%s}",
                getClass().getSimpleName(),
                _state);
    }
}
