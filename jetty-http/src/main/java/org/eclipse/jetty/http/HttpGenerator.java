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
import java.io.InterruptedIOException;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.BufferCache.CachedBuffer;
import org.eclipse.jetty.io.BufferUtil;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
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
public class HttpGenerator extends AbstractGenerator
{
    private static final Logger LOG = Log.getLogger(HttpGenerator.class);

    // Build cache of response lines for status
    private static class Status
    {
        Buffer _reason;
        Buffer _schemeCode;
        Buffer _responseLine;
    }
    private static final Status[] __status = new Status[HttpStatus.MAX_CODE+1];
    static
    {
        int versionLength=HttpVersions.HTTP_1_1_BUFFER.length();

        for (int i=0;i<__status.length;i++)
        {
            HttpStatus.Code code = HttpStatus.getCode(i);
            if (code==null)
                continue;
            String reason=code.getMessage();
            byte[] bytes=new byte[versionLength+5+reason.length()+2];
            HttpVersions.HTTP_1_1_BUFFER.peek(0,bytes, 0, versionLength);
            bytes[versionLength+0]=' ';
            bytes[versionLength+1]=(byte)('0'+i/100);
            bytes[versionLength+2]=(byte)('0'+(i%100)/10);
            bytes[versionLength+3]=(byte)('0'+(i%10));
            bytes[versionLength+4]=' ';
            for (int j=0;j<reason.length();j++)
                bytes[versionLength+5+j]=(byte)reason.charAt(j);
            bytes[versionLength+5+reason.length()]=HttpTokens.CARRIAGE_RETURN;
            bytes[versionLength+6+reason.length()]=HttpTokens.LINE_FEED;

            __status[i] = new Status();
            __status[i]._reason=new ByteArrayBuffer(bytes,versionLength+5,bytes.length-versionLength-7,Buffer.IMMUTABLE);
            __status[i]._schemeCode=new ByteArrayBuffer(bytes,0,versionLength+5,Buffer.IMMUTABLE);
            __status[i]._responseLine=new ByteArrayBuffer(bytes,0,bytes.length,Buffer.IMMUTABLE);
        }
    }

    /* ------------------------------------------------------------------------------- */
    public static Buffer getReasonBuffer(int code)
    {
        Status status = code<__status.length?__status[code]:null;
        if (status!=null)
            return status._reason;
        return null;
    }


    // common _content
    private static final byte[] LAST_CHUNK =
    { (byte) '0', (byte) '\015', (byte) '\012', (byte) '\015', (byte) '\012'};
    private static final byte[] CONTENT_LENGTH_0 = StringUtil.getBytes("Content-Length: 0\015\012");
    private static final byte[] CONNECTION_KEEP_ALIVE = StringUtil.getBytes("Connection: keep-alive\015\012");
    private static final byte[] CONNECTION_CLOSE = StringUtil.getBytes("Connection: close\015\012");
    private static final byte[] CONNECTION_ = StringUtil.getBytes("Connection: ");
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
    protected boolean _bypass = false; // True if _content buffer can be written directly to endp and bypass the content buffer
    private boolean _needCRLF = false;
    private boolean _needEOC = false;
    private boolean _bufferChunked = false;


