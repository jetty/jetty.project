//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.eclipse.jetty.http.HttpTokens.CARRIAGE_RETURN;
import static org.eclipse.jetty.http.HttpTokens.LINE_FEED;
import static org.eclipse.jetty.http.HttpTokens.SPACE;
import static org.eclipse.jetty.http.HttpTokens.TAB;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumSet;

import org.eclipse.jetty.http.HttpParser.RequestHandler;
import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.SearchPattern;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/*
 * RFC2046 and RFC7578
 * 
 * example:
------WebKitFormBoundaryzWHSH95mOxQwmKln
Content-Disposition: form-data; name="TextField"

Text value:;"' x ---- 
------WebKitFormBoundaryzWHSH95mOxQwmKln
Content-Disposition: form-data; name="file1"; filename="file with :%22; in name.txt"
Content-Type: text/plain


------WebKitFormBoundaryzWHSH95mOxQwmKln
Content-Disposition: form-data; name="file2"; filename="ManagedSelector.java"
Content-Type: text/x-java


------WebKitFormBoundaryzWHSH95mOxQwmKln
Content-Disposition: form-data; name="Action"

Submit
------WebKitFormBoundaryzWHSH95mOxQwmKln--
 *
 * BNF:
 * 
     boundary := 0*69<bchars> bcharsnospace

     bchars := bcharsnospace / " "

     bcharsnospace := DIGIT / ALPHA / "'" / "(" / ")" /
                      "+" / "_" / "," / "-" / "." /
                      "/" / ":" / "=" / "?"

     dash-boundary := "--" boundary
                      ; boundary taken from the value of
                      ; boundary parameter of the
                      ; Content-Type field.

     multipart-body := [preamble CRLF]
                       dash-boundary transport-padding CRLF
                       body-part *encapsulation
                       close-delimiter transport-padding
                       [CRLF epilogue]
 
 
     transport-padding := *LWSP-char
                          ; Composers MUST NOT generate
                          ; non-zero length transport
                          ; padding, but receivers MUST
                          ; be able to handle padding
                          ; added by message transports.

     encapsulation := delimiter transport-padding
                      CRLF body-part

     delimiter := CRLF dash-boundary

     close-delimiter := delimiter "--"

     preamble := discard-text

     epilogue := discard-text

     discard-text := *(*text CRLF) *text
                     ; May be ignored or discarded.

     body-part := MIME-part-headers [CRLF *OCTET]
                  ; Lines in a body-part must not start
                  ; with the specified dash-boundary and
                  ; the delimiter must not appear anywhere
                  ; in the body part.  Note that the
                  ; semantics of a body-part differ from
                  ; the semantics of a message, as
                  ; described in the text.

     OCTET := <any 0-255 octet value>
 * 
 */
public class MultiPartParser
{
    public static final Logger LOG = Log.getLogger(MultiPartParser.class);

    public final static Trie<MimeField> CACHE = new ArrayTrie<>(2048);

    // States
    public enum FieldState
    {
        FIELD,
        IN_NAME,
        AFTER_NAME,
        VALUE,
        IN_VALUE,
        
        PARAM,
        PARAM_NAME, 
        PARAM_VALUE
    }
    
    // States
    public enum State
    {
        PREAMBLE,
        DELIMITER,
        DELIMITER_PADDING,
        DELIMITER_CLOSE, 
        BODY_PART,
        PART,       
        EPILOGUE,
        END
    }

    private final static EnumSet<State> __delimiterStates = EnumSet.of(State.DELIMITER,State.DELIMITER_CLOSE,State.DELIMITER_PADDING);
    
    private final boolean DEBUG=LOG.isDebugEnabled(); 
    private final Handler _handler;
    private final String _boundary;
    private final SearchPattern _search;
    private MimeField _field;
    private String _headerString;
    private String _valueString;
    private int _headerBytes;

    private State _state = State.PREAMBLE;
    private FieldState _fieldState = FieldState.FIELD;
    private int _partialBoundary = 2; // No CRLF if no preamble
    private boolean _cr;
    private boolean _quote;

    private final StringBuilder _string=new StringBuilder();
    private int _length;

