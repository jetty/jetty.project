//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jetty.http.HttpTokens.EndOfContent;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * HttpGenerator. Builds HTTP Messages.
 * <p>
 * If the system property "org.eclipse.jetty.http.HttpGenerator.STRICT" is set to true,
 * then the generator will strictly pass on the exact strings received from methods and header
 * fields.  Otherwise a fast case insensitive string lookup is used that may alter the
 * case and white space of some methods/headers
 */
public class HttpGenerator
{
    private final static Logger LOG = Log.getLogger(HttpGenerator.class);

    public final static boolean __STRICT=Boolean.getBoolean("org.eclipse.jetty.http.HttpGenerator.STRICT");

    private final static byte[] __colon_space = new byte[] {':',' '};
    private final static HttpHeaderValue[] CLOSE = {HttpHeaderValue.CLOSE};
    public static final MetaData.Response CONTINUE_100_INFO = new MetaData.Response(HttpVersion.HTTP_1_1,100,null,null,-1);
    public static final MetaData.Response PROGRESS_102_INFO = new MetaData.Response(HttpVersion.HTTP_1_1,102,null,null,-1);
    public final static MetaData.Response RESPONSE_500_INFO =
        new MetaData.Response(HttpVersion.HTTP_1_1,HttpStatus.INTERNAL_SERVER_ERROR_500,null,new HttpFields(){{put(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE);}},0);

    // states
    public enum State { START, COMMITTED, COMPLETING, COMPLETING_1XX, END }
    public enum Result { NEED_CHUNK,NEED_INFO,NEED_HEADER,FLUSH,CONTINUE,SHUTDOWN_OUT,DONE}

    // other statics
    public static final int CHUNK_SIZE = 12;

    private State _state = State.START;
    private EndOfContent _endOfContent = EndOfContent.UNKNOWN_CONTENT;

    private long _contentPrepared = 0;
    private boolean _noContent = false;
    private Boolean _persistent = null;

    private final int _send;
    private final static int SEND_SERVER = 0x01;
    private final static int SEND_XPOWEREDBY = 0x02;
    private final static Set<String> __assumedContentMethods = new HashSet<>(Arrays.asList(new String[]{HttpMethod.POST.asString(),HttpMethod.PUT.asString()}));
  
    /* ------------------------------------------------------------------------------- */
    public static void setJettyVersion(String serverVersion)
    {
        SEND[SEND_SERVER] = StringUtil.getBytes("Server: " + serverVersion + "\015\012");
        SEND[SEND_XPOWEREDBY] = StringUtil.getBytes("X-Powered-By: " + serverVersion + "\015\012");
        SEND[SEND_SERVER | SEND_XPOWEREDBY] = StringUtil.getBytes("Server: " + serverVersion + "\015\012X-Powered-By: " +
                serverVersion + "\015\012");
    }

    /* ------------------------------------------------------------------------------- */
    // data
    private boolean _needCRLF = false;

    /* ------------------------------------------------------------------------------- */
    public HttpGenerator()
    {
        this(false,false);
    }

    /* ------------------------------------------------------------------------------- */
    public HttpGenerator(boolean sendServerVersion,boolean sendXPoweredBy)
    {
        _send=(sendServerVersion?SEND_SERVER:0) | (sendXPoweredBy?SEND_XPOWEREDBY:0);
    }

