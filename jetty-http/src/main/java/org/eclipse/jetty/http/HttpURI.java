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

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Objects;

import org.eclipse.jetty.util.ArrayTrie;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;

/**
 * Http URI.
 * Parse an HTTP URI from a string or byte array.  Given a URI
 * {@code http://user@host:port/path;param1/%2e/info;param2?query#fragment}
 * this class will split it into the following optional elements:<ul>
 * <li>{@link #getScheme()} - http:</li>
 * <li>{@link #getAuthority()} - //name@host:port</li>
 * <li>{@link #getHost()} - host</li>
 * <li>{@link #getPort()} - port</li>
 * <li>{@link #getPath()} - /path;param1/%2e/info;param2</li>
 * <li>{@link #getDecodedPath()} - /path/info</li>
 * <li>{@link #getParam()} - param2</li>
 * <li>{@link #getQuery()} - query</li>
 * <li>{@link #getFragment()} - fragment</li>
 * </ul>
 *
 * <p>The path part of the URI is provided in both raw form ({@link #getPath()}) and
 * decoded form ({@link #getDecodedPath}), which has: path parameters removed,
 * percent encoded characters expanded and relative segments resolved.  This approach
 * is somewhat contrary to <a href="https://tools.ietf.org/html/rfc3986#section-3.3">RFC3986</a>
 * which no longer defines path parameters (removed after
 * <a href="https://tools.ietf.org/html/rfc2396#section-3.3">RFC2396</a>) and specifies
 * that relative segment normalization should take place before percent encoded character
 * expansion. A literal interpretation of the RFC can result in URI paths with ambiguities
 * when viewed as strings. For example, a URI of {@code /foo%2f..%2fbar} is technically a single
 * segment of "/foo/../bar", but could easily be misinterpreted as 3 segments resolving to "/bar"
 * by a file system.
 * </p>
 * <p>
 * Thus this class avoid and/or detects such ambiguities. Furthermore, by decoding characters and
 * removing parameters before relative path normalization, ambiguous paths will be resolved in such
 * a way to be non-standard-but-non-ambiguous to down stream interpretation of the decoded path string.
 * The violations are recorded and available by API such as {@link #hasAmbiguousSegment()} so that requests
 * containing them may be rejected in case the non-standard-but-non-ambiguous interpretations
 * are not satisfactory for a given compliance configuration.
 * </p>
 * <p>
 * Implementations that wish to process ambiguous URI paths must configure the compliance modes
 * to accept them and then perform their own decoding of {@link #getPath()}.
 * </p>
 * <p>
 * If there are multiple path parameters, only the last one is returned by {@link #getParam()}.
 * </p>
 **/
public class HttpURI
{
    private enum State
    {
        START,
        HOST_OR_PATH,
        SCHEME_OR_PATH,
        HOST,
        IPV6,
        PORT,
        PATH,
        PARAM,
        QUERY,
        FRAGMENT,
        ASTERISK
    }

    /**
     * Violations of safe URI interpretations
     */
    enum Violation
    {
        /**
         * Ambiguous path segments e.g. {@code /foo/%2E%2E/bar}
         */
        SEGMENT("Ambiguous path segments"),
        /**
         * Ambiguous path separator within a URI segment e.g. {@code /foo%2Fbar}
         */
        SEPARATOR("Ambiguous path separator"),
        /**
         * Ambiguous path parameters within a URI segment e.g. {@code /foo/..;/bar} or {@code /foo/%2e%2e;param/bar}
         */
        PARAM("Ambiguous path parameters"),
        /**
         * Ambiguous double encoding within a URI segment e.g. {@code /%2557EB-INF}
         */
        ENCODING("Ambiguous double encoding"),
        /**
         * Ambiguous empty segments e.g. {@code /foo//bar}
         */
        EMPTY("Ambiguous empty segments"),
        /**
         * Non standard UTF-16 encoding eg {@code /foo%u2192bar}.
         */
        UTF16("Non standard UTF-16 encoding");

        private final String _message;

        Violation(String message)
        {
            _message = message;
        }

