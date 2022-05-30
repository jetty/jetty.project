//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpParser.RequestHandler;
import org.eclipse.jetty.http.HttpTokens;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.SearchPattern;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A parser for MultiPart content type.
 *
 * @see <a href="https://tools.ietf.org/html/rfc2046#section-5.1">https://tools.ietf.org/html/rfc2046#section-5.1</a>
 * @see <a href="https://tools.ietf.org/html/rfc2045">https://tools.ietf.org/html/rfc2045</a>
 *
 * TODO convert to use a {@link org.eclipse.jetty.io.Content.Source} and to be async
 */
public class MultiPartParser
{
    public static final Logger LOG = LoggerFactory.getLogger(MultiPartParser.class);

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

    private static final EnumSet<State> __delimiterStates = EnumSet.of(State.DELIMITER, State.DELIMITER_CLOSE, State.DELIMITER_PADDING);
    private static final int MAX_HEADER_LINE_LENGTH = 998;

    private final boolean debugEnabled = LOG.isDebugEnabled();
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

    public Handler getHandler()
    {
        return _handler;
    }

    public State getState()
    {
        return _state;
    }

    public boolean isState(State state)
    {
        return _state == state;
    }

    private static boolean hasNextByte(ByteBuffer buffer)
    {
        return BufferUtil.hasContent(buffer);
    }

    private HttpTokens.Token next(ByteBuffer buffer)
    {
        byte ch = buffer.get();
        HttpTokens.Token t = HttpTokens.TOKENS[0xff & ch];

        switch (t.getType())
        {
            case CNTL:
                throw new IllegalCharacterException(_state, t, buffer);

            case LF:
                _cr = false;
                break;

            case CR:
                if (_cr)
                    throw new BadMessageException("Bad EOL");

                _cr = true;
                return null;

            case ALPHA:
            case DIGIT:
            case TCHAR:
            case VCHAR:
            case HTAB:
            case SPACE:
            case OTEXT:
            case COLON:
                if (_cr)
                    throw new BadMessageException("Bad EOL");
                break;

            default:
                break;
        }

        return t;
    }

    private void setString(String s)
    {
        _string.reset();
        _string.append(s);
        _length = s.length();
    }

    /*
     * Mime Field strings are treated as UTF-8 as per https://tools.ietf.org/html/rfc7578#section-5.1
     */
    private String takeString()
    {
        String s = _string.toString();
        // trim trailing whitespace.
        if (s.length() > _length)
            s = s.substring(0, _length);
        _string.reset();
        _length = -1;
        return s;
    }

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
        while (!handle && BufferUtil.hasContent(buffer))
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

                if (LOG.isDebugEnabled())
                    LOG.debug("messageComplete {}", this);

                return _handler.messageComplete();
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("earlyEOF {}", this);