    /* ------------------------------------------------------------------------------- */
    public void reset()
    {
        _state = State.START;
        _endOfContent = EndOfContent.UNKNOWN_CONTENT;
        _noContent=false;
        _persistent = null;
        _contentPrepared = 0;
        _needCRLF = false;
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public boolean getSendServerVersion ()
    {
        return (_send&SEND_SERVER)!=0;
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void setSendServerVersion (boolean sendServerVersion)
    {
        throw new UnsupportedOperationException();
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
    public boolean isIdle()
    {
        return _state == State.START;
    }

    /* ------------------------------------------------------------ */
    public boolean isEnd()
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
        return _endOfContent==EndOfContent.CHUNKED_CONTENT;
    }

    /* ------------------------------------------------------------ */
    public boolean isNoContent()
    {
        return _noContent;
    }

    /* ------------------------------------------------------------ */
    public void setPersistent(boolean persistent)
    {
        _persistent=persistent;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return true if known to be persistent
     */
    public boolean isPersistent()
    {
        return Boolean.TRUE.equals(_persistent);
    }

    /* ------------------------------------------------------------ */
    public boolean isWritten()
    {
        return _contentPrepared>0;
    }

    /* ------------------------------------------------------------ */
    public long getContentPrepared()
    {
        return _contentPrepared;
    }

    /* ------------------------------------------------------------ */
    public void abort()
    {
        _persistent=false;
        _state=State.END;
        _endOfContent=null;
    }

    /* ------------------------------------------------------------ */
    public Result generateRequest(MetaData.Request info, ByteBuffer header, ByteBuffer chunk, ByteBuffer content, boolean last) throws IOException
    {
        switch(_state)
        {
            case START:
            {
                if (info==null)
                    return Result.NEED_INFO;

                if (header==null)
                    return Result.NEED_HEADER;

                // If we have not been told our persistence, set the default
                if (_persistent==null)
                {
                    _persistent=info.getVersion().ordinal() > HttpVersion.HTTP_1_0.ordinal();
                    if (!_persistent && HttpMethod.CONNECT.is(info.getMethod()))
                        _persistent=true;
                }

                // prepare the header
                int pos=BufferUtil.flipToFill(header);
                try
                {
                    // generate ResponseLine
                    generateRequestLine(info,header);

                    if (info.getVersion()==HttpVersion.HTTP_0_9)
                        throw new IllegalArgumentException("HTTP/0.9 not supported");
                    
                    generateHeaders(info,header,content,last);

                    boolean expect100 = info.getFields().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());

                    if (expect100)
                    {
                        _state = State.COMMITTED;
                    }
                    else
                    {
                        // handle the content.
                        int len = BufferUtil.length(content);
                        if (len>0)
                        {
                            _contentPrepared+=len;
                            if (isChunking())
                                prepareChunk(header,len);
                        }
                        _state = last?State.COMPLETING:State.COMMITTED;
                    }

                    return Result.FLUSH;
                }
                catch(Exception e)
                {
                    String message= (e instanceof BufferOverflowException)?"Request header too large":e.getMessage();
                    throw new IOException(message,e);
                }
                finally
                {
                    BufferUtil.flipToFlush(header,pos);
                }
            }

            case COMMITTED:
            {
                int len = BufferUtil.length(content);

                if (len>0)
                {
                    // Do we need a chunk buffer?
                    if (isChunking())
                    {
                        // Do we need a chunk buffer?
                        if (chunk==null)
                            return Result.NEED_CHUNK;
                        BufferUtil.clearToFill(chunk);
                        prepareChunk(chunk,len);
                        BufferUtil.flipToFlush(chunk,0);
                    }
                    _contentPrepared+=len;
                }

                if (last)
                    _state=State.COMPLETING;

                return len>0?Result.FLUSH:Result.CONTINUE;
            }

            case COMPLETING:
            {
                if (BufferUtil.hasContent(content))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("discarding content in COMPLETING");
                    BufferUtil.clear(content);
                }

                if (isChunking())
                {
                    // Do we need a chunk buffer?
                    if (chunk==null)
                        return Result.NEED_CHUNK;
                    BufferUtil.clearToFill(chunk);
                    prepareChunk(chunk,0);
                    BufferUtil.flipToFlush(chunk,0);
                    _endOfContent=EndOfContent.UNKNOWN_CONTENT;
                    return Result.FLUSH;
                }

                _state=State.END;
               return Boolean.TRUE.equals(_persistent)?Result.DONE:Result.SHUTDOWN_OUT;
            }

            case END:
                if (BufferUtil.hasContent(content))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("discarding content in COMPLETING");
                    BufferUtil.clear(content);
                }
                return Result.DONE;

            default:
                throw new IllegalStateException();
        }
    }

    /* ------------------------------------------------------------ */
    public Result generateResponse(MetaData.Response info, ByteBuffer header, ByteBuffer chunk, ByteBuffer content, boolean last) throws IOException
    {
        return generateResponse(info,false,header,chunk,content,last);
    }

