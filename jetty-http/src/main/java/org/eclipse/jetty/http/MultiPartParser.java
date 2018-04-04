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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;

import org.eclipse.jetty.http.HttpParser.RequestHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.SearchPattern;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/** A parser for MultiPart content type.
 * 
 * @see <a href="https://tools.ietf.org/html/rfc2046#section-5.1">https://tools.ietf.org/html/rfc2046#section-5.1</a>
 * @see <a href="https://tools.ietf.org/html/rfc2045">https://tools.ietf.org/html/rfc2045</a>
 */
public class MultiPartParser
{
    public static final Logger LOG = Log.getLogger(MultiPartParser.class);

    static final byte COLON = (byte)':';
    static final byte TAB = 0x09;
    static final byte LINE_FEED = 0x0A;
    static final byte CARRIAGE_RETURN = 0x0D;
    static final byte SPACE = 0x20;
    static final byte[] CRLF =
    { CARRIAGE_RETURN, LINE_FEED };
    static final byte SEMI_COLON = (byte)';';

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
        FIRST_OCTETS, 
        OCTETS,
        EPILOGUE, 
        END
    }

    private final static EnumSet<State> __delimiterStates = EnumSet.of(State.DELIMITER,State.DELIMITER_CLOSE,State.DELIMITER_PADDING);

    private final boolean DEBUG = LOG.isDebugEnabled();
    private final Handler _handler;
    private final SearchPattern _delimiterSearch;

    private String _fieldName;
    private String _fieldValue;

    private State _state = State.PREAMBLE;
    private FieldState _fieldState = FieldState.FIELD;
    private int _partialBoundary = 2; // No CRLF if no preamble
    private boolean _cr;
    private ByteBuffer _patternBuffer;

    private final Utf8StringBuilder _string = new Utf8StringBuilder();
    private int _length;

    private int _totalHeaderLineLength = -1;
    private int _maxHeaderLineLength = 998;

    /* ------------------------------------------------------------------------------- */
    public MultiPartParser(Handler handler, String boundary)
    {
        _handler = handler;

        String delimiter = "\r\n--" + boundary;
        _patternBuffer = ByteBuffer.wrap(delimiter.getBytes(StandardCharsets.US_ASCII));
        _delimiterSearch = SearchPattern.compile(_patternBuffer.array());
    }

    public void reset()
    {
        _state = State.PREAMBLE;
        _fieldState = FieldState.FIELD;
        _partialBoundary = 2; // No CRLF if no preamble
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
    enum CharState
    {
        ILLEGAL, CR, LF, LEGAL
    }
    private final static CharState[] __charState;
    static
    {
        // token = 1*tchar
        // tchar = "!" / "#" / "$" / "%" / "&" / "'" / "*"
        // / "+" / "-" / "." / "^" / "_" / "`" / "|" / "~"
        // / DIGIT / ALPHA
        // ; any VCHAR, except delimiters
        // quoted-string = DQUOTE *( qdtext / quoted-pair ) DQUOTE
        // qdtext = HTAB / SP /%x21 / %x23-5B / %x5D-7E / obs-text
        // obs-text = %x80-FF
        // comment = "(" *( ctext / quoted-pair / comment ) ")"
        // ctext = HTAB / SP / %x21-27 / %x2A-5B / %x5D-7E / obs-text
        // quoted-pair = "\" ( HTAB / SP / VCHAR / obs-text )

        __charState = new CharState[256];
        Arrays.fill(__charState,CharState.ILLEGAL);
        __charState[LINE_FEED] = CharState.LF;
        __charState[CARRIAGE_RETURN] = CharState.CR;
        __charState[TAB] = CharState.LEGAL;
        __charState[SPACE] = CharState.LEGAL;

        __charState['!'] = CharState.LEGAL;
        __charState['#'] = CharState.LEGAL;
        __charState['$'] = CharState.LEGAL;
        __charState['%'] = CharState.LEGAL;
        __charState['&'] = CharState.LEGAL;
        __charState['\''] = CharState.LEGAL;
        __charState['*'] = CharState.LEGAL;
        __charState['+'] = CharState.LEGAL;
        __charState['-'] = CharState.LEGAL;
        __charState['.'] = CharState.LEGAL;
        __charState['^'] = CharState.LEGAL;
        __charState['_'] = CharState.LEGAL;
        __charState['`'] = CharState.LEGAL;
        __charState['|'] = CharState.LEGAL;
        __charState['~'] = CharState.LEGAL;

        __charState['"'] = CharState.LEGAL;

        __charState['\\'] = CharState.LEGAL;
        __charState['('] = CharState.LEGAL;
        __charState[')'] = CharState.LEGAL;
        Arrays.fill(__charState,0x21,0x27 + 1,CharState.LEGAL);
        Arrays.fill(__charState,0x2A,0x5B + 1,CharState.LEGAL);
        Arrays.fill(__charState,0x5D,0x7E + 1,CharState.LEGAL);
        Arrays.fill(__charState,0x80,0xFF + 1,CharState.LEGAL);

    }

    /* ------------------------------------------------------------------------------- */
    private boolean hasNextByte(ByteBuffer buffer)
    {
        return BufferUtil.hasContent(buffer);
    }

    /* ------------------------------------------------------------------------------- */
    private byte getNextByte(ByteBuffer buffer)
    {

        byte ch = buffer.get();

        CharState s = __charState[0xff & ch];
        switch (s)
        {
            case LF:
                _cr = false;
                return ch;

            case CR:
                if (_cr)
                    throw new BadMessageException("Bad EOL");

                _cr = true;
                if (buffer.hasRemaining())
                    return getNextByte(buffer);

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
        _string.reset();
        _string.append(s);
        _length = s.length();
    }

    /* ------------------------------------------------------------------------------- */
    /*
     * Mime Field strings are treated as UTF-8 as per https://tools.ietf.org/html/rfc7578#section-5.1
     */
    private String takeString()
    {
        String s = _string.toString();
        // trim trailing whitespace.
        if (s.length()>_length)
            s = s.substring(0,_length);
        _string.reset();
        _length = -1;
        return s;
    }

    /* ------------------------------------------------------------------------------- */
    /**
     * Parse until next Event.
     * 
     * @param buffer the buffer to parse
     * @param last whether this buffer contains last bit of content
     * @return True if an {@link RequestHandler} method was called and it returned true;
     */
    public boolean parse(ByteBuffer buffer, boolean last)
    {
        boolean handle = false;
        while (handle == false && BufferUtil.hasContent(buffer))
        {
            switch (_state)
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
                    handle = parseMimePartHeaders(buffer);
                    break;

                case FIRST_OCTETS:
                case OCTETS:
                    handle = parseOctetContent(buffer);
                    break;

                case EPILOGUE:
                    BufferUtil.clear(buffer);
                    break;

                case END:
                    handle = true;
                    break;

                default:
                    throw new IllegalStateException();

            }
        }

        if (last && BufferUtil.isEmpty(buffer))
        {
            if (_state == State.EPILOGUE)
            {
                _state = State.END;
                
                if(LOG.isDebugEnabled())
                    LOG.debug("messageComplete {}", this);
                
                return _handler.messageComplete();
            }
            else
            {
                if(LOG.isDebugEnabled())
                    LOG.debug("earlyEOF {}", this);
                
                _handler.earlyEOF();
                return true;
            }
        }

        return handle;
    }

    /* ------------------------------------------------------------------------------- */
    private void parsePreamble(ByteBuffer buffer)
    {
        if (_partialBoundary > 0)
        {
            int partial = _delimiterSearch.startsWith(buffer.array(),buffer.arrayOffset() + buffer.position(),buffer.remaining(),_partialBoundary);
            if (partial > 0)
            {
                if (partial == _delimiterSearch.getLength())
                {
                    buffer.position(buffer.position() + partial - _partialBoundary);
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

        int delimiter = _delimiterSearch.match(buffer.array(),buffer.arrayOffset() + buffer.position(),buffer.remaining());
        if (delimiter >= 0)
        {
            buffer.position(delimiter - buffer.arrayOffset() + _delimiterSearch.getLength());
            setState(State.DELIMITER);
            return;
        }

        _partialBoundary = _delimiterSearch.endsWith(buffer.array(),buffer.arrayOffset() + buffer.position(),buffer.remaining());
        BufferUtil.clear(buffer);

        return;
    }

    /* ------------------------------------------------------------------------------- */
    private void parseDelimiter(ByteBuffer buffer)
    {
        while (__delimiterStates.contains(_state) && hasNextByte(buffer))
        {
            byte b = getNextByte(buffer);
            if (b == 0)
                return;

            if (b == '\n')
            {
                setState(State.BODY_PART);
                
                if(LOG.isDebugEnabled())
                    LOG.debug("startPart {}",this);
                
                _handler.startPart();
                return;
            }

            switch (_state)
            {
                case DELIMITER:
                    if (b == '-')
                        setState(State.DELIMITER_CLOSE);
                    else
                        setState(State.DELIMITER_PADDING);
                    continue;

                case DELIMITER_CLOSE:
                    if (b == '-')
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
    protected boolean parseMimePartHeaders(ByteBuffer buffer)
    {
        // Process headers
        while (_state == State.BODY_PART && hasNextByte(buffer))
        {
            // process each character
            byte b = getNextByte(buffer);
            if (b == 0)
                break;

            if (b != LINE_FEED)
                _totalHeaderLineLength++;

            if (_totalHeaderLineLength > _maxHeaderLineLength)
                throw new IllegalStateException("Header Line Exceeded Max Length");

            switch (_fieldState)
            {
                case FIELD:
                    switch (b)
                    {
                        case SPACE:
                        case TAB:
                        {
                            // Folded field value!

                            if (_fieldName == null)
                                throw new IllegalStateException("First field folded");

                            if (_fieldValue == null)
                            {
                                _string.reset();
                                _length = 0;
                            }
                            else
                            {
                                setString(_fieldValue);
                                _string.append(' ');
                                _length++;
                                _fieldValue = null;
                            }
                            setState(FieldState.VALUE);
                            break;
                        }

                        case LINE_FEED:
                        {
                            handleField();
                            setState(State.FIRST_OCTETS);
                            _partialBoundary = 2; // CRLF is option for empty parts
                            
                            if(LOG.isDebugEnabled())
                                LOG.debug("headerComplete {}", this);
                            
                            if (_handler.headerComplete())
                                return true;
                            break;
                        }

                        default:
                        {
                            // process previous header
                            handleField();

                            // New header
                            setState(FieldState.IN_NAME);
                            _string.reset();
                            _string.append(b);
                            _length = 1;
                        }
                    }
                    break;

                case IN_NAME:
                    switch (b)
                    {
                        case COLON:
                            _fieldName = takeString();
                            _length = -1;
                            setState(FieldState.VALUE);
                            break;

                        case SPACE:
                            // Ignore trailing whitespaces
                            setState(FieldState.AFTER_NAME);
                            break;

                        case LINE_FEED:
                        {
                            if(LOG.isDebugEnabled())
                                LOG.debug("Line Feed in Name {}", this);
                            
                            handleField();
                            setState(FieldState.FIELD);
                            break;
                        }

                        default:
                            _string.append(b);
                            _length = _string.length();
                            break;
                    }
                    break;

                case AFTER_NAME:
                    switch (b)
                    {
                        case COLON:
                            _fieldName = takeString();
                            _length = -1;
                            setState(FieldState.VALUE);
                            break;

                        case LINE_FEED:
                            _fieldName = takeString();
                            _string.reset();
                            _fieldValue = "";
                            _length = -1;
                            break;

                        case SPACE:
                            break;

                        default:
                            throw new IllegalCharacterException(_state,b,buffer);
                    }
                    break;

                case VALUE:
                    switch (b)
                    {
                        case LINE_FEED:
                            _string.reset();
                            _fieldValue = "";
                            _length = -1;

                            setState(FieldState.FIELD);
                            break;

                        case SPACE:
                        case TAB:
                            break;

                        default:
                            _string.append(b);
                            _length = _string.length();
                            setState(FieldState.IN_VALUE);
                            break;
                    }
                    break;

                case IN_VALUE:
                    switch (b)
                    {
                        case SPACE:
                            _string.append(b);
                            break;

                        case LINE_FEED:
                            if (_length > 0)
                            {
                                _fieldValue = takeString();
                                _length = -1;
                                _totalHeaderLineLength = -1;
                            }
                            setState(FieldState.FIELD);
                            break;

                        default:
                            _string.append(b);
                            if (b > SPACE || b < 0)
                                _length = _string.length();
                            break;
                    }
                    break;

                default:
                    throw new IllegalStateException(_state.toString());

            }
        }
        return false;
    }

    /* ------------------------------------------------------------------------------- */
    private void handleField()
    {
        if(LOG.isDebugEnabled())
            LOG.debug("parsedField:  _fieldName={} _fieldValue={} {}", _fieldName, _fieldValue, this);
        
        if (_fieldName != null && _fieldValue != null)
            _handler.parsedField(_fieldName,_fieldValue);
        _fieldName = _fieldValue = null;
    }

    /* ------------------------------------------------------------------------------- */

    protected boolean parseOctetContent(ByteBuffer buffer)
    {

        // Starts With
        if (_partialBoundary > 0)
        {
            int partial = _delimiterSearch.startsWith(buffer.array(),buffer.arrayOffset() + buffer.position(),buffer.remaining(),_partialBoundary);
            if (partial > 0)
            {
                if (partial == _delimiterSearch.getLength())
                {
                    buffer.position(buffer.position() + _delimiterSearch.getLength() - _partialBoundary);
                    setState(State.DELIMITER);
                    _partialBoundary = 0;
                    
                    if(LOG.isDebugEnabled())
                        LOG.debug("Content={}, Last={} {}",BufferUtil.toDetailString(BufferUtil.EMPTY_BUFFER),true,this);
                    
                    return _handler.content(BufferUtil.EMPTY_BUFFER,true);
                }

                _partialBoundary = partial;
                BufferUtil.clear(buffer);
                return false;
            }
            else
            {
                // output up to _partialBoundary of the search pattern
                ByteBuffer content = _patternBuffer.slice();
                if (_state == State.FIRST_OCTETS)
                {
                    setState(State.OCTETS);
                    content.position(2);
                }
                content.limit(_partialBoundary);
                _partialBoundary = 0;

                if(LOG.isDebugEnabled())
                    LOG.debug("Content={}, Last={} {}",BufferUtil.toDetailString(content),false,this);
                
                if (_handler.content(content,false))
                    return true;
            }
        }

        // Contains
        int delimiter = _delimiterSearch.match(buffer.array(),buffer.arrayOffset() + buffer.position(),buffer.remaining());
        if (delimiter >= 0)
        {
            ByteBuffer content = buffer.slice();
            content.limit(delimiter - buffer.arrayOffset() - buffer.position());

            buffer.position(delimiter - buffer.arrayOffset() + _delimiterSearch.getLength());
            setState(State.DELIMITER);

            if(LOG.isDebugEnabled())
                LOG.debug("Content={}, Last={} {}",BufferUtil.toDetailString(content),true,this);
            
            return _handler.content(content,true);
        }

        // Ends With
        _partialBoundary = _delimiterSearch.endsWith(buffer.array(),buffer.arrayOffset() + buffer.position(),buffer.remaining());
        if (_partialBoundary > 0)
        {
            ByteBuffer content = buffer.slice();
            content.limit(content.limit() - _partialBoundary);

            if(LOG.isDebugEnabled())
                LOG.debug("Content={}, Last={} {}",BufferUtil.toDetailString(content),false,this);
            
            BufferUtil.clear(buffer);
            return _handler.content(content,false);
        }

        // There is normal content with no delimiter
        ByteBuffer content = buffer.slice();
        
        if(LOG.isDebugEnabled())
            LOG.debug("Content={}, Last={} {}",BufferUtil.toDetailString(content),false,this);
        
        BufferUtil.clear(buffer);
        return _handler.content(content,false);
    }

    /* ------------------------------------------------------------------------------- */
    private void setState(State state)
    {
        if (DEBUG)
            LOG.debug("{} --> {}",_state,state);
        _state = state;
    }

    /* ------------------------------------------------------------------------------- */
    private void setState(FieldState state)
    {
        if (DEBUG)
            LOG.debug("{}:{} --> {}",_state,_fieldState,state);
        _fieldState = state;
    }

    /* ------------------------------------------------------------------------------- */
    @Override
    public String toString()
    {
        return String.format("%s{s=%s}",getClass().getSimpleName(),_state);
    }

    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /* ------------------------------------------------------------ */
    /*
     * Event Handler interface These methods return true if the caller should process the events so far received (eg return from parseNext and call
     * HttpChannel.handle). If multiple callbacks are called in sequence (eg headerComplete then messageComplete) from the same point in the parsing then it is
     * sufficient for the caller to process the events only once.
     */
    public interface Handler
    {
        public default void startPart()
        {
        }

        public default void parsedField(String name, String value)
        {
        }

        public default boolean headerComplete()
        {
            return false;
        }

        public default boolean content(ByteBuffer item, boolean last)
        {
            return false;
        }

        public default boolean messageComplete()
        {
            return false;
        }

        public default void earlyEOF()
        {
        }
    }

    /* ------------------------------------------------------------------------------- */
    @SuppressWarnings("serial")
    private static class IllegalCharacterException extends IllegalArgumentException
    {
        private IllegalCharacterException(State state, byte ch, ByteBuffer buffer)
        {
            super(String.format("Illegal character 0x%X",ch));
            // Bug #460642 - don't reveal buffers to end user
            LOG.warn(String.format("Illegal character 0x%X in state=%s for buffer %s",ch,state,BufferUtil.toDetailString(buffer)));
        }
    }
}