        String getMessage()
        {
            return _message;
        }
    }

    private static final Trie<Boolean> __ambiguousSegments = new ArrayTrie<>();

    static
    {
        __ambiguousSegments.put(".", Boolean.FALSE);
        __ambiguousSegments.put("%2e", Boolean.TRUE);
        __ambiguousSegments.put("%u002e", Boolean.TRUE);
        __ambiguousSegments.put("..", Boolean.FALSE);
        __ambiguousSegments.put(".%2e", Boolean.TRUE);
        __ambiguousSegments.put(".%u002e", Boolean.TRUE);
        __ambiguousSegments.put("%2e.", Boolean.TRUE);
        __ambiguousSegments.put("%2e%2e", Boolean.TRUE);
        __ambiguousSegments.put("%2e%u002e", Boolean.TRUE);
        __ambiguousSegments.put("%u002e.", Boolean.TRUE);
        __ambiguousSegments.put("%u002e%2e", Boolean.TRUE);
        __ambiguousSegments.put("%u002e%u002e", Boolean.TRUE);
    }

    private String _scheme;
    private String _user;
    private String _host;
    private int _port;
    private String _path;
    private String _param;
    private String _query;
    private String _fragment;
    private String _uri;
    private String _decodedPath;
    private final EnumSet<Violation> _violations = EnumSet.noneOf(Violation.class);
    private boolean _emptySegment;

    /**
     * Construct a normalized URI.
     * Port is not set if it is the default port.
     *
     * @param scheme the URI scheme
     * @param host the URI hose
     * @param port the URI port
     * @param path the URI path
     * @param param the URI param
     * @param query the URI query
     * @param fragment the URI fragment
     * @return the normalized URI
     */
    public static HttpURI createHttpURI(String scheme, String host, int port, String path, String param, String query, String fragment)
    {
        if (port == 80 && HttpScheme.HTTP.is(scheme))
            port = 0;
        if (port == 443 && HttpScheme.HTTPS.is(scheme))
            port = 0;
        return new HttpURI(scheme, host, port, path, param, query, fragment);
    }

    public HttpURI()
    {
    }

    public HttpURI(String scheme, String host, int port, String path, String param, String query, String fragment)
    {
        _scheme = scheme;
        _host = host;
        _port = port;
        if (path != null)
            parse(State.PATH, path, 0, path.length());
        if (param != null)
            _param = param;
        if (query != null)
            _query = query;
        if (fragment != null)
            _fragment = fragment;
    }

    public HttpURI(HttpURI uri)
    {
        _scheme = uri._scheme;
        _user = uri._user;
        _host = uri._host;
        _port = uri._port;
        _path = uri._path;
        _param = uri._param;
        _query = uri._query;
        _fragment = uri._fragment;
        _uri = uri._uri;
        _decodedPath = uri._decodedPath;
        _violations.addAll(uri._violations);
        _emptySegment = false;
    }

    public HttpURI(HttpURI schemeHostPort, HttpURI uri)
    {
        _scheme = schemeHostPort._scheme;
        _user = schemeHostPort._user;
        _host = schemeHostPort._host;
        _port = schemeHostPort._port;
        _path = uri._path;
        _param = uri._param;
        _query = uri._query;
        _fragment = uri._fragment;
        _uri = uri._uri;
        _decodedPath = uri._decodedPath;
        _violations.addAll(uri._violations);
        _emptySegment = false;
    }

    public HttpURI(String uri)
    {
        _port = -1;
        parse(State.START, uri, 0, uri.length());
    }

    public HttpURI(URI uri)
    {
        _uri = null;
        _scheme = uri.getScheme();
        _host = uri.getHost();
        if (_host == null && uri.getRawSchemeSpecificPart().startsWith("//"))
            _host = "";
        _port = uri.getPort();
        _user = uri.getUserInfo();
        String path = uri.getRawPath();
        if (path != null)
            parse(State.PATH, path, 0, path.length());
        _query = uri.getRawQuery();
        _fragment = uri.getFragment();
    }