    /* ------------------------------------------------------------ */
    public Result generateResponse(MetaData.Response info, boolean head, ByteBuffer header, ByteBuffer chunk, ByteBuffer content, boolean last) throws IOException
    {
        switch(_state)
        {
            case START:
            {
                if (info==null)
                    return Result.NEED_INFO;
                
                switch(info.getVersion())
                {
                    case HTTP_1_0:
                        if (_persistent==null)
                            _persistent=Boolean.FALSE;
                        break;
                        
                    case HTTP_1_1:
                        if (_persistent==null)
                            _persistent=Boolean.TRUE;
                        break;
                        
                    default:
                        throw new IllegalArgumentException(info.getVersion()+" not supported");
                }
                
                // Do we need a response header
                if (header==null)
                    return Result.NEED_HEADER;

                // prepare the header
                int pos=BufferUtil.flipToFill(header);
                try
                {
                    // generate ResponseLine
                    generateResponseLine(info,header);

                    // Handle 1xx and no content responses
                    int status=info.getStatus();
                    if (status>=100 && status<200 )
                    {
                        _noContent=true;

                        if (status!=HttpStatus.SWITCHING_PROTOCOLS_101 )
                        {
                            header.put(HttpTokens.CRLF);
                            _state=State.COMPLETING_1XX;
                            return Result.FLUSH;
                        }
                    }
                    else if (status==HttpStatus.NO_CONTENT_204 || status==HttpStatus.NOT_MODIFIED_304)
                    {
                        _noContent=true;
                    }

                    generateHeaders(info,header,content,last);

                    // handle the content.
                    int len = BufferUtil.length(content);
                    if (len>0)
                    {
                        _contentPrepared+=len;
                        if (isChunking() && !head)
                            prepareChunk(header,len);
                    }
                    _state = last?State.COMPLETING:State.COMMITTED;
                }
                catch(Exception e)
                {
                    String message= (e instanceof BufferOverflowException)?"Response header too large":e.getMessage();
                    throw new IOException(message,e);
                }
                finally
                {
                    BufferUtil.flipToFlush(header,pos);
                }

                return Result.FLUSH;
            }

            case COMMITTED:
            {
                int len = BufferUtil.length(content);

                // handle the content.
                if (len>0)
                {
                    if (isChunking())
                    {
                        if (chunk==null)
                            return Result.NEED_CHUNK;
                        BufferUtil.clearToFill(chunk);
                        prepareChunk(chunk,len);
                        BufferUtil.flipToFlush(chunk,0);
                    }
                    _contentPrepared+=len;
                }

                if (last)
                {
                    _state=State.COMPLETING;
                    return len>0?Result.FLUSH:Result.CONTINUE;
                }
                return len>0?Result.FLUSH:Result.DONE;

            }

            case COMPLETING_1XX:
            {
                reset();
                return Result.DONE;
            }

            case COMPLETING:
            {
                if (BufferUtil.hasContent(content))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("discarding content in COMPLETING");
                    BufferUtil.clear(content);
                }

                if (isChunking())
                {
                    // Do we need a chunk buffer?
                    if (chunk==null)
                        return Result.NEED_CHUNK;

                    // Write the last chunk
                    BufferUtil.clearToFill(chunk);
                    prepareChunk(chunk,0);
                    BufferUtil.flipToFlush(chunk,0);
                    _endOfContent=EndOfContent.UNKNOWN_CONTENT;
                    return Result.FLUSH;
                }

                _state=State.END;

               return Boolean.TRUE.equals(_persistent)?Result.DONE:Result.SHUTDOWN_OUT;
            }

            case END:
                if (BufferUtil.hasContent(content))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("discarding content in COMPLETING");
                    BufferUtil.clear(content);
                }
                return Result.DONE;

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
    private void generateRequestLine(MetaData.Request request,ByteBuffer header)
    {
        header.put(StringUtil.getBytes(request.getMethod()));
        header.put((byte)' ');
        header.put(StringUtil.getBytes(request.getURIString()));
        header.put((byte)' ');
        header.put(request.getVersion().toBytes());
        header.put(HttpTokens.CRLF);
    }

