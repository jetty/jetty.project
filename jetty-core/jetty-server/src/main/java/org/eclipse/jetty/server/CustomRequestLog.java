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

package org.eclipse.jetty.server;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.util.DateCache;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodType.methodType;

/**
 * <p>A flexible RequestLog, which produces log strings in a customizable format.</p>
 * <p>The Logger takes a format string where request characteristics can be added using "%" format codes which are
 * replaced by the corresponding value in the log output.</p>
 * <p>The terms server, client, local and remote are used to refer to the different addresses and ports
 * which can be logged. Server and client refer to the logical addresses which can be modified in the request
 * headers. Where local and remote refer to the physical addresses which may be a proxy between the
 * end-user and the server.</p>
 *
 * <!-- tag::documentation[] -->
 * <p>Format codes are specified with the syntax <code>%MODIFIERS{PARAM}CODE</code> as follows:</p>
 * <dl>
 * <dt>MODIFIERS</dt>
 * <dd>Optional list of comma separated HTTP status codes which may be preceded by a single "!" to indicate
 * negation. If the status code is not in the list the literal string "-" will be logged instead of
 * the resulting value from the percent code.</dd>
 * <dt>{PARAM}</dt>
 * <dd>Parameter string which may be optional depending on the percent code used.</dd>
 * <dt>CODE</dt>
 * <dd>A one or two character code specified by the table of format codes below.</dd>
 * </dl>
 *
 * <table>
 * <caption>Format Codes</caption>
 * <tr>
 * <th>Format String</th>
 * <th>Description</th>
 * </tr>
 * <tr>
 * <td>X</td>
 * <td>
 * <p>The X character.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%%</td>
 * <td>
 * <p>The percent character.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%{format}a</td>
 * <td>
 * <p>The address or host name.</p>
 * <p>Valid format values are: "server", "client", "local", "remote".
 * The format parameter is optional and defaults to "server".</p>
 * <p>Values "server" and "client" are the logical addresses which can be modified in the request headers,
 * while "local" and "remote" are the physical addresses so may be the addresses of a proxy between
 * the end-user and the server.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%{format}p</td>
 * <td>
 * <p>The port.</p>
 * <p>Valid format values are: "server", "client", "local", "remote".
 * The format parameter is optional and defaults to "server".</p>
 * <p>Values "server" and "client" are the logical ports which can be modified in the request headers,
 * while "local" and "remote" are the physical ports so may be the ports of a proxy between
 * the end-user and the server.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%{CLF}I</td>
 * <td>
 * <p>The size of request in bytes, excluding HTTP headers.</p>
 * <p>The parameter is optional.
 * When the parameter value is "CLF" the Common Log Format is used, i.e. a {@code -} rather than a {@code 0}
 * when no bytes are present.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%{CLF}O</td>
 * <td>
 * <p>The size of response in bytes, excluding HTTP headers.</p>
 * <p>The parameter is optional.
 * When the parameter value is "CLF" the Common Log Format is used, i.e. a {@code -} rather than a {@code 0}
 * when no bytes are present.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%{CLF}S</td>
 * <td>
 * <p>The bytes transferred (received and sent). This is the combination of {@code %I} and {@code %O}.</p>
 * <p>The parameter is optional.
 * When the parameter value is "CLF" the Common Log Format is used, i.e. a {@code -} rather than a {@code 0}
 * when no bytes are present.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%{VARNAME}C</td>
 * <td>
 * <p>The value of the request cookie VARNAME.</p>
 * <p>The parameter is optional.
 * Only version 0 cookies are fully supported.
 * When the parameter is missing, all request cookies will be logged.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%D</td>
 * <td>
 * <p>The time taken to serve the request, in microseconds.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%{VARNAME}e</td>
 * <td>
 * <p>The value of the environment variable VARNAME.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%f</td>
 * <td>
 * <p>The file system path of the requested resource.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%H</td>
 * <td>
 * <p>The name and version of the request protocol, such as "HTTP/1.1".</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%{VARNAME}i</td>
 * <td>
 * <p>The value of the VARNAME request header.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%k</td>
 * <td>
 * <p>The number of requests handled on a connection.</p>
 * <p>The initial request on a connection yields a value 0, the first request after the initial on the same connection
 * yields the value 1, the second request on the same connection yields the value 2, etc.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%m</td>
 * <td>
 * <p>The HTTP request method.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%{VARNAME}o</td>
 * <td>
 * <p>The value of the VARNAME response header.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%q</td>
 * <td>
 * <p>The query string, prepended with a ? if a query string exists, otherwise an empty string.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%r</td>
 * <td>
 * <p>First line of an HTTP/1.1 request (or equivalent information for HTTP/2 or later).</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%R</td>
 * <td>
 * <p>The name of the Handler or Servlet generating the response (if any).</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%s</td>
 * <td>
 * <p>The HTTP response status code.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%{format|timeZone|locale}t</td>
 * <td>
 * <p>The time at which the request was received.</p>
 * <p>The parameter is optional and may have the following values: {format}, {format|timeZone} or {format|timeZone|locale}.</p>
 * <dl>
 * <dt>format</dt>
 * <dd>Default is e.g. [18/Sep/2011:19:18:28 -0400] where the last number indicates the timezone offset from GMT.
 * Must be in a format supported by the {@code java.time} package.</dd>
 * <dt>timeZone</dt>
 * <dd>Default is GMT.
 * Must be in a format supported by the {@code java.time} package.</dd>
 * <dt>locale</dt>
 * <dd>Default is the JVM default locale.
 * Must be in a format supported by {@code java.util.Locale.forLanguageTag()}.</dd>
 * </dl>
 * </td>
 * </tr>
 * <tr>
 * <td>%{UNIT}T</td>
 * <td>
 * <p>The time taken to serve the request.</p>
 * <p>The parameter UNIT is optional and defaults to "s".
 * The parameter UNIT indicates the unit of time: "s" for seconds, "ms" for milliseconds, "us" for microseconds.
 * <code>%{us}T</code> is identical to {@code %D}.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%{d}u</td>
 * <td>
 * <p>The remote user if the request was authenticated with servlet authentication.</p>
 * <p>May be an invalid value if response status code ({@code %s}) is 401 (unauthorized).</p>
 * <p>The parameter is optional.
 * When the parameter value is "d", deferred authentication will also be checked.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%U</td>
 * <td>
 * <p>The URL path requested, not including any query string.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%X</td>
 * <td>
 * <p>The connection status when response is completed:</p>
 * <dl>
 * <dt>X</dt>
 * <dd>The connection is aborted before the response completed.</dd>
 * <dt>+</dt>
 * <dd>The connection may be kept alive after the response is sent.</dd>
 * <dt>-</dt>
 * <dd>The connection will be closed after the response is sent.</dd>
 * </dl>
 * </td>
 * </tr>
 * <tr>
 * <td>%{VARNAME}ti</td>
 * <td>
 * <p>The value of the VARNAME request trailer.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%{VARNAME}to</td>
 * <td>
 * <p>The value of the VARNAME response trailer.</p>
 * </td>
 * </tr>
 * <tr>
 * <td>%{OPTION}uri</td>
 * <td>
 * <p>The request URI.</p>
 * <p>The parameter is optional and may have the be one of the following options:</p>
 * <dl>
 * <dt>%uri</dt>
 * <dd>The entire request URI.</dd>
 * <dt>%{-query}uri</dt>
 * <dd>The entire request URI without the query.</dd>
 * <dt>%{-path,-query}uri</dt>
 * <dd>The request URI without path or query (so just `scheme://authority`).</dd>
 * <dt>%{scheme}uri</dt>
 * <dd>The scheme of the request URI.</dd>
 * <dt>%{authority}uri</dt>
 * <dd>The authority of the request URI.</dd>
 * <dt>%{path}uri</dt>
 * <dd>The path of the request URI.</dd>
 * <dt>%{query}uri</dt>
 * <dd>The query of the request URI.</dd>
 * <dt>%{host}uri</dt>
 * <dd>The host of the request URI.</dd>
 * <dt>%{port}uri</dt>
 * <dd>The port of the request URI.</dd>
 * </dl>
 * </td>
 * </tr>
 * <tr>
 * <td>%{attributeName}attr</td>
 * <td>
 * <p>The value of the request attribute with the given name.</p>
 * </td>
 * </tr>
 * </table>
 * <!-- end::documentation[] -->
 */