    public HttpURI(String scheme, String host, int port, String pathQuery)
    {
        _uri = null;
        _scheme = scheme;
        _host = host;
        _port = port;
        if (pathQuery != null)
            parse(State.PATH, pathQuery, 0, pathQuery.length());
    }

    public void clear()
    {
        _uri = null;
        _scheme = null;
        _user = null;
        _host = null;
        _port = -1;
        _path = null;
        _param = null;
        _query = null;
        _fragment = null;
        _decodedPath = null;
        _emptySegment = false;
        _violations.clear();
    }

    public void parse(String uri)
    {
        clear();
        _uri = uri;
        parse(State.START, uri, 0, uri.length());
    }

    /**
     * Parse according to https://tools.ietf.org/html/rfc7230#section-5.3
     *
     * @param method the request method
     * @param uri the request uri
     */
    public void parseRequestTarget(String method, String uri)
    {
        clear();
        _uri = uri;

        if (HttpMethod.CONNECT.is(method))
            parse(State.HOST, uri, 0, uri.length());
        else
            parse(uri.startsWith("/") ? State.PATH : State.START, uri, 0, uri.length());
    }

    @Deprecated
    public void parseConnect(String uri)
    {
        clear();
        _uri = uri;
        _path = uri;
    }

    public void parse(String uri, int offset, int length)
    {
        clear();
        int end = offset + length;
        _uri = uri.substring(offset, end);
        parse(State.START, uri, offset, end);
    }

