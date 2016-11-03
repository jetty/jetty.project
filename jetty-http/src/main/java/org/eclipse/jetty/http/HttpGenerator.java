//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.eclipse.jetty.http.HttpStatus.INTERNAL_SERVER_ERROR_500;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.jetty.http.HttpTokens.EndOfContent;
import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Trie;
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
    public static final MetaData.Response CONTINUE_100_INFO = new MetaData.Response(HttpVersion.HTTP_1_1,100,null,null,-1);
    public static final MetaData.Response PROGRESS_102_INFO = new MetaData.Response(HttpVersion.HTTP_1_1,102,null,null,-1);
    public final static MetaData.Response RESPONSE_500_INFO =
        new MetaData.Response(HttpVersion.HTTP_1_1,INTERNAL_SERVER_ERROR_500,null,new HttpFields(){{put(HttpHeader.CONNECTION,HttpHeaderValue.CLOSE);}},0);

    // states
    public enum State { START, COMMITTED, COMPLETING, COMPLETING_1XX, END }
    public enum Result { NEED_CHUNK,NEED_INFO,NEED_HEADER,FLUSH,CONTINUE,SHUTDOWN_OUT,DONE}

    // other statics
    public static final int CHUNK_SIZE = 12;

    private State _state = State.START;
    private EndOfContent _endOfContent = EndOfContent.UNKNOWN_CONTENT;

    private long _contentPrepared = 0;
    private boolean _noContentResponse = false;
    private Boolean _persistent = null;

    private final int _send;
    private final static int SEND_SERVER = 0x01;
    private final static int SEND_XPOWEREDBY = 0x02;
    private final static Trie<Boolean> __assumedContentMethods = new ArrayTrie<>(8);
    static
    {
        __assumedContentMethods.put(HttpMethod.POST.asString(),Boolean.TRUE);
        __assumedContentMethods.put(HttpMethod.PUT.asString(),Boolean.TRUE);
    }
  
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
        _noContentResponse=false;
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
        return _noContentResponse;
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
                    _persistent=info.getHttpVersion().ordinal() > HttpVersion.HTTP_1_0.ordinal();
                    if (!_persistent && HttpMethod.CONNECT.is(info.getMethod()))
                        _persistent=true;
                }

                // prepare the header
                int pos=BufferUtil.flipToFill(header);
                try
                {
                    // generate ResponseLine
                    generateRequestLine(info,header);

                    if (info.getHttpVersion()==HttpVersion.HTTP_0_9)
                        throw new BadMessageException(INTERNAL_SERVER_ERROR_500,"HTTP/0.9 not supported");
                    
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
                catch(BadMessageException e)
                {
                    throw e;
                }
                catch(BufferOverflowException e)
                {
                    throw new BadMessageException(INTERNAL_SERVER_ERROR_500,"Request header too large",e);
                }
                catch(Exception e)
                {
                    throw new BadMessageException(INTERNAL_SERVER_ERROR_500,e.getMessage(),e);
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
                HttpVersion version=info.getHttpVersion();
                if (version==null)
                    throw new BadMessageException(INTERNAL_SERVER_ERROR_500,"No version");
                switch(version)
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
                        _persistent = false;
                        _endOfContent=EndOfContent.EOF_CONTENT;
                        if (BufferUtil.hasContent(content))
                            _contentPrepared+=content.remaining();
                        _state = last?State.COMPLETING:State.COMMITTED;
                        return Result.FLUSH;
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
                        _noContentResponse=true;

                        if (status!=HttpStatus.SWITCHING_PROTOCOLS_101 )
                        {
                            header.put(HttpTokens.CRLF);
                            _state=State.COMPLETING_1XX;
                            return Result.FLUSH;
                        }
                    }
                    else if (status==HttpStatus.NO_CONTENT_204 || status==HttpStatus.NOT_MODIFIED_304)
                    {
                        _noContentResponse=true;
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
                catch(BadMessageException e)
                {
                    throw e;
                }
                catch(BufferOverflowException e)
                {
                    throw new BadMessageException(INTERNAL_SERVER_ERROR_500,"Request header too large",e);
                }
                catch(Exception e)
                {
                    throw new BadMessageException(INTERNAL_SERVER_ERROR_500,e.getMessage(),e);
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
        header.put(request.getHttpVersion().toBytes());
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
    private void generateHeaders(MetaData info,ByteBuffer header,ByteBuffer content,boolean last)
    {
        final MetaData.Request request=(info instanceof MetaData.Request)?(MetaData.Request)info:null;
        final MetaData.Response response=(info instanceof MetaData.Response)?(MetaData.Response)info:null;
        
        if (LOG.isDebugEnabled())
        {
            LOG.debug("generateHeaders {} last={} content={}",info,last,BufferUtil.toDetailString(content));
            LOG.debug(info.getFields().toString());
        }
        
        // default field values
        int send=_send;
        HttpField transfer_encoding=null;
        boolean http11 = info.getHttpVersion() == HttpVersion.HTTP_1_1;
        boolean close = false;
        boolean chunked = false;
        boolean content_type = false;
        long content_length = info.getContentLength();
        boolean content_length_field = false;

        // Generate fields
        HttpFields fields = info.getFields();
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
                            if (content_length<0)
                                content_length = field.getLongValue();
                            else if (content_length!=field.getLongValue())
                                throw new BadMessageException(INTERNAL_SERVER_ERROR_500,String.format("Incorrect Content-Length %d!=%d",content_length,field.getLongValue()));
                            content_length_field = true;
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
                            if (http11)
                            {
                                // Don't add yet, treat this only as a hint that there is content
                                // with a preference to chunk if we can
                                transfer_encoding = field;
                                chunked = field.contains(HttpHeaderValue.CHUNKED.asString());
                            }
                            break;
                        }

                        case CONNECTION:
                        {
                            putTo(field,header);
                            if (field.contains(HttpHeaderValue.CLOSE.asString()))
                            {
                                close=true;
                                _persistent=false;
                            }

                            if (!http11 && field.contains(HttpHeaderValue.KEEP_ALIVE.asString()))
                            {
                                _persistent=true;
                            }
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
 
        // Can we work out the content length?
        if (last && content_length<0)
            content_length = _contentPrepared+BufferUtil.length(content);
        
        // Calculate how to end _content and connection, _content length and transfer encoding
        // settings from http://tools.ietf.org/html/rfc7230#section-3.3.3

        boolean assumed_content_request = request!=null && Boolean.TRUE.equals(__assumedContentMethods.get(request.getMethod()));
        boolean assumed_content = assumed_content_request || content_type || chunked;
        boolean nocontent_request = request!=null && content_length<=0 && !assumed_content;

        // If the message is known not to have content
        if (_noContentResponse || nocontent_request)
        {
            // We don't need to indicate a body length
            _endOfContent=EndOfContent.NO_CONTENT;
            
            // But it is an error if there actually is content
            if (_contentPrepared>0 || content_length>0)
            {
                if (_contentPrepared==0 && last)
                {
                    // TODO discard content for backward compatibility with 9.3 releases
                    // TODO review if it is still needed in 9.4 or can we just throw.
                    content.clear();
                    content_length=0;                        
                }
                else
                    throw new BadMessageException(INTERNAL_SERVER_ERROR_500,"Content for no content response");
            }
        }
        // Else if we are HTTP/1.1 and the content length is unknown and we are either persistent
        // or it is a request with content (which cannot EOF)
        else if (http11 && content_length<0 && (_persistent || assumed_content_request))
        {
            // we use chunking
            _endOfContent = EndOfContent.CHUNKED_CONTENT;
            chunked = true;

            // try to use user supplied encoding as it may have other values.
            if (transfer_encoding == null)
                header.put(TRANSFER_ENCODING_CHUNKED);
            else if (transfer_encoding.toString().endsWith(HttpHeaderValue.CHUNKED.toString()))
            {
                putTo(transfer_encoding,header);
                transfer_encoding = null;
            }
            else
                throw new BadMessageException(INTERNAL_SERVER_ERROR_500,"Bad Transfer-Encoding");
        }
        // Else if we known the content length and are a request or a persistent response, 
        else if (content_length>=0 && (request!=null || _persistent))
        {
            // Use the content length 
            _endOfContent = EndOfContent.CONTENT_LENGTH;
            putContentLength(header,content_length);
        }
        // Else if we are a response
        else if (response!=null)
        {
            // We must use EOF - even if we were trying to be persistent
            _endOfContent = EndOfContent.EOF_CONTENT;
            _persistent=false;
            if (content_length>=0 && ( content_length> 0 || assumed_content || content_length_field ))
                putContentLength(header,content_length);
            
            if (http11 && !close)
                header.put(CONNECTION_CLOSE);
        }
        // Else we must be a request
        else
        {
            // with no way to indicate body length
            throw new BadMessageException(INTERNAL_SERVER_ERROR_500,"Unknown content length for request");
        }

        if (LOG.isDebugEnabled())
            LOG.debug(_endOfContent.toString());
        
        // Add transfer encoding if it is not chunking
        if (transfer_encoding!=null && !chunked)
            putTo(transfer_encoding,header);         
        
        // Send server?
        int status=response!=null?response.getStatus():-1;
        if (status>199)
            header.put(SEND[send]);

        // end the header.
        header.put(HttpTokens.CRLF);        
    }

    /* ------------------------------------------------------------------------------- */
    private static void putContentLength(ByteBuffer header,long contentLength)
    {
        if (contentLength==0)
            header.put(CONTENT_LENGTH_0);
        else
        {
            header.put(HttpHeader.CONTENT_LENGTH.getBytesColonSpace());
            BufferUtil.putDecLong(header, contentLength);
            header.put(HttpTokens.CRLF);
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
    private static final byte[] CONNECTION_CLOSE = StringUtil.getBytes("Connection: close\015\012");
    private static final byte[] HTTP_1_1_SPACE = StringUtil.getBytes(HttpVersion.HTTP_1_1+" ");
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
