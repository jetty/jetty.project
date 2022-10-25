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

package org.eclipse.jetty.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;

import org.eclipse.jetty.http.UriCompliance.Violation;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;

/**
 * Http URI.
 *
 * Both {@link Mutable} and {@link Immutable} implementations are available
 * via the static methods such as {@link #build()} and {@link #from(String)}.
 *
 * A URI such as
 * {@code http://user@host:port/path;param1/%2e/f%6fo%2fbar;param2?query#fragment}
 * is split into the following optional elements:<ul>
 * <li>{@link #getScheme()} - http:</li>
 * <li>{@link #getAuthority()} - //name@host:port</li>
 * <li>{@link #getHost()} - host</li>
 * <li>{@link #getPort()} - port</li>
 * <li>{@link #getPath()} - /path;param1/%2e/f%6fo%2fbar;param2</li>
 * <li>{@link #getCanonicalPath()} - /path/foo%2Fbar</li>
 * <li>{@link #getDecodedPath()} - /path/foo/bar</li>
 * <li>{@link #getParam()} - param2</li>
 * <li>{@link #getQuery()} - query</li>
 * <li>{@link #getFragment()} - fragment</li>
 * </ul>
 * <p>The path part of the URI is provided in both raw form ({@link #getPath()}) and
 * decoded form ({@link #getCanonicalPath}), which has: path parameters removed,
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
public interface HttpURI
{
    static Mutable build()
    {
        return new Mutable();
    }

    static Mutable build(HttpURI uri)
    {
        return new Mutable(uri);
    }

    static Mutable build(HttpURI uri, String pathQuery)
    {
        return new Mutable(uri, pathQuery);
    }

    static Mutable build(HttpURI uri, String path, String param, String query)
    {
        return new Mutable(uri, path, param, query);
    }

    static Mutable build(URI uri)
    {
        return new Mutable(uri);
    }

    static Mutable build(String uri)
    {
        return new Mutable(uri);
    }

    static Mutable build(String method, String uri)
    {
        if (HttpMethod.CONNECT.is(method))
        {
            HostPort hostPort = new HostPort(uri);
            return new Mutable(null, hostPort.getHost(), hostPort.getPort(), null);
        }
        if (uri.startsWith("/"))
            return HttpURI.build().pathQuery(uri);
        return HttpURI.build(uri);
    }

    static Immutable from(URI uri)
    {
        return new HttpURI.Mutable(uri).asImmutable();
    }

    static Immutable from(String uri)
    {
        return new HttpURI.Mutable(uri).asImmutable();
    }

    static Immutable from(String method, String uri)
    {
        if (HttpMethod.CONNECT.is(method))
            return HttpURI.build().uri(method, uri).asImmutable();
        if (uri.startsWith("/"))
            return HttpURI.build().pathQuery(uri).asImmutable();
        return HttpURI.from(uri);
    }

    static Immutable from(String scheme, HostPort hostPort, String pathQuery)
    {
        return new Mutable(scheme, hostPort.getHost(), hostPort.getPort(), pathQuery).asImmutable();
    }

    static Immutable from(String scheme, String host, int port, String pathQuery)
    {
        return new Mutable(scheme, host, port, pathQuery).asImmutable();
    }

    Immutable asImmutable();

    String asString();

    String getAuthority();

    String getDecodedPath();

    String getCanonicalPath();

    String getFragment();

    String getHost();

    /**
     * Get a URI path parameter. Only parameters from the last segment are returned.
     * @return The last path parameter or null
     */
    String getParam();

    String getPath();

    String getPathQuery();

    int getPort();

    String getQuery();

    String getScheme();

    String getUser();

    boolean hasAuthority();

    boolean isAbsolute();

    /**
     * @return True if the URI has any ambiguous {@link Violation}s.
     */
    boolean isAmbiguous();

    /**
     * @return True if the URI has any {@link Violation}s.
     */
    boolean hasViolations();

    /**
     * @param violation the violation to check.
     * @return true if the URI has the passed violation.
     */
    boolean hasViolation(Violation violation);

    /**
     * @return Set of violations in the URI.
     */
    Collection<Violation> getViolations();

    /**
     * @return True if the URI has a possibly ambiguous segment like '..;' or '%2e%2e'
     */
    default boolean hasAmbiguousSegment()
    {
        return hasViolation(Violation.AMBIGUOUS_PATH_SEGMENT);
    }

    /**
     * @return True if the URI empty segment that is ambiguous like '//' or '/;param/'.
     */
    default boolean hasAmbiguousEmptySegment()
    {
        return hasViolation(Violation.AMBIGUOUS_EMPTY_SEGMENT);
    }

    /**
     * @return True if the URI has a possibly ambiguous separator of %2f
     */
    default boolean hasAmbiguousSeparator()
    {
        return hasViolation(Violation.AMBIGUOUS_PATH_SEPARATOR);
    }

    /**
     * @return True if the URI has a possibly ambiguous path parameter like '..;'
     */
    default boolean hasAmbiguousParameter()
    {
        return hasViolation(Violation.AMBIGUOUS_PATH_PARAMETER);
    }

    /**
     * @return True if the URI has an encoded '%' character.
     */
    default boolean hasAmbiguousEncoding()
    {
        return hasViolation(Violation.AMBIGUOUS_PATH_ENCODING);
    }

    /**
     * @return True if the URI has UTF16 '%u' encodings.
     */
    default boolean hasUtf16Encoding()
    {
        return hasViolation(Violation.UTF16_ENCODINGS);
    }

    default URI toURI()
    {
        try
        {
            String query = getQuery();
            return new URI(getScheme(), null, getHost(), getPort(), getPath(), query == null ? null : UrlEncoded.decodeString(query), null);
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
        private String _canonicalPath;
        private final EnumSet<Violation> _violations = EnumSet.noneOf(Violation.class);

        private Immutable(Mutable builder)
        {
            _scheme = builder._scheme;
            _user = builder._user;
            _host = builder._host;
            _port = builder._port;
            _path = builder._path;
            _param = builder._param;
            _query = builder._query;
            _fragment = builder._fragment;
            _uri = builder._uri;
            _canonicalPath = builder._canonicalPath;
            _violations.addAll(builder._violations);
        }

        private Immutable(String uri)
        {
            _scheme = null;
            _user = null;
            _host = null;
            _port = -1;
            _path = uri;
            _param = null;
            _query = null;
            _fragment = null;
            _uri = uri;
            _canonicalPath = null;
        }

        @Override
        public Immutable asImmutable()
        {
            return this;
        }

        @Override
        public String asString()
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
            return asString().equals(((HttpURI)o).asString());
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
            return URIUtil.decodePath(getCanonicalPath());
        }

        @Override
        public String getCanonicalPath()
        {
            if (_canonicalPath == null && _path != null)
                _canonicalPath = URIUtil.canonicalPath(_path);
            return _canonicalPath;
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
        public int hashCode()
        {
            return asString().hashCode();
        }

        @Override
        public boolean isAbsolute()
        {
            return !StringUtil.isEmpty(_scheme);
        }

        @Override
        public boolean isAmbiguous()
        {
            return !_violations.isEmpty() && !(_violations.size() == 1 && _violations.contains(Violation.UTF16_ENCODINGS));
        }

        @Override
        public boolean hasViolations()
        {
            return !_violations.isEmpty();
        }

        @Override
        public boolean hasViolation(Violation violation)
        {
            return _violations.contains(violation);
        }

        @Override
        public Collection<Violation> getViolations()
        {
            return Collections.unmodifiableCollection(_violations);
        }

        @Override
        public String toString()
        {
            return asString();
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
    }

    class Mutable implements HttpURI
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
         * The concept of URI path parameters was originally specified in
         * <a href="https://tools.ietf.org/html/rfc2396#section-3.3">RFC2396</a>, but that was
         * obsoleted by
         * <a href="https://tools.ietf.org/html/rfc3986#section-3.3">RFC3986</a> which removed
         * a normative definition of path parameters. Specifically it excluded them from the
         * <a href="https://tools.ietf.org/html/rfc3986#section-5.2.4">Remove Dot Segments</a>
         * algorithm.  This results in some ambiguity as dot segments can result from later
         * parameter removal or % encoding expansion, that are not removed from the URI
         * by {@link URIUtil#normalizePath(String)}.  Thus this class flags such ambiguous
         * path segments, so that they may be rejected by the server if so configured.
         */
        private static final Index<Boolean> __ambiguousSegments = new Index.Builder<Boolean>()
            .caseSensitive(false)
            .with(".", Boolean.FALSE)
            .with("%2e", Boolean.TRUE)
            .with("%u002e", Boolean.TRUE)
            .with("..", Boolean.FALSE)
            .with(".%2e", Boolean.TRUE)
            .with(".%u002e", Boolean.TRUE)
            .with("%2e.", Boolean.TRUE)
            .with("%2e%2e", Boolean.TRUE)
            .with("%2e%u002e", Boolean.TRUE)
            .with("%u002e.", Boolean.TRUE)
            .with("%u002e%2e", Boolean.TRUE)
            .with("%u002e%u002e", Boolean.TRUE)
            .build();

        private String _scheme;
        private String _user;
        private String _host;
        private int _port;
        private String _path;
        private String _param;
        private String _query;
        private String _fragment;
        private String _uri;
        private String _canonicalPath;
        private final EnumSet<Violation> _violations = EnumSet.noneOf(Violation.class);
        private boolean _emptySegment;

        private Mutable()
        {
        }

        private Mutable(HttpURI uri)
        {
            uri(uri);
        }

        private Mutable(HttpURI baseURI, String pathQuery)
        {
            _uri = null;
            _scheme = baseURI.getScheme();
            _user = baseURI.getUser();
            _host = baseURI.getHost();
            _port = baseURI.getPort();
            if (pathQuery != null)
                parse(State.PATH, pathQuery);
        }

        private Mutable(HttpURI baseURI, String path, String param, String query)
        {
            _uri = null;
            _scheme = baseURI.getScheme();
            _user = baseURI.getUser();
            _host = baseURI.getHost();
            _port = baseURI.getPort();
            if (path != null)
                parse(State.PATH, path);
            if (param != null)
                _param = param;
            if (query != null)
                _query = query;
        }

        private Mutable(String uri)
        {
            _port = -1;
            parse(State.START, uri);
        }

        private Mutable(URI uri)
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
                parse(State.PATH, path);
            _query = uri.getRawQuery();
            _fragment = uri.getRawFragment();
        }

        private Mutable(String scheme, String host, int port, String pathQuery)
        {
            // TODO review if this should be here
            if (port == HttpScheme.getDefaultPort(scheme))
                port = 0;

            _uri = null;

            _scheme = scheme;
            _host = host;
            _port = port;

            if (pathQuery != null)
                parse(State.PATH, pathQuery);
        }

        @Override
        public Immutable asImmutable()
        {
            return new Immutable(this);
        }

        @Override
        public String asString()
        {
            return asImmutable().toString();
        }

        /**
         * @param host the host
         * @param port the port
         * @return this mutable
         */
        public Mutable authority(String host, int port)
        {
            if (host != null && !isPathValidForAuthority(_path))
                throw new IllegalArgumentException("Relative path with authority");
            _user = null;
            _host = host;
            _port = port;
            _uri = null;
            return this;
        }

        /**
         * @param hostPort the host and port combined
         * @return this mutable
         */
        public Mutable authority(String hostPort)
        {
            if (hostPort != null && !isPathValidForAuthority(_path))
                throw new IllegalArgumentException("Relative path with authority");
            HostPort hp = new HostPort(hostPort);
            _user = null;
            _host = hp.getHost();
            _port = hp.getPort();
            _uri = null;
            return this;
        }

        private boolean isPathValidForAuthority(String path)
        {
            if (path == null)
                return true;
            if (path.isEmpty() || "*".equals(path))
                return true;
            return path.startsWith("/");
        }

        public Mutable clear()
        {
            _scheme = null;
            _user = null;
            _host = null;
            _port = -1;
            _path = null;
            _param = null;
            _query = null;
            _fragment = null;
            _uri = null;
            _canonicalPath = null;
            _emptySegment = false;
            _violations.clear();
            return this;
        }

        public Mutable decodedPath(String path)
        {
            _uri = null;
            _path = URIUtil.encodePath(path);
            _canonicalPath = path;
            return this;
        }

        @Override
        public boolean equals(Object o)
        {
            if (o == this)
                return true;
            if (!(o instanceof HttpURI))
                return false;
            return asString().equals(((HttpURI)o).asString());
        }

        public Mutable fragment(String fragment)
        {
            _fragment = fragment;
            return this;
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
            return URIUtil.decodePath(getCanonicalPath());
        }

        @Override
        public String getCanonicalPath()
        {
            if (_canonicalPath == null && _path != null)
                _canonicalPath = URIUtil.canonicalPath(_path);
            return _canonicalPath;
        }

        @Override
        public String getFragment()
        {
            return _fragment;
        }

        @Override
        public String getHost()
        {
            return _host;
        }

        @Override
        public String getParam()
        {
            return _param;
        }

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
        public int hashCode()
        {
            return asString().hashCode();
        }

        public Mutable host(String host)
        {
            if (host != null && !isPathValidForAuthority(_path))
                throw new IllegalArgumentException("Relative path with authority");
            _host = host;
            _uri = null;
            return this;
        }

        @Override
        public boolean isAbsolute()
        {
            return StringUtil.isNotBlank(_scheme);
        }

        @Override
        public boolean isAmbiguous()
        {
            return !_violations.isEmpty() && !(_violations.size() == 1 && _violations.contains(Violation.UTF16_ENCODINGS));
        }

        @Override
        public boolean hasViolations()
        {
            return !_violations.isEmpty();
        }

        @Override
        public boolean hasViolation(Violation violation)
        {
            return _violations.contains(violation);
        }

        @Override
        public Collection<Violation> getViolations()
        {
            return Collections.unmodifiableCollection(_violations);
        }

        public Mutable normalize()
        {
            HttpScheme scheme = _scheme == null ? null : HttpScheme.CACHE.get(_scheme);
            if (scheme != null && _port == scheme.getDefaultPort())
            {
                _port = 0;
                _uri = null;
            }
            return this;
        }

        public Mutable param(String param)
        {
            _param = param;
            if (_path != null && _param != null)
            {
                int lastSlash = _path.lastIndexOf('/');
                if (lastSlash >= 0)
                {
                    int trailingParam = _path.indexOf(';', lastSlash + 1);
                    if (trailingParam >= 0)
                        _path = _path.substring(0, trailingParam);
                }
                _path += ";" + _param;
            }

            _uri = null;
            return this;
        }

        /**
         * @param path the path
         * @return this Mutable
         */
        public Mutable path(String path)
        {
            if (hasAuthority() && !isPathValidForAuthority(path))
                throw new IllegalArgumentException("Relative path with authority");
            if (!URIUtil.isValidPath(path))
                throw new IllegalArgumentException("Path not correctly encoded: " + path);
            _uri = null;
            _path = path;
            _canonicalPath = null;

            // If the passed path does not have a parameter, then keep the current parameter
            // else delete the current parameter
            if (_param != null)
            {
                if (path.indexOf(';') >= 0)
                    _param = null;
                else
                    _path = _path + ';' + _param;
            }

            return this;
        }

        public Mutable pathQuery(String pathQuery)
        {
            if (hasAuthority() && !isPathValidForAuthority(pathQuery))
                throw new IllegalArgumentException("Relative path with authority");
            _uri = null;
            _path = null;
            _canonicalPath = null;
            _param = null;
            _query = null;
            if (pathQuery != null)
                parse(State.PATH, pathQuery);
            return this;
        }

        public Mutable port(int port)
        {
            _port = port;
            _uri = null;
            return this;
        }

        public Mutable query(String query)
        {
            _query = query;
            _uri = null;
            return this;
        }

        public Mutable scheme(HttpScheme scheme)
        {
            return scheme(scheme.asString());
        }

        public Mutable scheme(String scheme)
        {
            _scheme = scheme;
            _uri = null;
            return this;
        }

        @Override
        public String toString()
        {
            return asString();
        }

        public URI toURI()
        {
            try
            {
                return new URI(_scheme, null, _host, _port, _path, _query == null ? null : UrlEncoded.decodeString(_query), null);
            }
            catch (URISyntaxException x)
            {
                throw new RuntimeException(x);
            }
        }

        public Mutable uri(HttpURI uri)
        {
            _scheme = uri.getScheme();
            _user = uri.getUser();
            _host = uri.getHost();
            _port = uri.getPort();
            _path = uri.getPath();
            _param = uri.getParam();
            _query = uri.getQuery();
            _uri = null;
            _canonicalPath = uri.getCanonicalPath();
            _violations.addAll(uri.getViolations());
            return this;
        }

        public Mutable uri(String uri)
        {
            clear();
            _uri = uri;
            parse(State.START, uri);
            return this;
        }

        public Mutable uri(String method, String uri)
        {
            if (HttpMethod.CONNECT.is(method))
            {
                clear();
                parse(State.HOST, uri);
            }
            else if (uri.startsWith("/"))
            {
                clear();
                pathQuery(uri);
            }
            else
                uri(uri);
            return this;
        }

        public Mutable uri(String uri, int offset, int length)
        {
            clear();
            int end = offset + length;
            _uri = uri.substring(offset, end);
            parse(State.START, uri);
            return this;
        }

        public Mutable user(String user)
        {
            _user = user;
            _uri = null;
            return this;
        }

        private void parse(State state, final String uri)
        {
            int mark = 0; // the start of the current section being parsed
            int pathMark = 0; // the start of the path section
            int segment = 0; // the start of the current segment within the path
            boolean encodedPath = false; // set to true if the path contains % encoded characters
            boolean encodedUtf16 = false; // Is the current encoding for UTF16?
            int encodedCharacters = 0; // partial state of parsing a % encoded character<x>
            int encodedValue = 0; // the partial encoded value
            boolean dot = false; // set to true if the path contains . or .. segments
            int end = uri.length();
            _emptySegment = false;
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
                                _violations.add(Violation.UTF16_ENCODINGS);
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
                                        _violations.add(Violation.AMBIGUOUS_PATH_SEPARATOR);
                                        break;
                                    case '%':
                                        _violations.add(Violation.AMBIGUOUS_PATH_ENCODING);
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
                    _canonicalPath = _path;
                else
                    _canonicalPath = _path.substring(0, _path.length() - _param.length() - 1);
            }
            else if (_path != null)
            {
                // The RFC requires this to be canonical before decoding, but this can leave dot segments and dot dot segments
                // which are not canonicalized and could be used in an attempt to bypass security checks.
                _canonicalPath = URIUtil.canonicalPath(_path);
                if (_canonicalPath == null)
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
                _violations.add(Violation.AMBIGUOUS_EMPTY_SEGMENT);

            if (end == segment)
            {
                // Empty segments are not ambiguous if followed by a '#', '?' or end of string.
                if (end >= uri.length() || ("#?".indexOf(uri.charAt(end)) >= 0))
                    return;

                // If this empty segment is the first segment then it is ambiguous.
                if (segment == 0)
                {
                    _violations.add(Violation.AMBIGUOUS_EMPTY_SEGMENT);
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
                // The segment is always ambiguous.
                if (Boolean.TRUE.equals(ambiguous))
                    _violations.add(Violation.AMBIGUOUS_PATH_SEGMENT);
                // The segment is ambiguous only when followed by a parameter.
                if (param)
                    _violations.add(Violation.AMBIGUOUS_PATH_PARAMETER);
            }
        }
    }
}
