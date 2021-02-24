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

    enum Ambiguous
    {
        SEGMENT,
        SEPARATOR,
        PARAM
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
        __ambiguousSegments.put("%2e", Boolean.TRUE);
        __ambiguousSegments.put("%2e%2e", Boolean.TRUE);
        __ambiguousSegments.put(".%2e", Boolean.TRUE);
        __ambiguousSegments.put("%2e.", Boolean.TRUE);
        __ambiguousSegments.put("..", Boolean.FALSE);
        __ambiguousSegments.put(".", Boolean.FALSE);
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
    private final EnumSet<Ambiguous> _ambiguous = EnumSet.noneOf(Ambiguous.class);

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
        _ambiguous.addAll(uri._ambiguous);
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
        _ambiguous.clear();
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
        boolean encoded = false; // set to true if the path contains % encoded characters
        boolean dot = false; // set to true if the path containers . or .. segments
        int escapedSlash = 0; // state of parsing a %2f

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
                            mark = i + 1;
                            state = State.PARAM;
                            break;
                        case '?':
                            // assume empty path (if seen at start)
                            _path = "";
                            mark = i + 1;
                            state = State.QUERY;
                            break;
                        case '#':
                            mark = i + 1;
                            state = State.FRAGMENT;
                            break;
                        case '*':
                            _path = "*";
                            state = State.ASTERISK;
                            break;
                        case '%':
                            encoded = true;
                            escapedSlash = 1;
                            mark = pathMark = segment = i;
                            state = State.PATH;
                            break;
                        case '.' :
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
                            // must have be in an encoded path 
                            encoded = true;
                            escapedSlash = 1;
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
                    continue;
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
                    continue;
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
                    continue;
                }
                case PATH:
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
                            checkSegment(uri, segment, i, false);
                            segment = i + 1;
                            break;
                        case '.':
                            dot |= segment == i;
                            break;
                        case '%':
                            encoded = true;
                            escapedSlash = 1;
                            break;
                        case '2':
                            escapedSlash = escapedSlash == 1 ? 2 : 0;
                            break;
                        case 'f':
                        case 'F':
                            if (escapedSlash == 2)
                                _ambiguous.add(Ambiguous.SEPARATOR);
                            escapedSlash = 0;
                            break;
                        default:
                            escapedSlash = 0;
                            break;
                    }
                    continue;
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
                            encoded = true;
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
                    continue;
                }
                case QUERY:
                {
                    if (c == '#')
                    {
                        _query = uri.substring(mark, i);
                        mark = i + 1;
                        state = State.FRAGMENT;
                    }
                    continue;
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
                    break;
            }
        }

        switch (state)
        {
            case START:
                break;
            case SCHEME_OR_PATH:
                _path = uri.substring(mark, end);
                break;
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
            case ASTERISK:
                break;
            case FRAGMENT:
                _fragment = uri.substring(mark, end);
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
            default:
                break;
        }

        if (!encoded && !dot)
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
     * @param uri The URI string
     * @param segment The inclusive starting index of the segment (excluding any '/')
     * @param end The exclusive end index of the segment
     */
    private void checkSegment(String uri, int segment, int end, boolean param)
    {
        if (!_ambiguous.contains(Ambiguous.SEGMENT))
        {
            Boolean ambiguous = __ambiguousSegments.get(uri, segment, end - segment);
            if (ambiguous == Boolean.TRUE)
                _ambiguous.add(Ambiguous.SEGMENT);
            else if (param && ambiguous == Boolean.FALSE)
                _ambiguous.add(Ambiguous.PARAM);
        }
    }

    /**
     * @return True if the URI has a possibly ambiguous segment like '..;' or '%2e%2e'
     */
    public boolean hasAmbiguousSegment()
    {
        return _ambiguous.contains(Ambiguous.SEGMENT);
    }

    /**
     * @return True if the URI has a possibly ambiguous separator of %2f
     */
    public boolean hasAmbiguousSeparator()
    {
        return _ambiguous.contains(Ambiguous.SEPARATOR);
    }

    /**
     * @return True if the URI has a possibly ambiguous path parameter like '..;'
     */
    public boolean hasAmbiguousParameter()
    {
        return _ambiguous.contains(Ambiguous.PARAM);
    }

    /**
     * @return True if the URI has either an {@link #hasAmbiguousSegment()} or {@link #hasAmbiguousSeparator()}.
     */
    public boolean isAmbiguous()
    {
        return !_ambiguous.isEmpty();
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
