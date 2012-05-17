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

import org.eclipse.jetty.http.HttpTokens.EndOfContent;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
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

    private final HttpHandler _handler;
    private final RequestHandler _requestHandler;
    private final ResponseHandler _responseHandler;
    private HttpHeader _header;
    private String _headerString;
    private HttpHeaderValue _value;
    private String _valueString;
    private int _responseStatus;
    private boolean _persistent;

    /* ------------------------------------------------------------------------------- */
    private State _state=State.START;
    private HttpMethod _method;
    private String _methodString;
    private HttpVersion _version;
    private String _uri;
    private byte _eol;
    private EndOfContent _endOfContent;
    private long _contentLength;
    private long _contentPosition;
    private int _chunkLength;
    private int _chunkPosition;
    private boolean _headResponse;
    private ByteBuffer _contentChunk;
    
    private int _length;
    private final StringBuilder _string=new StringBuilder();
    private final Utf8StringBuilder _utf8=new Utf8StringBuilder();

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
        return _endOfContent!=EndOfContent.NO_CONTENT && _endOfContent!=EndOfContent.UNKNOWN_CONTENT;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isChunking()
    {
        return _endOfContent==EndOfContent.CHUNKED_CONTENT;
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
    /* Quick lookahead for the start state looking for a request method or a HTTP version,
     * otherwise skip white space until something else to parse.
     */
    private void quickStart(ByteBuffer buffer)
    {
        // Quick start look
        while (_state==State.START && buffer.hasRemaining())
        {            
            if (_requestHandler!=null)
            {
                _method = HttpMethod.lookAheadGet(buffer);
                if (_method!=null)
                {
                    _methodString = _method.asString();
                    buffer.position(buffer.position()+_methodString.length()+1);
                    _state=State.SPACE1;
                    return;
                }
            }
            else if (_responseHandler!=null)
            {
                _version = HttpVersion.lookAheadGet(buffer);
                if (_version!=null)
                {
                    buffer.position(buffer.position()+_version.asString().length()+1);
                    _state=State.SPACE1;
                    return;
                }
            }

            byte ch=buffer.get();

            if (_eol == HttpTokens.CARRIAGE_RETURN && ch == HttpTokens.LINE_FEED)
            {
                _eol=HttpTokens.LINE_FEED;
                continue;
            }
            _eol=0;

            if (ch > HttpTokens.SPACE || ch<0)
            {
                _string.setLength(0);
                _string.append((char)ch);
                _state=_requestHandler!=null?State.METHOD:State.RESPONSE_VERSION;
                return;
            }
        }
    }

    private String takeString()
    {
        String s =_string.toString();
        _string.setLength(0);
        return s; 
    }
    
    private String takeLengthString()
    {
        _string.setLength(_length);
        String s =_string.toString();
        _string.setLength(0);
        _length=-1;
        return s;
    }
    
    /* ------------------------------------------------------------------------------- */
    /* Parse a request or response line
     */
    private boolean parseLine(ByteBuffer buffer) throws IOException
    {
        boolean return_from_parse=false;
        
        // Process headers
        while (_state.ordinal()<State.HEADER.ordinal() && buffer.hasRemaining() && !return_from_parse)
        {
            // process each character
            byte ch=buffer.get();

            if (_eol == HttpTokens.CARRIAGE_RETURN && ch == HttpTokens.LINE_FEED)
            {
                _eol=HttpTokens.LINE_FEED;
                continue;
            }
            _eol=0;

            switch (_state)
            {
                case METHOD:
                    if (ch == HttpTokens.SPACE)
                    {
                        _methodString=takeString();
                        HttpMethod method=HttpMethod.CACHE.get(_methodString);
                        if (method!=null)
                            _methodString=method.asString();
                        _state=State.SPACE1;
                    }
                    else if (ch < HttpTokens.SPACE && ch>=0)
                    {
                        throw new HttpException(HttpStatus.BAD_REQUEST_400);
                    }
                    else
                        _string.append((char)ch);
                    break;

                case RESPONSE_VERSION:
                    if (ch == HttpTokens.SPACE)
                    {
                        String version=takeString();
                        _version=HttpVersion.CACHE.get(version);
                        if (_version==null)
                            throw new HttpException(HttpStatus.BAD_REQUEST_400);
                        _persistent=HttpVersion.HTTP_1_1==_version;
                        _state=State.SPACE1;            
                    }
                    else if (ch < HttpTokens.SPACE && ch>=0)
                    {
                        throw new HttpException(HttpStatus.BAD_REQUEST_400);
                    }
                    else
                        _string.append((char)ch);
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
                            _state=State.URI;
                            _utf8.reset();
                            _utf8.append(ch);
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
                        return_from_parse|=_responseHandler.startResponse(_version, _responseStatus, null);
                        _eol=ch;
                        _state=State.HEADER;
                    }
                    else
                    {
                        throw new IllegalStateException();
                    }
                    break;

                case URI:
                    if (ch == HttpTokens.SPACE)
                    {
                        _uri=_utf8.toString();
                        _utf8.reset();
                        _state=State.SPACE2;
                    }
                    else if (ch < HttpTokens.SPACE && ch>=0)
                    {
                        // HTTP/0.9
                        _uri=_utf8.toString();
                        _utf8.reset();
                        return_from_parse|=_requestHandler.startRequest(_method,_methodString,_uri,null);
                        _persistent=false;
                        _state=State.SEEKING_EOF;
                        return_from_parse|=_handler.headerComplete(false,false);
                        return_from_parse|=_handler.messageComplete(_contentPosition);
                    }
                    else
                        _utf8.append(ch);
                    break;

                case SPACE2:
                    if (ch > HttpTokens.SPACE || ch<0)
                    {
                        _string.setLength(0);
                        _string.append((char)ch);
                        if (_responseHandler!=null)
                        {
                            _length=1;
                            _state=State.REASON;
                        }
                        else
                        {
                            _state=State.REQUEST_VERSION;
                            
                            // try quick look ahead
                            if (buffer.position()>0 && buffer.hasArray())
                            {
                                _version=HttpVersion.lookAheadGet(buffer.array(),buffer.arrayOffset()+buffer.position()-1,buffer.arrayOffset()+buffer.limit());
                                if (_version!=null)
                                {
                                    _string.setLength(0);
                                    buffer.position(buffer.position()+_version.asString().length()-1);
                                    _eol=buffer.get();
                                    _persistent=HttpVersion.HTTP_1_1==_version;
                                    _state=State.HEADER;
                                    return_from_parse|=_requestHandler.startRequest(_method,_methodString, _uri, _version);
                                }
                            }
                        }
                    }
                    else if (ch < HttpTokens.SPACE)
                    {
                        if (_responseHandler!=null)
                        {
                            return_from_parse|=_responseHandler.startResponse(_version, _responseStatus, null);
                            _eol=ch;
                            _state=State.HEADER;
                        }
                        else
                        {
                            // HTTP/0.9
                            return_from_parse|=_requestHandler.startRequest(_method,_methodString, _uri, null);
                            _persistent=false;
                            _state=State.SEEKING_EOF;
                            return_from_parse|=_handler.headerComplete(false,false);
                            return_from_parse|=_handler.messageComplete(_contentPosition);
                        }
                    }
                    break;

                case REQUEST_VERSION:
                    if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                    {
                        String version = takeString();
                        _version=HttpVersion.CACHE.get(version);
                        if (_version==null)
                            throw new HttpException(HttpStatus.BAD_REQUEST_400);
                        
                        _eol=ch;
                        _persistent=HttpVersion.HTTP_1_1==_version;
                        _state=State.HEADER;
                        return_from_parse|=_requestHandler.startRequest(_method,_methodString, _uri, _version);
                        continue;
                    }
                    else
                        _string.append((char)ch);
                        
                    break;

                case REASON:
                    if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                    {
                        String reason=takeLengthString();

                        _eol=ch;
                        _state=State.HEADER;
                        return_from_parse|=_responseHandler.startResponse(_version, _responseStatus, reason);
                        continue;
                    }
                    else
                    {
                        _string.append((char)ch);
                        if (ch!=' '&&ch!='\t')
                            _length=_string.length();
                    }
                    break;

                default:
                    throw new IllegalStateException(_state.toString());
                    
            }
        }
        
        return return_from_parse;
    }
    
    /* ------------------------------------------------------------------------------- */
    /*
     * Parse the message headers and return true if the handler has signaled for a return
     */
    private boolean parseHeaders(ByteBuffer buffer) throws IOException
    {
        boolean return_from_parse=false;

        // Process headers
        while (_state.ordinal()<State.END.ordinal() && buffer.hasRemaining() && !return_from_parse)
        {
            // process each character
            byte ch=buffer.get();

            if (_eol == HttpTokens.CARRIAGE_RETURN && ch == HttpTokens.LINE_FEED)
            {
                _eol=HttpTokens.LINE_FEED;
                continue;
            }
            _eol=0;

            switch (_state)
            {
                case HEADER:
                    switch(ch)
                    {
                        case HttpTokens.COLON:
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                        {
                            // header value without name - continuation?
                            _length=-1;
                            _string.setLength(0);
                            _state=State.HEADER_VALUE;
                            break;
                        }

                        default:
                        {
                            // handler last header if any.  Delayed to here just in case there was a continuation line (above)
                            if (_headerString!=null || _valueString!=null)
                            {
                                // Handle known headers
                                if (_header!=null)
                                {
                                    switch (_header)
                                    {
                                        case CONTENT_LENGTH:
                                            if (_endOfContent != EndOfContent.CHUNKED_CONTENT && _responseStatus!=304 && _responseStatus!=204 && (_responseStatus<100 || _responseStatus>=200))
                                            {
                                                try
                                                {
                                                    _contentLength=Long.parseLong(_valueString);
                                                }
                                                catch(NumberFormatException e)
                                                {
                                                    LOG.ignore(e);
                                                    throw new HttpException(HttpStatus.BAD_REQUEST_400);
                                                }
                                                if (_contentLength <= 0)
                                                    _endOfContent=EndOfContent.NO_CONTENT;
                                                else 
                                                    _endOfContent=EndOfContent.CONTENT_LENGTH;
                                            }
                                            break;

                                        case TRANSFER_ENCODING:
                                            if (_value==HttpHeaderValue.CHUNKED)
                                                _endOfContent=EndOfContent.CHUNKED_CONTENT;
                                            else
                                            {
                                                if (_valueString.endsWith(HttpHeaderValue.CHUNKED.toString()))
                                                    _endOfContent=EndOfContent.CHUNKED_CONTENT;
                                                else if (_valueString.indexOf(HttpHeaderValue.CHUNKED.toString()) >= 0)
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
                                                    for (String v : _valueString.toString().split(","))
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

                                return_from_parse|=_handler.parsedHeader(_header, _headerString, _valueString);
                            }
                            _headerString=_valueString=null;
                            _header=null;
                            _value=null;


                            // now handle the ch
                            if (ch == HttpTokens.CARRIAGE_RETURN || ch == HttpTokens.LINE_FEED)
                            {
                                _eol=ch;
                                _contentPosition=0;

                                // End of headers!
                                // so work out the _content demarcation
                                if (_endOfContent == EndOfContent.UNKNOWN_CONTENT)
                                {
                                    if (_responseStatus == 0  // request
                                            || _responseStatus == 304 // not-modified response
                                            || _responseStatus == 204 // no-content response
                                            || _responseStatus < 200) // 1xx response
                                        _endOfContent=EndOfContent.NO_CONTENT;
                                    else
                                        _endOfContent=EndOfContent.EOF_CONTENT;
                                }

                                // How is the message ended?
                                switch (_endOfContent)
                                {
                                    case EOF_CONTENT:
                                        _state=State.EOF_CONTENT;
                                        return_from_parse|=_handler.headerComplete(true,false); 
                                        break;

                                    case CHUNKED_CONTENT:
                                        _state=State.CHUNKED_CONTENT;
                                        return_from_parse|=_handler.headerComplete(true,_persistent); 
                                        break;

                                    case NO_CONTENT:
                                        return_from_parse|=_handler.headerComplete(false,_persistent);
                                        _state=_persistent||(_responseStatus>=100&&_responseStatus<200)?State.END:State.SEEKING_EOF;
                                        return_from_parse|=_handler.messageComplete(_contentPosition);
                                        break;

                                    default:
                                        _state=State.CONTENT;
                                        return_from_parse|=_handler.headerComplete(true,_persistent); 
                                        break;
                                }
                            }
                            else 
                            {
                                if (buffer.remaining()>6 && buffer.hasArray())
                                {
                                    // Try a look ahead for the known headers.
                                    _header=HttpHeader.lookAheadGet(buffer.array(),buffer.arrayOffset()+buffer.position()-1,buffer.arrayOffset()+buffer.limit());

                                    if (_header!=null)
                                    {
                                        _headerString=_header.asString();
                                        buffer.position(buffer.position()+_headerString.length());
                                        _state=buffer.get(buffer.position()-1)==':'?State.HEADER_VALUE:State.HEADER_NAME;
                                        break;
                                    }
                                }
                                
                                // New header
                                _state=State.HEADER_NAME;
                                _string.setLength(0);
                                _string.append((char)ch);
                                _length=1;
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
                            _headerString=takeLengthString();
                            _header=HttpHeader.CACHE.get(_headerString);
                            _state=State.HEADER;
                            
                            break;

                        case HttpTokens.COLON:
                            if (_headerString==null)
                            {
                                _headerString=takeLengthString();
                                _header=HttpHeader.CACHE.get(_headerString);
                            }
                            _state=State.HEADER_VALUE;
                            break;
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                            _string.append((char)ch);
                            break;
                        default:
                        {
                            if (_header!=null)
                            {
                                _string.setLength(0);
                                _string.append(_header.asString());
                                _string.append(' ');
                                _length=_string.length();
                                _header=null;
                                _headerString=null;
                            }
                            _string.append((char)ch);
                            _length=_string.length();
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
                            _headerString=takeString();
                            _length=-1;
                            _header=HttpHeader.CACHE.get(_headerString);
                            _state=State.HEADER;
                            break;

                        case HttpTokens.COLON:
                            if (_headerString==null)
                            {
                                _headerString=takeString();
                                _header=HttpHeader.CACHE.get(_headerString);
                            }
                            _length=-1;
                            _state=State.HEADER_VALUE;
                            break;
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                            _state=State.HEADER_NAME;
                            _string.append((char)ch);
                            break;
                        default:
                            _string.append((char)ch);
                            _length++;
                    }
                    break;

                case HEADER_VALUE:
                    switch(ch)
                    {
                        case HttpTokens.CARRIAGE_RETURN:
                        case HttpTokens.LINE_FEED:
                            _eol=ch;
                            if (_length > 0)
                            {
                                if (_valueString!=null)
                                {
                                    // multi line value!
                                    _value=null;
                                    _valueString+=" "+takeLengthString();                                    
                                }
                                else if (HttpHeaderValue.hasKnownValues(_header))
                                {
                                    _valueString=takeLengthString();
                                    _value=HttpHeaderValue.CACHE.get(_valueString);
                                }
                                else
                                {
                                    _value=null;
                                    _valueString=takeLengthString();
                                }
                            }
                            _state=State.HEADER;
                            break;
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                            break;
                        default:
                        {
                            _string.append((char)ch);
                            _length=_string.length();
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
                            if (_length > 0)
                            {
                                if (_valueString!=null)
                                {
                                    // multi line value!
                                    _value=null;
                                    _valueString+=" "+takeString();
                                }
                                else if (HttpHeaderValue.hasKnownValues(_header))
                                {
                                    _valueString=takeString();
                                    _value=HttpHeaderValue.CACHE.get(_valueString);
                                }
                                else
                                {
                                    _value=null;
                                    _valueString=takeString();
                                }
                                _length=-1;
                            }
                            _state=State.HEADER;
                            break;
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                            _string.append((char)ch);
                            _state=State.HEADER_VALUE;
                            break;
                        default:
                            _string.append((char)ch);
                            _length++;
                    }
                    break;

                default:
                    throw new IllegalStateException(_state.toString());
                    
            }
        } 

        return return_from_parse;
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
            // process end states
            if (_state == State.END)
                return false;
            if (_state == State.CONTENT && _contentPosition == _contentLength)
            {
                _state=State.END;
                if(_handler.messageComplete(_contentPosition))
                    return true;
            }

            // Handle start
            if (_state==State.START)
            {
                _version=null;
                _method=null;
                _methodString=null;
                _uri=null;
                _endOfContent=EndOfContent.UNKNOWN_CONTENT;
                _header=null;
                quickStart(buffer);
            }
            
            // Request/response line
            if (_state.ordinal()<State.HEADER.ordinal())
                if (parseLine(buffer))
                    return true;
            
            if (_state.ordinal()<State.END.ordinal())
                if (parseHeaders(buffer))
                    return true;
            
            // Handle HEAD response
            if (_responseStatus>0 && _headResponse)
            {
                _state=_persistent||(_responseStatus>=100&&_responseStatus<200)?State.END:State.SEEKING_EOF;
                if (_handler.messageComplete(_contentLength))
                    return true;
            }


            // Handle _content
            byte ch;
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
                        _contentChunk=buffer.asReadOnlyBuffer();
                        _contentPosition += _contentChunk.remaining();
                        buffer.position(buffer.position()+_contentChunk.remaining());
                        if (_handler.content(_contentChunk))
                            return true;
                        break;

                    case CONTENT:
                    {
                        long remaining=_contentLength - _contentPosition;
                        if (remaining == 0)
                        {
                            _state=_persistent?State.END:State.SEEKING_EOF;
                            if (_handler.messageComplete(_contentPosition))
                                return true;
                        }
                        else
                        {
                            _contentChunk=buffer.asReadOnlyBuffer();

                            // limit content by expected size
                            if (_contentChunk.remaining() > remaining)
                            {
                                // We can cast remaining to an int as we know that it is smaller than
                                // or equal to length which is already an int.
                                _contentChunk.limit(_contentChunk.position()+(int)remaining);
                            }

                            _contentPosition += _contentChunk.remaining();
                            buffer.position(buffer.position()+_contentChunk.remaining());
                            
                            if (_handler.content(_contentChunk))
                                return true;

                            if(_contentPosition == _contentLength)
                            {
                                _state=_persistent?State.END:State.SEEKING_EOF;
                                if (_handler.messageComplete(_contentPosition))
                                    return true;
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
                                if (_handler.messageComplete(_contentPosition))
                                    return true;
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
                                if (_handler.messageComplete(_contentPosition))
                                    return true;
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
                            _contentChunk=buffer.asReadOnlyBuffer();

                            if (_contentChunk.remaining() > remaining)
                                _contentChunk.limit(_contentChunk.position()+remaining);
                            remaining=_contentChunk.remaining();

                            _contentPosition += remaining;
                            _chunkPosition += remaining;
                            buffer.position(buffer.position()+remaining);
                            if (_handler.content(_contentChunk))
                                return true;
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

            return false;
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
                _string.setLength(0);
                buffer.position(start);
                _state=startState;
            }

        }
    }

    /* ------------------------------------------------------------------------------- */
    public boolean inputShutdown() throws IOException
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
        _endOfContent=EndOfContent.UNKNOWN_CONTENT;
        _contentPosition=0;
        _responseStatus=0;
        _contentChunk=null;
    }

    /* ------------------------------------------------------------------------------- */
    public void setState(State state)
    {
        this._state=state;
        _endOfContent=EndOfContent.UNKNOWN_CONTENT;
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public String toString()
    {
        return String.format("%s{s=%s,c=%d}",
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
    public interface HttpHandler
    {
        public boolean content(ByteBuffer ref) throws IOException;

        public boolean headerComplete(boolean hasBody,boolean persistent) throws IOException;

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

    public interface RequestHandler extends HttpHandler
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract boolean startRequest(HttpMethod method, String methodString, String uri, HttpVersion version)
                throws IOException;
    }

    public interface ResponseHandler extends HttpHandler
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract boolean startResponse(HttpVersion version, int status, String reason)
                throws IOException;
    }


}