@ManagedObject("Custom format request log")
public class CustomRequestLog extends ContainerLifeCycle implements RequestLog
{
    /**
     * Record holding extra detail for logging
     * @param handlerName The name of the entity that handled the request
     * @param realPath The real path on the filesystem represented by the request
     */
    public record LogDetail(String handlerName, String realPath)
    {
    }

    public static final String DEFAULT_DATE_FORMAT = "dd/MMM/yyyy:HH:mm:ss ZZZ";
    public static final String NCSA_FORMAT = "%{client}a - %u %t \"%r\" %s %O";
    public static final String EXTENDED_NCSA_FORMAT = NCSA_FORMAT + " \"%{Referer}i\" \"%{User-Agent}i\"";
    public static final String LOG_DETAIL = CustomRequestLog.class.getName() + ".logDetail";
    private static final Logger LOG = LoggerFactory.getLogger(CustomRequestLog.class);
    private static final ThreadLocal<StringBuilder> _buffers = ThreadLocal.withInitial(() -> new StringBuilder(256));
    private static final Pattern PATTERN = Pattern.compile("^(?:%(?<MOD>!?[0-9,]+)?(?:\\{(?<ARG>[^}]+)})?(?<CODE>(?:(?:ti)|(?:to)|(?:uri)|(?:attr)|[a-zA-Z%]))|(?<LITERAL>[^%]+))(?<REMAINING>.*)", Pattern.DOTALL | Pattern.MULTILINE);

    private final RequestLog.Writer _requestLogWriter;
    private final MethodHandle _logHandle;
    private final String _formatString;
    private transient PathMappings<String> _ignorePathMap;
    private String[] _ignorePaths;
    private BiPredicate<Request, Response> _filter;

    public CustomRequestLog()
    {
        this(new Slf4jRequestLogWriter(), EXTENDED_NCSA_FORMAT);
    }