    private void parse(State state, final String uri, final int offset, final int end)
    {
        int mark = offset; // the start of the current section being parsed
        int pathMark = 0; // the start of the path section
        int segment = 0; // the start of the current segment within the path
        boolean encodedPath = false; // set to true if the path contains % encoded characters
        boolean encodedUtf16 = false; // Is the current encoding for UTF16?
        int encodedCharacters = 0; // partial state of parsing a % encoded character<x>
        int encodedValue = 0; // the partial encoded value
        boolean dot = false; // set to true if the path contains . or .. segments

        for (int i = offset; i < end; i++)
        {
            char c = uri.charAt(i);

            switch (state)
            {
                case START:
                {
                    switch (c)
                    {
                        case '/':
                            mark = i;
                            state = State.HOST_OR_PATH;
                            break;
                        case ';':
                            checkSegment(uri, segment, i, true);
                            mark = i + 1;
                            state = State.PARAM;
                            break;
                        case '?':
                            // assume empty path (if seen at start)
                            checkSegment(uri, segment, i, false);
                            _path = "";
                            mark = i + 1;
                            state = State.QUERY;
                            break;
                        case '#':
                            // assume empty path (if seen at start)
                            checkSegment(uri, segment, i, false);
                            _path = "";
                            mark = i + 1;
                            state = State.FRAGMENT;
                            break;
                        case '*':
                            _path = "*";
                            state = State.ASTERISK;
                            break;
                        case '%':
                            encodedPath = true;
                            encodedCharacters = 2;
                            encodedValue = 0;
                            mark = pathMark = segment = i;
                            state = State.PATH;
                            break;
                        case '.':
                            dot = true;
                            pathMark = segment = i;
                            state = State.PATH;
                            break;
                        default:
                            mark = i;
                            if (_scheme == null)
                                state = State.SCHEME_OR_PATH;
                            else
                            {
                                pathMark = segment = i;
                                state = State.PATH;
                            }
                            break;
                    }
                    continue;
                }

                case SCHEME_OR_PATH:
                {
                    switch (c)
                    {
                        case ':':
                            // must have been a scheme
                            _scheme = uri.substring(mark, i);
                            // Start again with scheme set
                            state = State.START;
                            break;
                        case '/':
                            // must have been in a path and still are
                            segment = i + 1;
                            state = State.PATH;
                            break;
                        case ';':
                            // must have been in a path
                            mark = i + 1;
                            state = State.PARAM;
                            break;
                        case '?':
                            // must have been in a path
                            _path = uri.substring(mark, i);
                            mark = i + 1;
                            state = State.QUERY;
                            break;
                        case '%':
                            // must have been in an encoded path
                            encodedPath = true;
                            encodedCharacters = 2;
                            encodedValue = 0;
                            state = State.PATH;
                            break;
                        case '#':
                            // must have been in a path
                            _path = uri.substring(mark, i);
                            state = State.FRAGMENT;
                            break;
                        default:
                            break;
                    }
                    continue;
                }
                case HOST_OR_PATH:
                {
                    switch (c)
                    {
                        case '/':
                            _host = "";
                            mark = i + 1;
                            state = State.HOST;
                            break;
                        case '%':
                        case '@':
                        case ';':
                        case '?':
                        case '#':
                        case '.':
                            // was a path, look again
                            i--;
                            pathMark = mark;
                            segment = mark + 1;
                            state = State.PATH;
                            break;
                        default:
                            // it is a path
                            pathMark = mark;
                            segment = mark + 1;
                            state = State.PATH;
                    }
                    continue;
                }

                case HOST:
                {
                    switch (c)
                    {
                        case '/':
                            _host = uri.substring(mark, i);
                            pathMark = mark = i;
                            segment = mark + 1;
                            state = State.PATH;
                            break;
                        case ':':
                            if (i > mark)
                                _host = uri.substring(mark, i);
                            mark = i + 1;
                            state = State.PORT;
                            break;
                        case '@':
                            if (_user != null)
                                throw new IllegalArgumentException("Bad authority");
                            _user = uri.substring(mark, i);
                            mark = i + 1;
                            break;
                        case '[':
                            state = State.IPV6;
                            break;
                        default:
                            break;
                    }
                    break;
                }
                case IPV6:
                {
                    switch (c)
                    {
                        case '/':
                            throw new IllegalArgumentException("No closing ']' for ipv6 in " + uri);
                        case ']':
                            c = uri.charAt(++i);
                            _host = uri.substring(mark, i);
                            if (c == ':')
                            {
                                mark = i + 1;
                                state = State.PORT;
                            }
                            else
                            {
                                pathMark = mark = i;
                                state = State.PATH;
                            }
                            break;
                        default:
                            break;
                    }
                    break;
                }
                case PORT:
                {
                    if (c == '@')
                    {
                        if (_user != null)
                            throw new IllegalArgumentException("Bad authority");
                        // It wasn't a port, but a password!
                        _user = _host + ":" + uri.substring(mark, i);
                        mark = i + 1;
                        state = State.HOST;
                    }
                    else if (c == '/')
                    {
                        _port = TypeUtil.parseInt(uri, mark, i - mark, 10);
                        pathMark = mark = i;
                        segment = i + 1;
                        state = State.PATH;
                    }
                    break;
                }
                case PATH:
                {
                    if (encodedCharacters > 0)
                    {
                        if (encodedCharacters == 2 && c == 'u' && !encodedUtf16)
                        {
                            _violations.add(Violation.UTF16);
                            encodedUtf16 = true;
                            encodedCharacters = 4;
                            continue;
                        }
                        encodedValue = (encodedValue << 4) + TypeUtil.convertHexDigit(c);

                        if (--encodedCharacters == 0)
                        {
                            switch (encodedValue)
                            {
                                case 0:
                                    // Byte 0 cannot be present in a UTF-8 sequence in any position
                                    // other than as the NUL ASCII byte which we do not wish to allow.
                                    throw new IllegalArgumentException("Illegal character in path");
                                case '/':
                                    _violations.add(Violation.SEPARATOR);
                                    break;
                                case '%':
                                    _violations.add(Violation.ENCODING);
                                    break;
                                default:
                                    break;
                            }
                        }
                    }
                    else
                    {
                        switch (c)
                        {
                            case ';':
                                checkSegment(uri, segment, i, true);
                                mark = i + 1;
                                state = State.PARAM;
                                break;
                            case '?':
                                checkSegment(uri, segment, i, false);
                                _path = uri.substring(pathMark, i);
                                mark = i + 1;
                                state = State.QUERY;
                                break;
                            case '#':
                                checkSegment(uri, segment, i, false);
                                _path = uri.substring(pathMark, i);
                                mark = i + 1;
                                state = State.FRAGMENT;
                                break;
                            case '/':
                                // There is no leading segment when parsing only a path that starts with slash.
                                if (i != 0)
                                    checkSegment(uri, segment, i, false);
                                segment = i + 1;
                                break;
                            case '.':
                                dot |= segment == i;
                                break;
                            case '%':
                                encodedPath = true;
                                encodedUtf16 = false;
                                encodedCharacters = 2;
                                encodedValue = 0;
                                break;
                            default:
                                break;
                        }
                    }
                    break;
                }
                case PARAM:
                {
                    switch (c)
                    {
                        case '?':
                            _path = uri.substring(pathMark, i);
                            _param = uri.substring(mark, i);
                            mark = i + 1;
                            state = State.QUERY;
                            break;
                        case '#':
                            _path = uri.substring(pathMark, i);
                            _param = uri.substring(mark, i);
                            mark = i + 1;
                            state = State.FRAGMENT;
                            break;
                        case '/':
                            encodedPath = true;
                            segment = i + 1;
                            state = State.PATH;
                            break;
                        case ';':
                            // multiple parameters
                            mark = i + 1;
                            break;
                        default:
                            break;
                    }
                    break;
                }
                case QUERY:
                {
                    if (c == '#')
                    {
                        _query = uri.substring(mark, i);
                        mark = i + 1;
                        state = State.FRAGMENT;
                    }
                    break;
                }
                case ASTERISK:
                {
                    throw new IllegalArgumentException("Bad character '*'");
                }
                case FRAGMENT:
                {
                    _fragment = uri.substring(mark, end);
                    i = end;
                    break;
                }
                default:
                {
                    throw new IllegalStateException(state.toString());
                }
            }
        }

        switch (state)
        {
            case START:
                _path = "";
                checkSegment(uri, segment, end, false);
                break;
            case ASTERISK:
                break;
            case SCHEME_OR_PATH:
            case HOST_OR_PATH:
                _path = uri.substring(mark, end);
                break;
            case HOST:
                if (end > mark)
                    _host = uri.substring(mark, end);
                break;
            case IPV6:
                throw new IllegalArgumentException("No closing ']' for ipv6 in " + uri);
            case PORT:
                _port = TypeUtil.parseInt(uri, mark, end - mark, 10);
                break;
            case PARAM:
                _path = uri.substring(pathMark, end);
                _param = uri.substring(mark, end);
                break;
            case PATH:
                checkSegment(uri, segment, end, false);
                _path = uri.substring(pathMark, end);
                break;
            case QUERY:
                _query = uri.substring(mark, end);
                break;
            case FRAGMENT:
                _fragment = uri.substring(mark, end);
                break;
            default:
                throw new IllegalStateException(state.toString());
        }

        if (!encodedPath && !dot)
        {
            if (_param == null)
                _decodedPath = _path;
            else
                _decodedPath = _path.substring(0, _path.length() - _param.length() - 1);
        }
        else if (_path != null)
        {
            // The RFC requires this to be canonical before decoding, but this can leave dot segments and dot dot segments
            // which are not canonicalized and could be used in an attempt to bypass security checks.
            String decodedNonCanonical = URIUtil.decodePath(_path);
            _decodedPath = URIUtil.canonicalPath(decodedNonCanonical);
            if (_decodedPath == null)
                throw new IllegalArgumentException("Bad URI");
        }
    }

