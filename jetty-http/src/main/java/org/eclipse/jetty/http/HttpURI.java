//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import java.net.URI;
import java.net.URISyntaxException;

import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.StringUtil;
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
public interface HttpURI
{
    static HttpURI.Builder build()
    {
        return new HttpURI.Builder();
    }

    static HttpURI.Builder build(HttpURI uri)
    {
        return new HttpURI.Builder(uri);
    }

    static HttpURI.Builder build(HttpURI uri, String pathQuery)
    {
        return new HttpURI.Builder(uri, pathQuery);
    }

    static HttpURI.Builder build(HttpURI uri, String path, String param, String query)
    {
        return new HttpURI.Builder(uri, path, param, query);
    }

    static HttpURI.Builder build(URI uri)
    {
        return new HttpURI.Builder(uri);
    }

    static HttpURI.Builder build(String uri)
    {
        return new HttpURI.Builder(uri);
    }

    static HttpURI from(URI uri)
    {
        return new HttpURI.Immutable(uri);
    }

    static HttpURI from(String uri)
    {
        return new HttpURI.Immutable(uri);
    }

    static HttpURI from(String method, String uri)
    {
        if (HttpMethod.CONNECT.is(method))
            return new Immutable(uri, null, null, null, -1, uri, null, null, null, null);
        if (uri.startsWith("/"))
            return HttpURI.build().pathQuery(uri).asImmutable();
        return HttpURI.from(uri);
    }

    static HttpURI from(String scheme, String host, int port, String pathQuery)
    {
        return build(pathQuery).scheme(scheme).host(host).port(port).pathQuery(pathQuery).asImmutable();
    }

    String getAuthority();

    String getDecodedPath();

    String getFragment();

    String getHost();

    String getParam();

    /**
     * The parsed Path.
     *
     * @return the path as parsed on valid URI.  null for invalid URI.
     */
    String getPath();

    String getPathQuery();

    int getPort();

    String getQuery();

    String getScheme();

    String getUser();

    boolean hasAuthority();

    boolean hasQuery();

    boolean isAbsolute();

    HttpURI asImmutable();

    HttpURI.Builder asMutable();

    default URI toURI()
    {
        try
        {
            String query = getQuery();
            return new URI(getScheme(), null, getHost(), getPort(), getPath(), query == null ? null : UrlEncoded.decodeString(query), getFragment());
        }
        catch (URISyntaxException x)
        {
            throw new RuntimeException(x);
        }
    }

    class Immutable implements HttpURI
    {
        private final String _scheme;
        private final String _user;
        private final String _host;
        private final int _port;
        private final String _path;
        private final String _param;
        private final String _query;
        private final String _fragment;
        private String _uri;
        private String _decodedPath;

        private Immutable(String uri)
        {
            Builder builder = new Builder(uri);
            _uri = builder._uri;
            _scheme = builder._scheme;
            _user = builder._user;
            _host = builder._host;
            _port = builder._port;
            _path = builder._path;
            _decodedPath = builder._decodedPath;
            _param = builder._param;
            _query = builder._query;
            _fragment = builder._fragment;
        }

        private Immutable(URI uri)
        {
            Builder builder = new Builder(uri);
            _uri = builder._uri;
            _scheme = builder._scheme;
            _user = builder._user;
            _host = builder._host;
            _port = builder._port;
            _path = builder._path;
            _decodedPath = builder._decodedPath;
            _param = builder._param;
            _query = builder._query;
            _fragment = builder._fragment;
        }