    public CustomRequestLog(String file)
    {
        this(file, EXTENDED_NCSA_FORMAT);
    }

    public CustomRequestLog(String file, String format)
    {
        this(new RequestLogWriter(file), format);
    }

    public CustomRequestLog(RequestLog.Writer writer, String formatString)
    {
        _formatString = formatString;
        _requestLogWriter = writer;
        installBean(_requestLogWriter);

        try
        {
            _logHandle = getLogHandle(formatString);
        }
        catch (NoSuchMethodException | IllegalAccessException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * This allows you to set a custom filter to decide whether to log a request or omit it from the request log.
     * This filter is evaluated after path filtering is applied from {@link #setIgnorePaths(String[])}.
     * @param filter - a BiPredicate which returns true if this request should be logged.
     */
    public void setFilter(BiPredicate<Request, Response> filter)
    {
        _filter = filter;
    }

    @ManagedAttribute("The RequestLogWriter")
    public RequestLog.Writer getWriter()
    {
        return _requestLogWriter;
    }

    @Override
    public void log(Request request, Response response)
    {
        try
        {
            if (_ignorePathMap != null && _ignorePathMap.getMatched(request.getHttpURI().getCanonicalPath()) != null)
                return;

            if (_filter != null && !_filter.test(request, response))
                return;

            StringBuilder sb = _buffers.get();
            sb.setLength(0);

            _logHandle.invoke(sb, request, response);

            String log = sb.toString();
            _requestLogWriter.write(log);
        }
        catch (Throwable e)
        {
            LOG.warn("Unable to log request", e);
        }
    }

    /**
     * Set request paths that will not be logged.
     *
     * @param ignorePaths array of request paths
     */
    public void setIgnorePaths(String[] ignorePaths)
    {
        _ignorePaths = ignorePaths;
    }

    /**
     * Retrieve the request paths that will not be logged.
     *
     * @return array of request paths
     */
    public String[] getIgnorePaths()
    {
        return _ignorePaths;
    }

    /**
     * Retrieve the format string.
     *
     * @return the format string
     */
    @ManagedAttribute("format string")
    public String getFormatString()
    {
        return _formatString;
    }

    /**
     * Set up request logging and open log file.
     *
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        if (_ignorePaths != null && _ignorePaths.length > 0)
        {
            _ignorePathMap = new PathMappings<>();
            for (String ignorePath : _ignorePaths)
            {
                _ignorePathMap.put(ignorePath, ignorePath);
            }
        }
        else
            _ignorePathMap = null;

        super.doStart();
    }

    private static void append(StringBuilder buf, String s, boolean quoted)
    {
        if (s == null || s.isEmpty())
        {
            if (!quoted)
                buf.append('-');
        }
        else
        {
            for (int i = 0; i < s.length(); i++)
            {
                char c = s.charAt(i);
                if (c == '\\' || c == '"' || c == ',')
                {
                    if (!quoted)
                        buf.append('"');
                    for (int j = 0; j < s.length(); ++j)
                    {
                        c = s.charAt(j);
                        if (c == '"' || c == '\\')
                            buf.append('\\').append(c);
                        else
                            buf.append(c);
                    }
                    if (!quoted)
                        buf.append('"');
                    return;
                }
            }

            // no special delimiters used, no quote needed.
            buf.append(s);
        }
    }

    private static void logLiteral(String s, StringBuilder buf)
    {
        if (s != null && !s.isEmpty())
            buf.append(s);
    }

    private MethodHandle getLogHandle(String formatString) throws NoSuchMethodException, IllegalAccessException
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle logLiteral = lookup.findStatic(CustomRequestLog.class, "logLiteral", methodType(void.class, String.class, StringBuilder.class));
        MethodHandle logHandle = lookup.findStatic(CustomRequestLog.class, "logNothing", methodType(void.class, StringBuilder.class, Request.class, Response.class, Boolean.TYPE));
        logHandle = MethodHandles.insertArguments(logHandle, logHandle.type().parameterCount() - 1, false);

        List<Token> tokens = getTokens(formatString);
        Collections.reverse(tokens);

        boolean quoted = false;
        for (Token t : tokens)
        {
            if (t.isLiteralString())
            {
                logHandle = updateLogHandle(logHandle, logLiteral, t.literal);
                if (t.isQuote())
                    quoted = !quoted;
            }
            else if (t.isPercentCode())
                logHandle = updateLogHandle(logHandle, logLiteral, lookup, t.code, t.arg, t.modifiers, t.negated, quoted);
            else
                throw new IllegalStateException("bad token " + t);
        }

        return logHandle;
    }

    private static List<Token> getTokens(String formatString)
    {
        /*
        Extracts literal strings and percent codes out of the format string.
        We will either match a percent code of the format %MODIFIERS{PARAM}CODE, or a literal string
        until the next percent code or the end of the formatString is reached.

        where
            MODIFIERS is an optional comma separated list of numbers.
            {PARAM} is an optional string parameter to the percent code.
            CODE is a 1 to 2 character string corresponding to a format code.
         */
        List<Token> tokens = new ArrayList<>();
        String remaining = formatString;
        while (!remaining.isEmpty())
        {
            Matcher m = PATTERN.matcher(remaining);
            if (m.matches())
            {
                if (m.group("CODE") != null)
                {
                    String code = m.group("CODE");
                    String arg = m.group("ARG");
                    String modifierString = m.group("MOD");

                    List<Integer> modifiers = null;
                    boolean negated = false;
                    if (modifierString != null)
                    {
                        if (modifierString.startsWith("!"))
                        {
                            modifierString = modifierString.substring(1);
                            negated = true;
                        }

                        modifiers = new QuotedCSV(modifierString)
                            .getValues()
                            .stream()
                            .map(Integer::parseInt)
                            .collect(Collectors.toList());
                    }

                    tokens.add(new Token(code, arg, modifiers, negated));
                }
                else if (m.group("LITERAL") != null)
                {
                    String literal = m.group("LITERAL");
                    tokens.add(new Token(literal));
                }
                else
                {
                    throw new IllegalStateException("formatString parsing error: " + formatString);
                }

                remaining = m.group("REMAINING");
            }
            else
            {
                throw new IllegalArgumentException("Invalid format string: " + formatString);
            }
        }

        return tokens;
    }

    private static class Token
    {
        public final String code;
        public final String arg;
        public final List<Integer> modifiers;
        public final boolean negated;
        public final boolean quote;

        public final String literal;

        public Token(String code, String arg, List<Integer> modifiers, boolean negated)
        {
            this.code = code;
            this.arg = arg;
            this.modifiers = modifiers;
            this.negated = negated;
            this.literal = null;
            this.quote = false;
        }

        public Token(String literal)
        {
            this.code = null;
            this.arg = null;
            this.modifiers = null;
            this.negated = false;
            this.literal = literal;
            boolean quote = false;
            for (int i = 0; i < literal.length(); i++)
                if (literal.charAt(i) == '"')
                    quote = !quote;
            this.quote = quote;
        }

        public boolean isLiteralString()
        {
            return (literal != null);
        }

        public boolean isPercentCode()
        {
            return (code != null);
        }
        
        public boolean isQuote()
        {
            return quote;
        }
    }

    @SuppressWarnings("unused")
    private static boolean modify(List<Integer> modifiers, Boolean negated, StringBuilder b, Request request, Response response)
    {
        if (negated)
            return !modifiers.contains(response.getStatus());
        else
            return modifiers.contains(response.getStatus());
    }

    private MethodHandle updateLogHandle(MethodHandle logHandle, MethodHandle append, String literal)
    {
        return foldArguments(logHandle, dropArguments(dropArguments(append.bindTo(literal), 1, Request.class), 2, Response.class));
    }

    private MethodHandle updateLogHandle(MethodHandle logHandle, MethodHandle append, MethodHandles.Lookup lookup, String code, String arg, List<Integer> modifiers, boolean negated, boolean quoted) throws NoSuchMethodException, IllegalAccessException
    {
        MethodType logType = methodType(void.class, StringBuilder.class, Request.class, Response.class, Boolean.TYPE);
        MethodType logTypeArg = methodType(void.class, String.class, StringBuilder.class, Request.class, Response.class, Boolean.TYPE);

        //TODO should we throw IllegalArgumentExceptions when given arguments for codes which do not take them
        MethodHandle specificHandle = switch (code)
        {
            case "%" -> lookup.findStatic(CustomRequestLog.class, "logPercent", logType);
            case "a" ->
            {
                if (StringUtil.isEmpty(arg))
                    arg = "server";

                String method = switch (arg)
                {
                    case "server" -> "logServerHost";
                    case "client" -> "logClientHost";
                    case "local" -> "logLocalHost";
                    case "remote" -> "logRemoteHost";
                    default -> throw new IllegalArgumentException("Invalid arg for %a");
                };

                yield lookup.findStatic(CustomRequestLog.class, method, logType);
            }
            case "p" ->
            {
                if (StringUtil.isEmpty(arg))
                    arg = "server";

                String method = switch (arg)
                {
                    case "server" -> "logServerPort";
                    case "client" -> "logClientPort";
                    case "local" -> "logLocalPort";
                    case "remote" -> "logRemotePort";
                    default -> throw new IllegalArgumentException("Invalid arg for %p");
                };

                yield lookup.findStatic(CustomRequestLog.class, method, logType);
            }
            case "I" ->
            {
                String method;
                if (StringUtil.isEmpty(arg))
                    method = "logBytesReceived";
                else if (arg.equalsIgnoreCase("clf"))
                    method = "logBytesReceivedCLF";
                else
                    throw new IllegalArgumentException("Invalid argument for %I");

                yield lookup.findStatic(CustomRequestLog.class, method, logType);
            }
            case "O" ->
            {
                String method;
                if (StringUtil.isEmpty(arg))
                    method = "logBytesSent";
                else if (arg.equalsIgnoreCase("clf"))
                    method = "logBytesSentCLF";
                else
                    throw new IllegalArgumentException("Invalid argument for %O");

                yield lookup.findStatic(CustomRequestLog.class, method, logType);
            }
            case "S" ->
            {
                String method;
                if (StringUtil.isEmpty(arg))
                    method = "logBytesTransferred";
                else if (arg.equalsIgnoreCase("clf"))
                    method = "logBytesTransferredCLF";
                else
                    throw new IllegalArgumentException("Invalid argument for %S");

                yield lookup.findStatic(CustomRequestLog.class, method, logType);
            }
            case "C" ->
            {
                if (StringUtil.isEmpty(arg))
                {
                    yield lookup.findStatic(CustomRequestLog.class, "logRequestCookies", logType);
                }
                else
                {
                    yield lookup.findStatic(CustomRequestLog.class, "logRequestCookie", logTypeArg).bindTo(arg);
                }
            }
            case "D" -> lookup.findStatic(CustomRequestLog.class, "logLatencyMicroseconds", logType);
            case "e" ->
            {
                if (StringUtil.isEmpty(arg))
                    throw new IllegalArgumentException("No arg for %e");

                yield lookup.findStatic(CustomRequestLog.class, "logEnvironmentVar", logTypeArg).bindTo(arg);
            }
            case "f" -> lookup.findStatic(CustomRequestLog.class, "logFilename", logType);
            case "H" -> lookup.findStatic(CustomRequestLog.class, "logRequestProtocol", logType);
            case "i" ->
            {
                if (StringUtil.isEmpty(arg))
                    throw new IllegalArgumentException("No arg for %i");

                yield lookup.findStatic(CustomRequestLog.class, "logRequestHeader", logTypeArg).bindTo(arg);
            }
            case "k" -> lookup.findStatic(CustomRequestLog.class, "logKeepAliveRequests", logType);
            case "m" -> lookup.findStatic(CustomRequestLog.class, "logRequestMethod", logType);
            case "o" ->
            {
                if (StringUtil.isEmpty(arg))
                    throw new IllegalArgumentException("No arg for %o");
                yield lookup.findStatic(CustomRequestLog.class, "logResponseHeader", logTypeArg).bindTo(arg);
            }
            case "q" -> lookup.findStatic(CustomRequestLog.class, "logQueryString", logType);
            case "r" -> lookup.findStatic(CustomRequestLog.class, "logRequestFirstLine", logType);
            case "R" -> lookup.findStatic(CustomRequestLog.class, "logRequestHandler", logType);
            case "s" -> lookup.findStatic(CustomRequestLog.class, "logResponseStatus", logType);
            case "t" ->
            {
                String format = DEFAULT_DATE_FORMAT;
                TimeZone timeZone = TimeZone.getTimeZone("GMT");
                Locale locale = Locale.getDefault();

                if (arg != null && !arg.isEmpty())
                {
                    String[] args = arg.split("\\|");
                    switch (args.length)
                    {
                        case 1 -> format = args[0];
                        case 2 ->
                        {
                            format = args[0];
                            timeZone = TimeZone.getTimeZone(args[1]);
                        }
                        case 3 ->
                        {
                            format = args[0];
                            timeZone = TimeZone.getTimeZone(args[1]);
                            locale = Locale.forLanguageTag(args[2]);
                        }
                        default -> throw new IllegalArgumentException("Too many \"|\" characters in %t");
                    }
                }

                DateCache logDateCache = new DateCache(format, locale, timeZone);

                MethodType logTypeDateCache = methodType(void.class, DateCache.class, StringBuilder.class, Request.class, Response.class, Boolean.TYPE);
                yield lookup.findStatic(CustomRequestLog.class, "logRequestTime", logTypeDateCache).bindTo(logDateCache);
            }
            case "T" ->
            {
                if (arg == null)
                    arg = "s";

                String method = switch (arg)
                {
                    case "s" -> "logLatencySeconds";
                    case "us" -> "logLatencyMicroseconds";
                    case "ms" -> "logLatencyMilliseconds";
                    default -> throw new IllegalArgumentException("Invalid arg for %T");
                };

                yield lookup.findStatic(CustomRequestLog.class, method, logType);
            }
            case "u" ->
            {
                String method;
                if (StringUtil.isEmpty(arg))
                    method = "logRequestAuthentication";
                else if ("d".equals(arg))
                    method = "logRequestAuthenticationWithDeferred";
                else
                    throw new IllegalArgumentException("Invalid arg for %u: " + arg);

                yield lookup.findStatic(CustomRequestLog.class, method, logType);
            }
            case "U" -> lookup.findStatic(CustomRequestLog.class, "logUrlRequestPath", logType);
            case "X" -> lookup.findStatic(CustomRequestLog.class, "logConnectionStatus", logType);
            case "ti" ->
            {
                if (StringUtil.isEmpty(arg))
                    throw new IllegalArgumentException("No arg for %ti");

                yield lookup.findStatic(CustomRequestLog.class, "logRequestTrailer", logTypeArg).bindTo(arg);
            }
            case "to" ->
            {
                if (StringUtil.isEmpty(arg))
                    throw new IllegalArgumentException("No arg for %to");

                yield lookup.findStatic(CustomRequestLog.class, "logResponseTrailer", logTypeArg).bindTo(arg);
            }
            case "uri" ->
            {
                if (arg == null)
                    arg = "";
                String method = switch (arg)
                {
                    case "" -> "logRequestHttpUri";
                    case "-query" -> "logRequestHttpUriWithoutQuery";
                    case "-path,-query" -> "logRequestHttpUriWithoutPathQuery";
                    case "scheme" -> "logRequestScheme";
                    case "authority" -> "logRequestAuthority";
                    case "path" -> "logUrlRequestPath";
                    case "query" -> "logQueryString";
                    case "host" -> "logRequestHttpUriHost";
                    case "port" -> "logRequestHttpUriPort";
                    default -> throw new IllegalArgumentException("Invalid arg for %uri");
                };

                yield lookup.findStatic(CustomRequestLog.class, method, logType);
            }
            case "attr" ->
            {
                MethodType logRequestAttribute = methodType(void.class, String.class, StringBuilder.class, Request.class, Response.class, Boolean.TYPE);
                yield lookup.findStatic(CustomRequestLog.class, "logRequestAttribute", logRequestAttribute).bindTo(arg);
            }

            default -> throw new IllegalArgumentException("Unsupported code %" + code);
        };
        
        // Tell the method if it is quoted or not
        specificHandle = MethodHandles.insertArguments(specificHandle, specificHandle.type().parameterCount() - 1, quoted);

        if (modifiers != null && !modifiers.isEmpty())
        {
            MethodHandle dash = updateLogHandle(logHandle, append, "-");
            MethodHandle log = foldArguments(logHandle, specificHandle);

            MethodHandle modifierTest = lookup.findStatic(CustomRequestLog.class, "modify",
                methodType(Boolean.TYPE, List.class, Boolean.class, StringBuilder.class, Request.class, Response.class));
            modifierTest = modifierTest.bindTo(modifiers).bindTo(negated);
            return MethodHandles.guardWithTest(modifierTest, log, dash);
        }

        return foldArguments(logHandle, specificHandle);
    }

    @SuppressWarnings("unused")
    private static void logNothing(StringBuilder b, Request request, Response response, boolean quoted)
    {
    }

    @SuppressWarnings("unused")
    private static void logPercent(StringBuilder b, Request request, Response response, boolean quoted)
    {
        b.append('%');
    }

    @SuppressWarnings("unused")
    private static void logServerHost(StringBuilder b, Request request, Response response, boolean quoted)
    {
        append(b, Request.getServerName(request), quoted);
    }

    @SuppressWarnings("unused")
    private static void logClientHost(StringBuilder b, Request request, Response response, boolean quoted)
    {
        append(b, Request.getRemoteAddr(request), quoted);
    }

    @SuppressWarnings("unused")
    private static void logLocalHost(StringBuilder b, Request request, Response response, boolean quoted)
    {
        // Unwrap to bypass any customizers
        append(b, Request.getLocalAddr(Request.unWrap(request)), quoted);
    }

    @SuppressWarnings("unused")
    private static void logRemoteHost(StringBuilder b, Request request, Response response, boolean quoted)
    {
        // Unwrap to bypass any customizers
        append(b, Request.getRemoteAddr(Request.unWrap(request)), quoted);
    }

    @SuppressWarnings("unused")
    private static void logServerPort(StringBuilder b, Request request, Response response, boolean quoted)
    {
        b.append(Request.getServerPort(request));
    }

    @SuppressWarnings("unused")
    private static void logClientPort(StringBuilder b, Request request, Response response, boolean quoted)
    {
        b.append(Request.getRemotePort(request));
    }

    @SuppressWarnings("unused")
    private static void logLocalPort(StringBuilder b, Request request, Response response, boolean quoted)
    {
        // Unwrap to bypass any customizers
        b.append(Request.getLocalPort(Request.unWrap(request)));
    }

    @SuppressWarnings("unused")
    private static void logRemotePort(StringBuilder b, Request request, Response response, boolean quoted)
    {
        // Unwrap to bypass any customizers
        b.append(Request.getRemotePort(Request.unWrap(request)));
    }

    @SuppressWarnings("unused")
    private static void logResponseSize(StringBuilder b, Request request, Response response, boolean quoted)
    {
        b.append(Response.getContentBytesWritten(response));
    }

    @SuppressWarnings("unused")
    private static void logResponseSizeCLF(StringBuilder b, Request request, Response response, boolean quoted)
    {
        long written = Response.getContentBytesWritten(response);
        if (written == 0)
            b.append('-');
        else
            b.append(written);
    }

    @SuppressWarnings("unused")
    private static void logBytesSent(StringBuilder b, Request request, Response response, boolean quoted)
    {
        b.append(Response.getContentBytesWritten(response));
    }

    @SuppressWarnings("unused")
    private static void logBytesSentCLF(StringBuilder b, Request request, Response response, boolean quoted)
    {
        long sent = Response.getContentBytesWritten(response);
        if (sent == 0)
            b.append('-');
        else
            b.append(sent);
    }

    @SuppressWarnings("unused")
    private static void logBytesReceived(StringBuilder b, Request request, Response response, boolean quoted)
    {
        b.append(Request.getContentBytesRead(request));
    }

    @SuppressWarnings("unused")
    private static void logBytesReceivedCLF(StringBuilder b, Request request, Response response, boolean quoted)
    {
        long received = Request.getContentBytesRead(request);
        if (received == 0)
            b.append('-');
        else
            b.append(received);
    }

    @SuppressWarnings("unused")
    private static void logBytesTransferred(StringBuilder b, Request request, Response response, boolean quoted)
    {
        b.append(Request.getContentBytesRead(request) + Response.getContentBytesWritten(response));
    }

    @SuppressWarnings("unused")
    private static void logBytesTransferredCLF(StringBuilder b, Request request, Response response, boolean quoted)
    {
        long transferred = Request.getContentBytesRead(request) + Response.getContentBytesWritten(response);
        if (transferred == 0)
            b.append('-');
        else
            b.append(transferred);
    }

    @SuppressWarnings("unused")
    private static void logRequestCookie(String arg, StringBuilder b, Request request, Response response, boolean quoted)
    {
        List<HttpCookie> cookies = Request.getCookies(request);
        if (cookies != null)
        {
            for (HttpCookie c : cookies)
            {
                if (arg.equals(c.getName()))
                {
                    b.append(c.getValue());
                    return;
                }
            }
        }
        b.append('-');
    }

    @SuppressWarnings("unused")
    private static void logRequestCookies(StringBuilder b, Request request, Response response, boolean quoted)
    {
        List<HttpCookie> cookies = Request.getCookies(request);
        if (cookies == null || cookies.isEmpty())
        {
            b.append('-');
        }
        else
        {
            for (int i = 0; i < cookies.size(); i++)
            {
                if (i != 0)
                    b.append(';');
                b.append(cookies.get(i).getName());
                b.append('=');
                b.append(cookies.get(i).getValue());
            }
        }
    }

    @SuppressWarnings("unused")
    private static void logEnvironmentVar(String arg, StringBuilder b, Request request, Response response, boolean quoted)
    {
        append(b, System.getenv(arg), quoted);
    }

    @SuppressWarnings("unused")
    private static void logFilename(StringBuilder b, Request request, Response response, boolean quoted)
    {
        LogDetail logDetail = (LogDetail)request.getAttribute(LOG_DETAIL);
        if (logDetail == null || logDetail.realPath == null)
        {
            Context context = request.getContext();
            Resource baseResource = context.getBaseResource();
            if (baseResource != null)
            {
                String fileName = baseResource.resolve(Request.getPathInContext(request)).getName();
                append(b, fileName, quoted);
            }
            else
            {
                b.append("-");
            }
        }
        else
        {
            b.append(logDetail.realPath);
        }
    }

    @SuppressWarnings("unused")
    private static void logRequestProtocol(StringBuilder b, Request request, Response response, boolean quoted)
    {
        append(b, request.getConnectionMetaData().getProtocol(), quoted);
    }

    @SuppressWarnings("unused")
    private static void logRequestHeader(String arg, StringBuilder b, Request request, Response response, boolean quoted)
    {
        append(b, request.getHeaders().get(arg), quoted);
    }

    @SuppressWarnings("unused")
    private static void logKeepAliveRequests(StringBuilder b, Request request, Response response, boolean quoted)
    {
        long requests = request.getConnectionMetaData().getConnection().getMessagesIn();
        if (requests >= 0)
            b.append(requests);
        else
            b.append('-');
    }

    @SuppressWarnings("unused")
    private static void logRequestMethod(StringBuilder b, Request request, Response response, boolean quoted)
    {
        append(b, request.getMethod(), quoted);
    }

    @SuppressWarnings("unused")
    private static void logResponseHeader(String arg, StringBuilder b, Request request, Response response, boolean quoted)
    {
        append(b, response.getHeaders().get(arg), quoted);
    }

    @SuppressWarnings("unused")
    private static void logQueryString(StringBuilder b, Request request, Response response, boolean quoted)
    {
        String query = request.getHttpURI().getQuery();
        append(b, (query == null) ? null : "?" + query, quoted);
    }

    @SuppressWarnings("unused")
    private static void logRequestFirstLine(StringBuilder b, Request request, Response response, boolean quoted)
    {
        append(b, request.getMethod(), quoted);
        b.append(" ");
        append(b, request.getHttpURI().getPathQuery(), quoted);
        b.append(" ");
        append(b, request.getConnectionMetaData().getProtocol(), quoted);
    }

    @SuppressWarnings("unused")
    private static void logRequestHandler(StringBuilder b, Request request, Response response, boolean quoted)
    {
        LogDetail logDetail = (LogDetail)request.getAttribute(LOG_DETAIL);
        append(b, logDetail == null ? null : logDetail.handlerName, quoted);
    }

    @SuppressWarnings("unused")
    private static void logResponseStatus(StringBuilder b, Request request, Response response, boolean quoted)
    {
        b.append(response.getStatus());
    }

    @SuppressWarnings("unused")
    private static void logRequestTime(DateCache dateCache, StringBuilder b, Request request, Response response, boolean quoted)
    {
        b.append('[');
        append(b, dateCache.format(Request.getTimeStamp(request)), quoted);
        b.append(']');
    }

    @SuppressWarnings("unused")
    private static void logLatencyMicroseconds(StringBuilder b, Request request, Response response, boolean quoted)
    {
        logLatency(b, request, TimeUnit.MICROSECONDS);
    }

    @SuppressWarnings("unused")
    private static void logLatencyMilliseconds(StringBuilder b, Request request, Response response, boolean quoted)
    {
        logLatency(b, request, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unused")
    private static void logLatencySeconds(StringBuilder b, Request request, Response response, boolean quoted)
    {
        logLatency(b, request, TimeUnit.SECONDS);
    }

    private static void logLatency(StringBuilder b, Request request, TimeUnit unit)
    {
        b.append(unit.convert(NanoTime.since(request.getBeginNanoTime()), TimeUnit.NANOSECONDS));
    }

    @SuppressWarnings("unused")
    private static void logRequestAuthentication(StringBuilder b, Request request, Response response, boolean quoted)
    {
        Request.AuthenticationState authenticationState = Request.getAuthenticationState(request);
        Principal userPrincipal = authenticationState == null ? null : authenticationState.getUserPrincipal();
        append(b, userPrincipal == null ? null : userPrincipal.getName(), quoted);
    }

    @SuppressWarnings("unused")
    private static void logRequestAuthenticationWithDeferred(StringBuilder b, Request request, Response response, boolean quoted)
    {
        // TODO: deferred to be implemented.
        logRequestAuthentication(b, request, response, quoted);
    }

    @SuppressWarnings("unused")
    private static void logUrlRequestPath(StringBuilder b, Request request, Response response, boolean quoted)
    {
        append(b, request.getHttpURI().getPath(), quoted);
    }

    @SuppressWarnings("unused")
    private static void logConnectionStatus(StringBuilder b, Request request, Response response, boolean quoted)
    {
        b.append(response.isCompletedSuccessfully()
            ? (request.getConnectionMetaData().isPersistent() ? '+' : '-')
            : 'X');
    }

    @SuppressWarnings("unused")
    private static void logRequestTrailer(String arg, StringBuilder b, Request request, Response response, boolean quoted)
    {
        HttpFields trailers = request.getTrailers();
        if (trailers != null)
            append(b, trailers.get(arg), quoted);
        else
            b.append('-');
    }

    @SuppressWarnings("unused")
    private static void logResponseTrailer(String arg, StringBuilder b, Request request, Response response, boolean quoted)
    {
        Supplier<HttpFields> supplier = response.getTrailersSupplier();
        HttpFields trailers = supplier == null ? null : supplier.get();
        if (trailers != null)
            append(b, trailers.get(arg), quoted);
        else
            b.append('-');
    }

    @SuppressWarnings("unused")
    private static void logRequestAuthority(StringBuilder b, Request request, Response response, boolean quoted)
    {
        HttpURI httpURI = request.getHttpURI();
        if (httpURI.hasAuthority())
            append(b, httpURI.getAuthority(), quoted);
        else
            b.append('-');
    }

    @SuppressWarnings("unused")
    private static void logRequestScheme(StringBuilder b, Request request, Response response, boolean quoted)
    {
        append(b, request.getHttpURI().getScheme(), quoted);
    }

    @SuppressWarnings("unused")
    private static void logRequestHttpUri(StringBuilder b, Request request, Response response, boolean quoted)
    {
        append(b, request.getHttpURI().toString(), quoted);
    }

    @SuppressWarnings("unused")
    private static void logRequestHttpUriWithoutQuery(StringBuilder b, Request request, Response response, boolean quoted)
    {
        HttpURI.Mutable uri = HttpURI.build(request.getHttpURI()).query(null);
        append(b, uri.toString(), quoted);
    }

    @SuppressWarnings("unused")
    private static void logRequestHttpUriWithoutPathQuery(StringBuilder b, Request request, Response response, boolean quoted)
    {
        // HttpURI doesn't support null path so we do this manually.
        HttpURI httpURI = request.getHttpURI();
        if (httpURI.getScheme() != null)
            b.append(httpURI.getScheme()).append(':');
        if (httpURI.getHost() != null)
        {
            b.append("//");
            if (httpURI.getUser() != null)
                b.append(httpURI.getUser()).append('@');
            b.append(httpURI.getHost());
        }
        int normalizedPort = URIUtil.normalizePortForScheme(httpURI.getScheme(), httpURI.getPort());
        if (normalizedPort > 0)
            b.append(':').append(normalizedPort);
    }

    @SuppressWarnings("unused")
    private static void logRequestHttpUriHost(StringBuilder b, Request request, Response response, boolean quoted)
    {
        append(b, request.getHttpURI().getHost(), quoted);
    }

    @SuppressWarnings("unused")
    private static void logRequestHttpUriPort(StringBuilder b, Request request, Response response, boolean quoted)
    {
        b.append(request.getHttpURI().getPort());
    }

    @SuppressWarnings("unused")
    private static void logRequestAttribute(String arg, StringBuilder b, Request request, Response response, boolean quoted)
    {
        Object attribute = request.getAttribute(arg);
        if (attribute != null)
            append(b, attribute.toString(), quoted);
        else
            b.append('-');
    }
}
