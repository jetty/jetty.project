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

import org.eclipse.jetty.http.HttpTokens.Content;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpParser
{
    private static final Logger LOG = Log.getLogger(HttpParser.class);

    // States
    public enum State
    {
        START,
        METHOD,
        RESPONSE_VERSION,
        SPACE1,
        STATUS,
        URI,
        SPACE2,
        REQUEST_VERSION,
        REASON,
        HEADER,
        HEADER_NAME,
        HEADER_IN_NAME,
        HEADER_VALUE,
        HEADER_IN_VALUE,
        END,
        EOF_CONTENT,
        CONTENT,
        CHUNKED_CONTENT,
        CHUNK_SIZE,
        CHUNK_PARAMS,
        CHUNK,
        SEEKING_EOF
    };

    private final EventHandler _handler;
    private final RequestHandler _requestHandler;
    private final ResponseHandler _responseHandler;
    private HttpHeader _header;
    private HttpHeaderValue _value;
    private int _responseStatus;
    private boolean _persistent;

    /* ------------------------------------------------------------------------------- */
    private State _state=State.START;
    private String _field0;
    private String _field1;
    private byte _eol;
    private Content _content;
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
    public State getState()
    {
        return _state;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean inContentState()
    {
        return _state.ordinal() > State.END.ordinal();
    }

    /* ------------------------------------------------------------------------------- */
    public boolean inHeaderState()
    {
        return _state.ordinal() < State.END.ordinal();
    }
    
    /* ------------------------------------------------------------------------------- */
    public boolean isInContent()
    {
        return _content!=Content.NO_CONTENT && _content!=Content.UNKNOWN_CONTENT;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isChunking()
    {
        return _content==Content.CHUNKED_CONTENT;
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return isState(State.START);
    }

    /* ------------------------------------------------------------ */
    public boolean isComplete()
    {
        return isState(State.END);
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isState(State state)
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
        if (!_persistent &&(_state==State.END || _state==State.START))
            _state=State.SEEKING_EOF;
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until {@link #END END} state.
     * If the parser is already in the END state, then it is {@link #reset reset} and re-parsed.
     * @throws IllegalStateException If the buffers have already been partially parsed.
     */
    public void parseAll(ByteBuffer buffer) throws IOException
    {
        if (_state==State.END)
            reset();
        if (_state!=State.START)
            throw new IllegalStateException("!START");

        // continue parsing
        while (_state != State.END && buffer.hasRemaining())
        {
            int remaining=buffer.remaining();
            parseNext(buffer);
            if (remaining==buffer.remaining())
                break;
        }
    }


    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until next Event.
     * @return True if an {@link RequestHandler} method was called and it returned true;
     */
    public boolean parseNext(ByteBuffer buffer) throws IOException
    {
        int start=-1;
        State startState=null;

        try
        {
            if (_state == State.END)
                return false;

            if (_state == State.CONTENT && _contentPosition == _contentLength)
            {
                _state=State.END;
                if(_handler.messageComplete(_contentPosition))
                    return true;
            }

            // Handle header states
            byte ch;
            int length=-1;
            boolean at_next=false;

            while (_state.ordinal()<State.END.ordinal() && buffer.hasRemaining() && !at_next)
            {
                ch=buffer.get();

                if (_eol == HttpTokens.CARRIAGE_RETURN && ch == HttpTokens.LINE_FEED)
                {
                    _eol=HttpTokens.LINE_FEED;
                    continue;
                }
                _eol=0;

                switch (_state)
                {
                    case START:
                        _content=Content.UNKNOWN_CONTENT;
                        _header=null;
                        if (ch > HttpTokens.SPACE || ch<0)
                        {
                            start=buffer.position()-1;
                            startState=_state;
                            _state=_requestHandler!=null?State.METHOD:State.RESPONSE_VERSION;
                        }
                        break;

                    case METHOD:
                        if (ch == HttpTokens.SPACE)
                        {
                            HttpMethod method=HttpMethod.CACHE.get(buffer,start,buffer.position()-start-1);
                            _field0=method==null?BufferUtil.toString(buffer,start,buffer.position()-start-1,StringUtil.__ISO_8859_1_CHARSET):method.toString();
                            _state=State.SPACE1;
                        }
                        else if (ch < HttpTokens.SPACE && ch>=0)
                        {
                            throw new HttpException(HttpStatus.BAD_REQUEST_400);
                        }
                        break;

                    case RESPONSE_VERSION:
                        if (ch == HttpTokens.SPACE)
                        {
                            HttpVersion v=HttpVersion.CACHE.get(buffer,start,buffer.position()-start-1);
                            _field0=v==null?BufferUtil.toString(buffer,start,buffer.position()-start-1,StringUtil.__ISO_8859_1_CHARSET):v.toString();
                            start=-1;
                            _persistent=HttpVersion.HTTP_1_1==v;
                            _state=State.SPACE1;
                        }
                        else if (ch < HttpTokens.SPACE && ch>=0)
                        {
                            throw new HttpException(HttpStatus.BAD_REQUEST_400);
                        }
                        break;

                    case SPACE1:
                        if (ch > HttpTokens.SPACE || ch<0)
                        {
                            if (_responseHandler!=null)
                            {
                                _state=State.STATUS;
                                _responseStatus=ch-'0';
                            }
                            else
                            {
                                start=buffer.position()-1;
                                startState=_state;
                                _state=State.URI;
                            }
                        }
                        else if (ch < HttpTokens.SPACE)
                        {
                            throw new HttpException(HttpStatus.BAD_REQUEST_400);
                        }
                        break;

                    case STATUS:
                        if (ch == HttpTokens.SPACE)
                        {
                            _state=State.SPACE2;
                        }
                        else if (ch>='0' && ch<='9')
                        {
                            _responseStatus=_responseStatus*10+(ch-'0');
                        }
                        else if (ch < HttpTokens.SPACE && ch>=0)
                        {
                            at_next|=_responseHandler.startResponse(_field0, _responseStatus, null);

                            _eol=ch;
                            _state=State.HEADER;
                            _field0=_field1=null;
                        }
                        else
                        {
                            throw new IllegalStateException();
                        }
                        break;

                    case URI:
                        if (ch == HttpTokens.SPACE)
                        {
                            _field1=BufferUtil.toString(buffer,start,buffer.position()-start-1,StringUtil.__UTF8_CHARSET);
                            start=-1;
                            _state=State.SPACE2;
                        }
                        else if (ch < HttpTokens.SPACE && ch>=0)
                        {
                            // HTTP/0.9
                            _field1=BufferUtil.toString(buffer,start,buffer.position()-start-1,StringUtil.__UTF8_CHARSET);
                            start=-1;
                            at_next|=_requestHandler.startRequest(_field0,_field1,null);
                            _persistent=false;
                            _state=State.SEEKING_EOF;
                            at_next|=_handler.headerComplete();
                            at_next|=_handler.messageComplete(_contentPosition);
                        }
                        break;

                    case SPACE2:
                        if (ch > HttpTokens.SPACE || ch<0)
                        {
                            start=buffer.position()-1;
                            startState=_state;
                            _state=_requestHandler!=null?State.REQUEST_VERSION:State.REASON;
                        }
                        else if (ch < HttpTokens.SPACE)
                        {
                            if (_responseHandler!=null)
                            {
                                at_next|=_responseHandler.startResponse(_field0, _responseStatus, null);
                                _eol=ch;
                                _state=State.HEADER;
                                _field0=_field1=null;
                            }
                            else
                            {
                                // HTTP/0.9
                                at_next|=_requestHandler.startRequest(_field0, _field1, null);
                                _persistent=false;
                                _state=State.SEEKING_EOF;
                                at_next|=_handler.headerComplete();
                                at_next|=_handler.messageComplete(_contentPosition);
                            }
                        }
                        break;

                    case REQUEST_VERSION:
                        if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                        {
                            HttpVersion v=HttpVersion.CACHE.get(buffer,start,buffer.position()-start-1);
                            String version=v==null?BufferUtil.toString(buffer,start,buffer.position()-start-1,StringUtil.__ISO_8859_1_CHARSET):v.toString();
                            start=-1;

                            at_next|=_requestHandler.startRequest(_field0, _field1, version);
                            _eol=ch;
                            _persistent=HttpVersion.HTTP_1_1==v;
                            _state=State.HEADER;
                            _field0=_field1=null;
                            continue;
                        }
                        break;

                    case REASON:
                        if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                        {
                            String reason=BufferUtil.toString(buffer,start,buffer.position()-start-1,StringUtil.__ISO_8859_1_CHARSET);
                            start=-1;

                            at_next|=_responseHandler.startResponse(_field0, _responseStatus, reason);
                            _eol=ch;
                            _state=State.HEADER;
                            _field0=_field1=null;
                            continue;
                        }
                        break;

                    case HEADER:
                        switch(ch)
                        {
                            case HttpTokens.COLON:
                            case HttpTokens.SPACE:
                            case HttpTokens.TAB:
                            {
                                // header value without name - continuation?
                                length=-1;
                                _state=State.HEADER_VALUE;
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
                                                if (_content != Content.CHUNKED_CONTENT && _responseStatus!=304 && _responseStatus!=204 && (_responseStatus<100 || _responseStatus>=200))
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
                                                        _content=Content.NO_CONTENT;
                                                    else 
                                                        _content=Content.CONTENT_LENGTH;
                                                }
                                                break;

                                            case TRANSFER_ENCODING:
                                                if (_value==HttpHeaderValue.CHUNKED)
                                                    _content=Content.CHUNKED_CONTENT;
                                                else
                                                {
                                                    if (_field1.endsWith(HttpHeaderValue.CHUNKED.toString()))
                                                        _content=Content.CHUNKED_CONTENT;
                                                    else if (_field1.indexOf(HttpHeaderValue.CHUNKED.toString()) >= 0)
                                                        throw new HttpException(400,null);
                                                }
                                                break;

                                            case CONNECTION:
                                                switch(_value==null?HttpHeaderValue.UNKNOWN:_value)
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
                                                            switch(HttpHeaderValue.CACHE.get(v.trim()))
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

                                    at_next|=_handler.parsedHeader(_header, _field0, _field1);
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
                                    if (_content == Content.UNKNOWN_CONTENT)
                                    {
                                        if (_responseStatus == 0  // request
                                                || _responseStatus == 304 // not-modified response
                                                || _responseStatus == 204 // no-content response
                                                || _responseStatus < 200) // 1xx response
                                            _content=Content.NO_CONTENT;
                                        else
                                            _content=Content.EOF_CONTENT;
                                    }

                                    // How is the message ended?
                                    switch (_content)
                                    {
                                        case EOF_CONTENT:
                                            _state=State.EOF_CONTENT;
                                            at_next|=_handler.headerComplete(); // May recurse here !
                                            break;

                                        case CHUNKED_CONTENT:
                                            _state=State.CHUNKED_CONTENT;
                                            at_next|=_handler.headerComplete(); // May recurse here !
                                            break;

                                        case NO_CONTENT:
                                            at_next|=_handler.headerComplete();
                                            _state=_persistent||(_responseStatus>=100&&_responseStatus<200)?State.END:State.SEEKING_EOF;
                                            at_next|=_handler.messageComplete(_contentPosition);
                                            break;

                                        default:
                                            _state=State.CONTENT;
                                            at_next|=_handler.headerComplete(); // May recurse here !
                                            break;
                                    }
                                }
                                else
                                {
                                    // New header
                                    start=buffer.position()-1;
                                    startState=_state;
                                    length=1;
                                    _state=State.HEADER_NAME;
                                }
                            }
                        }

                        break;

                    case HEADER_NAME:
                        switch(ch)
                        {
                            case HttpTokens.CARRIAGE_RETURN:
                            case HttpTokens.LINE_FEED:
                                _eol=ch;
                                _header=HttpHeader.CACHE.get(buffer,start,length);
                                _field0=_header==null?BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET):_header.toString();
                                start=length=-1;
                                _state=State.HEADER;
                                break;

                            case HttpTokens.COLON:
                                _header=HttpHeader.CACHE.get(buffer,start,length);
                                _field0=_header==null?BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET):_header.toString();
                                start=length=-1;
                                _state=State.HEADER_VALUE;
                                break;
                            case HttpTokens.SPACE:
                            case HttpTokens.TAB:
                                break;
                            default:
                            {
                                length=buffer.position()-start;
                                _state=State.HEADER_IN_NAME;
                            }
                        }

                        break;

                    case HEADER_IN_NAME:
                        switch(ch)
                        {
                            case HttpTokens.CARRIAGE_RETURN:
                            case HttpTokens.LINE_FEED:
                                _eol=ch;
                                _header=HttpHeader.CACHE.get(buffer,start,length);
                                _field0=_header==null?BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET):_header.toString();
                                start=length=-1;
                                _state=State.HEADER;
                                break;

                            case HttpTokens.COLON:
                                _header=HttpHeader.CACHE.get(buffer,start,length);
                                _field0=_header==null?BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET):_header.toString();
                                start=length=-1;
                                _state=State.HEADER_VALUE;
                                break;
                            case HttpTokens.SPACE:
                            case HttpTokens.TAB:
                                _state=State.HEADER_NAME;
                                break;
                            default:
                                length++;
                        }
                        break;

                    case HEADER_VALUE:
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
                                    else if (HttpHeaderValue.hasKnownValues(_header))
                                    {
                                        _value=HttpHeaderValue.CACHE.get(buffer,start,length);
                                        _field1=_value.toString();
                                    }
                                    else
                                    {
                                        _field1=BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET);
                                    }
                                    start=length=-1;
                                }
                                _state=State.HEADER;
                                break;
                            case HttpTokens.SPACE:
                            case HttpTokens.TAB:
                                break;
                            default:
                            {
                                if (start==-1)
                                {
                                    start=buffer.position()-1;
                                    startState=_state;
                                }
                                length=buffer.position()-start;
                                _state=State.HEADER_IN_VALUE;
                            }
                        }
                        break;

                    case HEADER_IN_VALUE:
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
                                    else if (HttpHeaderValue.hasKnownValues(_header))
                                    {
                                        _value=HttpHeaderValue.CACHE.get(buffer,start,length);
                                        _field1=_value!=null?_value.toString():BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET);
                                    }
                                    else
                                    {
                                        _field1=BufferUtil.toString(buffer,start,length,StringUtil.__ISO_8859_1_CHARSET);
                                    }
                                    start=length=-1;
                                }
                                _state=State.HEADER;
                                break;
                            case HttpTokens.SPACE:
                            case HttpTokens.TAB:
                                _state=State.HEADER_VALUE;
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
                _state=_persistent||(_responseStatus>=100&&_responseStatus<200)?State.END:State.SEEKING_EOF;
                at_next|=_handler.messageComplete(_contentLength);
            }


            // ==========================

            // Handle _content
            ByteBuffer chunk;
            while (_state.ordinal() > State.END.ordinal() && buffer.hasRemaining())
            {
                if (_eol == HttpTokens.CARRIAGE_RETURN && buffer.get(buffer.position()) == HttpTokens.LINE_FEED)
                {
                    _eol=buffer.get();
                    continue;
                }
                _eol=0;

                switch (_state)
                {
                    case EOF_CONTENT:
                        chunk=buffer.asReadOnlyBuffer();
                        _contentPosition += chunk.remaining();
                        buffer.position(buffer.position()+chunk.remaining());
                        at_next|=_handler.content(chunk); // May recurse here
                        break;

                    case CONTENT:
                    {
                        long remaining=_contentLength - _contentPosition;
                        if (remaining == 0)
                        {
                            _state=_persistent?State.END:State.SEEKING_EOF;
                            at_next|=_handler.messageComplete(_contentPosition);
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
                            at_next|=_handler.content(chunk); // May recurse here

                            if(_contentPosition == _contentLength)
                            {
                                _state=_persistent?State.END:State.SEEKING_EOF;
                                at_next|=_handler.messageComplete(_contentPosition);
                            }
                        }
                        break;
                    }

                    case CHUNKED_CONTENT:
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
                            _state=State.CHUNK_SIZE;
                        }
                        break;
                    }

                    case CHUNK_SIZE:
                    {
                        ch=buffer.get();
                        if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                        {
                            _eol=ch;

                            if (_chunkLength == 0)
                            {
                                if (_eol==HttpTokens.CARRIAGE_RETURN && buffer.hasRemaining() && buffer.get(buffer.position())==HttpTokens.LINE_FEED)
                                    _eol=buffer.get();
                                _state=_persistent?State.END:State.SEEKING_EOF;
                                at_next|=_handler.messageComplete(_contentPosition);
                            }
                            else
                                _state=State.CHUNK;
                        }
                        else if (ch <= HttpTokens.SPACE || ch == HttpTokens.SEMI_COLON)
                            _state=State.CHUNK_PARAMS;
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

                    case CHUNK_PARAMS:
                    {
                        ch=buffer.get();
                        if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                        {
                            _eol=ch;
                            if (_chunkLength == 0)
                            {
                                if (_eol==HttpTokens.CARRIAGE_RETURN && buffer.hasRemaining() && buffer.get(buffer.position())==HttpTokens.LINE_FEED)
                                    _eol=buffer.get();
                                _state=_persistent?State.END:State.SEEKING_EOF;
                                at_next|=_handler.messageComplete(_contentPosition);
                            }
                            else
                                _state=State.CHUNK;
                        }
                        break;
                    }

                    case CHUNK:
                    {
                        int remaining=_chunkLength - _chunkPosition;
                        if (remaining == 0)
                        {
                            _state=State.CHUNKED_CONTENT;
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
                            at_next|=_handler.content(chunk); // May recurse here
                        }
                        break;
                    }

                    case SEEKING_EOF:
                    {
                        buffer.clear().limit(0);
                        break;
                    }
                }

            }

            return at_next;
        }
        catch(HttpException e)
        {
            _persistent=false;
            _state=State.SEEKING_EOF;
            throw e;
        }
        finally
        {
            if (start>=0)
            {
                buffer.position(start);
                _state=startState;
            }

        }
    }


    /* ------------------------------------------------------------------------------- */
    public boolean onEOF() throws IOException
    {
        _persistent=false;

        // was this unexpected?
        switch(_state)
        {
            case END:
            case SEEKING_EOF:
                _state=State.END;
                break;

            case EOF_CONTENT:
                _state=State.END;
                _handler.messageComplete(_contentPosition);
                break;

            default:
                _state=State.END;
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
        _state=_persistent?State.START:_state==State.END?State.END:State.SEEKING_EOF;
        _content=Content.UNKNOWN_CONTENT;
        _contentPosition=0;
        _responseStatus=0;


    }



    /* ------------------------------------------------------------------------------- */
    public void setState(State state)
    {
        this._state=state;
        _content=Content.UNKNOWN_CONTENT;
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
    /* Event Handler interface
     * These methods return true if they want parsing to return to
     * the caller.
     */
    public interface EventHandler
    {
        public boolean content(ByteBuffer ref) throws IOException;

        public boolean headerComplete() throws IOException;

        public boolean messageComplete(long contentLength) throws IOException;

        /**
         * This is the method called by parser when a HTTP Header name and value is found
         * @param header The HttpHeader value if there is a match
         * @param name The String value of the header name
         * @param value The String value of the header
         * @return
         * @throws IOException
         */
        public boolean parsedHeader(HttpHeader header, String name, String value) throws IOException;

        public boolean earlyEOF();
    }

    public interface RequestHandler extends EventHandler
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract boolean startRequest(String method, String uri, String version)
                throws IOException;
    }

    public interface ResponseHandler extends EventHandler
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract boolean startResponse(String version, int status, String reason)
                throws IOException;
    }


}
