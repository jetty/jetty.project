//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.http.HttpTokens.EndOfContent;
import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import static org.eclipse.jetty.http.HttpComplianceSection.MULTIPLE_CONTENT_LENGTHS;
import static org.eclipse.jetty.http.HttpComplianceSection.TRANSFER_ENCODING_WITH_CONTENT_LENGTH;

/**
 * A Parser for 1.0 and 1.1 as defined by RFC7230
 * <p>
 * This parser parses HTTP client and server messages from buffers
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
 * header and value) of a header.  Specifically the static {@link HttpHeader#CACHE}
 * is used to lookup common combinations of headers and values
 * (eg. "Connection: close"), or just header names (eg. "Connection:" ).
 * For headers who's value is not known statically (eg. Host, COOKIE) then a
 * per parser dynamic Trie of {@link HttpFields} from previous parsed messages
 * is used to help the parsing of subsequent messages.
 * </p>
 * <p>
 * The parser can work in varying compliance modes:
 * <dl>
 * <dt>RFC7230</dt><dd>(default) Compliance with RFC7230</dd>
 * <dt>RFC2616</dt><dd>Wrapped headers and HTTP/0.9 supported</dd>
 * <dt>LEGACY</dt><dd>(aka STRICT) Adherence to Servlet Specification requirement for
 * exact case of header names, bypassing the header caches, which are case insensitive,
 * otherwise equivalent to RFC2616</dd>
 * </dl>
 *
 * @see <a href="http://tools.ietf.org/html/rfc7230">RFC 7230</a>
 */
public class HttpParser
{
    public static final Logger LOG = Log.getLogger(HttpParser.class);
    @Deprecated
    public static final String __STRICT = "org.eclipse.jetty.http.HttpParser.STRICT";
    public static final int INITIAL_URI_LENGTH = 256;
    private static final int MAX_CHUNK_LENGTH = Integer.MAX_VALUE / 16 - 16;

    /**
     * Cache of common {@link HttpField}s including: <UL>
     * <LI>Common static combinations such as:<UL>
     * <li>Connection: close
     * <li>Accept-Encoding: gzip
     * <li>Content-Length: 0
     * </ul>
     * <li>Combinations of Content-Type header for common mime types by common charsets
     * <li>Most common headers with null values so that a lookup will at least
     * determine the header name even if the name:value combination is not cached
     * </ul>
     */
    public static final Trie<HttpField> CACHE = new ArrayTrie<>(2048);
    private static final Trie<HttpField> NO_CACHE = Trie.empty(true);

