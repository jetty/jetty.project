//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpTokens.EndOfContent;
import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/** A Parser for HTTP 0.9, 1.0 and 1.1
 * <p>
 * The is parser parses HTTP client and server messages from buffers
 * passed in the {@link #parseNext(ByteBuffer)} method.  The parsed
 * elements of the HTTP message are passed as event calls to the 
 * {@link HttpHandler} instance the parser is constructed with.
 * If the passed handler is a {@link RequestHandler} then server side
 * parsing is performed and if it is a {@link ResponseHandler}, then 
 * client side parsing is done.
 * </p>
 * <p>
 * The contract of the {@link HttpHandler} API is that if a call returns 
 * true then the call to {@link #parseNext(ByteBuffer)} will return as 
 * soon as possible also with a true response.  Typically this indicates
 * that the parsing has reached a stage where the caller should process 
 * the events accumulated by the handler.    It is the preferred calling
 * style that handling such as calling a servlet to process a request, 
 * should be done after a true return from {@link #parseNext(ByteBuffer)}
 * rather than from within the scope of a call like 
 * {@link RequestHandler#messageComplete()}
 * </p>
 * <p>
 * For performance, the parse is heavily dependent on the 
 * {@link Trie#getBest(ByteBuffer, int, int)} method to look ahead in a
 * single pass for both the structure ( : and CRLF ) and semantic (which
 * header and value) of a header.  Specifically the static {@link HttpField#CACHE}
 * is used to lookup common combinations of headers and values 
 * (eg. "Connection: close"), or just header names (eg. "Connection:" ).
 * For headers who's value is not known statically (eg. Host, COOKIE) then a
 * per parser dynamic Trie of {@link HttpFields} from previous parsed messages
 * is used to help the parsing of subsequent messages.
 * </p>
 * <p>
 * If the system property "org.eclipse.jetty.http.HttpParser.STRICT" is set to true,
 * then the parser will strictly pass on the exact strings received for methods and header
 * fields.  Otherwise a fast case insensitive string lookup is used that may alter the
 * case of the method and/or headers
 * </p>
 */
