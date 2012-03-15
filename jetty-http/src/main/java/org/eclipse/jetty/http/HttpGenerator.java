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
import org.eclipse.jetty.http.HttpTokens.Content;

/* ------------------------------------------------------------ */
/**
 * HttpGenerator. Builds HTTP Messages.
 *
 *
 *
 */
public class HttpGenerator
{
    private static final Logger LOG = Log.getLogger(HttpGenerator.class);

    // states

    public enum Action { FLUSH, COMPLETE, PREPARE };
    public enum State { START, COMMITTING, COMMITTING_COMPLETING, COMMITTED, COMPLETING, END };
    public enum Result { NEED_CHUNK,NEED_HEADER,NEED_BUFFER,FLUSH,FLUSH_CONTENT,OK,SHUTDOWN_OUT};
    
    // other statics
    public static final int CHUNK_SIZE = 12;
    
    public interface Info
    {
        HttpVersion getHttpVersion();
        HttpFields getHttpFields();
        long getContentLength();
    }
    
    public interface RequestInfo extends Info
    {
        String getMethod();
        String getURI();
    }
    
    public interface ResponseInfo extends Info
    {
        int getStatus();
        String getReason();
        boolean isHead();
    }
    
    // data
    private final Info _info;
    private final RequestInfo _request;
    private final ResponseInfo _response;

    private State _state = State.START;
    private Content _content = Content.UNKNOWN_CONTENT;
    
    private int _largeContent=4096;
    private long _contentPrepared = 0;
    private boolean _noContent = false;
    private Boolean _persistent = null;
    private boolean _sendServerVersion;


    /* ------------------------------------------------------------------------------- */
    public static void setServerVersion(String version)
    {
        SERVER=StringUtil.getBytes("Server: Jetty("+version+")\015\012");
    }
    
    /* ------------------------------------------------------------------------------- */
    // data
    private boolean _needCRLF = false;

    /* ------------------------------------------------------------------------------- */
    public HttpGenerator(RequestInfo info)
    {
        _info=info;
        _request=info;
        _response=null;
    }
    
    /* ------------------------------------------------------------------------------- */
    public HttpGenerator(ResponseInfo info)
    {
        _info=info;
        _request=null;
        _response=info;
    }

