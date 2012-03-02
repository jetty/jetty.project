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
import java.nio.BufferOverflowException;
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
    
    enum State { START, CHUNKING, STREAMING, COMPLETING, END };
    enum Result { NEED_COMMIT,NEED_CHUNK,NEED_BUFFER,FLUSH,FLUSH_CONTENT,NEED_COMPLETE,OK,SHUTDOWN_OUT};

    public static final byte[] NO_BYTES = {};

    // data

    private State _state = State.START;

    private int _status = 0;
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
    private boolean _needCRLF = false;
    private boolean _sentEOC = false;


   
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
        _sentEOC = false;
        _uri=null;
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
    public boolean isIdle()
    {
        return _state == State.START && _method==null && _status==0;
    }

    /* ------------------------------------------------------------ */
    public boolean isCommitted()
    {
        return _state != State.START;
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
    public boolean needsHeader()
    {
        return _state==State.START || _contentLength==HttpTokens.CHUNKED_CONTENT;
    }

    
    /* ------------------------------------------------------------ */
    public Result commit(HttpFields fields,ByteBuffer header,ByteBuffer buffer, ByteBuffer content, boolean last)
            throws IOException
    {
        
        if (header==null)
            throw new IllegalArgumentException("!header");

        if (isResponse() && _status==0)
            throw new EofException(); // TODO ???


        if (_state == State.START)
        {
            if (isRequest() && _version==HttpVersion.HTTP_0_9)
                _noContent=true;

            boolean has_server = false;
            int pos=header.position();
            try
            {
                BufferUtil.flipToFill(header);
                if (isRequest())
                {
                    _persistent=true;

                    if (_version == HttpVersion.HTTP_0_9)
                    {
                        _contentLength = HttpTokens.NO_CONTENT;
                        header.put(_method);
                        header.put((byte)' ');
                        header.put(_uri); 
                        header.put(HttpTokens.CRLF);
                        _state = State.END;
                        _noContent=true;
                        return Result.FLUSH;
                    }

                    header.put(_method);
                    header.put((byte)' ');
                    header.put(_uri); 
                    header.put((byte)' ');
                    header.put((_version==HttpVersion.HTTP_1_0?HttpVersion.HTTP_1_0:HttpVersion.HTTP_1_1).toBytes());
                    header.put(HttpTokens.CRLF);
                }
                else
                {
                    // Responses
                    if (_version == HttpVersion.HTTP_0_9)
                    {
                        _persistent = false;
                        _contentLength = HttpTokens.EOF_CONTENT;
                        _state = State.STREAMING;
                        return prepareContent(null,buffer,content);
                    }

                    // Are we persistent by default?
                    if (_persistent==null)
                        _persistent=(_version.ordinal() > HttpVersion.HTTP_1_0.ordinal());

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

                    // Handle 1xx
                    if (_status>=100 && _status<200 )
                    {
                        _noContent=true;

                        if (_status!=101 )
                        {
                            header.put(HttpTokens.CRLF);
                            _state = State.START;
                            return Result.FLUSH;
                        }
                    }
                    else if (_status==204 || _status==304)
                    {
                        _noContent=true;
                    }
                }

                // Add Date header
                if (_status>=200 && _date!=null)
                {
                    header.put(HttpHeader.DATE.toBytesColonSpace());
                    header.put(_date);
                    header.put(CRLF);
                }

                // default field values
                HttpFields.Field transfer_encoding=null;
                boolean keep_alive=false;
                boolean close=false;
                boolean content_type=false;
                boolean content_length=false;
                StringBuilder connection = null;

                // Generate fields
                if (fields != null)
                {
                    for (HttpFields.Field field : fields)
                    {
                        HttpHeader name = HttpHeader.CACHE.get(field.getName());

                        switch (name==null?HttpHeader.UNKNOWN:name)
                        {
                            case CONTENT_LENGTH:
                            {
                                long length = field.getLongValue();
                                if (length>=0)
                                {
                                    if (length < _contentPrepared || last && length != _contentPrepared)
                                        LOG.warn("Incorrect ContentLength ignored ",new Throwable());
                                    else
                                    {
                                        // write the field to the header 
                                        field.putTo(header);
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
                                header.put(name.toBytesColonSpace());
                                field.putValueTo(header);
                                header.put(CRLF);

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
                            _contentLength = _contentPrepared+BufferUtil.remaining(content);
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
                if (_contentLength == HttpTokens.CHUNKED_CONTENT)
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
                _state = _contentLength==HttpTokens.CHUNKED_CONTENT?State.CHUNKING:State.STREAMING;

            }
            catch(BufferOverflowException e)
            {
                throw new RuntimeException("Header>"+header.capacity(),e);
            }
            finally
            {
                BufferUtil.flipToFlush(header,pos);
            }
        }

        // Handle the content
        if (BufferUtil.hasContent(content))
        {
            // Can we do a direct flush
            if (BufferUtil.isEmpty(buffer) && content.remaining()>_largeContent)
            {
                _contentPrepared+=content.remaining();
                if (_state==State.CHUNKING)
                    prepareChunk(header,content.remaining());
                return Result.FLUSH_CONTENT;
            }

            // we copy content to buffer
            // if we don't have one, we need one
            if (buffer==null)
                return Result.NEED_BUFFER;

            _contentPrepared+=BufferUtil.put(content,buffer);

            if (_state==State.CHUNKING)
                prepareChunk(header,buffer.remaining());
        }

        if (BufferUtil.hasContent(buffer))
        {
            if (last && BufferUtil.isEmpty(content) || _contentLength>0&&_contentLength==_contentPrepared)
                return Result.NEED_COMPLETE;

            return Result.FLUSH;
        }
        return Result.OK;   
    }

    /* ------------------------------------------------------------ */
    public Result prepareContent(ByteBuffer chunk, ByteBuffer buffer, ByteBuffer content)
    {
        switch (_state)
        {
            case START:
                // Can we do a direct flush
                if (BufferUtil.isEmpty(buffer) && content.remaining()>_largeContent)
                    return Result.NEED_COMMIT;

                // we copy content to buffer
                // if we don't have one, we need one
                if (buffer==null)
                    return Result.NEED_BUFFER;

                // are we limited by content length?
                if (_contentLength>0)
                {
                    _contentPrepared+=BufferUtil.put(content,buffer,_contentLength-_contentPrepared);
                    if (_contentPrepared==_contentLength)
                        return Result.NEED_COMMIT;
                }
                else
                    _contentPrepared+=BufferUtil.put(content,buffer);

                // are we full?
                if (BufferUtil.isAtCapacity(buffer))
                    return Result.NEED_COMMIT;

                return Result.OK;   
                
            case STREAMING:
                // Can we do a direct flush
                if (BufferUtil.isEmpty(buffer) && content.remaining()>_largeContent)
                {
                    if (_contentLength>0)
                    {
                        long total=_contentPrepared+content.remaining();
                        if (total>_contentLength)
                            throw new IllegalStateException();
                        if (total==_contentLength)
                            return Result.NEED_COMPLETE;
                    }
                    _contentPrepared+=content.remaining();
                    return Result.FLUSH_CONTENT;
                }

                // we copy content to buffer
                // if we don't have one, we need one
                if (buffer==null)
                    return Result.NEED_BUFFER;

                // are we limited by content length?
                if (_contentLength>0)
                {
                    _contentPrepared+=BufferUtil.put(content,buffer,_contentLength-_contentPrepared);
                    if (_contentPrepared==_contentLength)
                        return Result.NEED_COMPLETE;
                }
                else
                    _contentPrepared+=BufferUtil.put(content,buffer);

                // are we full?
                if (BufferUtil.isAtCapacity(buffer))
                    return Result.FLUSH;

                return Result.OK;   
                
                
            case CHUNKING:
            {
                if (chunk==null)
                    return Result.NEED_CHUNK;

                // Can we do a direct flush
                if (BufferUtil.isEmpty(buffer) && content.remaining()>_largeContent)
                {
                    _contentPrepared+=content.remaining();
                    BufferUtil.clear(chunk);
                    prepareChunk(chunk,content.remaining());
                    return Result.FLUSH_CONTENT;
                }
                // we copy content to buffer
                // if we don't have one, we need one
                if (buffer==null)
                    return Result.NEED_BUFFER;

                _contentPrepared+=BufferUtil.put(content,buffer);

                // are we full?
                if (BufferUtil.isAtCapacity(buffer))
                {
                    BufferUtil.clear(chunk);
                    prepareChunk(chunk,buffer.remaining());
                    return Result.FLUSH;
                }

                return Result.OK;   
            }
            default:
                throw new IllegalStateException();
        }   
    }
   
    /* ------------------------------------------------------------ */
    private void prepareChunk(ByteBuffer chunk, int remaining)
    {
        // if we need CRLF add this to header
        if (_needCRLF)
            BufferUtil.putCRLF(chunk);
        
        // Add the chunk size to the header
        BufferUtil.putHexInt(chunk, remaining);
        BufferUtil.putCRLF(chunk);

        // Need a CRLF after the content
        _needCRLF=remaining>0;
    }

    /* ------------------------------------------------------------ */
    /**
     * Complete the message.
     *
     * @throws IOException
     */
    public Result flush(ByteBuffer chunk, ByteBuffer buffer) throws IOException
    {
        switch(_state)
        {
            case START:
                return Result.NEED_COMMIT;
            case CHUNKING:
                if (chunk==null)
                    return Result.NEED_CHUNK;

                if (BufferUtil.hasContent(buffer))
                {
                    BufferUtil.clear(chunk);
                    prepareChunk(chunk,buffer.remaining());
                }
        }
        return Result.FLUSH;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Complete the message.
     *
     * @throws IOException
     */
    public Result complete(ByteBuffer chunk, ByteBuffer buffer) throws IOException
    {
        if (_state == State.END)
            return Result.OK;

        switch(_state)
        {
            case START:
                return Result.NEED_COMMIT;
                
            case CHUNKING:
                if (chunk==null)
                    return Result.NEED_CHUNK;

                if (BufferUtil.hasContent(buffer))
                {
                    _state=State.COMPLETING;
                    BufferUtil.clear(chunk);
                    prepareChunk(chunk,buffer.remaining());
                    return Result.FLUSH;
                }
                
                if (!_sentEOC)
                {
                    _state=State.END;
                    prepareChunk(chunk,0);
                    _sentEOC=true;
                    return Result.FLUSH;
                }
                return Result.OK;
                
            case STREAMING:
                if (BufferUtil.hasContent(buffer))
                {
                    _state=State.COMPLETING;
                    return Result.FLUSH;
                }
                _state=State.END;
                return Result.OK;
        }
        return Result.OK;
    }

    /* ------------------------------------------------------------------------------- */
    public static byte[] getReasonBuffer(int code)
    {
        Status status = code<__status.length?__status[code]:null;
        if (status!=null)
            return status._reason;
        return null;
    }
    

    @Override
    public String toString()
    {
        return String.format("%s{s=%d}",
                getClass().getSimpleName(),
                _state);
    }
}