    /**
     * Check for ambiguous path segments.
     *
     * An ambiguous path segment is one that is perhaps technically legal, but is considered undesirable to handle
     * due to possible ambiguity.  Examples include segments like '..;', '%2e', '%2e%2e' etc.
     *
     * @param uri The URI string
     * @param segment The inclusive starting index of the segment (excluding any '/')
     * @param end The exclusive end index of the segment
     */
    private void checkSegment(String uri, int segment, int end, boolean param)
    {
        // This method is called once for every segment parsed.
        // A URI like "/foo/" has two segments: "foo" and an empty segment.
        // Empty segments are only ambiguous if they are not the last segment
        // So if this method is called for any segment and we have previously
        // seen an empty segment, then it was ambiguous.
        if (_emptySegment)
            _violations.add(Violation.EMPTY);

        if (end == segment)
        {
            // Empty segments are not ambiguous if followed by a '#', '?' or end of string.
            if (end >= uri.length() || ("#?".indexOf(uri.charAt(end)) >= 0))
                return;

            // If this empty segment is the first segment then it is ambiguous.
            if (segment == 0)
            {
                _violations.add(Violation.EMPTY);
                return;
            }

            // Otherwise remember we have seen an empty segment, which is check if we see a subsequent segment.
            if (!_emptySegment)
            {
                _emptySegment = true;
                return;
            }
        }

        // Look for segment in the ambiguous segment index.
        Boolean ambiguous = __ambiguousSegments.get(uri, segment, end - segment);
        if (ambiguous != null)
        {
            // Is the segment intrinsically ambiguous
            if (Boolean.TRUE.equals(ambiguous))
                _violations.add(Violation.SEGMENT);
            // Is the segment ambiguous with a parameter?
            if (param)
                _violations.add(Violation.PARAM);
        }
    }