    /* ------------------------------------------------------------------------------- */
    public void reset()
    {
        _state = State.START;
        _content = Content.UNKNOWN_CONTENT;
        _noContent=false;
        _persistent = null;
        _contentPrepared = 0;
        _needCRLF = false;
        _noContent=false;
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
    public boolean isCommitted()
    {
        return _state.ordinal() >= State.COMMITTED.ordinal();
    }

    /* ------------------------------------------------------------ */
    public boolean isChunking()
    {
        return _content==Content.CHUNKED_CONTENT;
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
     * @return <code>false</code> if the connection should be closed after a request has been read,
     * <code>true</code> if it should be used for additional requests.
     */
    public boolean isPersistent()
    {
        return _persistent!=null
                ?_persistent.booleanValue()
                        :(isRequest()?true:_info.getHttpVersion().ordinal()>HttpVersion.HTTP_1_0.ordinal());
    }

    /* ------------------------------------------------------------ */
    public void setPersistent(boolean persistent)
    {
        _persistent=persistent;
    }

    /* ------------------------------------------------------------ */
    public HttpVersion getVersion()
    {
        return _info.getHttpVersion();
    }


    /* ------------------------------------------------------------ */
    public boolean isWritten()
    {
        return _contentPrepared>0;
    }

    /* ------------------------------------------------------------ */
    public boolean isAllContentWritten()
    {
        return _content==Content.CONTENT_LENGTH && _contentPrepared>=_info.getContentLength();
    }
    
    /* ------------------------------------------------------------ */
    public long getContentWritten()
    {
        return _contentPrepared;
    }

    /* ------------------------------------------------------------ */
    public boolean isRequest()
    {
        return _request!=null;
    }

    /* ------------------------------------------------------------ */
    public boolean isResponse()
    {
        return _response!=null;
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
            if (_content==Content.CONTENT_LENGTH && _info.getContentLength()>=0 && content.remaining()>(_info.getContentLength()-_contentPrepared))
            {
                LOG.warn("Content truncated. Info.getContentLength()=="+_info.getContentLength()+" prepared="+_contentPrepared+" content="+content.remaining(),new Throwable());
                content.limit(content.position()+(int)(_info.getContentLength()-_contentPrepared));
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

                        if(_info.getHttpVersion()==HttpVersion.HTTP_0_9)
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
                        if (_info.getHttpVersion() == HttpVersion.HTTP_0_9)
                        {
                            _persistent = false;
                            _content=Content.EOF_CONTENT;
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
                            _persistent=(_info.getHttpVersion().ordinal() > HttpVersion.HTTP_1_0.ordinal());

                        generateResponseLine(header);

                        // Handle 1xx
                        int status=_response.getStatus();
                        if (status>=100 && status<200 )
                        {
                            _noContent=true;

                            if (status!=101 )
                            {
                                header.put(HttpTokens.CRLF);
                                _state = State.START;
                                return Result.OK;
                            }
                        }
                        else if (status==204 || status==304)
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
        header.put(StringUtil.getBytes(_request.getMethod()));
        header.put((byte)' ');
        header.put(StringUtil.getBytes(_request.getURI()));
        switch(_info.getHttpVersion())
        {
            case HTTP_1_0:
            case HTTP_1_1:
                header.put((byte)' ');
                header.put(_info.getHttpVersion().toBytes());
        }
        header.put(HttpTokens.CRLF);
    }

    /* ------------------------------------------------------------ */
    private void generateResponseLine(ByteBuffer header)
    {
        // Look for prepared response line
        int status=_response.getStatus();
        PreparedResponse preprepared = status<__preprepared.length?__preprepared[status]:null;
        String reason=_response.getReason();
        if (preprepared!=null)
        {
            if (reason==null)
                header.put(preprepared._responseLine);
            else
            {
                header.put(preprepared._schemeCode);
                header.put(getReasonBytes(reason));
                header.put(HttpTokens.CRLF);
            }
        }
        else // generate response line
        {
            header.put(HTTP_1_1_SPACE);
            header.put((byte) ('0' + status / 100));
            header.put((byte) ('0' + (status % 100) / 10));
            header.put((byte) ('0' + (status % 10)));
            header.put((byte) ' ');
            if (reason==null)
            {
                header.put((byte) ('0' + status / 100));
                header.put((byte) ('0' + (status % 100) / 10));
                header.put((byte) ('0' + (status % 10)));
            }
            else
                header.put(getReasonBytes(reason));
            header.put(HttpTokens.CRLF);
        }
    }

    /* ------------------------------------------------------------ */
    private byte[] getReasonBytes(String reason)
    {
        if (reason.length()>1024)
            reason=reason.substring(0,1024);
        byte[] _bytes = StringUtil.getBytes(reason);

        for (int i=_bytes.length;i-->0;)
            if (_bytes[i]=='\r' || _bytes[i]=='\n')
                _bytes[i]='?';
        return _bytes;
    }

    /* ------------------------------------------------------------ */
    private void generateHeaders(ByteBuffer header,ByteBuffer content,boolean last)
    {
        // default field values
        boolean has_server = false;
        HttpFields.Field transfer_encoding=null;
        boolean keep_alive=false;
        boolean close=false;
        boolean content_type=false;
        StringBuilder connection = null;

        // Generate fields
        if (_info.getHttpFields() != null)
        {
            for (HttpFields.Field field : _info.getHttpFields())
            {
                HttpHeader name = field.getHeader();

                switch (name==null?HttpHeader.UNKNOWN:name)
                {
                    case CONTENT_LENGTH:
                        // handle specially below
                        if (_info.getContentLength()>=0)
                            _content=Content.CONTENT_LENGTH;
                        break;

                    case CONTENT_TYPE:
                    {
                        if (field.getValue().startsWith(MimeTypes.Type.MULTIPART_BYTERANGES.toString()))
                            _content=Content.SELF_DEFINING_CONTENT;

                        // write the field to the header
                        content_type=true;
                        field.putTo(header);
                        break;
                    }

                    case TRANSFER_ENCODING:
                    {
                        if (_info.getHttpVersion() == HttpVersion.HTTP_1_1)
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
                                    header.put(HttpHeader.CONNECTION.getBytesColonSpace()).put(HttpHeader.UPGRADE.getBytes());
                                    break;
                                }

                                case CLOSE:
                                {
                                    close=true;
                                    if (isResponse())
                                        _persistent=false;
                                    if (!_persistent && isResponse() && _content == Content.UNKNOWN_CONTENT)
                                        _content=Content.EOF_CONTENT;
                                    break;
                                }

                                case KEEP_ALIVE:
                                {
                                    if (_info.getHttpVersion() == HttpVersion.HTTP_1_0)
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
                            header.put(name.getBytesColonSpace());
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
        int status=_response!=null?_response.getStatus():-1;
        switch (_content)
        {
            case UNKNOWN_CONTENT:
                // It may be that we have no _content, or perhaps _content just has not been
                // written yet?

                // Response known not to have a body
                if (_contentPrepared == 0 && isResponse() && (status < 200 || status == 204 || status == 304))
                    _content=Content.NO_CONTENT;
                else if (_info.getContentLength()>0)
                {
                    // we have been given a content length
                    _content=Content.CONTENT_LENGTH;
                    long content_length = _info.getContentLength();
                    if ((isResponse() || content_length>0 || content_type ) && !_noContent)
                    {
                        // known length but not actually set.
                        header.put(HttpHeader.CONTENT_LENGTH.getBytesColonSpace());
                        BufferUtil.putDecLong(header, content_length);
                        header.put(HttpTokens.CRLF);
                    }
                }
                else if (last)
                {
                    // we have seen all the _content there is, so we can be content-length limited.
                    _content=Content.CONTENT_LENGTH;
                    long content_length = _contentPrepared+BufferUtil.length(content);
                    
                    // Do we need to tell the headers about it
                    if ((isResponse() || content_length>0 || content_type ) && !_noContent)
                    {
                        header.put(HttpHeader.CONTENT_LENGTH.getBytesColonSpace());
                        BufferUtil.putDecLong(header, content_length);
                        header.put(HttpTokens.CRLF);
                    }
                }
                else
                {
                    // No idea, so we must assume that a body is coming
                    _content = (!_persistent || _info.getHttpVersion().ordinal() < HttpVersion.HTTP_1_1.ordinal() ) ? Content.EOF_CONTENT : Content.CHUNKED_CONTENT;
                    if (isRequest() && _content==Content.EOF_CONTENT)
                    {
                        _content=Content.NO_CONTENT;
                        _noContent=true;
                    }
                }
                break;

            case CONTENT_LENGTH:
                long content_length = _info.getContentLength();
                if ((isResponse() || content_length>0 || content_type ) && !_noContent)
                {
                    // known length but not actually set.
                    header.put(HttpHeader.CONTENT_LENGTH.getBytesColonSpace());
                    BufferUtil.putDecLong(header, content_length);
                    header.put(HttpTokens.CRLF);
                }
                break;
                
            case NO_CONTENT:
                if (isResponse() && status >= 200 && status != 204 && status != 304)
                    header.put(CONTENT_LENGTH_0);
                break;

            case EOF_CONTENT:
                _persistent = isRequest();
                break;

            case CHUNKED_CONTENT:
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
        if (_content==Content.EOF_CONTENT)
        {
            keep_alive=false;
            _persistent=false;
        }

        // If this is a response, work out persistence
        if (isResponse())
        {
            if (!_persistent && (close || _info.getHttpVersion().ordinal() > HttpVersion.HTTP_1_0.ordinal()))
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

        if (!has_server && status>199 && getSendServerVersion())
            header.put(SERVER);

        // end the header.
        header.put(HttpTokens.CRLF);

    }


    /* ------------------------------------------------------------------------------- */
    public static byte[] getReasonBuffer(int code)
    {
        PreparedResponse status = code<__preprepared.length?__preprepared[code]:null;
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
    
    /* ------------------------------------------------------------------------------- */
    /* ------------------------------------------------------------------------------- */
    /* ------------------------------------------------------------------------------- */
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
    private static final byte[] NO_BYTES = {};
    
    /* ------------------------------------------------------------------------------- */
    /* ------------------------------------------------------------------------------- */
    /* ------------------------------------------------------------------------------- */
    // Build cache of response lines for status
    private static class PreparedResponse
    {
        byte[] _reason;
        byte[] _schemeCode;
        byte[] _responseLine;
    }
    private static final PreparedResponse[] __preprepared = new PreparedResponse[HttpStatus.MAX_CODE+1];
    static
    {
        int versionLength=HttpVersion.HTTP_1_1.toString().length();

        for (int i=0;i<__preprepared.length;i++)
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

            __preprepared[i] = new PreparedResponse();
            __preprepared[i]._reason=new byte[line.length-versionLength-7] ;
            System.arraycopy(line,versionLength+5,__preprepared[i]._reason,0,line.length-versionLength-7);
            __preprepared[i]._schemeCode=new byte[versionLength+5];
            System.arraycopy(line,0,__preprepared[i]._schemeCode,0,versionLength+5);
            __preprepared[i]._responseLine=line;
        }
    }

}