    /* ------------------------------------------------------------------------------- */
    /**
     * Constructor.
     *
     * @param buffers buffer pool
     * @param io the end point to use
     */
    public HttpGenerator(Buffers buffers, EndPoint io)
    {
        super(buffers,io);
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public void reset()
    {
        if (_persistent!=null && !_persistent && _endp!=null && !_endp.isOutputShutdown())
        {
            try
            {
                _endp.shutdownOutput();
            }
            catch(IOException e)
            {
                LOG.ignore(e);
            }
        }
        super.reset();
        if (_buffer!=null)
            _buffer.clear();
        if (_header!=null)
            _header.clear();
        if (_content!=null)
            _content=null;
        _bypass = false;
        _needCRLF = false;
        _needEOC = false;
        _bufferChunked=false;
        _method=null;
        _uri=null;
        _noContent=false;
    }

    /* ------------------------------------------------------------ */
    /**
     * Add content.
     *
     * @param content
     * @param last
     * @throws IllegalArgumentException if <code>content</code> is {@link Buffer#isImmutable immutable}.
     * @throws IllegalStateException If the request is not expecting any more content,
     *   or if the buffers are full and cannot be flushed.
     * @throws IOException if there is a problem flushing the buffers.
     */
    public void addContent(Buffer content, boolean last) throws IOException
    {
        if (_noContent)
            throw new IllegalStateException("NO CONTENT");

        if (_last || _state==STATE_END)
        {
            LOG.warn("Ignoring extra content {}",content);
            content.clear();
            return;
        }
        _last = last;

        // Handle any unfinished business?
        if (_content!=null && _content.length()>0 || _bufferChunked)
        {
            if (_endp.isOutputShutdown())
                throw new EofException();
            flushBuffer();
            if (_content != null && _content.length()>0)
            {
                if (_bufferChunked)
                {
                    Buffer nc=_buffers.getBuffer(_content.length()+CHUNK_SPACE+content.length());
                    nc.put(_content);
                    nc.put(HttpTokens.CRLF);
                    BufferUtil.putHexInt(nc, content.length());
                    nc.put(HttpTokens.CRLF);
                    nc.put(content);
                    content=nc;
                }
                else
                {
                    Buffer nc=_buffers.getBuffer(_content.length()+content.length());
                    nc.put(_content);
                    nc.put(content);
                    content=nc;
                }
            }
        }

        _content = content;
        _contentWritten += content.length();

        // Handle the _content
        if (_head)
        {
            content.clear();
            _content=null;
        }
        else if (_endp != null && (_buffer==null || _buffer.length()==0) && _content.length() > 0 && (_last || isCommitted() && _content.length()>1024))
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
            if (_content.length() == 0)
                _content = null;
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * send complete response.
     *
     * @param response
     */
    public void sendResponse(Buffer response) throws IOException
    {
        if (_noContent || _state!=STATE_HEADER || _content!=null && _content.length()>0 || _bufferChunked || _head )
            throw new IllegalStateException();

        _last = true;

        _content = response;
        _bypass = true;
        _state = STATE_FLUSHING;

        // TODO this is not exactly right, but should do.
        _contentLength =_contentWritten = response.length();

    }

    /* ------------------------------------------------------------ */
    /** Prepare buffer for unchecked writes.
     * Prepare the generator buffer to receive unchecked writes
     * @return the available space in the buffer.
     * @throws IOException
     */
    @Override
    public int prepareUncheckedAddContent() throws IOException
    {
        if (_noContent)
            return -1;

        if (_last || _state==STATE_END)
            return -1;

        // Handle any unfinished business?
        Buffer content = _content;
        if (content != null && content.length()>0 || _bufferChunked)
        {
            flushBuffer();
            if (content != null && content.length()>0 || _bufferChunked)
                throw new IllegalStateException("FULL");
        }

        // we better check we have a buffer
        if (_buffer == null)
            _buffer = _buffers.getBuffer();

        _contentWritten-=_buffer.length();

        // Handle the _content
        if (_head)
            return Integer.MAX_VALUE;

        return _buffer.space()-(_contentLength == HttpTokens.CHUNKED_CONTENT?CHUNK_SPACE:0);
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isBufferFull()
    {
        // Should we flush the buffers?
        return super.isBufferFull() || _bufferChunked || _bypass  || (_contentLength == HttpTokens.CHUNKED_CONTENT && _buffer != null && _buffer.space() < CHUNK_SPACE);
    }

    /* ------------------------------------------------------------ */
    public void send1xx(int code) throws IOException
    {
        if (_state != STATE_HEADER)
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
            while(_header.length()>0)
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
    @Override
    public boolean isRequest()
    {
        return _method!=null;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isResponse()
    {
        return _method==null;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void completeHeader(HttpFields fields, boolean allContentAdded) throws IOException
    {
        if (_state != STATE_HEADER)
            return;

        // handle a reset
        if (isResponse() && _status==0)
            throw new EofException();

        if (_last && !allContentAdded)
            throw new IllegalStateException("last?");
        _last = _last | allContentAdded;

        // get a header buffer
        if (_header == null)
            _header = _buffers.getHeader();

        boolean has_server = false;

        try
        {
            if (isRequest())
            {
                _persistent=true;

                if (_version == HttpVersions.HTTP_0_9_ORDINAL)
                {
                    _contentLength = HttpTokens.NO_CONTENT;
                    _header.put(_method);
                    _header.put((byte)' ');
                    _header.put(_uri.getBytes("UTF-8")); // TODO check
                    _header.put(HttpTokens.CRLF);
                    _state = STATE_FLUSHING;
                    _noContent=true;
                    return;
                }
                else
                {
                    _header.put(_method);
                    _header.put((byte)' ');
                    _header.put(_uri.getBytes("UTF-8")); // TODO check
                    _header.put((byte)' ');
                    _header.put(_version==HttpVersions.HTTP_1_0_ORDINAL?HttpVersions.HTTP_1_0_BUFFER:HttpVersions.HTTP_1_1_BUFFER);
                    _header.put(HttpTokens.CRLF);
                }
            }
            else
            {
                // Responses
                if (_version == HttpVersions.HTTP_0_9_ORDINAL)
                {
                    _persistent = false;
                    _contentLength = HttpTokens.EOF_CONTENT;
                    _state = STATE_CONTENT;
                    return;
                }
                else
                {
                    if (_persistent==null)
                        _persistent= (_version > HttpVersions.HTTP_1_0_ORDINAL);

                    // add response line
                    Status status = _status<__status.length?__status[_status]:null;

                    if (status==null)
                    {
                        _header.put(HttpVersions.HTTP_1_1_BUFFER);
                        _header.put((byte) ' ');
                        _header.put((byte) ('0' + _status / 100));
                        _header.put((byte) ('0' + (_status % 100) / 10));
                        _header.put((byte) ('0' + (_status % 10)));
                        _header.put((byte) ' ');
                        if (_reason==null)
                        {
                            _header.put((byte) ('0' + _status / 100));
                            _header.put((byte) ('0' + (_status % 100) / 10));
                            _header.put((byte) ('0' + (_status % 10)));
                        }
                        else
                            _header.put(_reason);
                        _header.put(HttpTokens.CRLF);
                    }
                    else
                    {
                        if (_reason==null)
                            _header.put(status._responseLine);
                        else
                        {
                            _header.put(status._schemeCode);
                            _header.put(_reason);
                            _header.put(HttpTokens.CRLF);
                        }
                    }

                    if (_status<200 && _status>=100 )
                    {
                        _noContent=true;
                        _content=null;
                        if (_buffer!=null)
                            _buffer.clear();
                        // end the header.

                        if (_status!=101 )
                        {
                            _header.put(HttpTokens.CRLF);
                            _state = STATE_CONTENT;
                            return;
                        }
                    }
                    else if (_status==204 || _status==304)
                    {
                        _noContent=true;
                        _content=null;
                        if (_buffer!=null)
                            _buffer.clear();
                    }
                }
            }

            // Add headers
            if (_status>=200 && _date!=null)
            {
                _header.put(HttpHeaders.DATE_BUFFER);
                _header.put((byte)':');
                _header.put((byte)' ');
                _header.put(_date);
                _header.put(CRLF);
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

                    switch (field.getNameOrdinal())
                    {
                        case HttpHeaders.CONTENT_LENGTH_ORDINAL:
                            content_length = field;
                            _contentLength = field.getLongValue();

                            if (_contentLength < _contentWritten || _last && _contentLength != _contentWritten)
                                content_length = null;

                            // write the field to the header buffer
                            field.putTo(_header);
                            break;

                        case HttpHeaders.CONTENT_TYPE_ORDINAL:
                            if (BufferUtil.isPrefix(MimeTypes.MULTIPART_BYTERANGES_BUFFER, field.getValueBuffer())) _contentLength = HttpTokens.SELF_DEFINING_CONTENT;

                            // write the field to the header buffer
                            content_type=true;
                            field.putTo(_header);
                            break;

                        case HttpHeaders.TRANSFER_ENCODING_ORDINAL:
                            if (_version == HttpVersions.HTTP_1_1_ORDINAL)
                                transfer_encoding = field;
                            // Do NOT add yet!
                            break;

                        case HttpHeaders.CONNECTION_ORDINAL:
                            if (isRequest())
                                field.putTo(_header);

                            int connection_value = field.getValueOrdinal();
                            switch (connection_value)
                            {
                                case -1:
                                {
                                    String[] values = field.getValue().split(",");
                                    for  (int i=0;values!=null && i<values.length;i++)
                                    {
                                        CachedBuffer cb = HttpHeaderValues.CACHE.get(values[i].trim());

                                        if (cb!=null)
                                        {
                                            switch(cb.getOrdinal())
                                            {
                                                case HttpHeaderValues.CLOSE_ORDINAL:
                                                    close=true;
                                                    if (isResponse())
                                                        _persistent=false;
                                                    keep_alive=false;
                                                    if (!_persistent && isResponse() && _contentLength == HttpTokens.UNKNOWN_CONTENT)
                                                        _contentLength = HttpTokens.EOF_CONTENT;
                                                    break;

                                                case HttpHeaderValues.KEEP_ALIVE_ORDINAL:
                                                    if (_version == HttpVersions.HTTP_1_0_ORDINAL)
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
                                case HttpHeaderValues.UPGRADE_ORDINAL:
                                {
                                    // special case for websocket connection ordering
                                    if (isResponse())
                                    {
                                        field.putTo(_header);
                                        continue;
                                    }
                                }
                                case HttpHeaderValues.CLOSE_ORDINAL:
                                {
                                    close=true;
                                    if (isResponse())
                                        _persistent=false;
                                    if (!_persistent && isResponse() && _contentLength == HttpTokens.UNKNOWN_CONTENT)
                                        _contentLength = HttpTokens.EOF_CONTENT;
                                    break;
                                }
                                case HttpHeaderValues.KEEP_ALIVE_ORDINAL:
                                {
                                    if (_version == HttpVersions.HTTP_1_0_ORDINAL)
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
                                    connection.append(field.getValue());
                                }
                            }

                            // Do NOT add yet!
                            break;

                        case HttpHeaders.SERVER_ORDINAL:
                            if (getSendServerVersion())
                            {
                                has_server=true;
                                field.putTo(_header);
                            }
                            break;

                        default:
                            // write the field to the header buffer
                            field.putTo(_header);
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
                            _header.put(HttpHeaders.CONTENT_LENGTH_BUFFER);
                            _header.put(HttpTokens.COLON);
                            _header.put((byte) ' ');
                            BufferUtil.putDecLong(_header, _contentLength);
                            _header.put(HttpTokens.CRLF);
                        }
                    }
                    else
                    {
                        // No idea, so we must assume that a body is coming
                        _contentLength = (!_persistent || _version < HttpVersions.HTTP_1_1_ORDINAL ) ? HttpTokens.EOF_CONTENT : HttpTokens.CHUNKED_CONTENT;
                        if (isRequest() && _contentLength==HttpTokens.EOF_CONTENT)
                        {
                            _contentLength=HttpTokens.NO_CONTENT;
                            _noContent=true;
                        }
                    }
                    break;

                case HttpTokens.NO_CONTENT:
                    if (content_length == null && isResponse() && _status >= 200 && _status != 204 && _status != 304)
                        _header.put(CONTENT_LENGTH_0);
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
                if (transfer_encoding != null && HttpHeaderValues.CHUNKED_ORDINAL != transfer_encoding.getValueOrdinal())
                {
                    String c = transfer_encoding.getValue();
                    if (c.endsWith(HttpHeaderValues.CHUNKED))
                        transfer_encoding.putTo(_header);
                    else
                        throw new IllegalArgumentException("BAD TE");
                }
                else
                    _header.put(TRANSFER_ENCODING_CHUNKED);
            }

            // Handle connection if need be
            if (_contentLength==HttpTokens.EOF_CONTENT)
            {
                keep_alive=false;
                _persistent=false;
            }

            if (isResponse())
            {
                if (!_persistent && (close || _version > HttpVersions.HTTP_1_0_ORDINAL))
                {
                    _header.put(CONNECTION_CLOSE);
                    if (connection!=null)
                    {
                        _header.setPutIndex(_header.putIndex()-2);
                        _header.put((byte)',');
                        _header.put(connection.toString().getBytes());
                        _header.put(CRLF);
                    }
                }
                else if (keep_alive)
                {
                    _header.put(CONNECTION_KEEP_ALIVE);
                    if (connection!=null)
                    {
                        _header.setPutIndex(_header.putIndex()-2);
                        _header.put((byte)',');
                        _header.put(connection.toString().getBytes());
                        _header.put(CRLF);
                    }
                }
                else if (connection!=null)
                {
                    _header.put(CONNECTION_);
                    _header.put(connection.toString().getBytes());
                    _header.put(CRLF);
                }
            }

            if (!has_server && _status>199 && getSendServerVersion())
                _header.put(SERVER);

            // end the header.
            _header.put(HttpTokens.CRLF);
            _state = STATE_CONTENT;

        }
        catch(ArrayIndexOutOfBoundsException e)
        {
            throw new RuntimeException("Header>"+_header.capacity(),e);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Complete the message.
     *
     * @throws IOException
     */
    @Override
    public void complete() throws IOException
    {
        if (_state == STATE_END)
            return;

        super.complete();

        if (_state < STATE_FLUSHING)
        {
            _state = STATE_FLUSHING;
            if (_contentLength == HttpTokens.CHUNKED_CONTENT)
                _needEOC = true;
        }

        flushBuffer();
    }

    /* ------------------------------------------------------------ */
    @Override
    public int flushBuffer() throws IOException
    {
        try
        {

            if (_state == STATE_HEADER)
                throw new IllegalStateException("State==HEADER");

            prepareBuffers();

            if (_endp == null)
            {
                if (_needCRLF && _buffer!=null)
                    _buffer.put(HttpTokens.CRLF);
                if (_needEOC && _buffer!=null && !_head)
                    _buffer.put(LAST_CHUNK);
                _needCRLF=false;
                _needEOC=false;
                return 0;
            }

            int total= 0;

            int len = -1;
            int to_flush = flushMask();
            int last_flush;

            do
            {
                last_flush=to_flush;
                switch (to_flush)
                {
                    case 7:
                        throw new IllegalStateException(); // should never happen!
                    case 6:
                        len = _endp.flush(_header, _buffer, null);
                        break;
                    case 5:
                        len = _endp.flush(_header, _content, null);
                        break;
                    case 4:
                        len = _endp.flush(_header);
                        break;
                    case 3:
                        len = _endp.flush(_buffer, _content, null);
                        break;
                    case 2:
                        len = _endp.flush(_buffer);
                        break;
                    case 1:
                        len = _endp.flush(_content);
                        break;
                    case 0:
                    {
                        len=0;
                        // Nothing more we can write now.
                        if (_header != null)
                            _header.clear();

                        _bypass = false;
                        _bufferChunked = false;

                        if (_buffer != null)
                        {
                            _buffer.clear();
                            if (_contentLength == HttpTokens.CHUNKED_CONTENT)
                            {
                                // reserve some space for the chunk header
                                _buffer.setPutIndex(CHUNK_SPACE);
                                _buffer.setGetIndex(CHUNK_SPACE);

                                // Special case handling for small left over buffer from
                                // an addContent that caused a buffer flush.
                                if (_content != null && _content.length() < _buffer.space() && _state != STATE_FLUSHING)
                                {
                                    _buffer.put(_content);
                                    _content.clear();
                                    _content=null;
                                }
                            }
                        }

                        // Are we completely finished for now?
                        if (!_needCRLF && !_needEOC && (_content==null || _content.length()==0))
                        {
                            if (_state == STATE_FLUSHING)
                                _state = STATE_END;

                            if (_state==STATE_END && _persistent != null && !_persistent && _status!=100 && _method==null)
                                _endp.shutdownOutput();
                        }
                        else
                            // Try to prepare more to write.
                            prepareBuffers();
                    }

                }

                if (len > 0)
                    total+=len;

                to_flush = flushMask();
            }
            // loop while progress is being made (OR we have prepared some buffers that might make progress)
            while (len>0 || (to_flush!=0 && last_flush==0));

            return total;
        }
        catch (IOException e)
        {
            LOG.ignore(e);
            throw (e instanceof EofException) ? e:new EofException(e);
        }
    }

    /* ------------------------------------------------------------ */
    private int flushMask()
    {
        return  ((_header != null && _header.length() > 0)?4:0)
        | ((_buffer != null && _buffer.length() > 0)?2:0)
        | ((_bypass && _content != null && _content.length() > 0)?1:0);
    }

    /* ------------------------------------------------------------ */
    private void prepareBuffers()
    {
        // if we are not flushing an existing chunk
        if (!_bufferChunked)
        {
            // Refill buffer if possible
            if (!_bypass && _content != null && _content.length() > 0 && _buffer != null && _buffer.space() > 0)
            {
                int len = _buffer.put(_content);
                _content.skip(len);
                if (_content.length() == 0)
                    _content = null;
            }

            // Chunk buffer if need be
            if (_contentLength == HttpTokens.CHUNKED_CONTENT)
            {
                if (_bypass && (_buffer==null||_buffer.length()==0) && _content!=null)
                {
                    // this is a bypass write
                    int size = _content.length();
                    _bufferChunked = true;

                    if (_header == null)
                        _header = _buffers.getHeader();

                    // if we need CRLF add this to header
                    if (_needCRLF)
                    {
                        if (_header.length() > 0) throw new IllegalStateException("EOC");
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
                    int size = _buffer.length();
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
                                if (_header.length() > 0) throw new IllegalStateException("EOC");
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
                if (_needEOC && (_content == null || _content.length() == 0))
                {
                    if (_header == null && _buffer == null)
                        _header = _buffers.getHeader();

                    if (_needCRLF)
                    {
                        if (_buffer == null && _header != null && _header.space() >= HttpTokens.CRLF.length)
                        {
                            _header.put(HttpTokens.CRLF);
                            _needCRLF = false;
                        }
                        else if (_buffer!=null && _buffer.space() >= HttpTokens.CRLF.length)
                        {
                            _buffer.put(HttpTokens.CRLF);
                            _needCRLF = false;
                        }
                    }

                    if (!_needCRLF && _needEOC)
                    {
                        if (_buffer == null && _header != null && _header.space() >= LAST_CHUNK.length)
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

        if (_content != null && _content.length() == 0)
            _content = null;

    }

    public int getBytesBuffered()
    {
        return(_header==null?0:_header.length())+
        (_buffer==null?0:_buffer.length())+
        (_content==null?0:_content.length());
    }

    public boolean isEmpty()
    {
        return (_header==null||_header.length()==0) &&
        (_buffer==null||_buffer.length()==0) &&
        (_content==null||_content.length()==0);
    }

    @Override
    public String toString()
    {
        Buffer header=_header;
        Buffer buffer=_buffer;
        Buffer content=_content;
        return String.format("%s{s=%d,h=%d,b=%d,c=%d}",
                getClass().getSimpleName(),
                _state,
                header == null ? -1 : header.length(),
                buffer == null ? -1 : buffer.length(),
                content == null ? -1 : content.length());
    }
}