    /* ------------------------------------------------------------ */
    private void generateResponseLine(MetaData.Response response, ByteBuffer header)
    {
        // Look for prepared response line
        int status=response.getStatus();
        PreparedResponse preprepared = status<__preprepared.length?__preprepared[status]:null;
        String reason=response.getReason();
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
    private void generateHeaders(MetaData _info,ByteBuffer header,ByteBuffer content,boolean last)
    {
        final MetaData.Request request=(_info instanceof MetaData.Request)?(MetaData.Request)_info:null;
        final MetaData.Response response=(_info instanceof MetaData.Response)?(MetaData.Response)_info:null;

        // default field values
        int send=_send;
        HttpField transfer_encoding=null;
        boolean keep_alive=false;
        boolean close=false;
        boolean content_type=false;
        StringBuilder connection = null;
        long content_length = _info.getContentLength();

        // Generate fields
        HttpFields fields = _info.getFields();
        if (fields != null)
        {
            int n=fields.size();
            for (int f=0;f<n;f++)
            {
                HttpField field = fields.getField(f);
                String v = field.getValue();
                if (v==null || v.length()==0)
                    continue; // rfc7230 does not allow no value

                HttpHeader h = field.getHeader();
                if (h==null)
                    putTo(field,header);
                else
                {
                    switch (h)
                    {
                        case CONTENT_LENGTH:
                            _endOfContent=EndOfContent.CONTENT_LENGTH;
                            if (content_length<0)
                                content_length=Long.valueOf(field.getValue());
                            // handle setting the field specially below
                            break;

                        case CONTENT_TYPE:
                        {
                            // write the field to the header
                            content_type=true;
                            putTo(field,header);
                            break;
                        }

                        case TRANSFER_ENCODING:
                        {
                            if (_info.getVersion() == HttpVersion.HTTP_1_1)
                                transfer_encoding = field;
                            // Do NOT add yet!
                            break;
                        }

                        case CONNECTION:
                        {
                            if (request!=null)
                                putTo(field,header);

                            // Lookup and/or split connection value field
                            HttpHeaderValue[] values = HttpHeaderValue.CLOSE.is(field.getValue())?CLOSE:new HttpHeaderValue[]{HttpHeaderValue.CACHE.get(field.getValue())};
                            String[] split = null;

                            if (values[0]==null)
                            {
                                split = StringUtil.csvSplit(field.getValue());
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
                                        header.put(CRLF);
                                        break;
                                    }

                                    case CLOSE:
                                    {
                                        close=true;
                                        _persistent=false;
                                        if (response!=null)
                                        {
                                            if (_endOfContent == EndOfContent.UNKNOWN_CONTENT)
                                                _endOfContent=EndOfContent.EOF_CONTENT;
                                        }
                                        break;
                                    }

                                    case KEEP_ALIVE:
                                    {
                                        if (_info.getVersion() == HttpVersion.HTTP_1_0)
                                        {
                                            keep_alive = true;
                                            if (response!=null)
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
                            send=send&~SEND_SERVER;
                            putTo(field,header);
                            break;
                        }

                        default:
                            putTo(field,header);
                    }
                }
            }
        }


        // Calculate how to end _content and connection, _content length and transfer encoding
        // settings.
        // From http://tools.ietf.org/html/rfc7230#section-3.3.3
        // From RFC 2616 4.4:
        // 1. No body for 1xx, 204, 304 & HEAD response
        // 3. If Transfer-Encoding==(.*,)?chunked && HTTP/1.1 && !HttpConnection==close then chunk
        // 5. Content-Length without Transfer-Encoding
        // 6. Request and none over the above, then Content-Length=0 if POST/PUT
        // 7. close
        
        
        int status=response!=null?response.getStatus():-1;
        switch (_endOfContent)
        {
            case UNKNOWN_CONTENT:
                // It may be that we have no _content, or perhaps _content just has not been
                // written yet?

                // Response known not to have a body
                if (_contentPrepared == 0 && response!=null && _noContent)
                    _endOfContent=EndOfContent.NO_CONTENT;
                else if (_info.getContentLength()>0)
                {
                    // we have been given a content length
                    _endOfContent=EndOfContent.CONTENT_LENGTH;
                    if ((response!=null || content_length>0 || content_type ) && !_noContent)
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
                    _endOfContent=EndOfContent.CONTENT_LENGTH;
                    long actual_length = _contentPrepared+BufferUtil.length(content);

                    if (content_length>=0 && content_length!=actual_length)
                        throw new IllegalArgumentException("Content-Length header("+content_length+") != actual("+actual_length+")");
                    
                    // Do we need to tell the headers about it
                    putContentLength(header,actual_length,content_type,request,response);
                }
                else
                {
                    // No idea, so we must assume that a body is coming.
                    _endOfContent = EndOfContent.CHUNKED_CONTENT;
                    // HTTP 1.0 does not understand chunked content, so we must use EOF content.
                    // For a request with HTTP 1.0 & Connection: keep-alive
                    // we *must* close the connection, otherwise the client
                    // has no way to detect the end of the content.
                    if (!isPersistent() || _info.getVersion().ordinal() < HttpVersion.HTTP_1_1.ordinal())
                        _endOfContent = EndOfContent.EOF_CONTENT;
                }
                break;

            case CONTENT_LENGTH:
            {
                putContentLength(header,content_length,content_type,request,response);
                break;
            }

            case NO_CONTENT:
                throw new IllegalStateException();

            case EOF_CONTENT:
                _persistent = request!=null;
                break;

            case CHUNKED_CONTENT:
                break;

            default:
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
                    putTo(transfer_encoding,header);
                else
                    throw new IllegalArgumentException("BAD TE");
            }
            else
                header.put(TRANSFER_ENCODING_CHUNKED);
        }

        // Handle connection if need be
        if (_endOfContent==EndOfContent.EOF_CONTENT)
        {
            keep_alive=false;
            _persistent=false;
        }

        // If this is a response, work out persistence
        if (response!=null)
        {
            if (!isPersistent() && (close || _info.getVersion().ordinal() > HttpVersion.HTTP_1_0.ordinal()))
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
                    header.put(CONNECTION_KEEP_ALIVE,0,CONNECTION_KEEP_ALIVE.length-2);
                    header.put((byte)',');
                    header.put(StringUtil.getBytes(connection.toString()));
                    header.put(CRLF);
                }
            }
            else if (connection!=null)
            {
                header.put(HttpHeader.CONNECTION.getBytesColonSpace());
                header.put(StringUtil.getBytes(connection.toString()));
                header.put(CRLF);
            }
        }

        if (status>199)
            header.put(SEND[send]);

        // end the header.
        header.put(HttpTokens.CRLF);
    }

