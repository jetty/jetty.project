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
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpParser implements Parser
{
    private static final Logger LOG = Log.getLogger(HttpParser.class);

    // States
    public static final int STATE_START=-14;
    public static final int STATE_METHOD=-13;
    public static final int STATE_RESPONSE_VERSION=-12;
    public static final int STATE_SPACE1=-11;
    public static final int STATE_STATUS=-10;
    public static final int STATE_URI=-9;
    public static final int STATE_SPACE2=-8;
    public static final int STATE_REQUEST_VERSION=-7;
    public static final int STATE_REASON=-6;
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
    private final RequestHandler _requestHandler;
    private final ResponseHandler _responseHandler;
    private HttpHeaders _header;
    private HttpHeaderValues _value;
    private int _responseStatus; 
    private boolean _persistent;

    /* ------------------------------------------------------------------------------- */
    private int _state=STATE_START;
    private String _field0;
    private String _field1;
    private byte _eol;
    private long _contentLength;
    private long _contentPosition;
    private int _chunkLength;
    private int _chunkPosition;
    private boolean _headResponse;

    /* ------------------------------------------------------------------------------- */
    /**
     * Constructor.
     */
    public HttpParser(RequestHandler handler)
    {
        _handler=handler;
        _requestHandler=handler;
        _responseHandler=null;
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Constructor.
     */
    public HttpParser(ResponseHandler handler)
    {
        _handler=handler;
        _requestHandler=null;
        _responseHandler=handler;
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
    public void parse(ByteBuffer buffer) throws IOException
    {
        if (_state==STATE_END)
            reset();
        if (_state!=STATE_START)
            throw new IllegalStateException("!START");

        // continue parsing
        while (_state != STATE_END && buffer.hasRemaining())
            if (!parseNext(buffer))
                return;
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until END state.
     * This method will parse any remaining content in the current buffer. It does not care about the
     * {@link #getState current state} of the parser.
     * @see #parse
     * @see #parseNext
     */
    public boolean parseAvailable(ByteBuffer buffer) throws IOException
    {
        boolean progress=parseNext(buffer);

        // continue parsing
        while (!isComplete() && buffer.hasRemaining())
        {
            progress |= parseNext(buffer);
        }
        return progress;
    }
    
    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until next Event.
     * @return an indication of progress 
     */
    public boolean parseNext(ByteBuffer buffer) throws IOException
    {
        try
        {
            int progress=0;

            if (_state == STATE_END)
                return false;

            if (_state == STATE_CONTENT && _contentPosition == _contentLength)
            {
                _state=STATE_END;
                _handler.messageComplete(_contentPosition);
                return true;
            }


            // Handle header states
            byte ch;
            int last=_state;
            int start=-1;
            int length=-1;
            
            while (_state<STATE_END && buffer.hasRemaining())
            {
                if (last!=_state)
                {
                    progress++;
                    last=_state;
                }

                ch=buffer.get();

                if (_eol == HttpTokens.CARRIAGE_RETURN && ch == HttpTokens.LINE_FEED)
                {
                    _eol=HttpTokens.LINE_FEED;
                    continue;
                }
                _eol=0;

                switch (_state)
                {
                    case STATE_START:
                        _contentLength=HttpTokens.UNKNOWN_CONTENT;
                        _header=null;
                        if (ch > HttpTokens.SPACE || ch<0)
                        {
                            start=buffer.position()-1;
                            _state=_requestHandler!=null?STATE_METHOD:STATE_RESPONSE_VERSION;
                        }
                        break;

                    case STATE_METHOD:
                        if (ch == HttpTokens.SPACE)
                        {
                            HttpMethods method=HttpMethods.CACHE.get(buffer,start,buffer.position()-start-1);
                            _field0=method==null?BufferUtil.toString(buffer,start,buffer.position()-start-1,StringUtil.__ISO_8859_1_CHARSET):method.toString();
                            _state=STATE_SPACE1;
                        }
                        else if (ch < HttpTokens.SPACE && ch>=0)
                        {
                            throw new HttpException(HttpStatus.BAD_REQUEST_400);
                        }
                        break;

                    case STATE_RESPONSE_VERSION:
                        if (ch == HttpTokens.SPACE)
                        {
                            int l=buffer.position()-start;
                            HttpVersions v=HttpVersions.CACHE.get(buffer,start,l);
                            _field0=v==null?BufferUtil.toString(buffer,start,l,StringUtil.__ISO_8859_1_CHARSET):v.toString();
                            start=-1;
                            _persistent=HttpVersions.HTTP_1_1==v;
                            _state=STATE_SPACE1;
                        }
                        else if (ch < HttpTokens.SPACE && ch>=0)
                        {
                            throw new HttpException(HttpStatus.BAD_REQUEST_400);
                        }
                        break;

                    case STATE_SPACE1:
                        if (ch > HttpTokens.SPACE || ch<0)
                        {
                            if (_responseHandler!=null)
                            {
                                _state=STATE_STATUS;
                                _responseStatus=ch-'0';
                            }
                            else
                            {
                                _state=STATE_URI;
                                start=buffer.position()-1;
                            }
                        }
                        else if (ch < HttpTokens.SPACE)
                        {
                            throw new HttpException(HttpStatus.BAD_REQUEST_400);
                        }
                        break;

                    case STATE_STATUS:
                        if (ch == HttpTokens.SPACE)
                        {
                            _state=STATE_SPACE2;
                        }
                        else if (ch>='0' && ch<='9')
                        {
                            _responseStatus=_responseStatus*10+(ch-'0');
                        }
                        else if (ch < HttpTokens.SPACE && ch>=0)
                        {
                            _responseHandler.startResponse(_field0, _responseStatus, null);
                            
                            _eol=ch;
                            _state=STATE_HEADER;
                            _field0=_field1=null;
                        }
                        else
                        {
                            throw new IllegalStateException();
                        }
                        break;

                    case STATE_URI:
                        if (ch == HttpTokens.SPACE)
                        {
                            _field1=BufferUtil.toString(buffer,start,buffer.position()-start-1,StringUtil.__UTF8_CHARSET);
                            start=-1;
                            _state=STATE_SPACE2;
                        }
                        else if (ch < HttpTokens.SPACE && ch>=0)
                        {
                            // HTTP/0.9
                            _field1=BufferUtil.toString(buffer,start,buffer.position()-start-1,StringUtil.__UTF8_CHARSET);
                            start=-1;
                            _requestHandler.startRequest(_field0,_field1,null);
                            _persistent=false;
                            _state=STATE_SEEKING_EOF;
                            _handler.headerComplete();
                            _handler.messageComplete(_contentPosition);
                        }
                        break;

                    case STATE_SPACE2:
                        if (ch > HttpTokens.SPACE || ch<0)
                        {
                            _state=_requestHandler!=null?STATE_REQUEST_VERSION:STATE_REASON;
                            start=buffer.position()-1;
                        }
                        else if (ch < HttpTokens.SPACE)
                        {
                            if (_responseHandler!=null)
                            {
                                _responseHandler.startResponse(_field0, _responseStatus, null);
                                _eol=ch;
                                _state=STATE_HEADER;
                                _field0=_field1=null;
                            }
                            else
                            {
                                // HTTP/0.9
                                _requestHandler.startRequest(_field0, _field1, null);
                                _persistent=false;
                                _state=STATE_SEEKING_EOF;
                                _handler.headerComplete();
                                _handler.messageComplete(_contentPosition);
                            }
                        }
                        break;

                    case STATE_REQUEST_VERSION:
                        if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                        {
                            HttpVersions v=HttpVersions.CACHE.get(buffer,start,buffer.position()-start-1);
                            String version=v==null?BufferUtil.toString(buffer,start,buffer.position()-start-1,StringUtil.__ISO_8859_1_CHARSET):v.toString();
                            start=-1;
                            
                            _requestHandler.startRequest(_field0, _field1, version);
                            _eol=ch;
                            _persistent=HttpVersions.HTTP_1_1==v;
                            _state=STATE_HEADER;
                            _field0=_field1=null;
                            continue;
                        }
                        break;

                    case STATE_REASON:
                        if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                        {
                            String reason=BufferUtil.toString(buffer,start,buffer.position()-start,StringUtil.__ISO_8859_1_CHARSET);
                            start=-1;
                           
                            _responseHandler.startResponse(_field0, _responseStatus, reason);
                            _eol=ch;
                            _state=STATE_HEADER;
                            _field0=_field1=null;
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
                                length=-1;
                                _state=STATE_HEADER_VALUE;
                                break;
                            }

                            default:
                            {
                                // handler last header if any
                                if (_field0!=null || _field1!=null)
                                {
                                    // Handle known headers
                                    if (_header!=null)
                                    {
                                        switch (_header)
                                        {
                                            case CONTENT_LENGTH:
                                                if (_contentLength != HttpTokens.CHUNKED_CONTENT && _responseStatus!=304 && _responseStatus!=204 && (_responseStatus<100 || _responseStatus>=200))
                                                {
                                                    try
                                                    {
                                                        _contentLength=Long.parseLong(_field1);
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

                                            case TRANSFER_ENCODING:
                                                if (_value==HttpHeaderValues.CHUNKED)
                                                    _contentLength=HttpTokens.CHUNKED_CONTENT;
                                                else
                                                {
                                                    if (_field1.endsWith(HttpHeaderValues.CHUNKED.toString()))
                                                        _contentLength=HttpTokens.CHUNKED_CONTENT;
                                                    else if (_field1.indexOf(HttpHeaderValues.CHUNKED.toString()) >= 0)
                                                        throw new HttpException(400,null);
                                                }
                                                break;

                                            case CONNECTION:
                                                switch(_value)
                                                {
                                                    case CLOSE:
                                                        _persistent=false;
                                                        break;

                                                    case KEEP_ALIVE:
                                                        _persistent=true;
                                                        break;

                                                    default: // No match, may be multi valued
                                                    {
                                                        for (String v : _field1.toString().split(","))
                                                        {
                                                            switch(HttpHeaderValues.CACHE.get(v.trim()))
                                                            {
                                                                case CLOSE:
                                                                    _persistent=false;
                                                                    break;

                                                                case KEEP_ALIVE:
                                                                    _persistent=true;
                                                                    break;
                                                            }
                                                        }
                                                        break;
                                                    }
                                                }
                                        }
                                    }

                                    _handler.parsedHeader(_field0, _field1);
                                }
                                _field0=_field1=null;
                                _header=null;
                                _value=null;


                                // now handle ch
                                if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                                {
                                    _eol=ch;
                                    _contentPosition=0;
                                    
                                    // End of headers!
                                    // work out the _content demarcation
                                    if (_contentLength == HttpTokens.UNKNOWN_CONTENT)
                                    {
                                        if (_responseStatus == 0  // request
                                                || _responseStatus == 304 // not-modified response
                                                || _responseStatus == 204 // no-content response
                                                || _responseStatus < 200) // 1xx response
                                            _contentLength=HttpTokens.NO_CONTENT;
                                        else
                                            _contentLength=HttpTokens.EOF_CONTENT;
                                    }

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
                                            break;

                                        default:
                                            _state=STATE_CONTENT;
                                            _handler.headerComplete(); // May recurse here !
                                            break;
                                    }
                                }
                                else
                                {
                                    // New header
                                    start=buffer.position()-1;
                                    length=1;
                                    _state=STATE_HEADER_NAME;
                                }
                            }
                        }

                        break;

                    case STATE_HEADER_NAME:
                        switch(ch)
                        {
                            case HttpTokens.CARRIAGE_RETURN:
                            case HttpTokens.LINE_FEED:
                                _eol=ch;
                                _header=HttpHeaders.CACHE.get(buffer,start,length);
                                _field0=_header==null?BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET):_header.toString();
                                start=length=-1;
                                _state=STATE_HEADER;
                                break;
                                
                            case HttpTokens.COLON:
                                _header=HttpHeaders.CACHE.get(buffer,start,length);
                                _field0=_header==null?BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET):_header.toString();
                                start=length=-1;
                                _state=STATE_HEADER_VALUE;
                                break;
                            case HttpTokens.SPACE:
                            case HttpTokens.TAB:
                                break;
                            default:
                            {
                                length=buffer.position()-start;
                                _state=STATE_HEADER_IN_NAME;
                            }
                        }

                        break;

                    case STATE_HEADER_IN_NAME:
                        switch(ch)
                        {
                            case HttpTokens.CARRIAGE_RETURN:
                            case HttpTokens.LINE_FEED:
                                _eol=ch;
                                _header=HttpHeaders.CACHE.get(buffer,start,length);
                                _field0=_header==null?BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET):_header.toString();
                                start=length=-1;
                                _state=STATE_HEADER;
                                break;
                                
                            case HttpTokens.COLON:
                                _header=HttpHeaders.CACHE.get(buffer,start,length);
                                _field0=_header==null?BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET):_header.toString();
                                start=length=-1;
                                _state=STATE_HEADER_VALUE;
                                break;
                            case HttpTokens.SPACE:
                            case HttpTokens.TAB:
                                _state=STATE_HEADER_NAME;
                                break;
                            default:
                                length++;
                        }
                        break;

                    case STATE_HEADER_VALUE:
                        switch(ch)
                        {
                            case HttpTokens.CARRIAGE_RETURN:
                            case HttpTokens.LINE_FEED:
                                _eol=ch;
                                if (length > 0)
                                {
                                    if (_field1!=null)
                                    {
                                        // multi line value!
                                        _value=null;
                                        _field1+=" "+BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET);
                                    }
                                    else if (HttpHeaderValues.hasKnownValues(_header))
                                    {
                                        _value=HttpHeaderValues.CACHE.get(buffer,start,length);
                                        _field1=_value.toString();
                                    }
                                    else
                                    {
                                        _field1=BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET);
                                    }
                                    start=length=-1;
                                }
                                _state=STATE_HEADER;
                                break;
                            case HttpTokens.SPACE:
                            case HttpTokens.TAB:
                                break;
                            default:
                            {
                                if (start==-1)
                                    start=buffer.position()-1;
                                length=buffer.position()-start;
                                _state=STATE_HEADER_IN_VALUE;
                            }
                        }
                        break;

                    case STATE_HEADER_IN_VALUE:
                        switch(ch)
                        {
                            case HttpTokens.CARRIAGE_RETURN:
                            case HttpTokens.LINE_FEED:
                                _eol=ch;
                                if (length > 0)
                                {
                                    if (_field1!=null)
                                    {
                                        // multi line value!
                                        _value=null;
                                        _field1+=" "+BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET);
                                    }
                                    else if (HttpHeaderValues.hasKnownValues(_header))
                                    {
                                        _value=HttpHeaderValues.CACHE.get(buffer,start,length);
                                        _field1=_value.toString();
                                    }
                                    else
                                    {
                                        _field1=BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET);
                                    }
                                    start=length=-1;
                                }
                                _state=STATE_HEADER;
                                break;
                            case HttpTokens.SPACE:
                            case HttpTokens.TAB:
                                _state=STATE_HEADER_VALUE;
                                break;
                            default:
                                length++;
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
            last=_state;
            ByteBuffer chunk;
            while (_state > STATE_END && buffer.hasRemaining())
            {
                if (last!=_state)
                {
                    progress++;
                    last=_state;
                }

                if (_eol == HttpTokens.CARRIAGE_RETURN && buffer.get(buffer.position()) == HttpTokens.LINE_FEED)
                {
                    _eol=buffer.get();
                    continue;
                }
                _eol=0;
                
                switch (_state)
                {
                    case STATE_EOF_CONTENT:
                        chunk=buffer.asReadOnlyBuffer();
                        _contentPosition += chunk.remaining();
                        buffer.position(buffer.position()+chunk.remaining());
                        _handler.content(chunk); // May recurse here
                        break;

                    case STATE_CONTENT:
                    {
                        long remaining=_contentLength - _contentPosition;
                        if (remaining == 0)
                        {
                            _state=_persistent?STATE_END:STATE_SEEKING_EOF;
                            _handler.messageComplete(_contentPosition);
                        }
                        else
                        {
                            chunk=buffer.asReadOnlyBuffer();

                            // limit content by expected size
                            if (chunk.remaining() > remaining)
                            {
                                // We can cast remaining to an int as we know that it is smaller than
                                // or equal to length which is already an int.
                                chunk.limit(chunk.position()+(int)remaining);
                            }

                            _contentPosition += chunk.remaining();
                            buffer.position(buffer.position()+chunk.remaining());
                            _handler.content(chunk); // May recurse here

                            if(_contentPosition == _contentLength)
                            {
                                _state=_persistent?STATE_END:STATE_SEEKING_EOF;
                                _handler.messageComplete(_contentPosition);
                            }
                        }
                        break;
                    }

                    case STATE_CHUNKED_CONTENT:
                    {
                        ch=buffer.get(buffer.position());
                        if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                            _eol=buffer.get();
                        else if (ch <= HttpTokens.SPACE)
                            buffer.get();
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
                        ch=buffer.get();
                        if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                        {
                            _eol=ch;

                            if (_chunkLength == 0)
                            {
                                if (_eol==HttpTokens.CARRIAGE_RETURN && buffer.hasRemaining() && buffer.get(buffer.position())==HttpTokens.LINE_FEED)
                                    _eol=buffer.get();
                                _state=_persistent?STATE_END:STATE_SEEKING_EOF;
                                _handler.messageComplete(_contentPosition);
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
                        ch=buffer.get();
                        if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                        {
                            _eol=ch;
                            if (_chunkLength == 0)
                            {
                                if (_eol==HttpTokens.CARRIAGE_RETURN && buffer.hasRemaining() && buffer.get(buffer.position())==HttpTokens.LINE_FEED)
                                    _eol=buffer.get();
                                _state=_persistent?STATE_END:STATE_SEEKING_EOF;
                                _handler.messageComplete(_contentPosition);
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
                        }
                        else 
                        {
                            chunk=buffer.asReadOnlyBuffer();

                            if (chunk.remaining() > remaining)
                                chunk.limit(chunk.position()+remaining);
                            remaining=chunk.remaining();
                            
                            _contentPosition += remaining;
                            _chunkPosition += remaining;
                            buffer.position(buffer.position()+remaining);
                            _handler.content(chunk); // May recurse here
                            
                            _handler.content(chunk); 
                        }
                    }

                    case STATE_SEEKING_EOF:
                    {         
                        buffer.clear().limit(0);
                        break;
                    }
                }

            }

            return progress>0;
        }
        catch(HttpException e)
        {
            _persistent=false;
            _state=STATE_SEEKING_EOF;
            throw e;
        }
    }
    

    /* ------------------------------------------------------------------------------- */
    public boolean onEOF() throws IOException
    {
        _persistent=false;

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

        if (!isComplete() && !isIdle())
            throw new EofException();

        return true;
    }



    /* ------------------------------------------------------------------------------- */
    public void reset()
    {
        // reset state
        _state=_persistent?STATE_START:_state==STATE_END?STATE_END:STATE_SEEKING_EOF;
        _contentLength=HttpTokens.UNKNOWN_CONTENT;
        _contentPosition=0;
        _responseStatus=0;


    }



    /* ------------------------------------------------------------------------------- */
    public void setState(int state)
    {
        this._state=state;
        _contentLength=HttpTokens.UNKNOWN_CONTENT;
    }


    /* ------------------------------------------------------------------------------- */
    @Override
    public String toString()
    {
        return String.format("%s{s=%d,c=%d}",
                getClass().getSimpleName(),
                _state,
                _contentLength);
    }


    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    public interface EventHandler
    {
        public void content(ByteBuffer ref) throws IOException;

        public void headerComplete() throws IOException;

        public void messageComplete(long contentLength) throws IOException;

        /**
         * This is the method called by parser when a HTTP Header name and value is found
         */
        public void parsedHeader(String name, String value) throws IOException;

        public void earlyEOF();
    }

    public interface RequestHandler extends EventHandler
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract void startRequest(String method, String uri, String version)
                throws IOException;
    }
    
    public interface ResponseHandler extends EventHandler
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract void startResponse(String version, int status, String reason)
                throws IOException;
    }



}
