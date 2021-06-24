//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
 * <code>http://user@host:port/path/info;param?query#fragment</code>
 * this class will split it into the following undecoded optional elements:<ul>
 * <li>{@link #getScheme()} - http:</li>
 * <li>{@link #getAuthority()} - //name@host:port</li>
 * <li>{@link #getHost()} - host</li>
 * <li>{@link #getPort()} - port</li>
 * <li>{@link #getPath()} - /path/info</li>
 * <li>{@link #getParam()} - param</li>
 * <li>{@link #getQuery()} - query</li>
 * <li>{@link #getFragment()} - fragment</li>
 * </ul>
 *
 * <p>Any parameters will be returned from {@link #getPath()}, but are excluded from the
 * return value of {@link #getDecodedPath()}.   If there are multiple parameters, the
 * {@link #getParam()} method returns only the last one.
 */
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

    enum Violation
    {
        SEGMENT,
        SEPARATOR,
        PARAM,
        ENCODING,
        EMPTY,
        UTF16
    }

    /**
     * The concept of URI path parameters was originally specified in
     * <a href="https://tools.ietf.org/html/rfc2396#section-3.3">RFC2396</a>, but that was
     * obsoleted by
     * <a href="https://tools.ietf.org/html/rfc3986#section-3.3">RFC3986</a> which removed
     * a normative definition of path parameters. Specifically it excluded them from the
     * <a href="https://tools.ietf.org/html/rfc3986#section-5.2.4">Remove Dot Segments</a>
     * algorithm.  This results in some ambiguity as dot segments can result from later
     * parameter removal or % encoding expansion, that are not removed from the URI
     * by {@link URIUtil#canonicalPath(String)}.  Thus this class flags such ambiguous
     * path segments, so that they may be rejected by the server if so configured.
     */
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
            _path = uri;
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

        for (int i = 0; i < end; i++)
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
                    switch (c)
                    {
                        case '%':
                            encodedCharacters = 2;
                            break;
                        case 'u':
                        case 'U':
                            if (encodedCharacters == 1)
                                _violations.add(Violation.UTF16);
                            encodedCharacters = 0;
                            break;
                        case '#':
                            _query = uri.substring(mark, i);
                            mark = i + 1;
                            state = State.FRAGMENT;
                            encodedCharacters = 0;
                            break;
                        default:
                            encodedCharacters = 0;
                            break;
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
                    throw new IllegalStateException(state.toString());
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
            String canonical = URIUtil.canonicalPath(_path);
            if (canonical == null)
                throw new BadMessageException("Bad URI");
            _decodedPath = URIUtil.decodePath(canonical);
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
        // So if this method is called for any segment and we have previously seen an empty segment, then it was ambiguous
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
        if (ambiguous == Boolean.TRUE)
        {
            // The segment is always ambiguous.
            _violations.add(Violation.SEGMENT);
        }
        else if (param && ambiguous == Boolean.FALSE)
        {
            // The segment is ambiguous only when followed by a parameter.
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
     * @return True if the URI has either an {@link #hasAmbiguousSegment()} or {@link #hasAmbiguousSeparator()}.
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
        _host = host;
        _port = port;
        _uri = null;
    }

    /**
     * @param path the path
     */
    public void setPath(String path)
    {
        _uri = null;
        _path = null;
        if (path != null)
            parse(State.PATH, path, 0, path.length());
    }

    public void setPathQuery(String pathQuery)
    {
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