public class HttpParser
{
    public static final Logger LOG = Log.getLogger(HttpParser.class);
    public final static boolean __STRICT=Boolean.getBoolean("org.eclipse.jetty.http.HttpParser.STRICT"); 
    public final static int INITIAL_URI_LENGTH=256;

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
        HEADER_IN_NAME,
        HEADER_VALUE,
        HEADER_IN_VALUE,
        CONTENT,
        EOF_CONTENT,
        CHUNKED_CONTENT,
        CHUNK_SIZE,
        CHUNK_PARAMS,
        CHUNK,
        END,
        CLOSED
    };

    private final boolean DEBUG=LOG.isDebugEnabled(); // Cache debug to help branch prediction
    private final HttpHandler<ByteBuffer> _handler;
    private final RequestHandler<ByteBuffer> _requestHandler;
    private final ResponseHandler<ByteBuffer> _responseHandler;
    private final int _maxHeaderBytes;
    private final boolean _strict;
    private HttpField _field;
    private HttpHeader _header;
    private String _headerString;
    private HttpHeaderValue _value;
    private String _valueString;
    private int _responseStatus;
    private int _headerBytes;
    private boolean _host;

    /* ------------------------------------------------------------------------------- */
    private volatile State _state=State.START;
    private volatile boolean _eof;
    private volatile boolean _closed;
    private HttpMethod _method;
    private String _methodString;
    private HttpVersion _version;
    private ByteBuffer _uri=ByteBuffer.allocate(INITIAL_URI_LENGTH); // Tune?
    private EndOfContent _endOfContent;
    private long _contentLength;
    private long _contentPosition;
    private int _chunkLength;
    private int _chunkPosition;
    private boolean _headResponse;
    private boolean _cr;
    private ByteBuffer _contentChunk;
    private Trie<HttpField> _connectionFields;

    private int _length;
    private final StringBuilder _string=new StringBuilder();

    /* ------------------------------------------------------------------------------- */
    public HttpParser(RequestHandler<ByteBuffer> handler)
    {
        this(handler,-1,__STRICT);
    }

    /* ------------------------------------------------------------------------------- */
    public HttpParser(ResponseHandler<ByteBuffer> handler)
    {
        this(handler,-1,__STRICT);
    }

    /* ------------------------------------------------------------------------------- */
    public HttpParser(RequestHandler<ByteBuffer> handler,int maxHeaderBytes)
    {
        this(handler,maxHeaderBytes,__STRICT);
    }

    /* ------------------------------------------------------------------------------- */
    public HttpParser(ResponseHandler<ByteBuffer> handler,int maxHeaderBytes)
    {
        this(handler,maxHeaderBytes,__STRICT);
    }
    
    /* ------------------------------------------------------------------------------- */
    public HttpParser(RequestHandler<ByteBuffer> handler,int maxHeaderBytes,boolean strict)
    {
        _handler=handler;
        _requestHandler=handler;
        _responseHandler=null;
        _maxHeaderBytes=maxHeaderBytes;
        _strict=strict;
    }

    /* ------------------------------------------------------------------------------- */
    public HttpParser(ResponseHandler<ByteBuffer> handler,int maxHeaderBytes,boolean strict)
    {
        _handler=handler;
        _requestHandler=null;
        _responseHandler=handler;
        _maxHeaderBytes=maxHeaderBytes;
        _strict=strict;
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
        return _state.ordinal()>=State.CONTENT.ordinal() && _state.ordinal()<State.END.ordinal();
    }

    /* ------------------------------------------------------------------------------- */
    public boolean inHeaderState()
    {
        return _state.ordinal() < State.CONTENT.ordinal();
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
    private static class BadMessage extends Error
    {
        private static final long serialVersionUID = 1L;
        private final int _code;
        private final String _message;

        BadMessage()
        {
            this(400,null);
        }
        
        BadMessage(int code)
        {
            this(code,null);
        }
        
        BadMessage(String message)
        {
            this(400,message);
        }
        
        BadMessage(int code,String message)
        {
            _code=code;
            _message=message;
        }
        
    }
    
    /* ------------------------------------------------------------------------------- */
    private byte next(ByteBuffer buffer)
    {
        byte ch = buffer.get();
        
        if (_cr)
        {
            if (ch!=HttpTokens.LINE_FEED)
                throw new BadMessage("Bad EOL");
            _cr=false;
            return ch;
        }

        if (ch>=0 && ch<HttpTokens.SPACE)
        {
            if (ch==HttpTokens.CARRIAGE_RETURN)
            {
                if (buffer.hasRemaining())
                {
                    if(_maxHeaderBytes>0 && _state.ordinal()<State.END.ordinal())
                        _headerBytes++;
                    ch=buffer.get();
                    if (ch!=HttpTokens.LINE_FEED)
                        throw new BadMessage("Bad EOL");
                }
                else
                {
                    _cr=true;
                    // Can return 0 here to indicate the need for more characters, 
                    // because a real 0 in the buffer would cause a BadMessage below 
                    return 0;
                }
            }
            // Only LF or TAB acceptable special characters
            else if (!(ch==HttpTokens.LINE_FEED || ch==HttpTokens.TAB))
                throw new BadMessage("Illegal character");
        }
        
        return ch;
    }
    
    /* ------------------------------------------------------------------------------- */
    /* Quick lookahead for the start state looking for a request method or a HTTP version,
     * otherwise skip white space until something else to parse.
     */
    private boolean quickStart(ByteBuffer buffer)
    {
        if (_requestHandler!=null)
        {
            _method = HttpMethod.lookAheadGet(buffer);
            if (_method!=null)
            {
                _methodString = _method.asString();
                buffer.position(buffer.position()+_methodString.length()+1);
                setState(State.SPACE1);
                return false;
            }
        }
        else if (_responseHandler!=null)
        {
            _version = HttpVersion.lookAheadGet(buffer);
            if (_version!=null)
            {
                buffer.position(buffer.position()+_version.asString().length()+1);
                setState(State.SPACE1);
                return false;
            }
        }
        
        // Quick start look
        while (_state==State.START && buffer.hasRemaining())
        {
            int ch=next(buffer);

            if (ch > HttpTokens.SPACE)
            {
                _string.setLength(0);
                _string.append((char)ch);
                setState(_requestHandler!=null?State.METHOD:State.RESPONSE_VERSION);
                return false;
            }
            else if (ch==0)
                break;
            else if (ch<0)
                throw new BadMessage();
        }
        return false;
    }

    /* ------------------------------------------------------------------------------- */
    private void setString(String s)
    {
        _string.setLength(0);
        _string.append(s);
        _length=s.length();
    }
    
    /* ------------------------------------------------------------------------------- */
    private String takeString()
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
        boolean handle=false;

        // Process headers
        while (_state.ordinal()<State.HEADER.ordinal() && buffer.hasRemaining() && !handle)
        {
            // process each character
            byte ch=next(buffer);
            if (ch==0)
                break;

            if (_maxHeaderBytes>0 && ++_headerBytes>_maxHeaderBytes)
            {
                if (_state==State.URI)
                {
                    LOG.warn("URI is too large >"+_maxHeaderBytes);
                    throw new BadMessage(HttpStatus.REQUEST_URI_TOO_LONG_414);
                }
                else
                {
                    if (_requestHandler!=null)
                        LOG.warn("request is too large >"+_maxHeaderBytes);
                    else
                        LOG.warn("response is too large >"+_maxHeaderBytes);
                    throw new BadMessage(HttpStatus.REQUEST_ENTITY_TOO_LARGE_413);
                }
            }

            switch (_state)
            {
                case METHOD:
                    if (ch == HttpTokens.SPACE)
                    {
                        _length=_string.length();
                        _methodString=takeString();
                        HttpMethod method=HttpMethod.CACHE.get(_methodString);
                        if (method!=null && !_strict)
                            _methodString=method.asString();
                        setState(State.SPACE1);
                    }
                    else if (ch < HttpTokens.SPACE)
                        throw new BadMessage(ch<0?"Illegal character":"No URI");
                    else
                        _string.append((char)ch);
                    break;

                case RESPONSE_VERSION:
                    if (ch == HttpTokens.SPACE)
                    {
                        _length=_string.length();
                        String version=takeString();
                        _version=HttpVersion.CACHE.get(version);
                        if (_version==null)
                            throw new BadMessage(HttpStatus.BAD_REQUEST_400,"Unknown Version");
                        setState(State.SPACE1);
                    }
                    else if (ch < HttpTokens.SPACE)
                        throw new BadMessage(ch<0?"Illegal character":"No Status");
                    else
                        _string.append((char)ch);
                    break;

                case SPACE1:
                    if (ch > HttpTokens.SPACE || ch<0)
                    {
                        if (_responseHandler!=null)
                        {
                            setState(State.STATUS);
                            _responseStatus=ch-'0';
                        }
                        else
                        {
                            _uri.clear();
                            setState(State.URI);
                            // quick scan for space or EoBuffer
                            if (buffer.hasArray())
                            {
                                byte[] array=buffer.array();
                                int p=buffer.arrayOffset()+buffer.position();
                                int l=buffer.arrayOffset()+buffer.limit();
                                int i=p;
                                while (i<l && array[i]>HttpTokens.SPACE)
                                    i++;

                                int len=i-p;
                                _headerBytes+=len;
                                
                                if (_maxHeaderBytes>0 && ++_headerBytes>_maxHeaderBytes)
                                {
                                    LOG.warn("URI is too large >"+_maxHeaderBytes);
                                    throw new BadMessage(HttpStatus.REQUEST_URI_TOO_LONG_414);
                                }
                                if (_uri.remaining()<=len)
                                {
                                    ByteBuffer uri = ByteBuffer.allocate(_uri.capacity()+2*len);
                                    _uri.flip();
                                    uri.put(_uri);
                                    _uri=uri;
                                }
                                _uri.put(array,p-1,len+1);
                                buffer.position(i-buffer.arrayOffset());
                            }
                            else
                                _uri.put(ch);
                        }
                    }
                    else if (ch < HttpTokens.SPACE)
                    {
                        throw new BadMessage(HttpStatus.BAD_REQUEST_400,_requestHandler!=null?"No URI":"No Status");
                    }
                    break;

                case STATUS:
                    if (ch == HttpTokens.SPACE)
                    {
                        setState(State.SPACE2);
                    }
                    else if (ch>='0' && ch<='9')
                    {
                        _responseStatus=_responseStatus*10+(ch-'0');
                    }
                    else if (ch < HttpTokens.SPACE && ch>=0)
                    {
                        handle=_responseHandler.startResponse(_version, _responseStatus, null)||handle;
                        setState(State.HEADER);
                    }
                    else
                    {
                        throw new BadMessage();
                    }
                    break;

                case URI:
                    if (ch == HttpTokens.SPACE)
                    {
                        setState(State.SPACE2);
                    }
                    else if (ch < HttpTokens.SPACE && ch>=0)
                    {
                        // HTTP/0.9
                        _uri.flip();
                        handle=_requestHandler.startRequest(_method,_methodString,_uri,null)||handle;
                        setState(State.END);
                        BufferUtil.clear(buffer);
                        handle=_handler.headerComplete()||handle;
                        handle=_handler.messageComplete()||handle;
                    }
                    else
                    {
                        if (!_uri.hasRemaining())
                        {
                            ByteBuffer uri = ByteBuffer.allocate(_uri.capacity()*2);
                            _uri.flip();
                            uri.put(_uri);
                            _uri=uri;
                        }
                        _uri.put(ch);
                    }
                    break;

                case SPACE2:
                    if (ch > HttpTokens.SPACE)
                    {
                        _string.setLength(0);
                        _string.append((char)ch);
                        if (_responseHandler!=null)
                        {
                            _length=1;
                            setState(State.REASON);
                        }
                        else
                        {
                            setState(State.REQUEST_VERSION);

                            // try quick look ahead for HTTP Version
                            HttpVersion version;
                            if (buffer.position()>0 && buffer.hasArray())
                                version=HttpVersion.lookAheadGet(buffer.array(),buffer.arrayOffset()+buffer.position()-1,buffer.arrayOffset()+buffer.limit());
                            else
                                version=HttpVersion.CACHE.getBest(buffer,0,buffer.remaining());
                            if (version!=null) 
                            {
                                int pos = buffer.position()+version.asString().length()-1;
                                if (pos<buffer.limit())
                                {
                                    byte n=buffer.get(pos);
                                    if (n==HttpTokens.CARRIAGE_RETURN)
                                    {
                                        _cr=true;
                                        _version=version;
                                        _string.setLength(0);
                                        buffer.position(pos+1);
                                    }
                                    else if (n==HttpTokens.LINE_FEED)
                                    {
                                        _version=version;
                                        _string.setLength(0);
                                        buffer.position(pos);
                                    }
                                }
                            }
                        }
                    }
                    else if (ch == HttpTokens.LINE_FEED)
                    {
                        if (_responseHandler!=null)
                        {
                            handle=_responseHandler.startResponse(_version, _responseStatus, null)||handle;
                            setState(State.HEADER);
                        }
                        else
                        {
                            // HTTP/0.9
                            _uri.flip();
                            handle=_requestHandler.startRequest(_method,_methodString,_uri, null)||handle;
                            setState(State.END);
                            BufferUtil.clear(buffer);
                            handle=_handler.headerComplete()||handle;
                            handle=_handler.messageComplete()||handle;
                        }
                    }
                    else if (ch<0)
                        throw new BadMessage();
                    break;

                case REQUEST_VERSION:
                    if (ch == HttpTokens.LINE_FEED)
                    {
                        if (_version==null)
                        {
                            _length=_string.length();
                            _version=HttpVersion.CACHE.get(takeString());
                        }
                        if (_version==null)
                            throw new BadMessage(HttpStatus.BAD_REQUEST_400,"Unknown Version");
                        
                        // Should we try to cache header fields?
                        if (_connectionFields==null && _version.getVersion()>=HttpVersion.HTTP_1_1.getVersion())
                        {
                            int header_cache = _handler.getHeaderCacheSize();
                            if (header_cache>0)
                                _connectionFields=new ArrayTernaryTrie<>(header_cache);
                        }

                        setState(State.HEADER);
                        _uri.flip();
                        handle=_requestHandler.startRequest(_method,_methodString,_uri, _version)||handle;
                        continue;
                    }
                    else if (ch>=HttpTokens.SPACE)
                        _string.append((char)ch);
                    else
                        throw new BadMessage();

                    break;

                case REASON:
                    if (ch == HttpTokens.LINE_FEED)
                    {
                        String reason=takeString();

                        setState(State.HEADER);
                        handle=_responseHandler.startResponse(_version, _responseStatus, reason)||handle;
                        continue;
                    }
                    else if (ch>=HttpTokens.SPACE)
                    {
                        _string.append((char)ch);
                        if (ch!=' '&&ch!='\t')
                            _length=_string.length();
                    } 
                    else
                        throw new BadMessage();
                    break;

                default:
                    throw new IllegalStateException(_state.toString());

            }
        }

        return handle;
    }

    private boolean handleKnownHeaders(ByteBuffer buffer)
    {
        boolean add_to_connection_trie=false;
        switch (_header)
        {
            case CONTENT_LENGTH:
                if (_endOfContent != EndOfContent.CHUNKED_CONTENT)
                {
                    try
                    {
                        _contentLength=Long.parseLong(_valueString);
                    }
                    catch(NumberFormatException e)
                    {
                        LOG.ignore(e);
                        throw new BadMessage(HttpStatus.BAD_REQUEST_400,"Bad Content-Length");
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
                        throw new BadMessage(HttpStatus.BAD_REQUEST_400,"Bad chunking");
                    }
                }
                break;

            case HOST:
                add_to_connection_trie=_connectionFields!=null && _field==null;
                _host=true;
                String host=_valueString;
                int port=0;
                if (host==null || host.length()==0)
                {
                    throw new BadMessage(HttpStatus.BAD_REQUEST_400,"Bad Host header");
                }

                int len=host.length();
                loop: for (int i = len; i-- > 0;)
                {
                    char c2 = (char)(0xff & host.charAt(i));
                    switch (c2)
                    {
                        case ']':
                            break loop;

                        case ':':
                            try
                            {
                                len=i;
                                port = StringUtil.toInt(host.substring(i+1));
                            }
                            catch (NumberFormatException e)
                            {
                                if (DEBUG)
                                    LOG.debug(e);
                                throw new BadMessage(HttpStatus.BAD_REQUEST_400,"Bad Host header");
                            }
                            break loop;
                    }
                }
                if (host.charAt(0)=='[')
                {
                    if (host.charAt(len-1)!=']') 
                    {
                        throw new BadMessage(HttpStatus.BAD_REQUEST_400,"Bad IPv6 Host header");
                    }
                    host = host.substring(1,len-1);
                }
                else if (len!=host.length())
                    host = host.substring(0,len);
                
                if (_requestHandler!=null)
                    _requestHandler.parsedHostHeader(host,port);
                
              break;
              
            case CONNECTION:
                // Don't cache if not persistent
                if (_valueString!=null && _valueString.indexOf("close")>=0)
                {
                    _closed=true;
                    _connectionFields=null;
                }
                break;

            case AUTHORIZATION:
            case ACCEPT:
            case ACCEPT_CHARSET:
            case ACCEPT_ENCODING:
            case ACCEPT_LANGUAGE:
            case COOKIE:
            case CACHE_CONTROL:
            case USER_AGENT:
                add_to_connection_trie=_connectionFields!=null && _field==null;
                break;
                
            default: break;
        }
    
        if (add_to_connection_trie && !_connectionFields.isFull() && _header!=null && _valueString!=null)
        {
            _field=new HttpField.CachedHttpField(_header,_valueString);
            _connectionFields.put(_field);
        }
        
        return false;
    }
    
    
    /* ------------------------------------------------------------------------------- */
    /*
     * Parse the message headers and return true if the handler has signaled for a return
     */
    private boolean parseHeaders(ByteBuffer buffer)
    {
        boolean handle=false;

        // Process headers
        while (_state.ordinal()<State.CONTENT.ordinal() && buffer.hasRemaining() && !handle)
        {
            // process each character
            byte ch=next(buffer);
            if (ch==0)
                break;
            
            if (_maxHeaderBytes>0 && ++_headerBytes>_maxHeaderBytes)
            {
                LOG.warn("Header is too large >"+_maxHeaderBytes);
                throw new BadMessage(HttpStatus.REQUEST_ENTITY_TOO_LARGE_413);
            }

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
                            if (_valueString==null)
                            {
                                _string.setLength(0);
                                _length=0;
                            }
                            else
                            {
                                setString(_valueString);
                                _string.append(' ');
                                _length++;
                                _valueString=null;
                            }
                            setState(State.HEADER_VALUE);
                            break;
                        }

                        default:
                        {
                            // handler last header if any.  Delayed to here just in case there was a continuation line (above)
                            if (_headerString!=null || _valueString!=null)
                            {
                                // Handle known headers
                                if (_header!=null && handleKnownHeaders(buffer))
                                {
                                    _headerString=_valueString=null;
                                    _header=null;
                                    _value=null;
                                    _field=null;
                                    return true;
                                }
                                handle=_handler.parsedHeader(_field!=null?_field:new HttpField(_header,_headerString,_valueString))||handle;
                            }
                            _headerString=_valueString=null;
                            _header=null;
                            _value=null;
                            _field=null;

                            // now handle the ch
                            if (ch == HttpTokens.LINE_FEED)
                            {
                                _contentPosition=0;

                                // End of headers!

                                // Was there a required host header?
                                if (!_host && _version!=HttpVersion.HTTP_1_0 && _requestHandler!=null)
                                {
                                    throw new BadMessage(HttpStatus.BAD_REQUEST_400,"No Host");
                                }

                                // is it a response that cannot have a body?
                                if (_responseHandler !=null  && // response  
                                    (_responseStatus == 304  || // not-modified response
                                    _responseStatus == 204 || // no-content response
                                    _responseStatus < 200)) // 1xx response
                                    _endOfContent=EndOfContent.NO_CONTENT; // ignore any other headers set
                                
                                // else if we don't know framing
                                else if (_endOfContent == EndOfContent.UNKNOWN_CONTENT)
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
                                        setState(State.EOF_CONTENT);
                                        handle=_handler.headerComplete()||handle;
                                        break;

                                    case CHUNKED_CONTENT:
                                        setState(State.CHUNKED_CONTENT);
                                        handle=_handler.headerComplete()||handle;
                                        break;

                                    case NO_CONTENT:
                                        handle=_handler.headerComplete()||handle;
                                        setState(State.END);
                                        handle=_handler.messageComplete()||handle;
                                        break;

                                    default:
                                        setState(State.CONTENT);
                                        handle=_handler.headerComplete()||handle;
                                        break;
                                }
                            }
                            else if (ch<=HttpTokens.SPACE)
                                throw new BadMessage();
                            else
                            {
                                if (buffer.hasRemaining())
                                {
                                    // Try a look ahead for the known header name and value.
                                    HttpField field=_connectionFields==null?null:_connectionFields.getBest(buffer,-1,buffer.remaining());
                                    if (field==null)
                                        field=HttpField.CACHE.getBest(buffer,-1,buffer.remaining());
                                        
                                    if (field!=null)
                                    {
                                        final String n;
                                        final String v;

                                        if (_strict)
                                        {
                                            // Have to get the fields exactly from the buffer to match case
                                            String fn=field.getName();
                                            String fv=field.getValue();
                                            n=BufferUtil.toString(buffer,buffer.position()-1,fn.length(),StringUtil.__US_ASCII_CHARSET);
                                            if (fv==null)
                                                v=null;
                                            else
                                            {
                                                v=BufferUtil.toString(buffer,buffer.position()+fn.length()+1,fv.length(),StringUtil.__ISO_8859_1_CHARSET);
                                                field=new HttpField(field.getHeader(),n,v);
                                            }
                                        }
                                        else
                                        {
                                            n=field.getName();
                                            v=field.getValue(); 
                                        }
                                        
                                        _header=field.getHeader();
                                        _headerString=n;
         
                                        if (v==null)
                                        {
                                            // Header only
                                            setState(State.HEADER_VALUE);
                                            _string.setLength(0);
                                            _length=0;
                                            buffer.position(buffer.position()+n.length()+1);
                                            break;
                                        }
                                        else
                                        {
                                            // Header and value
                                            int pos=buffer.position()+n.length()+v.length()+1;
                                            byte b=buffer.get(pos);

                                            if (b==HttpTokens.CARRIAGE_RETURN || b==HttpTokens.LINE_FEED)
                                            {                     
                                                _field=field;
                                                _valueString=v;
                                                setState(State.HEADER_IN_VALUE);

                                                if (b==HttpTokens.CARRIAGE_RETURN)
                                                {
                                                    _cr=true;
                                                    buffer.position(pos+1);
                                                }
                                                else
                                                    buffer.position(pos);
                                                break;
                                            }
                                            else
                                            {
                                                setState(State.HEADER_IN_VALUE);
                                                setString(v);
                                                buffer.position(pos);
                                                break;
                                            }
                                        }
                                    }
                                }

                                // New header
                                setState(State.HEADER_IN_NAME);
                                _string.setLength(0);
                                _string.append((char)ch);
                                _length=1;
                            }
                        }
                    }
                    break;

                case HEADER_IN_NAME:
                    if (ch==HttpTokens.COLON || ch==HttpTokens.LINE_FEED)
                    {
                        if (_headerString==null)
                        {
                            _headerString=takeString();
                            _header=HttpHeader.CACHE.get(_headerString);
                        }
                        _length=-1;

                        setState(ch==HttpTokens.LINE_FEED?State.HEADER:State.HEADER_VALUE);
                        break;
                    }
                    
                    if (ch>=HttpTokens.SPACE || ch==HttpTokens.TAB)
                    {
                        if (_header!=null)
                        {
                            setString(_header.asString());
                            _header=null;
                            _headerString=null;
                        }

                        _string.append((char)ch);
                        if (ch>HttpTokens.SPACE)
                            _length=_string.length();
                        break;
                    }
                     
                    throw new BadMessage("Illegal character");

                case HEADER_VALUE:
                    if (ch>HttpTokens.SPACE || ch<0)
                    {
                        _string.append((char)(0xff&ch));
                        _length=_string.length();
                        setState(State.HEADER_IN_VALUE);
                        break;
                    }
                    
                    if (ch==HttpTokens.SPACE || ch==HttpTokens.TAB)
                        break;
                    
                    if (ch==HttpTokens.LINE_FEED)
                    {
                        if (_length > 0)
                        {
                            _value=null;
                            _valueString=(_valueString==null)?takeString():(_valueString+" "+takeString());
                        }
                        setState(State.HEADER);
                        break; 
                    }

                    throw new BadMessage("Illegal character");

                case HEADER_IN_VALUE:
                    if (ch>=HttpTokens.SPACE || ch<0)
                    {
                        if (_valueString!=null)
                        {
                            setString(_valueString);
                            _valueString=null;
                            _field=null;
                        }
                        _string.append((char)(0xff&ch));
                        if (ch>HttpTokens.SPACE || ch<0)
                            _length=_string.length();
                        break;
                    }
                    
                    if (ch==HttpTokens.LINE_FEED)
                    {
                        if (_length > 0)
                        {
                            _value=null;
                            _valueString=takeString();
                            _length=-1;
                        }
                        setState(State.HEADER);
                        break;
                    }
                    throw new BadMessage("Illegal character");
                    
                default:
                    throw new IllegalStateException(_state.toString());

            }
        }

        return handle;
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until next Event.
     * @return True if an {@link RequestHandler} method was called and it returned true;
     */
    public boolean parseNext(ByteBuffer buffer)
    {
        if (DEBUG)
            LOG.debug("parseNext s={} {}",_state,BufferUtil.toDetailString(buffer));
        try
        {
            boolean handle=false;
            
            // Start a request/response
            if (_state==State.START)
            {
                _version=null;
                _method=null;
                _methodString=null;
                _endOfContent=EndOfContent.UNKNOWN_CONTENT;
                _header=null;
                handle=quickStart(buffer);
            }
            
            // Request/response line
            if (!handle && _state.ordinal()>= State.START.ordinal() && _state.ordinal()<State.HEADER.ordinal())
                handle=parseLine(buffer);

            // parse headers
            if (!handle && _state.ordinal()>= State.HEADER.ordinal() && _state.ordinal()<State.CONTENT.ordinal())
                handle=parseHeaders(buffer);
            
            // parse content
            if (!handle && _state.ordinal()>= State.CONTENT.ordinal() && _state.ordinal()<State.END.ordinal())
            {
                // Handle HEAD response
                if (_responseStatus>0 && _headResponse)
                {
                    setState(State.END);
                    handle=_handler.messageComplete();
                }
                else
                    handle=parseContent(buffer);
            }
            
            // handle end states
            if (_state==State.END)
            {
                // eat white space
                while (buffer.remaining()>0 && buffer.get(buffer.position())<=HttpTokens.SPACE)
                    buffer.get();
            }
            else if (_state==State.CLOSED)
            {
                if (BufferUtil.hasContent(buffer))
                {
                    // Just ignore data when closed
                    _headerBytes+=buffer.remaining();
                    BufferUtil.clear(buffer);
                    if (_headerBytes>_maxHeaderBytes)
                    {
                        // Don't want to waste time reading data of a closed request
                        throw new IllegalStateException("too much data after closed");
                    }
                }
            }
            
            // Handle EOF
            if (_eof && !buffer.hasRemaining())
            {
                switch(_state)
                {
                    case CLOSED:
                        break;
                        
                    case START:
                        _handler.earlyEOF();
                        setState(State.CLOSED);
                        break;
                        
                    case END:
                        setState(State.CLOSED);
                        break;
                        
                    case EOF_CONTENT:
                        handle=_handler.messageComplete()||handle;
                        setState(State.CLOSED);
                        break;

                    case  CONTENT:
                    case  CHUNKED_CONTENT:
                    case  CHUNK_SIZE:
                    case  CHUNK_PARAMS:
                    case  CHUNK:
                        _handler.earlyEOF();
                        setState(State.CLOSED);
                        break;

                    default:
                        if (DEBUG)
                            LOG.debug("{} EOF in {}",this,_state);
                        _handler.badMessage(400,null);
                        setState(State.CLOSED);
                        break;
                }
            }
            
            return handle;
        }
        catch(BadMessage e)
        {
            BufferUtil.clear(buffer);

            LOG.warn("badMessage: "+e._code+(e._message!=null?" "+e._message:"")+" for "+_handler);
            if (DEBUG)
                LOG.debug(e);
            setState(State.CLOSED);
            _handler.badMessage(e._code, e._message);
            return false;
        }
        catch(Exception e)
        {
            BufferUtil.clear(buffer);

            LOG.warn("badMessage: "+e.toString()+" for "+_handler);
            if (DEBUG)
                LOG.debug(e);
            
            if (_state.ordinal()<=State.END.ordinal())
            {
                setState(State.CLOSED);
                _handler.badMessage(400,null);
            }
            else
            {
                _handler.earlyEOF();
                setState(State.CLOSED);
            }

            return false;
        }
    }

    private boolean parseContent(ByteBuffer buffer)
    {
        // Handle _content
        byte ch;
        while (_state.ordinal() < State.END.ordinal() && buffer.hasRemaining())
        {
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
                        setState(State.END);
                        if (_handler.messageComplete())
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

                        boolean handle=_handler.content(_contentChunk);
                        if(_contentPosition == _contentLength)
                        {
                            setState(State.END);
                            if (_handler.messageComplete())
                                return true;
                        }
                        if (handle)
                            return true;
                    }
                    break;
                }

                case CHUNKED_CONTENT:
                {
                    ch=next(buffer);
                    if (ch>HttpTokens.SPACE)
                    {
                        _chunkLength=TypeUtil.convertHexDigit(ch);
                        _chunkPosition=0;
                        setState(State.CHUNK_SIZE);
                    }

                    break;
                }

                case CHUNK_SIZE:
                {
                    ch=next(buffer);
                    if (ch==0)
                        break;
                    if (ch == HttpTokens.LINE_FEED)
                    {
                        if (_chunkLength == 0)
                        {
                            setState(State.END);
                            if (_handler.messageComplete())
                                return true;
                        }
                        else
                            setState(State.CHUNK);
                    }
                    else if (ch <= HttpTokens.SPACE || ch == HttpTokens.SEMI_COLON)
                        setState(State.CHUNK_PARAMS);
                    else
                        _chunkLength=_chunkLength * 16 + TypeUtil.convertHexDigit(ch);
                    break;
                }

                case CHUNK_PARAMS:
                {
                    ch=next(buffer);
                    if (ch == HttpTokens.LINE_FEED)
                    {
                        if (_chunkLength == 0)
                        {
                            setState(State.END);
                            if (_handler.messageComplete())
                                return true;
                        }
                        else
                            setState(State.CHUNK);
                    }
                    break;
                }

                case CHUNK:
                {
                    int remaining=_chunkLength - _chunkPosition;
                    if (remaining == 0)
                    {
                        setState(State.CHUNKED_CONTENT);
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
                
                case CLOSED:
                {
                    BufferUtil.clear(buffer);
                    return false;
                }

                default: 
                    break;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isAtEOF()
 
    {
        return _eof;
    }
    
    /* ------------------------------------------------------------------------------- */
    public void atEOF()

    {        
        if (DEBUG)
            LOG.debug("atEOF {}", this);
        _eof=true;
    }

    /* ------------------------------------------------------------------------------- */
    public void close()
    {
        if (DEBUG)
            LOG.debug("close {}", this);
        setState(State.CLOSED);
    }
    
    /* ------------------------------------------------------------------------------- */
    public void reset()
    {
        if (DEBUG)
            LOG.debug("reset {}", this);
        // reset state
        if (_state==State.CLOSED)
            return;
        if (_closed)
        {
            setState(State.CLOSED);
            return;
        }
        
        setState(State.START);
        _endOfContent=EndOfContent.UNKNOWN_CONTENT;
        _contentLength=-1;
        _contentPosition=0;
        _responseStatus=0;
        _contentChunk=null;
        _headerBytes=0;
        _host=false;
    }

    /* ------------------------------------------------------------------------------- */
    private void setState(State state)
    {
        if (DEBUG)
            LOG.debug("{} --> {}",_state,state);
        _state=state;
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
     * These methods return true if the caller should process the events
     * so far received (eg return from parseNext and call HttpChannel.handle).
     * If multiple callbacks are called in sequence (eg 
     * headerComplete then messageComplete) from the same point in the parsing
     * then it is sufficient for the caller to process the events only once.
     */
    public interface HttpHandler<T>
    {
        public boolean content(T item);

        public boolean headerComplete();

        public boolean messageComplete();

        /**
         * This is the method called by parser when a HTTP Header name and value is found
         * @param field The field parsed
         * @return True if the parser should return to its caller
         */
        public boolean parsedHeader(HttpField field);

        /* ------------------------------------------------------------ */
        /** Called to signal that an EOF was received unexpectedly
         * during the parsing of a HTTP message
         */
        public void earlyEOF();

        /* ------------------------------------------------------------ */
        /** Called to signal that a bad HTTP message has been received.
         * @param status The bad status to send
         * @param reason The textual reason for badness
         */
        public void badMessage(int status, String reason);
        
        /* ------------------------------------------------------------ */
        /** @return the size in bytes of the per parser header cache
         */
        public int getHeaderCacheSize();
    }

    public interface RequestHandler<T> extends HttpHandler<T>
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         * @param method The method as enum if of a known type
         * @param methodString The method as a string
         * @param uri The raw bytes of the URI.  These are copied into a ByteBuffer that will not be changed until this parser is reset and reused.
         * @param version
         * @return true if handling parsing should return.
         */
        public abstract boolean startRequest(HttpMethod method, String methodString, ByteBuffer uri, HttpVersion version);

        /**
         * This is the method called by the parser after it has parsed the host header (and checked it's format). This is
         * called after the {@link HttpHandler#parsedHeader(HttpField)} methods and before
         * HttpHandler#headerComplete();
         */
        public abstract boolean parsedHostHeader(String host,int port);
    }

    public interface ResponseHandler<T> extends HttpHandler<T>
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         */
        public abstract boolean startResponse(HttpVersion version, int status, String reason);
    }

    public Trie<HttpField> getFieldCache()
    {
        return _connectionFields;
    }

}
