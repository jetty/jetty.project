//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpTokens.EndOfContent;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpParser
{
    public static final Logger LOG = Log.getLogger(HttpParser.class);

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
        CLOSED
    };

    private final HttpHandler _handler;
    private final RequestHandler _requestHandler;
    private final ResponseHandler _responseHandler;
    private final int _maxHeaderBytes;
    private HttpHeader _header;
    private String _headerString;
    private HttpHeaderValue _value;
    private String _valueString;
    private int _responseStatus;
    private int _headerBytes;
    private boolean _host;

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
    public HttpParser(RequestHandler handler)
    {
        this(handler,-1);
    }

    /* ------------------------------------------------------------------------------- */
    public HttpParser(ResponseHandler handler)
    {
        this(handler,-1);
    }

    /* ------------------------------------------------------------------------------- */
    public HttpParser(RequestHandler handler,int maxHeaderBytes)
    {
        _handler=handler;
        _requestHandler=handler;
        _responseHandler=null;
        _maxHeaderBytes=maxHeaderBytes;
    }

    /* ------------------------------------------------------------------------------- */
    public HttpParser(ResponseHandler handler,int maxHeaderBytes)
    {
        _handler=handler;
        _requestHandler=null;
        _responseHandler=handler;
        _maxHeaderBytes=maxHeaderBytes;
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
        return _state.ordinal()>State.END.ordinal() && _state.ordinal()<State.CLOSED.ordinal();
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isChunking()
    {
        return _endOfContent==EndOfContent.CHUNKED_CONTENT;
    }

    /* ------------------------------------------------------------ */
    public boolean isStart()
    {
        return isState(State.START);
    }

    /* ------------------------------------------------------------ */
    public boolean isClosed()
    {
        return isState(State.CLOSED);
    }

    /* ------------------------------------------------------------ */
    public boolean isIdle()
    {
        return isState(State.START)||isState(State.END)||isState(State.CLOSED);
    }

    /* ------------------------------------------------------------ */
    public boolean isComplete()
    {
        return isState(State.END)||isState(State.CLOSED);
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isState(State state)
    {
        return _state == state;
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
    private boolean parseLine(ByteBuffer buffer)
    {
        boolean return_from_parse=false;

        // Process headers
        while (_state.ordinal()<State.HEADER.ordinal() && buffer.hasRemaining() && !return_from_parse)
        {
            // process each character
            byte ch=buffer.get();

            if (_maxHeaderBytes>0 && ++_headerBytes>_maxHeaderBytes)
            {
                if (_state==State.URI)
                {
                    LOG.warn("URI is too large >"+_maxHeaderBytes);
                    badMessage(buffer,HttpStatus.REQUEST_URI_TOO_LONG_414,null);
                }
                else
                {
                    if (_requestHandler!=null)
                        LOG.warn("request is too large >"+_maxHeaderBytes);
                    else
                        LOG.warn("response is too large >"+_maxHeaderBytes);
                    badMessage(buffer,HttpStatus.REQUEST_ENTITY_TOO_LARGE_413,null);
                }
                return true;
            }

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
                        badMessage(buffer,HttpStatus.BAD_REQUEST_400,"No URI");
                        return true;
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
                        {
                            badMessage(buffer,HttpStatus.BAD_REQUEST_400,"Unknown Version");
                            return true;
                        }
                        _state=State.SPACE1;
                    }
                    else if (ch < HttpTokens.SPACE && ch>=0)
                    {
                        badMessage(buffer,HttpStatus.BAD_REQUEST_400,"No Status");
                        return true;
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
                        badMessage(buffer,HttpStatus.BAD_REQUEST_400,_requestHandler!=null?"No URI":"No Status");
                        return true;
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
                        _state=State.END;
                        BufferUtil.clear(buffer);
                        return_from_parse|=_handler.headerComplete();
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
                            _state=State.END;
                            BufferUtil.clear(buffer);
                            return_from_parse|=_handler.headerComplete();
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
                        {
                            badMessage(buffer,HttpStatus.BAD_REQUEST_400,"Unknown Version");
                            return true;
                        }

                        _eol=ch;
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
    private boolean parseHeaders(ByteBuffer buffer)
    {
        boolean return_from_parse=false;

        // Process headers
        while (_state.ordinal()<State.END.ordinal() && buffer.hasRemaining() && !return_from_parse)
        {
            // process each character
            byte ch=buffer.get();
            if (_maxHeaderBytes>0 && ++_headerBytes>_maxHeaderBytes)
            {
                LOG.warn("Header is too large >"+_maxHeaderBytes);
                badMessage(buffer,HttpStatus.REQUEST_ENTITY_TOO_LARGE_413,null);
                return true;
            }

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
                                                    badMessage(buffer,HttpStatus.BAD_REQUEST_400,"Bad Content-Length");
                                                    return true;
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
                                                {
                                                    badMessage(buffer,HttpStatus.BAD_REQUEST_400,"Bad chunking");
                                                    return true;
                                                }
                                            }
                                            break;

                                        case HOST:
                                            _host=true;
                                            String host=_valueString;
                                            int port=0;
                                            if (host==null || host.length()==0)
                                            {
                                                badMessage(buffer,HttpStatus.BAD_REQUEST_400,"Bad Host header");
                                                return true;
                                            }

                                            loop: for (int i = host.length(); i-- > 0;)
                                            {
                                                char c2 = (char)(0xff & host.charAt(i));
                                                switch (c2)
                                                {
                                                    case ']':
                                                        break loop;

                                                    case ':':
                                                        try
                                                        {
                                                            port = StringUtil.toInt(host.substring(i+1));
                                                        }
                                                        catch (NumberFormatException e)
                                                        {
                                                            LOG.debug(e);
                                                            badMessage(buffer,HttpStatus.BAD_REQUEST_400,"Bad Host header");
                                                            return true;
                                                        }
                                                        host = host.substring(0,i);
                                                        break loop;
                                                }
                                            }
                                            if (_requestHandler!=null)
                                                _requestHandler.parsedHostHeader(host,port);
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
                                consumeCRLF(ch,buffer);

                                _contentPosition=0;

                                // End of headers!

                                // Was there a required host header?
                                if (!_host && _version!=HttpVersion.HTTP_1_0 && _requestHandler!=null)
                                {
                                    badMessage(buffer,HttpStatus.BAD_REQUEST_400,"No Host");
                                    return true;
                                }

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
                                        return_from_parse|=_handler.headerComplete();
                                        break;

                                    case CHUNKED_CONTENT:
                                        _state=State.CHUNKED_CONTENT;
                                        return_from_parse|=_handler.headerComplete();
                                        break;

                                    case NO_CONTENT:
                                        return_from_parse|=_handler.headerComplete();
                                        _state=State.END;
                                        return_from_parse|=_handler.messageComplete(_contentPosition);
                                        break;

                                    default:
                                        _state=State.CONTENT;
                                        return_from_parse|=_handler.headerComplete();
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
                            consumeCRLF(ch,buffer);
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
                            consumeCRLF(ch,buffer);
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
                            consumeCRLF(ch,buffer);
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
                            consumeCRLF(ch,buffer);
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
    private void consumeCRLF(byte ch, ByteBuffer buffer)
    {
        _eol=ch;
        if (_eol==HttpTokens.CARRIAGE_RETURN && buffer.hasRemaining() && buffer.get(buffer.position())==HttpTokens.LINE_FEED)
        {
            buffer.get();
            _eol=0;
        }
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until next Event.
     * @return True if an {@link RequestHandler} method was called and it returned true;
     */
    public boolean parseNext(ByteBuffer buffer)
    {
        try
        {
            // handle initial state
            switch(_state)
            {
                case START:
                    _version=null;
                    _method=null;
                    _methodString=null;
                    _uri=null;
                    _endOfContent=EndOfContent.UNKNOWN_CONTENT;
                    _header=null;
                    quickStart(buffer);
                    break;

                case CONTENT:
                    if (_contentPosition==_contentLength)
                    {
                        _state=State.END;
                        if(_handler.messageComplete(_contentPosition))
                            return true;
                    }
                    break;

                case END:
                    return false;

                case CLOSED:
                    if (BufferUtil.hasContent(buffer))
                    {
                        _headerBytes+=buffer.remaining();
                        if (_headerBytes>_maxHeaderBytes)
                        {
                            String chars = BufferUtil.toDetailString(buffer);
                            BufferUtil.clear(buffer);
                            throw new IllegalStateException(this+" Extra data after oshut: "+chars);
                        }
                        BufferUtil.clear(buffer);
                    }
                    return false;
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
                _state=State.END;
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
                            _state=State.END;
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
                                _state=State.END;
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
                                _state=State.END;
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
                                _state=State.END;
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
                }
            }

            return false;
        }
        catch(Exception e)
        {
            BufferUtil.clear(buffer);
            if (isClosed())
            {
                LOG.debug(e);
                if (e instanceof IllegalStateException)
                    throw (IllegalStateException)e;
                throw new IllegalStateException(e);
            }

            LOG.warn("badMessage: "+e.toString()+" for "+_handler);
            LOG.debug(e);
            badMessage(buffer,HttpStatus.BAD_REQUEST_400,null);
            return true;
        }
    }

    /* ------------------------------------------------------------------------------- */
    private void badMessage(ByteBuffer buffer, int status, String reason)
    {
        BufferUtil.clear(buffer);
        _state=State.CLOSED;
        _handler.badMessage(status, reason);
    }

    /* ------------------------------------------------------------------------------- */
    public void inputShutdown()
    {
        // was this unexpected?
        switch(_state)
        {
            case END:
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

        LOG.debug("shutdownInput {}",this);
    }

    /* ------------------------------------------------------------------------------- */
    public void close()
    {
        switch(_state)
        {
            case START:
            case CLOSED:
            case END:
                break;
            default:
                LOG.warn("Closing {}",this);
        }
        _state=State.CLOSED;
        _endOfContent=EndOfContent.UNKNOWN_CONTENT;
        _contentPosition=0;
        _responseStatus=0;
        _headerBytes=0;
        _contentChunk=null;
    }

    /* ------------------------------------------------------------------------------- */
    public void reset()
    {
        // reset state
        _state=State.START;
        _endOfContent=EndOfContent.UNKNOWN_CONTENT;
        _contentPosition=0;
        _responseStatus=0;
        _contentChunk=null;
        _headerBytes=0;
        _host=false;
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
        return String.format("%s{s=%s,%d of %d}",
                getClass().getSimpleName(),
                _state,
                _contentPosition,
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
        public boolean content(ByteBuffer ref);

        public boolean headerComplete();

        public boolean messageComplete(long contentLength);

        /**
         * This is the method called by parser when a HTTP Header name and value is found
         * @param header The HttpHeader value if there is a match
         * @param name The String value of the header name
         * @param value The String value of the header
         * @return
         */
        public boolean parsedHeader(HttpHeader header, String name, String value);

        public boolean earlyEOF();

        public void badMessage(int status, String reason);
    }

    public interface RequestHandler extends HttpHandler
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract boolean startRequest(HttpMethod method, String methodString, String uri, HttpVersion version);

        /**
         * This is the method called by the parser after it has parsed the host header (and checked it's format). This is
         * called after the {@link HttpHandler#parsedHeader(HttpHeader, String, String) methods and before
         * HttpHandler#headerComplete();
         */
        public abstract boolean parsedHostHeader(String host,int port);
    }

    public interface ResponseHandler extends HttpHandler
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract boolean startResponse(HttpVersion version, int status, String reason);
    }


}