    static
    {
        CACHE.put(new MimeField("Content-Disposition","form-data"));
        CACHE.put(new MimeField("Content-Type","text/plain"));
    }


    /* ------------------------------------------------------------------------------- */
    public MultiPartParser(Handler handler, String boundary)
    {
        _handler = handler;
        _boundary = boundary;
        _search = SearchPattern.compile("\r\n--"+boundary);
    }

    /* ------------------------------------------------------------------------------- */
    public Handler getHandler()
    {
        return _handler;
    }
    
    /* ------------------------------------------------------------------------------- */
    public State getState()
    {
        return _state;
    }

    /* ------------------------------------------------------------------------------- */
    public boolean isState(State state)
    {
        return _state == state;
    }

    /* ------------------------------------------------------------------------------- */
    enum CharState { ILLEGAL, CR, LF, LEGAL }
    private final static CharState[] __charState;
    static
    {
        // token          = 1*tchar
        // tchar          = "!" / "#" / "$" / "%" / "&" / "'" / "*"
        //                / "+" / "-" / "." / "^" / "_" / "`" / "|" / "~"
        //                / DIGIT / ALPHA
        //                ; any VCHAR, except delimiters
        // quoted-string  = DQUOTE *( qdtext / quoted-pair ) DQUOTE
        // qdtext         = HTAB / SP /%x21 / %x23-5B / %x5D-7E / obs-text
        // obs-text       = %x80-FF
        // comment        = "(" *( ctext / quoted-pair / comment ) ")"
        // ctext          = HTAB / SP / %x21-27 / %x2A-5B / %x5D-7E / obs-text
        // quoted-pair    = "\" ( HTAB / SP / VCHAR / obs-text )

        __charState=new CharState[256];
        Arrays.fill(__charState,CharState.ILLEGAL);
        __charState[LINE_FEED]=CharState.LF;
        __charState[CARRIAGE_RETURN]=CharState.CR;
        __charState[TAB]=CharState.LEGAL;
        __charState[SPACE]=CharState.LEGAL;

        __charState['!']=CharState.LEGAL;
        __charState['#']=CharState.LEGAL;
        __charState['$']=CharState.LEGAL;
        __charState['%']=CharState.LEGAL;
        __charState['&']=CharState.LEGAL;
        __charState['\'']=CharState.LEGAL;
        __charState['*']=CharState.LEGAL;
        __charState['+']=CharState.LEGAL;
        __charState['-']=CharState.LEGAL;
        __charState['.']=CharState.LEGAL;
        __charState['^']=CharState.LEGAL;
        __charState['_']=CharState.LEGAL;
        __charState['`']=CharState.LEGAL;
        __charState['|']=CharState.LEGAL;
        __charState['~']=CharState.LEGAL;

        __charState['"']=CharState.LEGAL;

        __charState['\\']=CharState.LEGAL;
        __charState['(']=CharState.LEGAL;
        __charState[')']=CharState.LEGAL;
        Arrays.fill(__charState,0x21,0x27+1,CharState.LEGAL);
        Arrays.fill(__charState,0x2A,0x5B+1,CharState.LEGAL);
        Arrays.fill(__charState,0x5D,0x7E+1,CharState.LEGAL);
        Arrays.fill(__charState,0x80,0xFF+1,CharState.LEGAL);

    }