                _handler.earlyEOF();
                return true;
            }
        }

        return handle;
    }

    private void parsePreamble(ByteBuffer buffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parsePreamble({})", BufferUtil.toDetailString(buffer));

        if (_partialBoundary > 0)
        {
            int partial = _delimiterSearch.startsWith(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining(), _partialBoundary);
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

        int delimiter = _delimiterSearch.match(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
        if (delimiter >= 0)
        {
            buffer.position(delimiter - buffer.arrayOffset() + _delimiterSearch.getLength());
            setState(State.DELIMITER);
            return;
        }

        _partialBoundary = _delimiterSearch.endsWith(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
        BufferUtil.clear(buffer);
    }

    private void parseDelimiter(ByteBuffer buffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parseDelimiter({})", BufferUtil.toDetailString(buffer));

        while (__delimiterStates.contains(_state) && hasNextByte(buffer))
        {
            HttpTokens.Token t = next(buffer);
            if (t == null)
                return;

            if (t.getType() == HttpTokens.Type.LF)
            {
                setState(State.BODY_PART);

                if (LOG.isDebugEnabled())
                    LOG.debug("startPart {}", this);

                _handler.startPart();
                return;
            }

            switch (_state)
            {
                case DELIMITER:
                    if (t.getChar() == '-')
                        setState(State.DELIMITER_CLOSE);
                    else
                        setState(State.DELIMITER_PADDING);
                    continue;

                case DELIMITER_CLOSE:
                    if (t.getChar() == '-')
                    {
                        setState(State.EPILOGUE);
                        return;
                    }
                    setState(State.DELIMITER_PADDING);
                    continue;

                case DELIMITER_PADDING:
                default:
            }
        }
    }

    /*
     * Parse the message headers and return true if the handler has signaled for a return
     */
    protected boolean parseMimePartHeaders(ByteBuffer buffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parseMimePartHeaders({})", BufferUtil.toDetailString(buffer));

        // Process headers
        while (_state == State.BODY_PART && hasNextByte(buffer))
        {
            // process each character
            HttpTokens.Token t = next(buffer);
            if (t == null)
                break;

            if (t.getType() != HttpTokens.Type.LF)
                _totalHeaderLineLength++;

            if (_totalHeaderLineLength > MAX_HEADER_LINE_LENGTH)
                throw new IllegalStateException("Header Line Exceeded Max Length");

            switch (_fieldState)
            {
                case FIELD:
                    switch (t.getType())
                    {
                        case SPACE:
                        case HTAB:
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

                        case LF:
                            handleField();
                            setState(State.FIRST_OCTETS);
                            _partialBoundary = 2; // CRLF is option for empty parts

                            if (LOG.isDebugEnabled())
                                LOG.debug("headerComplete {}", this);

                            if (_handler.headerComplete())
                                return true;
                            break;

                        case ALPHA:
                        case DIGIT:
                        case TCHAR:
                            // process previous header
                            handleField();

                            // New header
                            setState(FieldState.IN_NAME);
                            _string.reset();
                            _string.append(t.getChar());
                            _length = 1;

                            break;

                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;

                case IN_NAME:
                    switch (t.getType())
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

                        case LF:
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Line Feed in Name {}", this);

                            handleField();
                            setState(FieldState.FIELD);
                            break;
                        }

                        case ALPHA:
                        case DIGIT:
                        case TCHAR:
                            _string.append(t.getChar());
                            _length = _string.length();
                            break;

                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;

                case AFTER_NAME:
                    switch (t.getType())
                    {
                        case COLON:
                            _fieldName = takeString();
                            _length = -1;
                            setState(FieldState.VALUE);
                            break;

                        case LF:
                            _fieldName = takeString();
                            _string.reset();
                            _fieldValue = "";
                            _length = -1;
                            break;

                        case SPACE:
                            break;

                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;

                case VALUE:
                    switch (t.getType())
                    {
                        case LF:
                            _string.reset();
                            _fieldValue = "";
                            _length = -1;

                            setState(FieldState.FIELD);
                            break;

                        case SPACE:
                        case HTAB:
                            break;

                        case ALPHA:
                        case DIGIT:
                        case TCHAR:
                        case VCHAR:
                        case COLON:
                        case OTEXT:
                            _string.append(t.getByte());
                            _length = _string.length();
                            setState(FieldState.IN_VALUE);
                            break;

                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;

                case IN_VALUE:
                    switch (t.getType())
                    {
                        case SPACE:
                        case HTAB:
                            _string.append(' ');
                            break;

                        case LF:
                            if (_length > 0)
                            {
                                _fieldValue = takeString();
                                _length = -1;
                                _totalHeaderLineLength = -1;
                            }
                            setState(FieldState.FIELD);
                            break;

                        case ALPHA:
                        case DIGIT:
                        case TCHAR:
                        case VCHAR:
                        case COLON:
                        case OTEXT:
                            _string.append(t.getByte());
                            _length = _string.length();
                            break;

                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;

                default:
                    throw new IllegalStateException(_state.toString());
            }
        }
        return false;
    }

    private void handleField()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parsedField:  _fieldName={} _fieldValue={} {}", _fieldName, _fieldValue, this);

        if (_fieldName != null && _fieldValue != null)
            _handler.parsedField(_fieldName, _fieldValue);
        _fieldName = _fieldValue = null;
    }

    protected boolean parseOctetContent(ByteBuffer buffer)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parseOctetContent({})", BufferUtil.toDetailString(buffer));

        // Starts With
        if (_partialBoundary > 0)
        {
            int partial = _delimiterSearch.startsWith(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining(), _partialBoundary);
            if (partial > 0)
            {
                if (partial == _delimiterSearch.getLength())
                {
                    buffer.position(buffer.position() + _delimiterSearch.getLength() - _partialBoundary);
                    setState(State.DELIMITER);
                    _partialBoundary = 0;

                    if (LOG.isDebugEnabled())
                        LOG.debug("Content={}, Last={} {}", BufferUtil.toDetailString(BufferUtil.EMPTY_BUFFER), true, this);

                    return _handler.content(BufferUtil.EMPTY_BUFFER, true);
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

                if (LOG.isDebugEnabled())
                    LOG.debug("Content={}, Last={} {}", BufferUtil.toDetailString(content), false, this);

                if (_handler.content(content, false))
                    return true;
            }
        }

        // Contains
        int delimiter = _delimiterSearch.match(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
        if (delimiter >= 0)
        {
            ByteBuffer content = buffer.slice();
            content.limit(delimiter - buffer.arrayOffset() - buffer.position());

            buffer.position(delimiter - buffer.arrayOffset() + _delimiterSearch.getLength());
            setState(State.DELIMITER);

            if (LOG.isDebugEnabled())
                LOG.debug("Content={}, Last={} {}", BufferUtil.toDetailString(content), true, this);

            return _handler.content(content, true);
        }

        // Ends With
        _partialBoundary = _delimiterSearch.endsWith(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
        if (_partialBoundary > 0)
        {
            ByteBuffer content = buffer.slice();
            content.limit(content.limit() - _partialBoundary);

            if (LOG.isDebugEnabled())
                LOG.debug("Content={}, Last={} {}", BufferUtil.toDetailString(content), false, this);

            BufferUtil.clear(buffer);
            return _handler.content(content, false);
        }

        // There is normal content with no delimiter
        ByteBuffer content = buffer.slice();

        if (LOG.isDebugEnabled())
            LOG.debug("Content={}, Last={} {}", BufferUtil.toDetailString(content), false, this);

        BufferUtil.clear(buffer);
        return _handler.content(content, false);
    }

    private void setState(State state)
    {
        if (debugEnabled)
            LOG.debug("{} --> {}", _state, state);
        _state = state;
    }

    private void setState(FieldState state)
    {
        if (debugEnabled)
            LOG.debug("{}:{} --> {}", _state, _fieldState, state);
        _fieldState = state;
    }

    @Override
    public String toString()
    {
        return String.format("%s{s=%s}", getClass().getSimpleName(), _state);
    }

    /*
     * Event Handler interface These methods return true if the caller should process the events so far received (eg return from parseNext and call
     * HttpChannel.handle). If multiple callbacks are called in sequence (eg headerComplete then messageComplete) from the same point in the parsing then it is
     * sufficient for the caller to process the events only once.
     */
    public interface Handler
    {
        default void startPart()
        {
        }

        @SuppressWarnings("unused")
        default void parsedField(String name, String value)
        {
        }

        default boolean headerComplete()
        {
            return false;
        }

        @SuppressWarnings("unused")
        default boolean content(ByteBuffer item, boolean last)
        {
            return false;
        }

        default boolean messageComplete()
        {
            return false;
        }

        default void earlyEOF()
        {
        }
    }

    @SuppressWarnings("serial")
    private static class IllegalCharacterException extends BadMessageException
    {
        private IllegalCharacterException(State state, HttpTokens.Token token, ByteBuffer buffer)
        {
            super(400, String.format("Illegal character %s", token));
            if (LOG.isDebugEnabled())
                LOG.debug(String.format("Illegal character %s in state=%s for buffer %s", token, state, BufferUtil.toDetailString(buffer)));
        }
    }
}
