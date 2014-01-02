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
import org.eclipse.jetty.io.BufferCache.CachedBuffer;
import org.eclipse.jetty.io.BufferUtil;
import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.io.View;
import org.eclipse.jetty.io.bio.StreamEndPoint;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpParser implements Parser
{
    private static final Logger LOG = Log.getLogger(HttpParser.class);

    // States
    public static final int STATE_START=-14;
    public static final int STATE_FIELD0=-13;
    public static final int STATE_SPACE1=-12;
    public static final int STATE_STATUS=-11;
    public static final int STATE_URI=-10;
    public static final int STATE_SPACE2=-9;
    public static final int STATE_END0=-8;
    public static final int STATE_END1=-7;
    public static final int STATE_FIELD2=-6;
    public static final int STATE_HEADER=-5;
    public static final int STATE_HEADER_NAME=-4;
    public static final int STATE_HEADER_IN_NAME=-3;
    public static final int STATE_HEADER_VALUE=-2;
    public static final int STATE_HEADER_IN_VALUE=-1;
    public static final int STATE_END=0;
    public static final int STATE_EOF_CONTENT=1;
    public static final int STATE_CONTENT=2;
    public static final int STATE_CHUNKED_CONTENT=3;
    public static final int STATE_CHUNK_SIZE=4;
    public static final int STATE_CHUNK_PARAMS=5;
    public static final int STATE_CHUNK=6;
    public static final int STATE_SEEKING_EOF=7;

    private final EventHandler _handler;
    private final Buffers _buffers; // source of buffers
    private final EndPoint _endp;
    private Buffer _header; // Buffer for header data (and small _content)
    private Buffer _body; // Buffer for large content
    private Buffer _buffer; // The current buffer in use (either _header or _content)
    private CachedBuffer _cached;
    private final View.CaseInsensitive _tok0; // Saved token: header name, request method or response version
    private final View.CaseInsensitive _tok1; // Saved token: header value, request URI or response code
    private String _multiLineValue;
    private int _responseStatus; // If >0 then we are parsing a response
    private boolean _forceContentBuffer;
    private boolean _persistent;

    /* ------------------------------------------------------------------------------- */
    protected final View  _contentView=new View(); // View of the content in the buffer for {@link Input}
    protected int _state=STATE_START;
    protected byte _eol;
    protected int _length;
    protected long _contentLength;
    protected long _contentPosition;
    protected int _chunkLength;
    protected int _chunkPosition;
    private boolean _headResponse;

    /* ------------------------------------------------------------------------------- */
    /**
     * Constructor.
     */
    public HttpParser(Buffer buffer, EventHandler handler)
    {
        _endp=null;
        _buffers=null;
        _header=buffer;
        _buffer=buffer;
        _handler=handler;

        _tok0=new View.CaseInsensitive(_header);
        _tok1=new View.CaseInsensitive(_header);
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Constructor.
     * @param buffers the buffers to use
     * @param endp the endpoint
     * @param handler the even handler
     */
    public HttpParser(Buffers buffers, EndPoint endp, EventHandler handler)
    {
        _buffers=buffers;
        _endp=endp;
        _handler=handler;
        _tok0=new View.CaseInsensitive();
        _tok1=new View.CaseInsensitive();
    }

    /* ------------------------------------------------------------------------------- */
    public long getContentLength()
    {
        return _contentLength;
    }

    /* ------------------------------------------------------------ */
    public long getContentRead()
    {
        return _contentPosition;
    }

    /* ------------------------------------------------------------ */
    /** Set if a HEAD response is expected
     * @param head
     */
    public void setHeadResponse(boolean head)
    {
        _headResponse=head;
    }

    /* ------------------------------------------------------------------------------- */
    public int getState()
    {
        return _state;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean inContentState()
    {
        return _state > 0;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean inHeaderState()
    {
        return _state < 0;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isChunking()
    {
        return _contentLength==HttpTokens.CHUNKED_CONTENT;
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return isState(STATE_START);
    }

    /* ------------------------------------------------------------ */
    public boolean isComplete()
    {
        return isState(STATE_END);
    }

    /* ------------------------------------------------------------ */
    public boolean isMoreInBuffer()
    throws IOException
    {
        return ( _header!=null && _header.hasContent() ||
             _body!=null && _body.hasContent());
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isState(int state)
    {
        return _state == state;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isPersistent()
    {
        return _persistent;
    }

    /* ------------------------------------------------------------------------------- */
    public void setPersistent(boolean persistent)
    {
        _persistent = persistent;
        if (!_persistent &&(_state==STATE_END || _state==STATE_START))
            _state=STATE_SEEKING_EOF;
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until {@link #STATE_END END} state.
     * If the parser is already in the END state, then it is {@link #reset reset} and re-parsed.
     * @throws IllegalStateException If the buffers have already been partially parsed.
     */
    public void parse() throws IOException
    {
        if (_state==STATE_END)
            reset();
        if (_state!=STATE_START)
            throw new IllegalStateException("!START");

        // continue parsing
        while (_state != STATE_END)
            if (parseNext()<0)
                return;
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until END state.
     * This method will parse any remaining content in the current buffer as long as there is
     * no unconsumed content. It does not care about the {@link #getState current state} of the parser.
     * @see #parse
     * @see #parseNext
     */
    public boolean parseAvailable() throws IOException
    {
        boolean progress=parseNext()>0;

        // continue parsing
        while (!isComplete() && _buffer!=null && _buffer.length()>0 && !_contentView.hasContent())
        {
            progress |= parseNext()>0;
        }
        return progress;
    }


    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until next Event.
     * @return an indication of progress <0 EOF, 0 no progress, >0 progress.
     */
    public int parseNext() throws IOException
    {
        try
        {
            int progress=0;

            if (_state == STATE_END)
                return 0;

            if (_buffer==null)
                _buffer=getHeaderBuffer();


            if (_state == STATE_CONTENT && _contentPosition == _contentLength)
            {
                _state=STATE_END;
                _handler.messageComplete(_contentPosition);
                return 1;
            }

            int length=_buffer.length();

            // Fill buffer if we can
            if (length == 0)
            {
                int filled=-1;
                IOException ex=null;
                try
                {
                    filled=fill();
                    LOG.debug("filled {}/{}",filled,_buffer.length());
                }
                catch(IOException e)
                {
                    LOG.debug(this.toString(),e);
                    ex=e;
                }

                if (filled > 0 )
                    progress++;
                else if (filled < 0 )
                {
                    _persistent=false;

                    // do we have content to deliver?
                    if (_state>STATE_END)
                    {
                        if (_buffer.length()>0 && !_headResponse)
                        {
                            Buffer chunk=_buffer.get(_buffer.length());
                            _contentPosition += chunk.length();
                            _contentView.update(chunk);
                            _handler.content(chunk); // May recurse here
                        }
                    }

                    // was this unexpected?
                    switch(_state)
                    {
                        case STATE_END:
                        case STATE_SEEKING_EOF:
                            _state=STATE_END;
                            break;

                        case STATE_EOF_CONTENT:
                            _state=STATE_END;
                            _handler.messageComplete(_contentPosition);
                            break;

                        default:
                            _state=STATE_END;
                            if (!_headResponse)
                                _handler.earlyEOF();
                            _handler.messageComplete(_contentPosition);
                    }

                    if (ex!=null)
                        throw ex;

                    if (!isComplete() && !isIdle())
                        throw new EofException();

                    return -1;
                }
                length=_buffer.length();
            }


            // Handle header states
            byte ch;
            byte[] array=_buffer.array();
            int last=_state;
            while (_state<STATE_END && length-->0)
            {
                if (last!=_state)
                {
                    progress++;
                    last=_state;
                }

                ch=_buffer.get();

                if (_eol == HttpTokens.CARRIAGE_RETURN)
                {
                    if (ch == HttpTokens.LINE_FEED)
                    {
                        _eol=HttpTokens.LINE_FEED;
                        continue;
                    }
                    throw new HttpException(HttpStatus.BAD_REQUEST_400);
                }
                _eol=0;

                switch (_state)
                {
                    case STATE_START:
                        _contentLength=HttpTokens.UNKNOWN_CONTENT;
                        _cached=null;
                        if (ch > HttpTokens.SPACE || ch<0)
                        {
                            _buffer.mark();
                            _state=STATE_FIELD0;
                        }
                        break;

                    case STATE_FIELD0:
                        if (ch == HttpTokens.SPACE)
                        {
                            _tok0.update(_buffer.markIndex(), _buffer.getIndex() - 1);
                            _responseStatus=HttpVersions.CACHE.get(_tok0)==null?-1:0;
                            _state=STATE_SPACE1;
                            continue;
                        }
                        else if (ch < HttpTokens.SPACE && ch>=0)
                        {
                            throw new HttpException(HttpStatus.BAD_REQUEST_400);
                        }
                        break;

                    case STATE_SPACE1:
                        if (ch > HttpTokens.SPACE || ch<0)
                        {
                            _buffer.mark();
                            if (_responseStatus>=0)
                            {
                                _state=STATE_STATUS;
                                _responseStatus=ch-'0';
                            }
                            else
                                _state=STATE_URI;
                        }
                        else if (ch < HttpTokens.SPACE)
                        {
                            throw new HttpException(HttpStatus.BAD_REQUEST_400);
                        }
                        break;

                    case STATE_STATUS:
                        if (ch == HttpTokens.SPACE)
                        {
                            _tok1.update(_buffer.markIndex(), _buffer.getIndex() - 1);
                            _state=STATE_SPACE2;
                            continue;
                        }
                        else if (ch>='0' && ch<='9')
                        {
                            _responseStatus=_responseStatus*10+(ch-'0');
                            continue;
                        }
                        else if (ch < HttpTokens.SPACE && ch>=0)
                        {
                            _handler.startResponse(HttpMethods.CACHE.lookup(_tok0), _responseStatus, null);
                            _eol=ch;
                            _state=STATE_HEADER;
                            _tok0.setPutIndex(_tok0.getIndex());
                            _tok1.setPutIndex(_tok1.getIndex());
                            _multiLineValue=null;
                            continue;
                        }
                        // not a digit, so must be a URI
                        _state=STATE_URI;
                        _responseStatus=-1;
                        break;

                    case STATE_URI:
                        if (ch == HttpTokens.SPACE)
                        {
                            _tok1.update(_buffer.markIndex(), _buffer.getIndex() - 1);
                            _state=STATE_SPACE2;
                            continue;
                        }
                        else if (ch < HttpTokens.SPACE && ch>=0)
                        {
                            // HTTP/0.9
                            _handler.startRequest(HttpMethods.CACHE.lookup(_tok0), _buffer.sliceFromMark(), null);
                            _persistent=false;
                            _state=STATE_SEEKING_EOF;
                            _handler.headerComplete();
                            _handler.messageComplete(_contentPosition);
                            return 1;
                        }
                        break;

                    case STATE_SPACE2:
                        if (ch > HttpTokens.SPACE || ch<0)
                        {
                            _buffer.mark();
                            _state=STATE_FIELD2;
                        }
                        else if (ch < HttpTokens.SPACE)
                        {
                            if (_responseStatus>0)
                            {
                                _handler.startResponse(HttpMethods.CACHE.lookup(_tok0), _responseStatus, null);
                                _eol=ch;
                                _state=STATE_HEADER;
                                _tok0.setPutIndex(_tok0.getIndex());
                                _tok1.setPutIndex(_tok1.getIndex());
                                _multiLineValue=null;
                            }
                            else
                            {
                                // HTTP/0.9
                                _handler.startRequest(HttpMethods.CACHE.lookup(_tok0), _tok1, null);
                                _persistent=false;
                                _state=STATE_SEEKING_EOF;
                                _handler.headerComplete();
                                _handler.messageComplete(_contentPosition);
                                return 1;
                            }
                        }
                        break;

                    case STATE_FIELD2:
                        if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                        {
                            Buffer version;
                            if (_responseStatus>0)
                                _handler.startResponse(version=HttpVersions.CACHE.lookup(_tok0), _responseStatus,_buffer.sliceFromMark());
                            else
                                _handler.startRequest(HttpMethods.CACHE.lookup(_tok0), _tok1, version=HttpVersions.CACHE.lookup(_buffer.sliceFromMark()));
                            _eol=ch;
                            _persistent=HttpVersions.CACHE.getOrdinal(version)>=HttpVersions.HTTP_1_1_ORDINAL;
                            _state=STATE_HEADER;
                            _tok0.setPutIndex(_tok0.getIndex());
                            _tok1.setPutIndex(_tok1.getIndex());
                            _multiLineValue=null;
                            continue;
                        }
                        break;

                    case STATE_HEADER:
                        switch(ch)
                        {
                            case HttpTokens.COLON:
                            case HttpTokens.SPACE:
                            case HttpTokens.TAB:
                            {
                                // header value without name - continuation?
                                _length=-1;
                                _state=STATE_HEADER_VALUE;
                                break;
                            }

                            default:
                            {
                                // handler last header if any
                                if (_cached!=null || _tok0.length() > 0 || _tok1.length() > 0 || _multiLineValue != null)
                                {
                                    Buffer header=_cached!=null?_cached:HttpHeaders.CACHE.lookup(_tok0);
                                    _cached=null;
                                    Buffer value=_multiLineValue == null ? _tok1 : new ByteArrayBuffer(_multiLineValue);

                                    int ho=HttpHeaders.CACHE.getOrdinal(header);
                                    if (ho >= 0)
                                    {
                                        int vo;

                                        switch (ho)
                                        {
                                            case HttpHeaders.CONTENT_LENGTH_ORDINAL:
                                                if (_contentLength != HttpTokens.CHUNKED_CONTENT )
                                                {
                                                    try
                                                    {
                                                        _contentLength=BufferUtil.toLong(value);
                                                    }
                                                    catch(NumberFormatException e)
                                                    {
                                                        LOG.ignore(e);
                                                        throw new HttpException(HttpStatus.BAD_REQUEST_400);
                                                    }
                                                    if (_contentLength <= 0)
                                                        _contentLength=HttpTokens.NO_CONTENT;
                                                }
                                                break;

                                            case HttpHeaders.TRANSFER_ENCODING_ORDINAL:
                                                value=HttpHeaderValues.CACHE.lookup(value);
                                                vo=HttpHeaderValues.CACHE.getOrdinal(value);
                                                if (HttpHeaderValues.CHUNKED_ORDINAL == vo)
                                                    _contentLength=HttpTokens.CHUNKED_CONTENT;
                                                else
                                                {
                                                    String c=value.toString(StringUtil.__ISO_8859_1);
                                                    if (c.endsWith(HttpHeaderValues.CHUNKED))
                                                        _contentLength=HttpTokens.CHUNKED_CONTENT;

                                                    else if (c.indexOf(HttpHeaderValues.CHUNKED) >= 0)
                                                        throw new HttpException(400,null);
                                                }
                                                break;

                                            case HttpHeaders.CONNECTION_ORDINAL:
                                                switch(HttpHeaderValues.CACHE.getOrdinal(value))
                                                {
                                                    case HttpHeaderValues.CLOSE_ORDINAL:
                                                        _persistent=false;
                                                        break;

                                                    case HttpHeaderValues.KEEP_ALIVE_ORDINAL:
                                                        _persistent=true;
                                                        break;

                                                    case -1: // No match, may be multi valued
                                                    {
                                                        for (String v : value.toString().split(","))
                                                        {
                                                            switch(HttpHeaderValues.CACHE.getOrdinal(v.trim()))
                                                            {
                                                                case HttpHeaderValues.CLOSE_ORDINAL:
                                                                    _persistent=false;
                                                                    break;

                                                                case HttpHeaderValues.KEEP_ALIVE_ORDINAL:
                                                                    _persistent=true;
                                                                    break;
                                                            }
                                                        }
                                                        break;
                                                    }
                                                }
                                        }
                                    }

                                    _handler.parsedHeader(header, value);
                                    _tok0.setPutIndex(_tok0.getIndex());
                                    _tok1.setPutIndex(_tok1.getIndex());
                                    _multiLineValue=null;
                                }
                                _buffer.setMarkIndex(-1);

                                // now handle ch
                                if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                                {
                                    // is it a response that cannot have a body?
                                    if (_responseStatus > 0  && // response  
                                       (_responseStatus == 304  || // not-modified response
                                        _responseStatus == 204 || // no-content response
                                        _responseStatus < 200)) // 1xx response
                                        _contentLength=HttpTokens.NO_CONTENT; // ignore any other headers set
                                    // else if we don't know framing
                                    else if (_contentLength == HttpTokens.UNKNOWN_CONTENT)
                                    {
                                        if (_responseStatus == 0  // request
                                                || _responseStatus == 304 // not-modified response
                                                || _responseStatus == 204 // no-content response
                                                || _responseStatus < 200) // 1xx response
                                            _contentLength=HttpTokens.NO_CONTENT;
                                        else
                                            _contentLength=HttpTokens.EOF_CONTENT;
                                    }

                                    _contentPosition=0;
                                    _eol=ch;
                                    if (_eol==HttpTokens.CARRIAGE_RETURN && _buffer.hasContent() && _buffer.peek()==HttpTokens.LINE_FEED)
                                        _eol=_buffer.get();

                                    // We convert _contentLength to an int for this switch statement because
                                    // we don't care about the amount of data available just whether there is some.
                                    switch (_contentLength > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) _contentLength)
                                    {
                                        case HttpTokens.EOF_CONTENT:
                                            _state=STATE_EOF_CONTENT;
                                            _handler.headerComplete(); // May recurse here !
                                            break;

                                        case HttpTokens.CHUNKED_CONTENT:
                                            _state=STATE_CHUNKED_CONTENT;
                                            _handler.headerComplete(); // May recurse here !
                                            break;

                                        case HttpTokens.NO_CONTENT:
                                            _handler.headerComplete();
                                            _state=_persistent||(_responseStatus>=100&&_responseStatus<200)?STATE_END:STATE_SEEKING_EOF;
                                            _handler.messageComplete(_contentPosition);
                                            return 1;

                                        default:
                                            _state=STATE_CONTENT;
                                            _handler.headerComplete(); // May recurse here !
                                            break;
                                    }
                                    return 1;
                                }
                                else
                                {
                                    // New header
                                    _length=1;
                                    _buffer.mark();
                                    _state=STATE_HEADER_NAME;

                                    // try cached name!
                                    if (array!=null)
                                    {
                                        _cached=HttpHeaders.CACHE.getBest(array, _buffer.markIndex(), length+1);

                                        if (_cached!=null)
                                        {
                                            _length=_cached.length();
                                            _buffer.setGetIndex(_buffer.markIndex()+_length);
                                            length=_buffer.length();
                                        }
                                    }
                                }
                            }
                        }

                        break;

                    case STATE_HEADER_NAME:
                        switch(ch)
                        {
                            case HttpTokens.CARRIAGE_RETURN:
                            case HttpTokens.LINE_FEED:
                                if (_length > 0)
                                    _tok0.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                                _eol=ch;
                                _state=STATE_HEADER;
                                break;
                            case HttpTokens.COLON:
                                if (_length > 0 && _cached==null)
                                    _tok0.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                                _length=-1;
                                _state=STATE_HEADER_VALUE;
                                break;
                            case HttpTokens.SPACE:
                            case HttpTokens.TAB:
                                break;
                            default:
                            {
                                _cached=null;
                                if (_length == -1)
                                    _buffer.mark();
                                _length=_buffer.getIndex() - _buffer.markIndex();
                                _state=STATE_HEADER_IN_NAME;
                            }
                        }

                        break;

                    case STATE_HEADER_IN_NAME:
                        switch(ch)
                        {
                            case HttpTokens.CARRIAGE_RETURN:
                            case HttpTokens.LINE_FEED:
                                if (_length > 0)
                                    _tok0.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                                _eol=ch;
                                _state=STATE_HEADER;
                                break;
                            case HttpTokens.COLON:
                                if (_length > 0 && _cached==null)
                                    _tok0.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                                _length=-1;
                                _state=STATE_HEADER_VALUE;
                                break;
                            case HttpTokens.SPACE:
                            case HttpTokens.TAB:
                                _state=STATE_HEADER_NAME;
                                break;
                            default:
                            {
                                _cached=null;
                                _length++;
                            }
                        }
                        break;

                    case STATE_HEADER_VALUE:
                        switch(ch)
                        {
                            case HttpTokens.CARRIAGE_RETURN:
                            case HttpTokens.LINE_FEED:
                                if (_length > 0)
                                {
                                    if (_tok1.length() == 0)
                                        _tok1.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                                    else
                                    {
                                        // Continuation line!
                                        if (_multiLineValue == null) _multiLineValue=_tok1.toString(StringUtil.__ISO_8859_1);
                                        _tok1.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                                        _multiLineValue += " " + _tok1.toString(StringUtil.__ISO_8859_1);
                                    }
                                }
                                _eol=ch;
                                _state=STATE_HEADER;
                                break;
                            case HttpTokens.SPACE:
                            case HttpTokens.TAB:
                                break;
                            default:
                            {
                                if (_length == -1)
                                    _buffer.mark();
                                _length=_buffer.getIndex() - _buffer.markIndex();
                                _state=STATE_HEADER_IN_VALUE;
                            }
                        }
                        break;

                    case STATE_HEADER_IN_VALUE:
                        switch(ch)
                        {
                            case HttpTokens.CARRIAGE_RETURN:
                            case HttpTokens.LINE_FEED:
                                if (_length > 0)
                                {
                                    if (_tok1.length() == 0)
                                        _tok1.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                                    else
                                    {
                                        // Continuation line!
                                        if (_multiLineValue == null) _multiLineValue=_tok1.toString(StringUtil.__ISO_8859_1);
                                        _tok1.update(_buffer.markIndex(), _buffer.markIndex() + _length);
                                        _multiLineValue += " " + _tok1.toString(StringUtil.__ISO_8859_1);
                                    }
                                }
                                _eol=ch;
                                _state=STATE_HEADER;
                                break;
                            case HttpTokens.SPACE:
                            case HttpTokens.TAB:
                                _state=STATE_HEADER_VALUE;
                                break;
                            default:
                                _length++;
                        }
                        break;
                }
            } // end of HEADER states loop

            // ==========================

            // Handle HEAD response
            if (_responseStatus>0 && _headResponse)
            {
                _state=_persistent||(_responseStatus>=100&&_responseStatus<200)?STATE_END:STATE_SEEKING_EOF;
                _handler.messageComplete(_contentLength);
            }


            // ==========================

            // Handle _content
            length=_buffer.length();
            Buffer chunk;
            last=_state;
            while (_state > STATE_END && length > 0)
            {
                if (last!=_state)
                {
                    progress++;
                    last=_state;
                }

                if (_eol == HttpTokens.CARRIAGE_RETURN && _buffer.peek() == HttpTokens.LINE_FEED)
                {
                    _eol=_buffer.get();
                    length=_buffer.length();
                    continue;
                }
                _eol=0;
                switch (_state)
                {
                    case STATE_EOF_CONTENT:
                        chunk=_buffer.get(_buffer.length());
                        _contentPosition += chunk.length();
                        _contentView.update(chunk);
                        _handler.content(chunk); // May recurse here
                        // TODO adjust the _buffer to keep unconsumed content
                        return 1;

                    case STATE_CONTENT:
                    {
                        long remaining=_contentLength - _contentPosition;
                        if (remaining == 0)
                        {
                            _state=_persistent?STATE_END:STATE_SEEKING_EOF;
                            _handler.messageComplete(_contentPosition);
                            return 1;
                        }

                        if (length > remaining)
                        {
                            // We can cast reamining to an int as we know that it is smaller than
                            // or equal to length which is already an int.
                            length=(int)remaining;
                        }

                        chunk=_buffer.get(length);
                        _contentPosition += chunk.length();
                        _contentView.update(chunk);
                        _handler.content(chunk); // May recurse here

                        if(_contentPosition == _contentLength)
                        {
                            _state=_persistent?STATE_END:STATE_SEEKING_EOF;
                            _handler.messageComplete(_contentPosition);
                        }
                        // TODO adjust the _buffer to keep unconsumed content
                        return 1;
                    }

                    case STATE_CHUNKED_CONTENT:
                    {
                        ch=_buffer.peek();
                        if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                            _eol=_buffer.get();
                        else if (ch <= HttpTokens.SPACE)
                            _buffer.get();
                        else
                        {
                            _chunkLength=0;
                            _chunkPosition=0;
                            _state=STATE_CHUNK_SIZE;
                        }
                        break;
                    }

                    case STATE_CHUNK_SIZE:
                    {
                        ch=_buffer.get();
                        if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                        {
                            _eol=ch;

                            if (_chunkLength == 0)
                            {
                                if (_eol==HttpTokens.CARRIAGE_RETURN && _buffer.hasContent() && _buffer.peek()==HttpTokens.LINE_FEED)
                                    _eol=_buffer.get();
                                _state=_persistent?STATE_END:STATE_SEEKING_EOF;
                                _handler.messageComplete(_contentPosition);
                                return 1;
                            }
                            else
                                _state=STATE_CHUNK;
                        }
                        else if (ch <= HttpTokens.SPACE || ch == HttpTokens.SEMI_COLON)
                            _state=STATE_CHUNK_PARAMS;
                        else if (ch >= '0' && ch <= '9')
                            _chunkLength=_chunkLength * 16 + (ch - '0');
                        else if (ch >= 'a' && ch <= 'f')
                            _chunkLength=_chunkLength * 16 + (10 + ch - 'a');
                        else if (ch >= 'A' && ch <= 'F')
                            _chunkLength=_chunkLength * 16 + (10 + ch - 'A');
                        else
                            throw new IOException("bad chunk char: " + ch);
                        break;
                    }

                    case STATE_CHUNK_PARAMS:
                    {
                        ch=_buffer.get();
                        if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                        {
                            _eol=ch;
                            if (_chunkLength == 0)
                            {
                                if (_eol==HttpTokens.CARRIAGE_RETURN && _buffer.hasContent() && _buffer.peek()==HttpTokens.LINE_FEED)
                                    _eol=_buffer.get();
                                _state=_persistent?STATE_END:STATE_SEEKING_EOF;
                                _handler.messageComplete(_contentPosition);
                                return 1;
                            }
                            else
                                _state=STATE_CHUNK;
                        }
                        break;
                    }

                    case STATE_CHUNK:
                    {
                        int remaining=_chunkLength - _chunkPosition;
                        if (remaining == 0)
                        {
                            _state=STATE_CHUNKED_CONTENT;
                            break;
                        }
                        else if (length > remaining)
                            length=remaining;
                        chunk=_buffer.get(length);
                        _contentPosition += chunk.length();
                        _chunkPosition += chunk.length();
                        _contentView.update(chunk);
                        _handler.content(chunk); // May recurse here
                        // TODO adjust the _buffer to keep unconsumed content
                        return 1;
                    }

                    case STATE_SEEKING_EOF:
                    {                        
                        // Close if there is more data than CRLF
                        if (_buffer.length()>2)
                        {
                            _state=STATE_END;
                            _endp.close();
                        }
                        else  
                        {
                            // or if the data is not white space
                            while (_buffer.length()>0)
                                if (!Character.isWhitespace(_buffer.get()))
                                {
                                    _state=STATE_END;
                                    _endp.close();
                                    _buffer.clear();
                                }
                        }
                        
                        _buffer.clear();
                        break;
                    }
                }

                length=_buffer.length();
            }

            return progress;
        }
        catch(HttpException e)
        {
            _persistent=false;
            _state=STATE_SEEKING_EOF;
            throw e;
        }
    }

    /* ------------------------------------------------------------------------------- */
    /** fill the buffers from the endpoint
     *
     */
    protected int fill() throws IOException
    {
        // Do we have a buffer?
        if (_buffer==null)
            _buffer=getHeaderBuffer();

        // Is there unconsumed content in body buffer
        if (_state>STATE_END && _buffer==_header && _header!=null && !_header.hasContent() && _body!=null && _body.hasContent())
        {
            _buffer=_body;
            return _buffer.length();
        }

        // Shall we switch to a body buffer?
        if (_buffer==_header && _state>STATE_END && _header.length()==0 && (_forceContentBuffer || (_contentLength-_contentPosition)>_header.capacity()) && (_body!=null||_buffers!=null))
        {
            if (_body==null)
                _body=_buffers.getBuffer();
            _buffer=_body;
        }

        // Do we have somewhere to fill from?
        if (_endp != null )
        {
            // Shall we compact the body?
            if (_buffer==_body || _state>STATE_END)
            {
                _buffer.compact();
            }

            // Are we full?
            if (_buffer.space() == 0)
            {
                LOG.warn("HttpParser Full for {} ",_endp);
                _buffer.clear();
                throw new HttpException(HttpStatus.REQUEST_ENTITY_TOO_LARGE_413, "Request Entity Too Large: "+(_buffer==_body?"body":"head"));
            }

            try
            {
                int filled = _endp.fill(_buffer);
                return filled;
            }
            catch(IOException e)
            {
                LOG.debug(e);
                throw (e instanceof EofException) ? e:new EofException(e);
            }
        }

        return -1;
    }

    /* ------------------------------------------------------------------------------- */
    public void reset()
    {
        // reset state
        _contentView.setGetIndex(_contentView.putIndex());
        _state=_persistent?STATE_START:(_endp.isInputShutdown()?STATE_END:STATE_SEEKING_EOF);
        _contentLength=HttpTokens.UNKNOWN_CONTENT;
        _contentPosition=0;
        _length=0;
        _responseStatus=0;

        // Consume LF if CRLF
        if (_eol == HttpTokens.CARRIAGE_RETURN && _buffer!=null && _buffer.hasContent() && _buffer.peek() == HttpTokens.LINE_FEED)
            _eol=_buffer.get();

        if (_body!=null && _body.hasContent())
        {
            // There is content in the body after the end of the request.
            // This is probably a pipelined header of the next request, so we need to
            // copy it to the header buffer.
            if (_header==null)
                getHeaderBuffer();
            else
            {
                _header.setMarkIndex(-1);
                _header.compact();
            }
            int take=_header.space();
            if (take>_body.length())
                take=_body.length();
            _body.peek(_body.getIndex(),take);
            _body.skip(_header.put(_body.peek(_body.getIndex(),take)));
        }

        if (_header!=null)
        {
            _header.setMarkIndex(-1);
            _header.compact();
        }
        if (_body!=null)
            _body.setMarkIndex(-1);

        _buffer=_header;
        returnBuffers();
    }


    /* ------------------------------------------------------------------------------- */
    public void returnBuffers()
    {
        if (_body!=null && !_body.hasContent() && _body.markIndex()==-1 && _buffers!=null)
        {
            if (_buffer==_body)
                _buffer=_header;
            if (_buffers!=null)
                _buffers.returnBuffer(_body);
            _body=null;
        }

        if (_header!=null && !_header.hasContent() && _header.markIndex()==-1 && _buffers!=null)
        {
            if (_buffer==_header)
                _buffer=null;
            _buffers.returnBuffer(_header);
            _header=null;
        }
    }

    /* ------------------------------------------------------------------------------- */
    public void setState(int state)
    {
        this._state=state;
        _contentLength=HttpTokens.UNKNOWN_CONTENT;
    }

    /* ------------------------------------------------------------------------------- */
    public String toString(Buffer buf)
    {
        return "state=" + _state + " length=" + _length + " buf=" + buf.hashCode();
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public String toString()
    {
        return String.format("%s{s=%d,l=%d,c=%d}",
                getClass().getSimpleName(),
                _state,
                _length,
                _contentLength);
    }

    /* ------------------------------------------------------------ */
    public Buffer getHeaderBuffer()
    {
        if (_header == null)
        {
            _header=_buffers.getHeader();
            _tok0.update(_header);
            _tok1.update(_header);
        }
        return _header;
    }

    /* ------------------------------------------------------------ */
    public Buffer getBodyBuffer()
    {
        return _body;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param force True if a new buffer will be forced to be used for content and the header buffer will not be used.
     */
    public void setForceContentBuffer(boolean force)
    {
        _forceContentBuffer=force;
    }

    /* ------------------------------------------------------------ */
    public Buffer blockForContent(long maxIdleTime) throws IOException
    {
        if (_contentView.length()>0)
            return _contentView;

        if (getState() <= STATE_END || isState(STATE_SEEKING_EOF))
            return null;

        try
        {
            parseNext();

            // parse until some progress is made (or IOException thrown for timeout)
            while(_contentView.length() == 0 && !(isState(HttpParser.STATE_END)||isState(HttpParser.STATE_SEEKING_EOF)) && _endp!=null && _endp.isOpen())
            {
                if (!_endp.isBlocking())
                {
                    if (parseNext()>0)
                        continue;

                    if (!_endp.blockReadable(maxIdleTime))
                    {
                        _endp.close();
                        throw new EofException("timeout");
                    }
                }

                parseNext();
            }
        }
        catch(IOException e)
        {
            // TODO is this needed?
            _endp.close();
            throw e;
        }

        return _contentView.length()>0?_contentView:null;
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see java.io.InputStream#available()
     */
    public int available() throws IOException
    {
        if (_contentView!=null && _contentView.length()>0)
            return _contentView.length();

        if (_endp.isBlocking())
        {
            if (_state>0 && _endp instanceof StreamEndPoint)
                return ((StreamEndPoint)_endp).getInputStream().available()>0?1:0;

            return 0;
        }

        parseNext();
        return _contentView==null?0:_contentView.length();
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public static abstract class EventHandler
    {
        public abstract void content(Buffer ref) throws IOException;

        public void headerComplete() throws IOException
        {
        }

        public void messageComplete(long contentLength) throws IOException
        {
        }

        /**
         * This is the method called by parser when a HTTP Header name and value is found
         */
        public void parsedHeader(Buffer name, Buffer value) throws IOException
        {
        }

        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract void startRequest(Buffer method, Buffer url, Buffer version)
                throws IOException;

        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract void startResponse(Buffer version, int status, Buffer reason)
                throws IOException;

        public void earlyEOF()
        {}
    }




}