    /* ------------------------------------------------------------------------------- */
    private void putContentLength(ByteBuffer header, long contentLength, boolean contentType, MetaData.Request request, MetaData.Response response)
    {
        if (contentLength>0)
        {
            header.put(HttpHeader.CONTENT_LENGTH.getBytesColonSpace());
            BufferUtil.putDecLong(header, contentLength);
            header.put(HttpTokens.CRLF);
        }
        else if (!_noContent)
        {
            if (contentType || response!=null || (request!=null && __assumedContentMethods.contains(request.getMethod())))
                header.put(CONTENT_LENGTH_0);
        }
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
        return String.format("%s@%x{s=%s}",
                getClass().getSimpleName(),
                hashCode(),
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
    private static final byte[] HTTP_1_1_SPACE = StringUtil.getBytes(HttpVersion.HTTP_1_1+" ");
    private static final byte[] CRLF = StringUtil.getBytes("\015\012");
    private static final byte[] TRANSFER_ENCODING_CHUNKED = StringUtil.getBytes("Transfer-Encoding: chunked\015\012");
    private static final byte[][] SEND = new byte[][]{
            new byte[0],
            StringUtil.getBytes("Server: Jetty(9.x.x)\015\012"),
        StringUtil.getBytes("X-Powered-By: Jetty(9.x.x)\015\012"),
        StringUtil.getBytes("Server: Jetty(9.x.x)\015\012X-Powered-By: Jetty(9.x.x)\015\012")
    };

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
            __preprepared[i]._schemeCode = Arrays.copyOfRange(line, 0,versionLength+5);
            __preprepared[i]._reason = Arrays.copyOfRange(line, versionLength+5, line.length-2);
            __preprepared[i]._responseLine=line;
        }
    }

    private static void putSanitisedName(String s,ByteBuffer buffer)
    {
        int l=s.length();
        for (int i=0;i<l;i++)
        {
            char c=s.charAt(i);

            if (c<0 || c>0xff || c=='\r' || c=='\n'|| c==':')
                buffer.put((byte)'?');
            else
                buffer.put((byte)(0xff&c));
        }
    }

    private static void putSanitisedValue(String s,ByteBuffer buffer)
    {
        int l=s.length();
        for (int i=0;i<l;i++)
        {
            char c=s.charAt(i);

            if (c<0 || c>0xff || c=='\r' || c=='\n')
                buffer.put((byte)' ');
            else
                buffer.put((byte)(0xff&c));
        }
    }

    public static void putTo(HttpField field, ByteBuffer bufferInFillMode)
    {
        if (field instanceof PreEncodedHttpField)
        {
            ((PreEncodedHttpField)field).putTo(bufferInFillMode,HttpVersion.HTTP_1_0);
        }
        else
        {
            HttpHeader header=field.getHeader();
            if (header!=null)
            {
                bufferInFillMode.put(header.getBytesColonSpace());
                putSanitisedValue(field.getValue(),bufferInFillMode);
            }
            else
            {
                putSanitisedName(field.getName(),bufferInFillMode);
                bufferInFillMode.put(__colon_space);
                putSanitisedValue(field.getValue(),bufferInFillMode);
            }

            BufferUtil.putCRLF(bufferInFillMode);
        }
    }

    public static void putTo(HttpFields fields, ByteBuffer bufferInFillMode)
    {
        for (HttpField field : fields)
        {
            if (field != null)
                putTo(field,bufferInFillMode);
        }
        BufferUtil.putCRLF(bufferInFillMode);
    }
}
