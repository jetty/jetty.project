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
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.SearchPattern;
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


    // States
    public enum FieldState
    {
        FIELD,
        IN_NAME,
        AFTER_NAME,
        VALUE,
        IN_VALUE
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
    private String _fieldName;
    private String _fieldValue;

    private State _state = State.PREAMBLE;
    private FieldState _fieldState = FieldState.FIELD;
    private int _partialBoundary = 2; // No CRLF if no preamble
    private boolean _cr;
    private boolean _quote;

    private final StringBuilder _string=new StringBuilder();
    private int _length;


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
                    continue;
                    
                case BODY_PART:
                    handle = parseFields(buffer);
                    break;
                    
                case PART:
                    // TODO
                    handle = true;
                    break;
                    
                    
                case EPILOGUE:
                    // TODO
                    handle = true;
                    break;
                    
                case END:
                    // TODO
                    handle = true;
                    break;
                    
                default:
                    throw new IllegalStateException();
                
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
        // Process headers
        while (_state==State.BODY_PART && buffer.hasRemaining())
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
                        case HttpTokens.SPACE:
                        case HttpTokens.TAB:
                        {
                            // Folded field value!
                            
                            if (_fieldName==null)
                                throw new IllegalStateException("First field folded");
                            
                            if (_fieldValue==null)
                            {
                                _string.setLength(0);
                                _length=0;
                            }
                            else
                            {
                                setString(_fieldValue);
                                _string.append(' ');
                                _length++;
                                _fieldValue=null;
                            }
                            setState(FieldState.VALUE);
                            break;
                        }

                        case HttpTokens.LINE_FEED:
                        {
                            handleField();
                            setState(State.PART);
                            if (_handler.headerComplete())
                                return true;
                            break;
                        }

                        default:
                        {
                            // now handle the ch
                            if (b<HttpTokens.SPACE)
                                throw new BadMessageException();

                            // process previous header
                            handleField();

                            // New header
                            setState(FieldState.IN_NAME);
                            _string.setLength(0);
                            _string.append((char)b);
                            _length=1;
                        }
                    }
                    break;

                case IN_NAME:
                    if (b==HttpTokens.COLON)
                    {
                        _fieldName=takeString();
                        _length=-1;
                        setState(FieldState.VALUE);
                        break;
                    }
                    
                    if (b>HttpTokens.SPACE)
                    {
                        _string.append((char)b);
                        _length=_string.length();
                        break;
                    }

                    //Ignore trailing whitespaces
                    if (b==HttpTokens.SPACE)
                    {
                        setState(FieldState.AFTER_NAME);
                        break;
                    }
                    
                    throw new IllegalCharacterException(_state,b,buffer);
                    
                case AFTER_NAME:
                    if (b==HttpTokens.COLON)
                    {
                        _fieldName=takeString();
                        _length=-1;
                        setState(FieldState.VALUE);
                        break;
                    }
                    
                    if (b==HttpTokens.LINE_FEED)
                    {
                        _fieldName=takeString();
                        _string.setLength(0);
                        _fieldValue="";
                        _length=-1;
                    }

                    if (b==HttpTokens.SPACE)
                        break;
                    
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
                        _fieldValue="";
                        _length=-1;

                        setState(FieldState.FIELD);
                        break;
                    }
                    throw new IllegalCharacterException(_state,b,buffer);

                case IN_VALUE:
                    if (b>=HttpTokens.SPACE || b<0 || b==HttpTokens.TAB)
                    {
                        if (_fieldValue!=null)
                        {
                            setString(_fieldValue);
                            _fieldValue=null;
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
                            _fieldValue=takeString();
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
        return true;
    }
    
    /* ------------------------------------------------------------------------------- */
    private void handleField()
    {
        if (_fieldName!=null && _fieldValue!=null)
            _handler.parsedHeader(_fieldName,_fieldValue);
        _fieldName = _fieldValue = null;
    }
    
    /* ------------------------------------------------------------------------------- */

    protected boolean parseContent(ByteBuffer buffer)
    {
        
        //Starts With
        if (_partialBoundary>0)
        {
            int partial = _search.startsWith(buffer.array(),buffer.arrayOffset()+buffer.position(),buffer.remaining(),_partialBoundary);
            if (partial>0)
            {
                if (partial==_search.getLength())
                {
                    
                    
                    _partialBoundary = 0;
                    return _handler.content(content, true);
                }

                _partialBoundary = partial;
                BufferUtil.clear(buffer);
                
                //TODO
                return false;
            }
            
            _partialBoundary = 0;
        }
        
        
        // Contains
        int delimiter = _search.match(buffer.array(),buffer.arrayOffset()+buffer.position(),buffer.remaining());
        if (delimiter>=0)
        {
            ByteBuffer content = buffer.slice();
            content.limit(delimiter - buffer.arrayOffset()+buffer.position() - buffer.arrayOffset());
            
            buffer.position(delimiter-buffer.arrayOffset()+_search.getLength());
            setState(State.DELIMITER);
            
            return _handler.content(content, true);
        }

        // Ends With
        _partialBoundary = _search.endsWith(buffer.array(), buffer.arrayOffset()+buffer.position(), buffer.remaining());
        if(_partialBoundary > 0) 
        {
            ByteBuffer content = buffer.slice();
            content.limit(delimiter - buffer.arrayOffset()+buffer.position() - buffer.arrayOffset());
        }
        BufferUtil.clear(buffer);
        
        //TODO
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
            LOG.debug("{}:{} --> {}",_state,_fieldState,state);
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
        public default void parsedHeader(String name, String value) {}
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
    
    
    
    public static void main(String[] args) {
        System.out.println("hello");
    }
    
}