    /**
     * @return True if the URI has a possibly ambiguous segment like '..;' or '%2e%2e'
     */
    public boolean hasAmbiguousSegment()
    {
        return _violations.contains(Violation.SEGMENT);
    }

    /**
     * @return True if the URI empty segment that is ambiguous like '//' or '/;param/'.
     */
    public boolean hasAmbiguousEmptySegment()
    {
        return _violations.contains(Violation.EMPTY);
    }

    /**
     * @return True if the URI has a possibly ambiguous separator of %2f
     */
    public boolean hasAmbiguousSeparator()
    {
        return _violations.contains(Violation.SEPARATOR);
    }

    /**
     * @return True if the URI has a possibly ambiguous path parameter like '..;'
     */
    public boolean hasAmbiguousParameter()
    {
        return _violations.contains(Violation.PARAM);
    }

    /**
     * @return True if the URI has an encoded '%' character.
     */
    public boolean hasAmbiguousEncoding()
    {
        return _violations.contains(Violation.ENCODING);
    }

    /**
     * @return True if the URI has either an {@link #hasAmbiguousSegment()} or {@link #hasAmbiguousEmptySegment()}
     * or {@link #hasAmbiguousSeparator()} or {@link #hasAmbiguousParameter()}
     */
    public boolean isAmbiguous()
    {
        return !_violations.isEmpty() && !(_violations.size() == 1 && _violations.contains(Violation.UTF16));
    }

    /**
     * @return True if the URI has any Violations.
     */
    public boolean hasViolations()
    {
        return !_violations.isEmpty();
    }

    boolean hasViolation(Violation violation)
    {
        return _violations.contains(violation);
    }

    /**
     * @return True if the URI encodes UTF-16 characters with '%u'.
     */
    public boolean hasUtf16Encoding()
    {
        return _violations.contains(Violation.UTF16);
    }

    public String getScheme()
    {
        return _scheme;
    }

    public String getHost()
    {
        // Return null for empty host to retain compatibility with java.net.URI
        if (_host != null && _host.isEmpty())
            return null;
        return _host;
    }

    public int getPort()
    {
        return _port;
    }

    /**
     * The parsed Path.
     *
     * @return the path as parsed on valid URI.  null for invalid URI.
     */
    public String getPath()
    {
        return _path;
    }

    /**
     * @return The decoded canonical path.
     * @see URIUtil#canonicalPath(String)
     */
    public String getDecodedPath()
    {
        return _decodedPath;
    }

    /**
     * Get a URI path parameter. Multiple and in segment parameters are ignored and only
     * the last trailing parameter is returned.
     * @return The last path parameter or null
     */
    public String getParam()
    {
        return _param;
    }

    public void setParam(String param)
    {
        if (!Objects.equals(_param, param))
        {
            if (_param != null && _path.endsWith(";" + _param))
                _path = _path.substring(0, _path.length() - 1 - _param.length());
            _param = param;
            if (_param != null)
                _path = (_path == null ? "" : _path) + ";" + _param;
            _uri = null;
        }
    }