        private Immutable(String uri, String scheme, String user, String host, int port, String path, String decodedPath, String param, String query, String fragment)
        {
            _uri = uri;
            _scheme = scheme;
            _user = user;
            _host = host;
            _port = port;
            _path = path;
            _decodedPath = decodedPath;
            _param = param;
            _query = query;
            _fragment = fragment;
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
        public String getAuthority()
        {
            if (_port > 0)
                return _host + ":" + _port;
            return _host;
        }

        @Override
        public String getDecodedPath()
        {
            if (_decodedPath == null && _path != null)
                _decodedPath = URIUtil.canonicalPath(URIUtil.decodePath(_path));
            return _decodedPath;
        }

        @Override
        public String getFragment()
        {
            return _fragment;
        }

        @Override
        public String getHost()
        {
            // Return null for empty host to retain compatibility with java.net.URI
            if (_host != null && _host.isEmpty())
                return null;
            return _host;
        }

        @Override
        public String getParam()
        {
            return _param;
        }

        /**
         * The parsed Path.
         *
         * @return the path as parsed on valid URI.  null for invalid URI.
         */
        @Override
        public String getPath()
        {
            return _path;
        }

        @Override
        public String getPathQuery()
        {
            if (_query == null)
                return _path;
            return _path + "?" + _query;
        }

        @Override
        public int getPort()
        {
            return _port;
        }

        @Override
        public String getQuery()
        {
            return _query;
        }

        @Override
        public String getScheme()
        {
            return _scheme;
        }

        @Override
        public String getUser()
        {
            return _user;
        }

        @Override
        public boolean hasAuthority()
        {
            return _host != null;
        }

        @Override
        public boolean hasQuery()
        {
            return _query != null && !_query.isEmpty();
        }

        @Override
        public boolean isAbsolute()
        {
            return !StringUtil.isEmpty(_scheme);
        }

        @Override
        public URI toURI()
        {
            try
            {
                return new URI(_scheme, null, _host, _port, _path, _query == null ? null : UrlEncoded.decodeString(_query), _fragment);
            }
            catch (URISyntaxException x)
            {
                throw new RuntimeException(x);
            }
        }

        @Override
        public HttpURI asImmutable()
        {
            return this;
        }

        @Override
        public HttpURI.Builder asMutable()
        {
            return build(this);
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
    }

    class Builder implements HttpURI
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

        private String _uri;
        private String _decodedPath;
        private String _scheme;
        private String _user;
        private String _host;
        private int _port;
        private String _path;
        private String _param;
        private String _query;
        private String _fragment;

        private Builder()
        {
        }

        private Builder(HttpURI uri)
        {
            uri(uri);
        }

        private Builder(HttpURI baseURI, String pathQuery)
        {
            _uri = null;
            _scheme = baseURI.getScheme();
            _user = baseURI.getUser();
            _host = baseURI.getHost();
            _port = baseURI.getPort();
            if (pathQuery != null)
                parse(State.PATH, pathQuery);
        }

        private Builder(HttpURI baseURI, String path, String param, String query)
        {
            _uri = null;
            _scheme = baseURI.getScheme();
            _user = baseURI.getUser();
            _host = baseURI.getHost();
            _port = baseURI.getPort();
            _path = path;
            _param = param;
            _query = query;
            _fragment = null;
        }

        private Builder(String uri)
        {
            _port = -1;
            parse(State.START, uri);
        }

        private Builder(URI uri)
        {
            _uri = null;

            _scheme = uri.getScheme();
            _host = uri.getHost();
            if (_host == null && uri.getRawSchemeSpecificPart().startsWith("//"))
                _host = "";
            _port = uri.getPort();
            _user = uri.getUserInfo();
            _path = uri.getRawPath();

            String pathParam = uri.getPath();
            if (pathParam != null)
            {
                int p = pathParam.lastIndexOf(';');
                if (p >= 0)
                    _param = pathParam.substring(p + 1);
                else
                    _decodedPath = pathParam;
            }
            _query = uri.getRawQuery();
            _fragment = uri.getFragment();
        }

        private Builder(String scheme, String host, int port, String pathQuery)
        {
            _uri = null;

            _scheme = scheme;
            _host = host;
            _port = port;

            if (pathQuery != null)
                parse(State.PATH, pathQuery);
        }

        @Override
        public HttpURI asImmutable()
        {
            return new Immutable(_uri, _scheme, _user, _host, _port, _path, _decodedPath, _param, _query, _fragment);
        }

        @Override
        public HttpURI.Builder asMutable()
        {
            return this;
        }

        @Override
        public String getAuthority()
        {
            if (_port > 0)
                return _host + ":" + _port;
            return _host;
        }

        /**
         * @param host the host
         * @param port the port
         */
        public Builder authority(String host, int port)
        {
            _user = null;
            _host = host;
            _port = port;
            _uri = null;
            return this;
        }

        /**
         * @param hostport the host and port combined
         */
        public Builder authority(String hostport)
        {
            HostPort hp = new HostPort(hostport);
            _user = null;
            _host = hp.getHost();
            _port = hp.getPort();
            _uri = null;
            return this;
        }

        public void clear()
        {
            _uri = null;
            _scheme = null;
            _host = null;
            _port = -1;
            _path = null;
            _param = null;
            _query = null;
            _fragment = null;
            _decodedPath = null;
            _user = null;
        }

        @Override
        public String getDecodedPath()
        {
            if (_decodedPath == null && _path != null)
                _decodedPath = URIUtil.canonicalPath(URIUtil.decodePath(_path));
            return _decodedPath;
        }

        public Builder decodedPath(String path)
        {
            _uri = null;
            _path = URIUtil.encodePath(path);
            _decodedPath = path;
            return this;
        }

        @Override
        public String getFragment()
        {
            return _fragment;
        }

        public Builder fragment(String fragment)
        {
            _fragment = fragment;
            _uri = null;
            return this;
        }

        @Override
        public boolean hasAuthority()
        {
            return _host != null;
        }

        @Override
        public boolean hasQuery()
        {
            return _query != null && !_query.isEmpty();
        }

        @Override
        public String getHost()
        {
            return _host;
        }

        public Builder host(String host)
        {
            _host = host;
            _uri = null;
            return this;
        }

        @Override
        public boolean isAbsolute()
        {
            return _scheme != null && !_scheme.isEmpty();
        }

        public Builder normalize()
        {
            if (_port == 80 && HttpScheme.HTTP.is(_scheme))
                _port = 0;
            if (_port == 443 && HttpScheme.HTTPS.is(_scheme))
                _port = 0;
            _uri = null;
            return this;
        }

        @Override
        public String getParam()
        {
            return _param;
        }

        public Builder param(String param)
        {
            _param = param;
            if (_path != null && _param != null && !_path.contains(_param))
            {
                _path += ";" + _param;
            }
            _uri = null;
            return this;
        }

        @Override
        public String getPath()
        {
            return _path;
        }

        /**
         * @param path the path
         */
        public Builder path(String path)
        {
            _uri = null;
            _path = path;
            _decodedPath = null;
            return this;
        }

        @Override
        public String getPathQuery()
        {
            if (_query == null)
                return _path;
            return _path + "?" + _query;
        }

        public Builder pathQuery(String pathQuery)
        {
            _uri = null;
            _path = null;
            _decodedPath = null;
            _param = null;
            _fragment = null;
            if (pathQuery != null)
                parse(State.PATH, pathQuery);
            return this;
        }

        @Override
        public int getPort()
        {
            return _port;
        }

        public Builder port(int port)
        {
            _port = port;
            _uri = null;
            return this;
        }

        @Override
        public String getQuery()
        {
            return _query;
        }

        public Builder query(String query)
        {
            _query = query;
            _uri = null;
            return this;
        }

        @Override
        public String getScheme()
        {
            return _scheme;
        }

        public Builder scheme(HttpScheme scheme)
        {
            return scheme(scheme.asString());
        }

        public Builder scheme(String scheme)
        {
            _scheme = scheme;
            _uri = null;
            return this;
        }

        @Override
        public String toString()
        {
            return asImmutable().toString();
        }

        public URI toURI()
        {
            try
            {
                return new URI(_scheme, null, _host, _port, _path, _query == null ? null : UrlEncoded.decodeString(_query), _fragment);
            }
            catch (URISyntaxException x)
            {
                throw new RuntimeException(x);
            }
        }

        public Builder uri(HttpURI uri)
        {
            _uri = null;
            _scheme = uri.getScheme();
            _user = uri.getUser();
            _host = uri.getHost();
            _port = uri.getPort();
            _path = uri.getPath();
            _decodedPath = uri.getDecodedPath();
            _param = uri.getParam();
            _query = uri.getQuery();
            _fragment = uri.getFragment();
            return this;
        }

        public Builder uri(String uri)
        {
            clear();
            _uri = uri;
            parse(State.START, uri);
            return this;
        }

        public Builder uri(String uri, int offset, int length)
        {
            clear();
            int end = offset + length;
            _uri = uri.substring(offset, end);
            parse(State.START, uri);
            return this;
        }

        public String getUser()
        {
            return _user;
        }

        public Builder user(String user)
        {
            _user = user;
            _uri = null;
            return this;
        }

        private void parse(State state, final String uri)
        {
            boolean encoded = false;
            int end = uri.length();
            int mark = 0;
            int pathMark = 0;
            char last = '/';
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

                            case '.':
                                pathMark = i;
                                state = State.PATH;
                                encoded = true;
                                break;

                            default:
                                mark = i;
                                if (_scheme == null)
                                    state = State.SCHEME_OR_PATH;
                                else
                                {
                                    pathMark = i;
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

                            case '@':
                            case ';':
                            case '?':
                            case '#':
                                // was a path, look again
                                i--;
                                pathMark = mark;
                                state = State.PATH;
                                break;

                            case '.':
                                // it is a path
                                encoded = true;
                                pathMark = mark;
                                state = State.PATH;
                                break;

                            default:
                                // it is a path
                                pathMark = mark;
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
                            state = State.PATH;
                        }
                        break;
                    }

                    case PATH:
                    {
                        switch (c)
                        {
                            case ';':
                                mark = i + 1;
                                state = State.PARAM;
                                break;
                            case '?':
                                _path = uri.substring(pathMark, i);
                                mark = i + 1;
                                state = State.QUERY;
                                break;
                            case '#':
                                _path = uri.substring(pathMark, i);
                                mark = i + 1;
                                state = State.FRAGMENT;
                                break;
                            case '%':
                                encoded = true;
                                break;
                            case '.':
                                if ('/' == last)
                                    encoded = true;
                                break;
                            default:
                                break;
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
                                encoded = true;
                                // ignore internal params
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
                        throw new IllegalStateException(state.toString());
                }
                last = c;
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
                    _path = uri.substring(pathMark, end);
                    break;

                case QUERY:
                    _query = uri.substring(mark, end);
                    break;

                default:
                    throw new IllegalStateException(state.toString());
            }

            if (!encoded)
            {
                if (_param == null)
                    _decodedPath = _path;
                else
                    _decodedPath = _path.substring(0, _path.length() - _param.length() - 1);
            }
        }
    }
}
