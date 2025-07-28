//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.io.Serial;
import java.io.Serializable;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import org.eclipse.jetty.http.UriCompliance.Violation;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;

/**
 * <p>Representation of HTTP URIs.</p>
 * <p>Both {@link Mutable} and {@link Immutable} implementations are available
 * via the static methods such as {@link #build()} and {@link #from(String)},
 * and {@link Unsafe} can be used for tests or invalid URIs.</p>
 * <p>An HTTP URI such as
 * {@code http://user@host:port/path;param1/%2e/f%6fo%2fbar%20bob;param2?query#fragment}
 * is split into the following optional elements:</p>
 * <ul>
 * <li>{@link #getScheme()} - http:</li>
 * <li>{@link #getAuthority()} - //name@host:port</li>
 * <li>{@link #getHost()} - host</li>
 * <li>{@link #getPort()} - port</li>
 * <li>{@link #getPath()} - /path;param1/%2e/f%6fo%2fbar%20bob;param2</li>
 * <li>{@link #getCanonicalPath()} - /path/foo%2Fbar%20bob</li>
 * <li>{@link #getDecodedPath()} - /path/foo/bar bob</li>
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
 * by a file system.</p>
 * <p>Thus this class avoid and/or detects such ambiguities. Furthermore, by decoding characters and
 * removing parameters before relative path normalization, ambiguous paths will be resolved in such
 * a way to be non-standard-but-non-ambiguous to down stream interpretation of the decoded path string.</p>
 * <p>This class collates any {@link UriCompliance.Violation violations} against the specification
 * and/or best practises in the {@link #getViolations()}.  Users of this class should check against a
 * configured {@link UriCompliance} mode if the {@code HttpURI} is suitable for use
 * (see {@link UriCompliance#checkUriCompliance(UriCompliance, HttpURI, ComplianceViolation.Listener)}).</p>
 * <p>For example, implementations that wish to process ambiguous URI paths must configure the compliance
 * modes to accept them and then perform their own decoding of {@link #getPath()}.</p>
 * <p>If there are multiple path parameters, only the last one is returned by {@link #getParam()}.</p>
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

    /**
     * <p>Creates a new {@code HttpURI} with the given arguments.</p>
     *
     * @param scheme the URI scheme (normalized to lower-case)
     * @param host the URI host
     * @param port the URI port, or {@code -1} for no port
     * @param path the URI path
     * @param query the URI query
     * @param fragment the URI fragment
     * @return a new {@code HttpURI}
     */
    static Immutable from(String scheme, String host, int port, String path, String query, String fragment)
    {
        return new Immutable(scheme, host, port, path, query, fragment, false);
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
        return URI.create(toString());
    }

    class Immutable implements HttpURI, Serializable
    {
        @Serial
        private static final long serialVersionUID = 2245620284548399386L;

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
        private Set<Violation> _violations;

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
            if (builder._violations != null)
                _violations = Collections.unmodifiableSet(EnumSet.copyOf(builder._violations));
        }

        private Immutable(String scheme, String host, int port, String path, String query, String fragment, boolean unsafe)
        {
            _uri = null;
            _scheme = unsafe ? scheme : URIUtil.normalizeScheme(scheme);
            _user = null;
            _host = host;
            _port = unsafe || port > 0 ? port : URIUtil.UNDEFINED_PORT;
            _path = path;
            _canonicalPath = unsafe || path == null ? path : URIUtil.canonicalPath(path);
            _param = null;
            _query = query;
            _fragment = fragment;
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

                int normalizedPort = URIUtil.normalizePortForScheme(_scheme, _port);
                if (normalizedPort > 0)
                    out.append(':').append(normalizedPort);

                // we output even if the input is an empty string (to match java URI / URL behaviors)
                boolean hasQuery = _query != null;
                boolean hasFragment = _fragment != null;

                if (_path != null)
                    out.append(_path);
                else if (hasQuery || hasFragment)
                    out.append('/');

                if (hasQuery)
                    out.append('?').append(_query);

                if (hasFragment)
                    out.append('#').append(_fragment);

                if (!out.isEmpty())
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
            return _violations != null && UriCompliance.isAmbiguous(_violations);
        }

        @Override
        public boolean hasViolations()
        {
            return _violations != null && !_violations.isEmpty();
        }

        @Override
        public boolean hasViolation(Violation violation)
        {
            return _violations != null && _violations.contains(violation);
        }

        @Override
        public Collection<Violation> getViolations()
        {
            return _violations == null ? Collections.emptySet() : _violations;
        }

        @Override
        public String toString()
        {
            return asString();
        }
    }

    /**
     * <p>An unsafe {@link HttpURI} that accepts URI parts without checking whether they are valid.</p>
     * <p>This class should be primarily used for testing, since possibly invalid URI may break
     * arbitrary code.</p>
     */
    class Unsafe extends Immutable
    {
        /**
         * <p>Creates a new unsafe {@link HttpURI} with the given arguments.</p>
         *
         * @param scheme the URI scheme
         * @param host the URI host
         * @param port the URI port, or {@code -1} for no port
         * @param path the URI path
         * @param query the URI query
         * @param fragment the URI fragment
         */
        public Unsafe(String scheme, String host, int port, String path, String query, String fragment)
        {
            super(scheme, host, port, path, query, fragment, true);
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

        private static final boolean[] __suspiciousPathCharacters;

        private static final boolean[] __unreservedPctEncodedSubDelims;

        private static final boolean[] __pathCharacters;

        private static boolean isDigit(char c)
        {
            return (c >= '0') && (c <= '9');
        }

        private static boolean isHexDigit(char c)
        {
            return (((c >= 'a') && (c <= 'f')) || // ALPHA (lower)
                ((c >= 'A') && (c <= 'F')) ||  // ALPHA (upper)
                ((c >= '0') && (c <= '9')));
        }

        private static boolean isUnreserved(char c)
        {
            return (((c >= 'a') && (c <= 'z')) || // ALPHA (lower)
                ((c >= 'A') && (c <= 'Z')) ||  // ALPHA (upper)
                ((c >= '0') && (c <= '9')) || // DIGIT
                (c == '-') || (c == '.') || (c == '_') || (c == '~'));
        }

        private static boolean isSubDelim(char c)
        {
            return c == '!' || c == '$' || c == '&' || c == '\'' || c == '(' || c == ')' || c == '*' || c == '+' || c == ',' || c == ';' || c == '=';
        }

        static boolean isUnreservedPctEncodedOrSubDelim(char c)
        {
            return c < __unreservedPctEncodedSubDelims.length && __unreservedPctEncodedSubDelims[c];
        }

        static
        {
            // Establish allowed and disallowed characters per the path rules of
            // https://datatracker.ietf.org/doc/html/rfc3986#section-3.3
            // ABNF
            //   path          = path-abempty    ; begins with "/" or is empty
            //                 / path-absolute   ; begins with "/" but not "//"
            //                 / path-noscheme   ; begins with a non-colon segment
            //                 / path-rootless   ; begins with a segment
            //                 / path-empty      ; zero characters
            //   path-abempty  = *( "/" segment )
            //   path-absolute = "/" [ segment-nz *( "/" segment ) ]
            //   path-noscheme = segment-nz-nc *( "/" segment )
            //   path-rootless = segment-nz *( "/" segment )
            //   path-empty    = 0<pchar>
            //
            //   segment       = *pchar
            //   segment-nz    = 1*pchar
            //   segment-nz-nc = 1*( unreserved / pct-encoded / sub-delims / "@" )
            //                 ; non-zero-length segment without any colon ":"
            //   pchar         = unreserved / pct-encoded / sub-delims / ":" / "@"
            //   pct-encoded   = "%" HEXDIG HEXDIG
            //
            //   unreserved    = ALPHA / DIGIT / "-" / "." / "_" / "~"
            //   reserved      = gen-delims / sub-delims
            //   gen-delims    = ":" / "/" / "?" / "#" / "[" / "]" / "@"
            //   sub-delims    = "!" / "$" / "&" / "'" / "(" / ")"
            //                 / "*" / "+" / "," / ";" / "="
            //
            //   authority     = [ userinfo "@" ] host [ ":" port ]
            //   userinfo      = *( unreserved / pct-encoded / sub-delims / ":" )
            //   host          = IP-literal / IPv4address / reg-name
            //   port          = *DIGIT
            //
            //   reg-name      = *( unreserved / pct-encoded / sub-delims )
            //
            // we are limited to US-ASCII per https://datatracker.ietf.org/doc/html/rfc3986#section-2
            __unreservedPctEncodedSubDelims = new boolean[128];
            __pathCharacters = new boolean[128];

            for (int i = 0; i < __pathCharacters.length; i++)
            {
                char c = (char)i;

                __unreservedPctEncodedSubDelims[i] = isUnreserved(c) || c == '%' || isSubDelim(c);
                __pathCharacters[i] = __unreservedPctEncodedSubDelims[i] ||  c == ':' || c == '@';
            }

            // suspicious path characters
            __suspiciousPathCharacters = new boolean[128];
            __suspiciousPathCharacters['\\'] = true;
            __suspiciousPathCharacters[0x7F] = true;
            for (int i = 0; i <= 0x1F; i++)
                __suspiciousPathCharacters[i] = true;
        }

        private String _scheme;
        private String _user;
        private String _host;
        private int _port = URIUtil.UNDEFINED_PORT;
        private String _path;
        private String _param;
        private String _query;
        private String _fragment;
        private String _uri;
        private String _canonicalPath;
        private Set<Violation> _violations;
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
            if (_user != null)
                _violations = EnumSet.of(Violation.USER_INFO);
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
            if (_user != null)
                _violations = EnumSet.of(Violation.USER_INFO);
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
            parse(State.START, uri);
        }

        private Mutable(URI uri)
        {
            _uri = null;

            _scheme = URIUtil.normalizeScheme(uri.getScheme());
            _host = uri.getHost();
            if (_host == null && uri.getRawSchemeSpecificPart().startsWith("//"))
                _host = "";
            _port = uri.getPort();
            _user = uri.getUserInfo();
            if (_user != null)
                _violations = EnumSet.of(Violation.USER_INFO);
            String path = uri.getRawPath();
            if (path != null)
                parse(State.PATH, path);
            _query = uri.getRawQuery();
            _fragment = uri.getRawFragment();
        }

        private Mutable(String scheme, String host, int port, String pathQuery)
        {
            _uri = null;

            _scheme = URIUtil.normalizeScheme(scheme);
            _host = host;
            _port = (port > 0) ? port : URIUtil.UNDEFINED_PORT;

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
            _port = (port > 0) ? port : URIUtil.UNDEFINED_PORT;
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
            _port = URIUtil.UNDEFINED_PORT;
            _path = null;
            _param = null;
            _query = null;
            _fragment = null;
            _uri = null;
            _canonicalPath = null;
            _emptySegment = false;
            if (_violations != null)
                _violations.clear();
            return this;
        }

        public Mutable decodedPath(String path)
        {
            _uri = null;
            _path = URIUtil.encodePath(path);
            _canonicalPath = URIUtil.canonicalPath(_path);
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
            return _violations != null && UriCompliance.isAmbiguous(_violations);
        }

        @Override
        public boolean hasViolations()
        {
            return _violations != null && !_violations.isEmpty();
        }

        @Override
        public boolean hasViolation(Violation violation)
        {
            return _violations != null && _violations.contains(violation);
        }

        @Override
        public Collection<Violation> getViolations()
        {
            return _violations == null ? Collections.emptySet() : Collections.unmodifiableCollection(_violations);
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
            if (!URIUtil.isPathValid(path))
                throw new IllegalArgumentException("Path not correctly encoded: " + path);
            // since we are resetting the path, lets clear out the path specific violations.
            if (_violations != null)
                _violations.removeIf(UriCompliance::isPathViolation);
            _uri = null;
            _path = null;
            _canonicalPath = null;
            String param = _param;
            _param = null;
            parse(State.PATH, path);

            // If the passed path does not have a parameter, then keep the current parameter
            // else delete the current parameter
            if (param != null && path.indexOf(';') < 0)
            {
                _param = param;
                _path = _path + ';' + _param;
            }

            return this;
        }

        public Mutable pathQuery(String pathQuery)
        {
            if (hasAuthority() && !isPathValidForAuthority(pathQuery))
                throw new IllegalArgumentException("Relative path with authority");
            // since we are resetting the path, lets clear out the path specific violations.
            if (_violations != null)
                _violations.removeIf(UriCompliance::isPathViolation);
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
            _port = (port > 0) ? port : URIUtil.UNDEFINED_PORT;
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
            _scheme = URIUtil.normalizeScheme(scheme);
            _uri = null;
            return this;
        }

        @Override
        public String toString()
        {
            return asString();
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
            Collection<Violation> violations = uri.getViolations();
            if (!violations.isEmpty())
                _violations = EnumSet.copyOf(violations);
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
                _path = null;
                _query = null;
                _param = null;
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
            if (user == null)
                removeViolation(Violation.USER_INFO);
            else
                addViolation(Violation.USER_INFO);
            _uri = null;
            return this;
        }

        private void parse(State state, final String uri)
        {
            int mark = 0; // the start of the current section being parsed
            int pathMark = 0; // the start of the path section
            int segment = 0; // the start of the current segment within the path
            boolean encoded = false; // set to true if the string contains % encoded characters
            boolean encodedUtf16 = false; // Is the current encoding for UTF16?
            int encodedCharacters = 0; // partial state of parsing a % encoded character<x>
            int encodedValue = 0; // the partial encoded value
            boolean dot = false; // set to true if the path contains . or .. segments
            int end = uri.length();
            boolean password = false;
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
                                checkSegment(uri, false, segment, i, true);
                                mark = i + 1;
                                state = State.PARAM;
                                break;
                            case '?':
                                // assume empty path (if seen at start)
                                checkSegment(uri, false, segment, i, false);
                                _path = "";
                                mark = i + 1;
                                state = State.QUERY;
                                break;
                            case '#':
                                // assume empty path (if seen at start)
                                checkSegment(uri, false, segment, i, false);
                                _path = "";
                                mark = i + 1;
                                state = State.FRAGMENT;
                                break;
                            case '*':
                                _path = "*";
                                state = State.ASTERISK;
                                break;
                            case '%':
                                encoded = true;
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
                                _scheme = URIUtil.normalizeScheme(uri.substring(mark, i));
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
                                checkSegment(uri, false, segment, i, false);
                                _path = uri.substring(mark, i);
                                mark = i + 1;
                                state = State.QUERY;
                                break;
                            case '%':
                                // must have been in an encoded path
                                encoded = true;
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
                            case '?':
                            case '#':
                                if (encodedCharacters > 0 || password)
                                    throw new IllegalArgumentException("Bad authority");
                                _host = uri.substring(mark, i);
                                encoded = false;
                                if (c == '/')
                                {
                                    pathMark = mark = i;
                                    segment = mark + 1;
                                    state = State.PATH;
                                }
                                else
                                {
                                    mark = i + 1;
                                    _path = "";
                                    state = switch (c)
                                    {
                                        case '?' -> State.QUERY;
                                        case '#' -> State.FRAGMENT;
                                        default -> throw new IllegalArgumentException("Bad authority");
                                    };
                                }
                                break;
                            case ';':
                                throw new IllegalArgumentException("Bad authority");
                            case ':':
                                if (encodedCharacters > 0 || password)
                                    throw new IllegalArgumentException("Bad authority");
                                if (i > mark)
                                    _host = uri.substring(mark, i);
                                mark = i + 1;
                                state = State.PORT;
                                break;
                            case '@':
                                if (encodedCharacters > 0)
                                    throw new IllegalArgumentException("Bad authority");
                                _user = uri.substring(mark, i);
                                addViolation(Violation.USER_INFO);
                                password = false;
                                encoded = false;
                                mark = i + 1;
                                break;
                            case '[':
                                if (i != mark)
                                    throw new IllegalArgumentException("Bad authority");
                                state = State.IPV6;
                                break;
                            case '%':
                                if (encodedCharacters > 0)
                                    throw new IllegalArgumentException("Bad authority");
                                encoded = true;
                                encodedCharacters = 2;
                                break;
                            default:
                                if (encodedCharacters > 0)
                                {
                                    encodedCharacters--;
                                    if (!isHexDigit(c))
                                        throw new IllegalArgumentException("Bad authority");
                                }
                                else if (!isUnreservedPctEncodedOrSubDelim(c))
                                {
                                    throw new IllegalArgumentException("Bad authority");
                                }
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
                            case ':':
                                break;
                            default:
                                if (!isHexDigit(c))
                                    throw new IllegalArgumentException("Bad authority");
                                break;
                        }
                        break;
                    }
                    case PORT:
                    {
                        switch (c)
                        {
                            case '@' ->
                            {
                                if (_user != null)
                                    throw new IllegalArgumentException("Bad authority");
                                // It wasn't a port, but a password!
                                _user = _host + ":" + uri.substring(mark, i);
                                addViolation(Violation.USER_INFO);
                                mark = i + 1;
                                state = State.HOST;
                            }
                            case '/' ->
                            {
                                _port = TypeUtil.parseInt(uri, mark, i - mark, 10);
                                pathMark = mark = i;
                                segment = mark + 1;
                                state = State.PATH;
                            }
                            case '?', '#' ->
                            {
                                _port = TypeUtil.parseInt(uri, mark, i - mark, 10);
                                mark = i + 1;
                                _path = "";
                                state = switch (c)
                                {
                                    case '?' -> State.QUERY;
                                    case '#' -> State.FRAGMENT;
                                    default -> throw new IllegalStateException();
                                };
                            }
                            case ';' ->
                            {
                                throw new IllegalArgumentException("Bad authority");
                            }
                            default ->
                            {
                                if (!isDigit(c))
                                {
                                    if (isUnreservedPctEncodedOrSubDelim(c))
                                    {
                                        // must be a password
                                        password = true;
                                        state = State.HOST;
                                        if (_host != null)
                                        {
                                            mark = mark - _host.length() - 1;
                                            _host = null;
                                        }
                                        break;
                                    }
                                    throw new IllegalArgumentException("Bad authority");
                                }
                            }
                        }
                        break;
                    }
                    case PATH:
                    {
                        if (encodedCharacters > 0)
                        {
                            if (encodedCharacters == 2 && c == 'u' && !encodedUtf16)
                            {
                                addViolation(Violation.UTF16_ENCODINGS);
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
                                        addViolation(Violation.AMBIGUOUS_PATH_SEPARATOR);
                                        break;
                                    case '%':
                                        addViolation(Violation.AMBIGUOUS_PATH_ENCODING);
                                        break;
                                    default:
                                        if (encodedValue < __suspiciousPathCharacters.length && __suspiciousPathCharacters[encodedValue])
                                            addViolation(Violation.SUSPICIOUS_PATH_CHARACTERS);
                                        break;
                                }
                            }
                        }
                        else
                        {
                            switch (c)
                            {
                                case ';':
                                    checkSegment(uri, dot || encoded, segment, i, true);
                                    mark = i + 1;
                                    state = State.PARAM;
                                    break;
                                case '?':
                                    checkSegment(uri, dot || encoded, segment, i, false);
                                    _path = uri.substring(pathMark, i);
                                    mark = i + 1;
                                    state = State.QUERY;
                                    break;
                                case '#':
                                    checkSegment(uri, dot || encoded, segment, i, false);
                                    _path = uri.substring(pathMark, i);
                                    mark = i + 1;
                                    state = State.FRAGMENT;
                                    break;
                                case '/':
                                    // There is no leading segment when parsing only a path that starts with slash.
                                    if (i != 0)
                                        checkSegment(uri, dot || encoded, segment, i, false);
                                    segment = i + 1;
                                    break;
                                case '.':
                                    dot |= segment == i;
                                    break;
                                case '%':
                                    encoded = true;
                                    encodedUtf16 = false;
                                    encodedCharacters = 2;
                                    encodedValue = 0;
                                    break;
                                default:
                                    // The RFC does not allow unencoded path characters that are outside the ABNF
                                    if (c > __pathCharacters.length || !__pathCharacters[c])
                                        addViolation(Violation.ILLEGAL_PATH_CHARACTERS);
                                    if (c < __suspiciousPathCharacters.length && __suspiciousPathCharacters[c])
                                       addViolation(Violation.SUSPICIOUS_PATH_CHARACTERS);
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
                                encoded = true;
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
                    checkSegment(uri, false, segment, end, false);
                    break;
                case ASTERISK:
                    break;
                case SCHEME_OR_PATH:
                case HOST_OR_PATH:
                    _path = uri.substring(mark, end);
                    break;
                case HOST:
                    if (end > mark)
                    {
                        _host = uri.substring(mark, end);
                        _path = "";
                    }
                    break;
                case IPV6:
                    throw new IllegalArgumentException("No closing ']' for ipv6 in " + uri);
                case PORT:
                    _port = TypeUtil.parseInt(uri, mark, end - mark, 10);
                    _path = "";
                    break;
                case PARAM:
                    _path = uri.substring(pathMark, end);
                    _param = uri.substring(mark, end);
                    break;
                case PATH:
                    checkSegment(uri, dot || encoded, segment, end, false);
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

            if (!encoded && !dot)
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
                _canonicalPath = URIUtil.canonicalPath(_path, this::onBadUtf8);
                if (_canonicalPath == null)
                    throw new IllegalArgumentException("Bad URI");
            }
        }

        private RuntimeException onBadUtf8()
        {
            // We just remember the violation and return null so nothing is thrown
            addViolation(Violation.BAD_UTF8_ENCODING);
            return null;
        }

        /**
         * Check for ambiguous path segments.
         *
         * An ambiguous path segment is one that is perhaps technically legal, but is considered undesirable to handle
         * due to possible ambiguity.  Examples include segments like '..;', '%2e', '%2e%2e' etc.
         *
         * @param uri The URI string
         * @param dotOrEncoded true if the URI might contain dot segments
         * @param segment The inclusive starting index of the segment (excluding any '/')
         * @param end The exclusive end index of the segment
         */
        private void checkSegment(String uri, boolean dotOrEncoded, int segment, int end, boolean param)
        {
            // This method is called once for every segment parsed.
            // A URI like "/foo/" has two segments: "foo" and an empty segment.
            // Empty segments are only ambiguous if they are not the last segment
            // So if this method is called for any segment and we have previously
            // seen an empty segment, then it was ambiguous.
            if (_emptySegment)
                addViolation(Violation.AMBIGUOUS_EMPTY_SEGMENT);

            if (end == segment)
            {
                // Empty segments are not ambiguous if followed by a '#', '?' or end of string.
                if (end >= uri.length() || ("#?".indexOf(uri.charAt(end)) >= 0))
                    return;

                // If this empty segment is the first segment then it is ambiguous.
                if (segment == 0)
                {
                    addViolation(Violation.AMBIGUOUS_EMPTY_SEGMENT);
                    return;
                }

                // Otherwise remember we have seen an empty segment, which is checked if we see a subsequent segment.
                if (!_emptySegment)
                {
                    _emptySegment = true;
                    return;
                }
            }

            // Look for segment in the ambiguous segment index.
            Boolean ambiguous = dotOrEncoded ? __ambiguousSegments.get(uri, segment, end - segment) : null;
            if (ambiguous != null)
            {
                // The segment is always ambiguous.
                if (ambiguous)
                    addViolation(Violation.AMBIGUOUS_PATH_SEGMENT);
                // The segment is ambiguous only when followed by a parameter.
                if (param)
                    addViolation(Violation.AMBIGUOUS_PATH_PARAMETER);
            }
        }

        private void addViolation(Violation violation)
        {
            if (_violations == null)
                _violations = EnumSet.of(violation);
            else 
                _violations.add(violation);
        }

        private void removeViolation(Violation violation)
        {
            if (_violations == null)
                return;
            _violations.remove(violation);
        }
    }
}