    /* ------------------------------------------------------------------------------- */
    private byte next(ByteBuffer buffer)
    {
        byte ch = buffer.get();

        CharState s = __charState[0xff & ch];
        switch(s)
        {
            case LF:
                _cr=false;
                return ch;

            case CR:
                if (_cr)
                    throw new BadMessageException("Bad EOL");

                _cr=true;
                if (buffer.hasRemaining())
                    return next(buffer);

                // Can return 0 here to indicate the need for more characters,
                // because a real 0 in the buffer would cause a BadMessage below
                return 0;

            case LEGAL:
                if (_cr)
                    throw new BadMessageException("Bad EOL");
                return ch;

            case ILLEGAL:
            default:
                throw new IllegalCharacterException(_state,ch,buffer);
        }
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
    /**
     * Parse until next Event.
     * @param buffer the buffer to parse
     * @return True if an {@link RequestHandler} method was called and it returned true;
     */
    public boolean parse(ByteBuffer buffer,boolean last)
    {
        boolean handle = false;
        while(handle==false && BufferUtil.hasContent(buffer))
        {
            switch(_state)
            {
                case PREAMBLE:
                    parsePreamble(buffer);
                    continue;
                    
                case DELIMITER:
                case DELIMITER_PADDING:
                case DELIMITER_CLOSE:
                    parseDelimiter(buffer);
                    break;

                    
                case BODY_PART:
                    handle = parseFields(buffer);
                    break;
                    
                case PART:
                    break;
                    
                case END:
                    break;
                    
                case EPILOGUE:
                    break;
                    
                default:
                    break;
                
            }
        }
        
        return handle;
    }
    
    /* ------------------------------------------------------------------------------- */
    private void parsePreamble(ByteBuffer buffer)
    {
        if (_partialBoundary>0)
        {
            int partial = _search.startsWith(buffer.array(),buffer.arrayOffset()+buffer.position(),buffer.remaining(),_partialBoundary);
            if (partial>0)
            {
                // TODO this should not be needed?
                partial+=_partialBoundary;
                
                if (partial==_search.getLength())
                {
                    buffer.position(buffer.position()+partial-_partialBoundary);
                    _partialBoundary = 0;
                    setState(State.DELIMITER);
                    return;
                }

                _partialBoundary = partial;
                BufferUtil.clear(buffer);
                return;
            }
            
            _partialBoundary = 0;
        }
        
        int delimiter = _search.match(buffer.array(),buffer.arrayOffset()+buffer.position(),buffer.remaining());
        if (delimiter>=0)
        {
            buffer.position(delimiter-buffer.arrayOffset()+_search.getLength());
            setState(State.DELIMITER);
            return;
        }

        _partialBoundary = _search.endsWith(buffer.array(),buffer.arrayOffset()+buffer.position(),buffer.remaining());
        BufferUtil.clear(buffer);

        return;
    }
    
    /* ------------------------------------------------------------------------------- */
    private void parseDelimiter(ByteBuffer buffer)
    {        
        while (__delimiterStates.contains(_state) && buffer.hasRemaining())
        {
            byte b=next(buffer);
            if (b==0)
                return;
            
            if (b=='\n')
            {
                setState(State.BODY_PART);
                return;
            }            

            switch(_state)
            {
                case DELIMITER:
                    if (b=='-')
                        setState(State.DELIMITER_CLOSE);
                    else
                        setState(State.DELIMITER_PADDING);
                    continue;
                         
                case DELIMITER_CLOSE:
                    if (b=='-')
                    {
                        setState(State.EPILOGUE);
                        return;
                    }
                    setState(State.DELIMITER_PADDING);
                    continue;
                    
                case DELIMITER_PADDING:
                default:
                    continue;       
            }
        }
    }

    /* ------------------------------------------------------------------------------- */
    /*
     * Parse the message headers and return true if the handler has signaled for a return
     */
    protected boolean parseFields(ByteBuffer buffer)
    {
        /*
        // Process headers
        while ((_state==State.HEADER && buffer.hasRemaining())
        {
            // process each character
            byte b=next(buffer);
            if (b==0)
                break;


            switch (_fieldState)
            {
                case FIELD:
                    switch(b)
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
                            setState(FieldState.VALUE);
                            break;
                        }

                        case HttpTokens.LINE_FEED:
                        {
                            // process previous header
                            if (_state==State.HEADER)
                                parsedHeader();
                            else
                                parsedTrailer();

                            _contentPosition=0;

                            // End of headers or trailers?
                            if (_state==State.TRAILER)
                            {
                                setState(State.END);
                                return _handler.messageComplete();
                            }
                            
                            // Was there a required host header?
                            if (!_host && _version==HttpVersion.HTTP_1_1 && _requestHandler!=null)
                            {
                                throw new BadMessageException(HttpStatus.BAD_REQUEST_400,"No Host");
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
                                {
                                    setState(State.EOF_CONTENT);
                                    boolean handle=_handler.headerComplete();
                                    _headerComplete=true;
                                    return handle;
                                }
                                case CHUNKED_CONTENT:
                                {
                                    setState(State.CHUNKED_CONTENT);
                                    boolean handle=_handler.headerComplete();
                                    _headerComplete=true;
                                    return handle;
                                }
                                case NO_CONTENT:
                                {
                                    setState(State.END);
                                    return handleHeaderContentMessage();
                                }
                                default:
                                {
                                    setState(State.CONTENT);
                                    boolean handle=_handler.headerComplete();
                                    _headerComplete=true;
                                    return handle;
                                }
                            }
                        }

                        default:
                        {
                            // now handle the ch
                            if (b<HttpTokens.SPACE)
                                throw new BadMessageException();

                            // process previous header
                            if (_state==State.HEADER)
                                parsedHeader();
                            else
                                parsedTrailer();

                            // handle new header
                            if (buffer.hasRemaining())
                            {
                                // Try a look ahead for the known header name and value.
                                HttpField cached_field=_fieldCache==null?null:_fieldCache.getBest(buffer,-1,buffer.remaining());
                                if (cached_field==null)
                                    cached_field=CACHE.getBest(buffer,-1,buffer.remaining());

                                if (cached_field!=null)
                                {
                                    String n = cached_field.getName();
                                    String v = cached_field.getValue();

                                    if (!_compliances.contains(HttpComplianceSection.FIELD_NAME_CASE_INSENSITIVE))
                                    {
                                        // Have to get the fields exactly from the buffer to match case
                                        String en = BufferUtil.toString(buffer,buffer.position()-1,n.length(),StandardCharsets.US_ASCII);
                                        if (!n.equals(en))
                                        {
                                            handleViolation(HttpComplianceSection.FIELD_NAME_CASE_INSENSITIVE,en);
                                            n = en;
                                            cached_field = new HttpField(cached_field.getHeader(),n,v);
                                        }
                                    }
                                    
                                    if (v!=null && !_compliances.contains(HttpComplianceSection.CASE_INSENSITIVE_FIELD_VALUE_CACHE))
                                    {
                                        String ev = BufferUtil.toString(buffer,buffer.position()+n.length()+1,v.length(),StandardCharsets.ISO_8859_1);
                                        if (!v.equals(ev))
                                        {
                                            handleViolation(HttpComplianceSection.CASE_INSENSITIVE_FIELD_VALUE_CACHE,ev+"!="+v);
                                            v = ev;
                                            cached_field = new HttpField(cached_field.getHeader(),n,v);
                                        }
                                    }
                                    
                                    _header=cached_field.getHeader();
                                    _headerString=n;

                                    if (v==null)
                                    {
                                        // Header only
                                        setState(FieldState.VALUE);
                                        _string.setLength(0);
                                        _length=0;
                                        buffer.position(buffer.position()+n.length()+1);
                                        break;
                                    }
                                    else
                                    {
                                        // Header and value
                                        int pos=buffer.position()+n.length()+v.length()+1;
                                        byte peek=buffer.get(pos);

                                        if (peek==HttpTokens.CARRIAGE_RETURN || peek==HttpTokens.LINE_FEED)
                                        {
                                            _field=cached_field;
                                            _valueString=v;
                                            setState(FieldState.IN_VALUE);

                                            if (peek==HttpTokens.CARRIAGE_RETURN)
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
                                            setState(FieldState.IN_VALUE);
                                            setString(v);
                                            buffer.position(pos);
                                            break;
                                        }
                                    }
                                }
                            }

                            // New header
                            setState(FieldState.IN_NAME);
                            _string.setLength(0);
                            _string.append((char)b);
                            _length=1;
                        }
                    }
                    break;

                case IN_NAME:
                    if (b>HttpTokens.SPACE && b!=HttpTokens.COLON)
                    {
                        if (_header!=null)
                        {
                            setString(_header.asString());
                            _header=null;
                            _headerString=null;
                        }

                        _string.append((char)b);
                        _length=_string.length();
                        break;
                    }

                    // Fallthrough
                    
                case AFTER_NAME:
                    if (b==HttpTokens.COLON)
                    {
                        if (_headerString==null)
                        {
                            _headerString=takeString();
                            _header=HttpHeader.CACHE.get(_headerString);
                        }
                        _length=-1;

                        setState(FieldState.VALUE);
                        break;
                    }
                    
                    if (b==HttpTokens.LINE_FEED)
                    {
                        if (_headerString==null)
                        {
                            _headerString=takeString();
                            _header=HttpHeader.CACHE.get(_headerString);
                        }
                        _string.setLength(0);
                        _valueString="";
                        _length=-1;

                        if (!complianceViolation(HttpComplianceSection.FIELD_COLON,_headerString))
                        {                        
                            setState(FieldState.FIELD);
                            break;
                        }
                    }

                    //Ignore trailing whitespaces
                    if (b==HttpTokens.SPACE && !complianceViolation(HttpComplianceSection.NO_WS_AFTER_FIELD_NAME,null))
                    {
                        setState(FieldState.AFTER_NAME);
                        break;
                    }

                    throw new IllegalCharacterException(_state,b,buffer);

                case VALUE:
                    if (b>HttpTokens.SPACE || b<0)
                    {
                        _string.append((char)(0xff&b));
                        _length=_string.length();
                        setState(FieldState.IN_VALUE);
                        break;
                    }

                    if (b==HttpTokens.SPACE || b==HttpTokens.TAB)
                        break;

                    if (b==HttpTokens.LINE_FEED)
                    {
                        _string.setLength(0);
                        _valueString="";
                        _length=-1;

                        setState(FieldState.FIELD);
                        break;
                    }
                    throw new IllegalCharacterException(_state,b,buffer);

                case IN_VALUE:
                    if (b>=HttpTokens.SPACE || b<0 || b==HttpTokens.TAB)
                    {
                        if (_valueString!=null)
                        {
                            setString(_valueString);
                            _valueString=null;
                            _field=null;
                        }
                        _string.append((char)(0xff&b));
                        if (b>HttpTokens.SPACE || b<0)
                            _length=_string.length();
                        break;
                    }

                    if (b==HttpTokens.LINE_FEED)
                    {
                        if (_length > 0)
                        {
                            _valueString=takeString();
                            _length=-1;
                        }
                        setState(FieldState.FIELD);
                        break;
                    }

                    throw new IllegalCharacterException(_state,b,buffer);

                default:
                    throw new IllegalStateException(_state.toString());

            }
        }
        */
        return true;
    }


    
    /* ------------------------------------------------------------------------------- */

    protected boolean parseContent(ByteBuffer buffer)
    {
        return false;
    }


    /* ------------------------------------------------------------------------------- */
    private void setState(State state)
    {
        if (DEBUG)
            LOG.debug("{} --> {}",_state,state);
        _state=state;
    }

    /* ------------------------------------------------------------------------------- */
    private void setState(FieldState state)
    {
        if (DEBUG)
            LOG.debug("{}:{} --> {}",_state,_field,state);
        _fieldState=state;
    }


    /* ------------------------------------------------------------------------------- */
    @Override
    public String toString()
    {
        return String.format("%s{s=%s}",
                getClass().getSimpleName(),
                _state);
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
    public interface Handler
    {
        public default void parsedHeader(MimeField field) {}
        public default void parsedParameter(MimeField field, String name, String value) {};
        public default boolean headerComplete() {return false;}
        
        public default boolean content(ByteBuffer item, boolean last) {return false;}
        
        public default boolean messageComplete() {return false;}

        public default void earlyEOF() {}
    }

    /* ------------------------------------------------------------------------------- */
    @SuppressWarnings("serial")
    private static class IllegalCharacterException extends BadMessageException
    {
        private IllegalCharacterException(State state,byte ch,ByteBuffer buffer)
        {
            super(400,String.format("Illegal character 0x%X",ch));
            // Bug #460642 - don't reveal buffers to end user
            LOG.warn(String.format("Illegal character 0x%X in state=%s for buffer %s",ch,state,BufferUtil.toDetailString(buffer)));
        }
    }
    

    static class MimeField
    {
        final String _name;
        final String _value;
        final String _string;
        
        public MimeField(String name, String value)
        {
            _name = name;
            _value = value;
            _string = name + ": " + value;
        }
        
        @Override
        public String toString()
        {
            return _string;
        }
    }
    
}