    public String getQuery()
    {
        return _query;
    }

    public boolean hasQuery()
    {
        return _query != null && !_query.isEmpty();
    }

    public String getFragment()
    {
        return _fragment;
    }

    public void decodeQueryTo(MultiMap<String> parameters)
    {
        if (_query == null)
            return;
        UrlEncoded.decodeUtf8To(_query, parameters);
    }

    public void decodeQueryTo(MultiMap<String> parameters, String encoding) throws UnsupportedEncodingException
    {
        decodeQueryTo(parameters, Charset.forName(encoding));
    }

    public void decodeQueryTo(MultiMap<String> parameters, Charset encoding) throws UnsupportedEncodingException
    {
        if (_query == null)
            return;

        if (encoding == null || StandardCharsets.UTF_8.equals(encoding))
            UrlEncoded.decodeUtf8To(_query, parameters);
        else
            UrlEncoded.decodeTo(_query, parameters, encoding);
    }

    public boolean isAbsolute()
    {
        return _scheme != null && !_scheme.isEmpty();
    }

    @Override
    public String toString()
    {
        if (_uri == null)
        {
            StringBuilder out = new StringBuilder();

            if (_scheme != null)
                out.append(_scheme).append(':');

            if (_host != null)
            {
                out.append("//");
                if (_user != null)
                    out.append(_user).append('@');
                out.append(_host);
            }

            if (_port > 0)
                out.append(':').append(_port);

            if (_path != null)
                out.append(_path);

            if (_query != null)
                out.append('?').append(_query);

            if (_fragment != null)
                out.append('#').append(_fragment);

            if (out.length() > 0)
                _uri = out.toString();
            else
                _uri = "";
        }
        return _uri;
    }

    @Override
    public boolean equals(Object o)
    {
        if (o == this)
            return true;
        if (!(o instanceof HttpURI))
            return false;
        return toString().equals(o.toString());
    }

    @Override
    public int hashCode()
    {
        return toString().hashCode();
    }

    public void setScheme(String scheme)
    {
        _scheme = scheme;
        _uri = null;
    }

    /**
     * @param host the host
     * @param port the port
     */
    public void setAuthority(String host, int port)
    {
        if (host != null && !isPathValidForAuthority(_path))
            throw new IllegalArgumentException("Relative path with authority");
        _host = host;
        _port = port;
        _uri = null;
    }

    private boolean isPathValidForAuthority(String path)
    {
        if (path == null)
            return true;
        if (path.isEmpty() || "*".equals(path))
            return true;
        return path.startsWith("/");
    }

    /**
     * @param path the path
     */
    public void setPath(String path)
    {
        if (hasAuthority() && !isPathValidForAuthority(path))
            throw new IllegalArgumentException("Relative path with authority");
        _uri = null;
        _path = null;
        if (path != null)
            parse(State.PATH, path, 0, path.length());
    }

    public void setPathQuery(String pathQuery)
    {
        if (hasAuthority() && !isPathValidForAuthority(pathQuery))
            throw new IllegalArgumentException("Relative path with authority");
        _uri = null;
        _path = null;
        _decodedPath = null;
        _param = null;
        _fragment = null;
        /*
         * The query is not cleared here and old values may be retained if there is no query in
         * the pathQuery. This has been fixed in 10, but left as is here to preserve behaviour in 9.
         */
        if (pathQuery != null)
            parse(State.PATH, pathQuery, 0, pathQuery.length());
    }

    private boolean hasAuthority()
    {
        return _host != null;
    }

    public void setQuery(String query)
    {
        _query = query;
        _uri = null;
    }

    public URI toURI() throws URISyntaxException
    {
        return new URI(_scheme, null, _host, _port, _path, _query == null ? null : UrlEncoded.decodeString(_query), _fragment);
    }

    public String getPathQuery()
    {
        if (_query == null)
            return _path;
        return _path + "?" + _query;
    }

    public String getAuthority()
    {
        if (_port > 0)
            return _host + ":" + _port;
        return _host;
    }

    public String getUser()
    {
        return _user;
    }
}