    // States
    public enum FieldState
    {
        FIELD,
        IN_NAME,
        VALUE,
        IN_VALUE,
        WS_AFTER_NAME,
    }

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
        PROXY,
        HEADER,
        CONTENT,
        EOF_CONTENT,
        CHUNKED_CONTENT,
        CHUNK_SIZE,
        CHUNK_PARAMS,
        CHUNK,
        CONTENT_END,
        TRAILER,
        END,
        CLOSE,  // The associated stream/endpoint should be closed
        CLOSED  // The associated stream/endpoint is at EOF
    }

    private static final EnumSet<State> __idleStates = EnumSet.of(State.START, State.END, State.CLOSE, State.CLOSED);
    private static final EnumSet<State> __completeStates = EnumSet.of(State.END, State.CLOSE, State.CLOSED);
    private static final EnumSet<State> __terminatedStates = EnumSet.of(State.CLOSE, State.CLOSED);

    private final boolean debug = LOG.isDebugEnabled(); // Cache debug to help branch prediction
    private final HttpHandler _handler;
    private final RequestHandler _requestHandler;
    private final ResponseHandler _responseHandler;
    private final ComplianceHandler _complianceHandler;
    private final int _maxHeaderBytes;
    private final HttpCompliance _compliance;
    private final EnumSet<HttpComplianceSection> _compliances;
    private final Utf8StringBuilder _uri = new Utf8StringBuilder(INITIAL_URI_LENGTH);
    private HttpField _field;
    private HttpHeader _header;
    private String _headerString;
    private String _valueString;
    private int _responseStatus;
    private int _headerBytes;
    private boolean _host;
    private boolean _headerComplete;

    private volatile State _state = State.START;
    private volatile FieldState _fieldState = FieldState.FIELD;
    private volatile boolean _eof;
    private HttpMethod _method;
    private String _methodString;
    private HttpVersion _version;
    private EndOfContent _endOfContent;
    private boolean _hasContentLength;
    private boolean _hasTransferEncoding;
    private long _contentLength = -1;
    private long _contentPosition;
    private int _chunkLength;
    private int _chunkPosition;
    private boolean _headResponse;
    private boolean _cr;
    private ByteBuffer _contentChunk;
    private Trie<HttpField> _fieldCache;

    private int _length;
    private final StringBuilder _string = new StringBuilder();

    static
    {
        CACHE.put(new HttpField(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE));
        CACHE.put(new HttpField(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE));
        CACHE.put(new HttpField(HttpHeader.CONNECTION, HttpHeaderValue.UPGRADE));
        CACHE.put(new HttpField(HttpHeader.ACCEPT_ENCODING, "gzip"));
        CACHE.put(new HttpField(HttpHeader.ACCEPT_ENCODING, "gzip, deflate"));
        CACHE.put(new HttpField(HttpHeader.ACCEPT_ENCODING, "gzip, deflate, br"));
        CACHE.put(new HttpField(HttpHeader.ACCEPT_ENCODING, "gzip,deflate,sdch"));
        CACHE.put(new HttpField(HttpHeader.ACCEPT_LANGUAGE, "en-US,en;q=0.5"));
        CACHE.put(new HttpField(HttpHeader.ACCEPT_LANGUAGE, "en-GB,en-US;q=0.8,en;q=0.6"));
        CACHE.put(new HttpField(HttpHeader.ACCEPT_LANGUAGE, "en-AU,en;q=0.9,it-IT;q=0.8,it;q=0.7,en-GB;q=0.6,en-US;q=0.5"));
        CACHE.put(new HttpField(HttpHeader.ACCEPT_CHARSET, "ISO-8859-1,utf-8;q=0.7,*;q=0.3"));
        CACHE.put(new HttpField(HttpHeader.ACCEPT, "*/*"));
        CACHE.put(new HttpField(HttpHeader.ACCEPT, "image/png,image/*;q=0.8,*/*;q=0.5"));
        CACHE.put(new HttpField(HttpHeader.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"));
        CACHE.put(new HttpField(HttpHeader.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8"));
        CACHE.put(new HttpField(HttpHeader.ACCEPT_RANGES, HttpHeaderValue.BYTES));
        CACHE.put(new HttpField(HttpHeader.PRAGMA, "no-cache"));
        CACHE.put(new HttpField(HttpHeader.CACHE_CONTROL, "private, no-cache, no-cache=Set-Cookie, proxy-revalidate"));
        CACHE.put(new HttpField(HttpHeader.CACHE_CONTROL, "no-cache"));
        CACHE.put(new HttpField(HttpHeader.CACHE_CONTROL, "max-age=0"));
        CACHE.put(new HttpField(HttpHeader.CONTENT_LENGTH, "0"));
        CACHE.put(new HttpField(HttpHeader.CONTENT_ENCODING, "gzip"));
        CACHE.put(new HttpField(HttpHeader.CONTENT_ENCODING, "deflate"));
        CACHE.put(new HttpField(HttpHeader.TRANSFER_ENCODING, "chunked"));
        CACHE.put(new HttpField(HttpHeader.EXPIRES, "Fri, 01 Jan 1990 00:00:00 GMT"));

        // Add common Content types as fields
        for (String type : new String[]{
            "text/plain", "text/html", "text/xml", "text/json", "application/json", "application/x-www-form-urlencoded"
        })
        {
            HttpField field = new PreEncodedHttpField(HttpHeader.CONTENT_TYPE, type);
            CACHE.put(field);

            for (String charset : new String[]{"utf-8", "iso-8859-1"})
            {
                CACHE.put(new PreEncodedHttpField(HttpHeader.CONTENT_TYPE, type + ";charset=" + charset));
                CACHE.put(new PreEncodedHttpField(HttpHeader.CONTENT_TYPE, type + "; charset=" + charset));
                CACHE.put(new PreEncodedHttpField(HttpHeader.CONTENT_TYPE, type + ";charset=" + charset.toUpperCase(Locale.ENGLISH)));
                CACHE.put(new PreEncodedHttpField(HttpHeader.CONTENT_TYPE, type + "; charset=" + charset.toUpperCase(Locale.ENGLISH)));
            }
        }

        // Add headers with null values so HttpParser can avoid looking up name again for unknown values
        for (HttpHeader h : HttpHeader.values())
        {
            if (!h.isPseudo() && !CACHE.put(new HttpField(h, (String)null)))
                throw new IllegalStateException("CACHE FULL");
        }
    }

    private static HttpCompliance compliance()
    {
        boolean strict = Boolean.getBoolean(__STRICT);
        if (strict)
        {
            LOG.warn("Deprecated property used: " + __STRICT);
            return HttpCompliance.LEGACY;
        }
        return HttpCompliance.RFC7230;
    }

    public HttpParser(RequestHandler handler)
    {
        this(handler, -1, compliance());
    }

    public HttpParser(ResponseHandler handler)
    {
        this(handler, -1, compliance());
    }

    public HttpParser(RequestHandler handler, int maxHeaderBytes)
    {
        this(handler, maxHeaderBytes, compliance());
    }

    public HttpParser(ResponseHandler handler, int maxHeaderBytes)
    {
        this(handler, maxHeaderBytes, compliance());
    }

    @Deprecated
    public HttpParser(RequestHandler handler, int maxHeaderBytes, boolean strict)
    {
        this(handler, maxHeaderBytes, strict ? HttpCompliance.LEGACY : compliance());
    }

    @Deprecated
    public HttpParser(ResponseHandler handler, int maxHeaderBytes, boolean strict)
    {
        this(handler, maxHeaderBytes, strict ? HttpCompliance.LEGACY : compliance());
    }

    public HttpParser(RequestHandler handler, HttpCompliance compliance)
    {
        this(handler, -1, compliance);
    }

    public HttpParser(RequestHandler handler, int maxHeaderBytes, HttpCompliance compliance)
    {
        this(handler, null, maxHeaderBytes, compliance == null ? compliance() : compliance);
    }

    public HttpParser(ResponseHandler handler, int maxHeaderBytes, HttpCompliance compliance)
    {
        this(null, handler, maxHeaderBytes, compliance == null ? compliance() : compliance);
    }

    private HttpParser(RequestHandler requestHandler, ResponseHandler responseHandler, int maxHeaderBytes, HttpCompliance compliance)
    {
        _handler = requestHandler != null ? requestHandler : responseHandler;
        _requestHandler = requestHandler;
        _responseHandler = responseHandler;
        _maxHeaderBytes = maxHeaderBytes;
        _compliance = compliance;
        _compliances = compliance.sections();
        _complianceHandler = (ComplianceHandler)(_handler instanceof ComplianceHandler ? _handler : null);
    }

    public HttpHandler getHandler()
    {
        return _handler;
    }

    public HttpCompliance getHttpCompliance()
    {
        return _compliance;
    }

    /**
     * Check RFC compliance violation
     *
     * @param violation The compliance section violation
     * @return True if the current compliance level is set so as to Not allow this violation
     */
    protected boolean complianceViolation(HttpComplianceSection violation)
    {
        return complianceViolation(violation, null);
    }

    /**
     * Check RFC compliance violation
     *
     * @param violation The compliance section violation
     * @param reason The reason for the violation
     * @return True if the current compliance level is set so as to Not allow this violation
     */
    protected boolean complianceViolation(HttpComplianceSection violation, String reason)
    {
        if (_compliances.contains(violation))
            return true;
        if (reason == null)
            reason = violation.description;
        if (_complianceHandler != null)
            _complianceHandler.onComplianceViolation(_compliance, violation, reason);

        return false;
    }

    protected void handleViolation(HttpComplianceSection section, String reason)
    {
        if (_complianceHandler != null)
            _complianceHandler.onComplianceViolation(_compliance, section, reason);
    }

    protected String caseInsensitiveHeader(String orig, String normative)
    {
        if (_compliances.contains(HttpComplianceSection.FIELD_NAME_CASE_INSENSITIVE))
            return normative;
        if (!orig.equals(normative))
            handleViolation(HttpComplianceSection.FIELD_NAME_CASE_INSENSITIVE, orig);
        return orig;
    }

    public long getContentLength()
    {
        return _contentLength;
    }

    public long getContentRead()
    {
        return _contentPosition;
    }

    public int getHeaderLength()
    {
        return _headerBytes;
    }

    /**
     * Set if a HEAD response is expected
     *
     * @param head true if head response is expected
     */
    public void setHeadResponse(boolean head)
    {
        _headResponse = head;
    }

    protected void setResponseStatus(int status)
    {
        _responseStatus = status;
    }

    public State getState()
    {
        return _state;
    }

    public boolean inContentState()
    {
        return _state.ordinal() >= State.CONTENT.ordinal() && _state.ordinal() < State.END.ordinal();
    }

    public boolean inHeaderState()
    {
        return _state.ordinal() < State.CONTENT.ordinal();
    }

    public boolean isChunking()
    {
        return _endOfContent == EndOfContent.CHUNKED_CONTENT;
    }

    public boolean isStart()
    {
        return isState(State.START);
    }

    public boolean isClose()
    {
        return isState(State.CLOSE);
    }

    public boolean isClosed()
    {
        return isState(State.CLOSED);
    }

    public boolean isIdle()
    {
        return __idleStates.contains(_state);
    }

    public boolean isComplete()
    {
        return __completeStates.contains(_state);
    }

    public boolean isTerminated()
    {
        return __terminatedStates.contains(_state);
    }

    public boolean isState(State state)
    {
        return _state == state;
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
                if (buffer.hasRemaining())
                {
                    // Don't count the CRs and LFs of the chunked encoding.
                    if (_maxHeaderBytes > 0 && (_state == State.HEADER || _state == State.TRAILER))
                        _headerBytes++;
                    return next(buffer);
                }

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

    /* Quick lookahead for the start state looking for a request method or an HTTP version,
     * otherwise skip white space until something else to parse.
     */
    private void quickStart(ByteBuffer buffer)
    {
        if (_requestHandler != null)
        {
            _method = HttpMethod.lookAheadGet(buffer);
            if (_method != null)
            {
                _methodString = _method.asString();
                buffer.position(buffer.position() + _methodString.length() + 1);

                setState(State.SPACE1);
                return;
            }
        }
        else if (_responseHandler != null)
        {
            _version = HttpVersion.lookAheadGet(buffer);
            if (_version != null)
            {
                buffer.position(buffer.position() + _version.asString().length() + 1);
                setState(State.SPACE1);
                return;
            }
        }

        // Quick start look
        while (_state == State.START && buffer.hasRemaining())
        {
            HttpTokens.Token t = next(buffer);
            if (t == null)
                break;

            switch (t.getType())
            {
                case ALPHA:
                case DIGIT:
                case TCHAR:
                case VCHAR:
                {
                    _string.setLength(0);
                    _string.append(t.getChar());
                    setState(_requestHandler != null ? State.METHOD : State.RESPONSE_VERSION);
                    return;
                }
                case OTEXT:
                case SPACE:
                case HTAB:
                    throw new IllegalCharacterException(_state, t, buffer);

                default:
                    break;
            }

            // count this white space as a header byte to avoid DOS
            if (_maxHeaderBytes > 0 && ++_headerBytes > _maxHeaderBytes)
            {
                LOG.warn("padding is too large >" + _maxHeaderBytes);
                throw new BadMessageException(HttpStatus.BAD_REQUEST_400);
            }
        }
    }

    private void setString(String s)
    {
        _string.setLength(0);
        _string.append(s);
        _length = s.length();
    }

    private String takeString()
    {
        _string.setLength(_length);
        String s = _string.toString();
        _string.setLength(0);
        _length = -1;
        return s;
    }

    private boolean handleHeaderContentMessage()
    {
        boolean handleHeader = _handler.headerComplete();
        _headerComplete = true;
        if (handleHeader)
            return true;
        setState(State.CONTENT_END);
        return handleContentMessage();
    }

    private boolean handleContentMessage()
    {
        boolean handleContent = _handler.contentComplete();
        if (handleContent)
            return true;
        setState(State.END);
        return _handler.messageComplete();
    }

    /* Parse a request or response line
     */
    private boolean parseLine(ByteBuffer buffer)
    {
        boolean handle = false;

        // Process headers
        while (_state.ordinal() < State.HEADER.ordinal() && buffer.hasRemaining() && !handle)
        {
            // process each character
            HttpTokens.Token t = next(buffer);
            if (t == null)
                break;

            if (_maxHeaderBytes > 0 && ++_headerBytes > _maxHeaderBytes)
            {
                if (_state == State.URI)
                {
                    LOG.warn("URI is too large >" + _maxHeaderBytes);
                    throw new BadMessageException(HttpStatus.URI_TOO_LONG_414);
                }
                else
                {
                    if (_requestHandler != null)
                        LOG.warn("request is too large >" + _maxHeaderBytes);
                    else
                        LOG.warn("response is too large >" + _maxHeaderBytes);
                    throw new BadMessageException(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE_431);
                }
            }

            switch (_state)
            {
                case METHOD:
                    switch (t.getType())
                    {
                        case SPACE:
                            _length = _string.length();
                            _methodString = takeString();

                            if (_compliances.contains(HttpComplianceSection.METHOD_CASE_SENSITIVE))
                            {
                                HttpMethod method = HttpMethod.CACHE.get(_methodString);
                                if (method != null)
                                    _methodString = method.asString();
                            }
                            else
                            {
                                HttpMethod method = HttpMethod.INSENSITIVE_CACHE.get(_methodString);

                                if (method != null)
                                {
                                    if (!method.asString().equals(_methodString))
                                        handleViolation(HttpComplianceSection.METHOD_CASE_SENSITIVE, _methodString);
                                    _methodString = method.asString();
                                }
                            }

                            setState(State.SPACE1);
                            break;

                        case LF:
                            throw new BadMessageException("No URI");

                        case ALPHA:
                        case DIGIT:
                        case TCHAR:
                            _string.append(t.getChar());
                            break;

                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;

                case RESPONSE_VERSION:
                    switch (t.getType())
                    {
                        case SPACE:
                            _length = _string.length();
                            String version = takeString();
                            _version = HttpVersion.CACHE.get(version);
                            checkVersion();
                            setState(State.SPACE1);
                            break;

                        case ALPHA:
                        case DIGIT:
                        case TCHAR:
                        case VCHAR:
                        case COLON:
                            _string.append(t.getChar());
                            break;
                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;

                case SPACE1:
                    switch (t.getType())
                    {
                        case SPACE:
                            break;

                        case ALPHA:
                        case DIGIT:
                        case TCHAR:
                        case VCHAR:
                        case COLON:
                            if (_responseHandler != null)
                            {
                                if (t.getType() != HttpTokens.Type.DIGIT)
                                    throw new IllegalCharacterException(_state, t, buffer);
                                setState(State.STATUS);
                                setResponseStatus(t.getByte() - '0');
                            }
                            else
                            {
                                _uri.reset();
                                setState(State.URI);
                                // quick scan for space or EoBuffer
                                if (buffer.hasArray())
                                {
                                    byte[] array = buffer.array();
                                    int p = buffer.arrayOffset() + buffer.position();
                                    int l = buffer.arrayOffset() + buffer.limit();
                                    int i = p;
                                    while (i < l && array[i] > HttpTokens.SPACE)
                                    {
                                        i++;
                                    }

                                    int len = i - p;
                                    _headerBytes += len;

                                    if (_maxHeaderBytes > 0 && ++_headerBytes > _maxHeaderBytes)
                                    {
                                        LOG.warn("URI is too large >" + _maxHeaderBytes);
                                        throw new BadMessageException(HttpStatus.URI_TOO_LONG_414);
                                    }
                                    _uri.append(array, p - 1, len + 1);
                                    buffer.position(i - buffer.arrayOffset());
                                }
                                else
                                    _uri.append(t.getByte());
                            }
                            break;

                        default:
                            throw new BadMessageException(HttpStatus.BAD_REQUEST_400, _requestHandler != null ? "No URI" : "No Status");
                    }
                    break;

                case STATUS:
                    switch (t.getType())
                    {
                        case SPACE:
                            setState(State.SPACE2);
                            break;

                        case DIGIT:
                            _responseStatus = _responseStatus * 10 + (t.getByte() - '0');
                            if (_responseStatus >= 1000)
                                throw new BadMessageException("Bad status");
                            break;

                        case LF:
                            setState(State.HEADER);
                            _responseHandler.startResponse(_version, _responseStatus, null);
                            break;

                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;

                case URI:
                    switch (t.getType())
                    {
                        case SPACE:
                            setState(State.SPACE2);
                            break;

                        case LF:
                            // HTTP/0.9
                            if (complianceViolation(HttpComplianceSection.NO_HTTP_0_9, "No request version"))
                                throw new BadMessageException(HttpStatus.HTTP_VERSION_NOT_SUPPORTED_505, "HTTP/0.9 not supported");
                            _requestHandler.startRequest(_methodString, _uri.toString(), HttpVersion.HTTP_0_9);
                            setState(State.CONTENT);
                            _endOfContent = EndOfContent.NO_CONTENT;
                            BufferUtil.clear(buffer);
                            handle = handleHeaderContentMessage();
                            break;

                        case ALPHA:
                        case DIGIT:
                        case TCHAR:
                        case VCHAR:
                        case COLON:
                        case OTEXT:
                            _uri.append(t.getByte());
                            break;

                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;

                case SPACE2:
                    switch (t.getType())
                    {
                        case SPACE:
                            break;

                        case ALPHA:
                        case DIGIT:
                        case TCHAR:
                        case VCHAR:
                        case COLON:
                            _string.setLength(0);
                            _string.append(t.getChar());
                            if (_responseHandler != null)
                            {
                                _length = 1;
                                setState(State.REASON);
                            }
                            else
                            {
                                setState(State.REQUEST_VERSION);

                                // try quick look ahead for HTTP Version
                                HttpVersion version;
                                if (buffer.position() > 0 && buffer.hasArray())
                                    version = HttpVersion.lookAheadGet(buffer.array(), buffer.arrayOffset() + buffer.position() - 1, buffer.arrayOffset() + buffer.limit());
                                else
                                    version = HttpVersion.CACHE.getBest(buffer, 0, buffer.remaining());

                                if (version != null)
                                {
                                    int pos = buffer.position() + version.asString().length() - 1;
                                    if (pos < buffer.limit())
                                    {
                                        byte n = buffer.get(pos);
                                        if (n == HttpTokens.CARRIAGE_RETURN)
                                        {
                                            _cr = true;
                                            _version = version;
                                            checkVersion();
                                            _string.setLength(0);
                                            buffer.position(pos + 1);
                                        }
                                        else if (n == HttpTokens.LINE_FEED)
                                        {
                                            _version = version;
                                            checkVersion();
                                            _string.setLength(0);
                                            buffer.position(pos);
                                        }
                                    }
                                }
                            }
                            break;

                        case LF:
                            if (_responseHandler != null)
                            {
                                setState(State.HEADER);
                                _responseHandler.startResponse(_version, _responseStatus, null);
                            }
                            else
                            {
                                // HTTP/0.9
                                if (complianceViolation(HttpComplianceSection.NO_HTTP_0_9, "No request version"))
                                    throw new BadMessageException("HTTP/0.9 not supported");

                                _requestHandler.startRequest(_methodString, _uri.toString(), HttpVersion.HTTP_0_9);
                                setState(State.CONTENT);
                                _endOfContent = EndOfContent.NO_CONTENT;
                                BufferUtil.clear(buffer);
                                handle = handleHeaderContentMessage();
                            }
                            break;

                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;

                case REQUEST_VERSION:
                    switch (t.getType())
                    {
                        case LF:
                            if (_version == null)
                            {
                                _length = _string.length();
                                _version = HttpVersion.CACHE.get(takeString());
                            }
                            checkVersion();

                            setState(State.HEADER);

                            _requestHandler.startRequest(_methodString, _uri.toString(), _version);
                            continue;

                        case ALPHA:
                        case DIGIT:
                        case TCHAR:
                        case VCHAR:
                        case COLON:
                            _string.append(t.getChar());
                            break;

                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;

                case REASON:
                    switch (t.getType())
                    {
                        case LF:
                            String reason = takeString();
                            setState(State.HEADER);
                            _responseHandler.startResponse(_version, _responseStatus, reason);
                            continue;

                        case ALPHA:
                        case DIGIT:
                        case TCHAR:
                        case VCHAR:
                        case COLON:
                        case OTEXT: // TODO should this be UTF8
                            _string.append(t.getChar());
                            _length = _string.length();
                            break;

                        case SPACE:
                        case HTAB:
                            _string.append(t.getChar());
                            break;

                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;

                default:
                    throw new IllegalStateException(_state.toString());
            }
        }

        return handle;
    }

    private void checkVersion()
    {
        if (_version == null)
            throw new BadMessageException(HttpStatus.HTTP_VERSION_NOT_SUPPORTED_505, "Unknown Version");

        if (_version.getVersion() < 10 || _version.getVersion() > 20)
            throw new BadMessageException(HttpStatus.HTTP_VERSION_NOT_SUPPORTED_505, "Unsupported Version");
    }

    private void parsedHeader()
    {
        // handler last header if any.  Delayed to here just in case there was a continuation line (above)
        if (_headerString != null || _valueString != null)
        {
            // Handle known headers
            if (_header != null)
            {
                boolean addToFieldCache = false;
                switch (_header)
                {
                    case CONTENT_LENGTH:
                        if (_hasTransferEncoding && complianceViolation(TRANSFER_ENCODING_WITH_CONTENT_LENGTH))
                            throw new BadMessageException(HttpStatus.BAD_REQUEST_400, "Transfer-Encoding and Content-Length");
                        long contentLength = convertContentLength(_valueString);
                        if (_hasContentLength)
                        {
                            if (complianceViolation(MULTIPLE_CONTENT_LENGTHS))
                                throw new BadMessageException(HttpStatus.BAD_REQUEST_400, MULTIPLE_CONTENT_LENGTHS.description);
                            if (contentLength != _contentLength)
                                throw new BadMessageException(HttpStatus.BAD_REQUEST_400, MULTIPLE_CONTENT_LENGTHS.getDescription());
                        }
                        _hasContentLength = true;

                        if (_endOfContent != EndOfContent.CHUNKED_CONTENT)
                        {
                            _contentLength = contentLength;
                            _endOfContent = EndOfContent.CONTENT_LENGTH;
                        }
                        break;

                    case TRANSFER_ENCODING:
                        _hasTransferEncoding = true;

                        if (_hasContentLength && complianceViolation(TRANSFER_ENCODING_WITH_CONTENT_LENGTH))
                            throw new BadMessageException(HttpStatus.BAD_REQUEST_400, "Transfer-Encoding and Content-Length");

                        // we encountered another Transfer-Encoding header, but chunked was already set
                        if (_endOfContent == EndOfContent.CHUNKED_CONTENT)
                            throw new BadMessageException(HttpStatus.BAD_REQUEST_400, "Bad Transfer-Encoding, chunked not last");

                        if (HttpHeaderValue.CHUNKED.is(_valueString))
                        {
                            _endOfContent = EndOfContent.CHUNKED_CONTENT;
                            _contentLength = -1;
                        }
                        else
                        {
                            List<String> values = new QuotedCSV(_valueString).getValues();
                            int chunked = -1;
                            int len = values.size();
                            for (int i = 0; i < len; i++)
                            {
                                if (HttpHeaderValue.CHUNKED.is(values.get(i)))
                                {
                                    if (chunked != -1)
                                        throw new BadMessageException(HttpStatus.BAD_REQUEST_400, "Bad Transfer-Encoding, multiple chunked tokens");
                                    chunked = i;
                                    // declared chunked
                                    _endOfContent = EndOfContent.CHUNKED_CONTENT;
                                    _contentLength = -1;
                                }
                                // we have a non-chunked token after a declared chunked token
                                else if (_endOfContent == EndOfContent.CHUNKED_CONTENT)
                                {
                                    throw new BadMessageException(HttpStatus.BAD_REQUEST_400, "Bad Transfer-Encoding, chunked not last");
                                }
                            }
                        }
                        break;

                    case HOST:
                        _host = true;
                        if (!(_field instanceof HostPortHttpField) && _valueString != null && !_valueString.isEmpty())
                        {
                            _field = new HostPortHttpField(_header,
                                _compliances.contains(HttpComplianceSection.FIELD_NAME_CASE_INSENSITIVE) ? _header.asString() : _headerString,
                                _valueString);
                            addToFieldCache = true;
                        }
                        break;

                    case CONNECTION:
                        // Don't cache headers if not persistent
                        if (_field == null)
                            _field = new HttpField(_header, caseInsensitiveHeader(_headerString, _header.asString()), _valueString);
                        if (_handler.getHeaderCacheSize() > 0 && _field.contains(HttpHeaderValue.CLOSE.asString()))
                            _fieldCache = NO_CACHE;
                        break;

                    case AUTHORIZATION:
                    case ACCEPT:
                    case ACCEPT_CHARSET:
                    case ACCEPT_ENCODING:
                    case ACCEPT_LANGUAGE:
                    case COOKIE:
                    case CACHE_CONTROL:
                    case USER_AGENT:
                        addToFieldCache = _field == null;
                        break;

                    default:
                        break;
                }

                // Cache field?
                if (addToFieldCache && _header != null && _valueString != null)
                {
                    if (_fieldCache == null)
                    {
                        _fieldCache = (_handler.getHeaderCacheSize() > 0 && (_version != null && _version == HttpVersion.HTTP_1_1))
                            ? new ArrayTernaryTrie<>(_handler.getHeaderCacheSize())
                            : NO_CACHE;
                    }

                    if (!_fieldCache.isFull())
                    {
                        if (_field == null)
                            _field = new HttpField(_header, caseInsensitiveHeader(_headerString, _header.asString()), _valueString);
                        _fieldCache.put(_field);
                    }
                }
            }
            _handler.parsedHeader(_field != null ? _field : new HttpField(_header, _headerString, _valueString));
        }

        _headerString = _valueString = null;
        _header = null;
        _field = null;
    }

    private void parsedTrailer()
    {
        // handler last header if any.  Delayed to here just in case there was a continuation line (above)
        if (_headerString != null || _valueString != null)
            _handler.parsedTrailer(_field != null ? _field : new HttpField(_header, _headerString, _valueString));

        _headerString = _valueString = null;
        _header = null;
        _field = null;
    }

    private long convertContentLength(String valueString)
    {
        if (valueString == null || valueString.length() == 0)
            throw new BadMessageException("Invalid Content-Length Value", new NumberFormatException());

        long value = 0;
        int length = valueString.length();

        for (int i = 0; i < length; i++)
        {
            char c = valueString.charAt(i);
            if (c < '0' || c > '9')
                throw new BadMessageException("Invalid Content-Length Value", new NumberFormatException());

            value = Math.addExact(Math.multiplyExact(value, 10L), c - '0');
        }
        return value;
    }

    /*
     * Parse the message headers and return true if the handler has signalled for a return
     */
    protected boolean parseFields(ByteBuffer buffer)
    {
        // Process headers
        while ((_state == State.HEADER || _state == State.TRAILER) && buffer.hasRemaining())
        {
            // process each character
            HttpTokens.Token t = next(buffer);
            if (t == null)
                break;

            if (_maxHeaderBytes > 0 && ++_headerBytes > _maxHeaderBytes)
            {
                boolean header = _state == State.HEADER;
                LOG.warn("{} is too large {}>{}", header ? "Header" : "Trailer", _headerBytes, _maxHeaderBytes);
                throw new BadMessageException(header
                    ? HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE_431
                    : HttpStatus.PAYLOAD_TOO_LARGE_413);
            }

            switch (_fieldState)
            {
                case FIELD:
                    switch (t.getType())
                    {
                        case COLON:
                        case SPACE:
                        case HTAB:
                        {
                            if (complianceViolation(HttpComplianceSection.NO_FIELD_FOLDING, _headerString))
                                throw new BadMessageException(HttpStatus.BAD_REQUEST_400, "Header Folding");

                            // header value without name - continuation?
                            if (StringUtil.isEmpty(_valueString))
                            {
                                _string.setLength(0);
                                _length = 0;
                            }
                            else
                            {
                                setString(_valueString);
                                _string.append(' ');
                                _length++;
                                _valueString = null;
                            }
                            setState(FieldState.VALUE);
                            break;
                        }

                        case LF:
                        {
                            // process previous header
                            if (_state == State.HEADER)
                                parsedHeader();
                            else
                                parsedTrailer();

                            _contentPosition = 0;

                            // End of headers or trailers?
                            if (_state == State.TRAILER)
                            {
                                setState(State.END);
                                return _handler.messageComplete();
                            }

                            // We found Transfer-Encoding headers, but none declared the 'chunked' token
                            if (_hasTransferEncoding && _endOfContent != EndOfContent.CHUNKED_CONTENT)
                            {
                                if (_responseHandler == null || _endOfContent != EndOfContent.EOF_CONTENT)
                                {
                                    // Transfer-Encoding chunked not specified
                                    // https://tools.ietf.org/html/rfc7230#section-3.3.1
                                    throw new BadMessageException(HttpStatus.BAD_REQUEST_400, "Bad Transfer-Encoding, chunked not last");
                                }
                            }

                            // Was there a required host header?
                            if (!_host && _version == HttpVersion.HTTP_1_1 && _requestHandler != null)
                            {
                                throw new BadMessageException(HttpStatus.BAD_REQUEST_400, "No Host");
                            }

                            // is it a response that cannot have a body?
                            if (_responseHandler != null && // response
                                (_responseStatus == 304 || // not-modified response
                                    _responseStatus == 204 || // no-content response
                                    _responseStatus < 200)) // 1xx response
                                _endOfContent = EndOfContent.NO_CONTENT; // ignore any other headers set

                                // else if we don't know framing
                            else if (_endOfContent == EndOfContent.UNKNOWN_CONTENT)
                            {
                                if (_responseStatus == 0 || // request
                                    _responseStatus == 304 || // not-modified response
                                    _responseStatus == 204 || // no-content response
                                    _responseStatus < 200) // 1xx response
                                    _endOfContent = EndOfContent.NO_CONTENT;
                                else
                                    _endOfContent = EndOfContent.EOF_CONTENT;
                            }

                            // How is the message ended?
                            switch (_endOfContent)
                            {
                                case EOF_CONTENT:
                                {
                                    setState(State.EOF_CONTENT);
                                    boolean handle = _handler.headerComplete();
                                    _headerComplete = true;
                                    return handle;
                                }
                                case CHUNKED_CONTENT:
                                {
                                    setState(State.CHUNKED_CONTENT);
                                    boolean handle = _handler.headerComplete();
                                    _headerComplete = true;
                                    return handle;
                                }
                                default:
                                {
                                    setState(State.CONTENT);
                                    boolean handle = _handler.headerComplete();
                                    _headerComplete = true;
                                    return handle;
                                }
                            }
                        }

                        case ALPHA:
                        case DIGIT:
                        case TCHAR:
                        {
                            // process previous header
                            if (_state == State.HEADER)
                                parsedHeader();
                            else
                                parsedTrailer();

                            // handle new header
                            if (buffer.hasRemaining())
                            {
                                // Try a look ahead for the known header name and value.
                                HttpField cachedField = _fieldCache == null ? null : _fieldCache.getBest(buffer, -1, buffer.remaining());
                                if (cachedField == null)
                                    cachedField = CACHE.getBest(buffer, -1, buffer.remaining());

                                if (cachedField != null)
                                {
                                    String n = cachedField.getName();
                                    String v = cachedField.getValue();

                                    if (!_compliances.contains(HttpComplianceSection.FIELD_NAME_CASE_INSENSITIVE))
                                    {
                                        // Have to get the fields exactly from the buffer to match case
                                        String en = BufferUtil.toString(buffer, buffer.position() - 1, n.length(), StandardCharsets.US_ASCII);
                                        if (!n.equals(en))
                                        {
                                            handleViolation(HttpComplianceSection.FIELD_NAME_CASE_INSENSITIVE, en);
                                            n = en;
                                            cachedField = new HttpField(cachedField.getHeader(), n, v);
                                        }
                                    }

                                    if (v != null && !_compliances.contains(HttpComplianceSection.CASE_INSENSITIVE_FIELD_VALUE_CACHE))
                                    {
                                        String ev = BufferUtil.toString(buffer, buffer.position() + n.length() + 1, v.length(), StandardCharsets.ISO_8859_1);
                                        if (!v.equals(ev))
                                        {
                                            handleViolation(HttpComplianceSection.CASE_INSENSITIVE_FIELD_VALUE_CACHE, ev + "!=" + v);
                                            v = ev;
                                            cachedField = new HttpField(cachedField.getHeader(), n, v);
                                        }
                                    }

                                    _header = cachedField.getHeader();
                                    _headerString = n;

                                    if (v == null)
                                    {
                                        // Header only
                                        setState(FieldState.VALUE);
                                        _string.setLength(0);
                                        _length = 0;
                                        buffer.position(buffer.position() + n.length() + 1);
                                        break;
                                    }

                                    // Header and value
                                    int pos = buffer.position() + n.length() + v.length() + 1;
                                    byte peek = buffer.get(pos);
                                    if (peek == HttpTokens.CARRIAGE_RETURN || peek == HttpTokens.LINE_FEED)
                                    {
                                        _field = cachedField;
                                        _valueString = v;
                                        setState(FieldState.IN_VALUE);

                                        if (peek == HttpTokens.CARRIAGE_RETURN)
                                        {
                                            _cr = true;
                                            buffer.position(pos + 1);
                                        }
                                        else
                                            buffer.position(pos);
                                        break;
                                    }
                                    setState(FieldState.IN_VALUE);
                                    setString(v);
                                    buffer.position(pos);
                                    break;
                                }
                            }

                            // New header
                            setState(FieldState.IN_NAME);
                            _string.setLength(0);
                            _string.append(t.getChar());
                            _length = 1;
                            break;
                        }

                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;

                case IN_NAME:
                    switch (t.getType())
                    {
                        case SPACE:
                        case HTAB:
                            //Ignore trailing whitespaces ?
                            if (!complianceViolation(HttpComplianceSection.NO_WS_AFTER_FIELD_NAME, null))
                            {
                                _headerString = takeString();
                                _header = HttpHeader.CACHE.get(_headerString);
                                _length = -1;
                                setState(FieldState.WS_AFTER_NAME);
                                break;
                            }
                            throw new IllegalCharacterException(_state, t, buffer);

                        case COLON:
                            _headerString = takeString();
                            _header = HttpHeader.CACHE.get(_headerString);
                            _length = -1;
                            setState(FieldState.VALUE);
                            break;

                        case LF:
                            _headerString = takeString();
                            _header = HttpHeader.CACHE.get(_headerString);
                            _string.setLength(0);
                            _valueString = "";
                            _length = -1;

                            if (!complianceViolation(HttpComplianceSection.FIELD_COLON, _headerString))
                            {
                                setState(FieldState.FIELD);
                                break;
                            }
                            throw new IllegalCharacterException(_state, t, buffer);

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

                case WS_AFTER_NAME:

                    switch (t.getType())
                    {
                        case SPACE:
                        case HTAB:
                            break;

                        case COLON:
                            setState(FieldState.VALUE);
                            break;

                        case LF:
                            if (!complianceViolation(HttpComplianceSection.FIELD_COLON, _headerString))
                            {
                                setState(FieldState.FIELD);
                                break;
                            }
                            throw new IllegalCharacterException(_state, t, buffer);

                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;

                case VALUE:
                    switch (t.getType())
                    {
                        case LF:
                            _string.setLength(0);
                            _valueString = "";
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
                        case OTEXT: // TODO review? should this be a utf8 string?
                            _string.append(t.getChar());
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
                        case LF:
                            if (_length > 0)
                            {
                                _valueString = takeString();
                                _length = -1;
                            }
                            setState(FieldState.FIELD);
                            break;

                        case SPACE:
                        case HTAB:
                            _string.append(t.getChar());
                            break;

                        case ALPHA:
                        case DIGIT:
                        case TCHAR:
                        case VCHAR:
                        case COLON:
                        case OTEXT: // TODO review? should this be a utf8 string?
                            _string.append(t.getChar());
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

    /**
     * Parse until next Event.
     *
     * @param buffer the buffer to parse
     * @return True if an {@link RequestHandler} method was called and it returned true;
     */
    public boolean parseNext(ByteBuffer buffer)
    {
        if (debug)
            LOG.debug("parseNext s={} {}", _state, BufferUtil.toDetailString(buffer));
        try
        {
            // Start a request/response
            if (_state == State.START)
            {
                _version = null;
                _method = null;
                _methodString = null;
                _endOfContent = EndOfContent.UNKNOWN_CONTENT;
                _header = null;
                quickStart(buffer);
            }

            // Request/response line
            if (_state.ordinal() < State.HEADER.ordinal())
            {
                if (parseLine(buffer))
                    return true;
            }

            // parse headers
            if (_state == State.HEADER)
            {
                if (parseFields(buffer))
                    return true;
            }

            // parse content
            if (_state.ordinal() >= State.CONTENT.ordinal() && _state.ordinal() < State.TRAILER.ordinal())
            {
                // Handle HEAD response
                if (_responseStatus > 0 && _headResponse)
                {
                    if (_state != State.CONTENT_END)
                    {
                        setState(State.CONTENT_END);
                        return handleContentMessage();
                    }
                    else
                    {
                        setState(State.END);
                        return _handler.messageComplete();
                    }
                }
                else
                {
                    if (parseContent(buffer))
                        return true;
                }
            }

            // parse headers
            if (_state == State.TRAILER)
            {
                if (parseFields(buffer))
                    return true;
            }

            // handle end states
            if (_state == State.END)
            {
                // Eat CR or LF white space, but not SP.
                int whiteSpace = 0;
                while (buffer.remaining() > 0)
                {
                    byte b = buffer.get(buffer.position());
                    if (b != HttpTokens.CARRIAGE_RETURN && b != HttpTokens.LINE_FEED)
                        break;
                    buffer.get();
                    ++whiteSpace;
                }
                if (debug && whiteSpace > 0)
                    LOG.debug("Discarded {} CR or LF characters", whiteSpace);
            }
            else if (isTerminated())
            {
                BufferUtil.clear(buffer);
            }

            // Handle EOF
            if (isAtEOF() && !buffer.hasRemaining())
            {
                switch (_state)
                {
                    case CLOSED:
                        break;

                    case END:
                    case CLOSE:
                        setState(State.CLOSED);
                        break;

                    case EOF_CONTENT:
                    case TRAILER:
                        if (_fieldState == FieldState.FIELD)
                        {
                            // Be forgiving of missing last CRLF
                            setState(State.CONTENT_END);
                            boolean handle = handleContentMessage();
                            if (handle && _state == State.CONTENT_END)
                                return true;
                            setState(State.CLOSED);
                            return handle;
                        }
                        setState(State.CLOSED);
                        _handler.earlyEOF();
                        break;

                    case START:
                    case CONTENT:
                    case CHUNKED_CONTENT:
                    case CHUNK_SIZE:
                    case CHUNK_PARAMS:
                    case CHUNK:
                        setState(State.CLOSED);
                        _handler.earlyEOF();
                        break;

                    default:
                        if (debug)
                            LOG.debug("{} EOF in {}", this, _state);
                        setState(State.CLOSED);
                        _handler.badMessage(new BadMessageException(HttpStatus.BAD_REQUEST_400));
                        break;
                }
            }
        }
        catch (BadMessageException x)
        {
            BufferUtil.clear(buffer);
            badMessage(x);
        }
        catch (Throwable x)
        {
            BufferUtil.clear(buffer);
            badMessage(new BadMessageException(HttpStatus.BAD_REQUEST_400, _requestHandler != null ? "Bad Request" : "Bad Response", x));
        }
        return false;
    }

    protected void badMessage(BadMessageException x)
    {
        if (debug)
            LOG.debug("Parse exception: " + this + " for " + _handler, x);
        setState(State.CLOSE);
        if (_headerComplete)
            _handler.earlyEOF();
        else
            _handler.badMessage(x);
    }

    protected boolean parseContent(ByteBuffer buffer)
    {
        int remaining = buffer.remaining();
        if (remaining == 0)
        {
            switch (_state)
            {
                case CONTENT:
                    long content = _contentLength - _contentPosition;
                    if (_endOfContent == EndOfContent.NO_CONTENT || content == 0)
                    {
                        setState(State.CONTENT_END);
                        return handleContentMessage();
                    }
                    break;
                case CONTENT_END:
                    setState(_endOfContent == EndOfContent.EOF_CONTENT ? State.CLOSED : State.END);
                    return _handler.messageComplete();
                default:
                    // No bytes to parse, return immediately.
                    return false;
            }
        }

        // Handle content.
        while (_state.ordinal() < State.TRAILER.ordinal() && remaining > 0)
        {
            switch (_state)
            {
                case EOF_CONTENT:
                    _contentChunk = buffer.asReadOnlyBuffer();
                    _contentPosition += remaining;
                    buffer.position(buffer.position() + remaining);
                    if (_handler.content(_contentChunk))
                        return true;
                    break;

                case CONTENT:
                {
                    long content = _contentLength - _contentPosition;
                    if (_endOfContent == EndOfContent.NO_CONTENT || content == 0)
                    {
                        setState(State.CONTENT_END);
                        return handleContentMessage();
                    }
                    else
                    {
                        _contentChunk = buffer.asReadOnlyBuffer();

                        // limit content by expected size
                        if (remaining > content)
                        {
                            // We can cast remaining to an int as we know that it is smaller than
                            // or equal to length which is already an int.
                            _contentChunk.limit(_contentChunk.position() + (int)content);
                        }

                        _contentPosition += _contentChunk.remaining();
                        buffer.position(buffer.position() + _contentChunk.remaining());

                        if (_handler.content(_contentChunk))
                            return true;

                        if (_contentPosition == _contentLength)
                        {
                            setState(State.CONTENT_END);
                            return handleContentMessage();
                        }
                    }
                    break;
                }

                case CHUNKED_CONTENT:
                {
                    HttpTokens.Token t = next(buffer);
                    if (t == null)
                        break;
                    switch (t.getType())
                    {
                        case LF:
                            break;

                        case DIGIT:
                            _chunkLength = t.getHexDigit();
                            _chunkPosition = 0;
                            setState(State.CHUNK_SIZE);
                            break;

                        case ALPHA:
                            if (t.isHexDigit())
                            {
                                _chunkLength = t.getHexDigit();
                                _chunkPosition = 0;
                                setState(State.CHUNK_SIZE);
                                break;
                            }
                            throw new IllegalCharacterException(_state, t, buffer);

                        default:
                            throw new IllegalCharacterException(_state, t, buffer);
                    }
                    break;
                }

                case CHUNK_SIZE:
                {
                    HttpTokens.Token t = next(buffer);
                    if (t == null)
                        break;

                    switch (t.getType())
                    {
                        case LF:
                            if (_chunkLength == 0)
                            {
                                setState(State.TRAILER);
                                if (_handler.contentComplete())
                                    return true;
                            }
                            else
                                setState(State.CHUNK);
                            break;

                        case SPACE:
                            setState(State.CHUNK_PARAMS);
                            break;

                        default:
                            if (t.isHexDigit())
                            {
                                if (_chunkLength > MAX_CHUNK_LENGTH)
                                    throw new BadMessageException(HttpStatus.PAYLOAD_TOO_LARGE_413);
                                _chunkLength = _chunkLength * 16 + t.getHexDigit();
                            }
                            else
                            {
                                setState(State.CHUNK_PARAMS);
                            }
                    }
                    break;
                }

                case CHUNK_PARAMS:
                {
                    HttpTokens.Token t = next(buffer);
                    if (t == null)
                        break;

                    switch (t.getType())
                    {
                        case LF:
                            if (_chunkLength == 0)
                            {
                                setState(State.TRAILER);
                                if (_handler.contentComplete())
                                    return true;
                            }
                            else
                                setState(State.CHUNK);
                            break;
                        default:
                            break; // TODO review
                    }
                    break;
                }

                case CHUNK:
                {
                    int chunk = _chunkLength - _chunkPosition;
                    if (chunk == 0)
                    {
                        setState(State.CHUNKED_CONTENT);
                    }
                    else
                    {
                        _contentChunk = buffer.asReadOnlyBuffer();

                        if (remaining > chunk)
                            _contentChunk.limit(_contentChunk.position() + chunk);
                        chunk = _contentChunk.remaining();

                        _contentPosition += chunk;
                        _chunkPosition += chunk;
                        buffer.position(buffer.position() + chunk);
                        if (_handler.content(_contentChunk))
                            return true;
                    }
                    break;
                }

                case CONTENT_END:
                {
                    setState(_endOfContent == EndOfContent.EOF_CONTENT ? State.CLOSED : State.END);
                    return _handler.messageComplete();
                }

                default:
                    break;
            }

            remaining = buffer.remaining();
        }
        return false;
    }

    public boolean isAtEOF()
    {
        return _eof;
    }

    /**
     * Signal that the associated data source is at EOF
     */
    public void atEOF()
    {
        if (debug)
            LOG.debug("atEOF {}", this);
        _eof = true;
    }

    /**
     * Request that the associated data source be closed
     */
    public void close()
    {
        if (debug)
            LOG.debug("close {}", this);
        setState(State.CLOSE);
    }

    public void reset()
    {
        if (debug)
            LOG.debug("reset {}", this);

        // reset state
        if (_state == State.CLOSE || _state == State.CLOSED)
            return;

        setState(State.START);
        _endOfContent = EndOfContent.UNKNOWN_CONTENT;
        _contentLength = -1;
        _hasContentLength = false;
        _hasTransferEncoding = false;
        _contentPosition = 0;
        _responseStatus = 0;
        _contentChunk = null;
        _headerBytes = 0;
        _host = false;
        _headerComplete = false;
    }

    protected void setState(State state)
    {
        if (debug)
            LOG.debug("{} --> {}", _state, state);
        _state = state;
    }

    protected void setState(FieldState state)
    {
        if (debug)
            LOG.debug("{}:{} --> {}", _state, _field != null ? _field : _headerString != null ? _headerString : _string, state);
        _fieldState = state;
    }

    public Trie<HttpField> getFieldCache()
    {
        return _fieldCache;
    }

    @Override
    public String toString()
    {
        return String.format("%s{s=%s,%d of %d}",
            getClass().getSimpleName(),
            _state,
            getContentRead(),
            getContentLength());
    }

    /* Event Handler interface
     * These methods return true if the caller should process the events
     * so far received (eg return from parseNext and call HttpChannel.handle).
     * If multiple callbacks are called in sequence (eg
     * headerComplete then messageComplete) from the same point in the parsing
     * then it is sufficient for the caller to process the events only once.
     */
    public interface HttpHandler
    {
        boolean content(ByteBuffer item);

        boolean headerComplete();

        boolean contentComplete();

        boolean messageComplete();

        /**
         * This is the method called by parser when an HTTP Header name and value is found
         *
         * @param field The field parsed
         */
        void parsedHeader(HttpField field);

        /**
         * This is the method called by parser when an HTTP Trailer name and value is found
         *
         * @param field The field parsed
         */
        default void parsedTrailer(HttpField field)
        {
        }

        /**
         * Called to signal that an EOF was received unexpectedly
         * during the parsing of an HTTP message
         */
        void earlyEOF();

        /**
         * Called to signal that a bad HTTP message has been received.
         *
         * @param failure the failure with the bad message information
         */
        default void badMessage(BadMessageException failure)
        {
            badMessage(failure.getCode(), failure.getReason());
        }

        /**
         * @deprecated use {@link #badMessage(BadMessageException)} instead
         */
        @Deprecated
        default void badMessage(int status, String reason)
        {
        }

        /**
         * @return the size in bytes of the per parser header cache
         */
        int getHeaderCacheSize();
    }

    public interface RequestHandler extends HttpHandler
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         *
         * @param method The method
         * @param uri The raw bytes of the URI.  These are copied into a ByteBuffer that will not be changed until this parser is reset and reused.
         * @param version the http version in use
         * @return true if handling parsing should return.
         */
        boolean startRequest(String method, String uri, HttpVersion version);
    }

    public interface ResponseHandler extends HttpHandler
    {
        /**
         * This is the method called by parser when the HTTP request line is parsed
         *
         * @param version the http version in use
         * @param status the response status
         * @param reason the response reason phrase
         * @return true if handling parsing should return
         */
        boolean startResponse(HttpVersion version, int status, String reason);
    }

    public interface ComplianceHandler extends HttpHandler
    {
        @Deprecated
        default void onComplianceViolation(HttpCompliance compliance, HttpCompliance required, String reason)
        {
        }

        default void onComplianceViolation(HttpCompliance compliance, HttpComplianceSection violation, String details)
        {
            onComplianceViolation(compliance, HttpCompliance.requiredCompliance(violation), details);
        }
    }

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
