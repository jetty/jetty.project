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

package org.eclipse.jetty.server;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.QuotedCSV;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.util.DateCache;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
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
 *
 * <tr>
 * <td>X</td>
 * <td>
 * <p>The X character.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%%</td>
 * <td>
 * <p>The percent character.</p>
 * </td>
 * </tr>
 *
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
 *
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
 *
 * <tr>
 * <td>%{CLF}I</td>
 * <td>
 * <p>The size of request in bytes, excluding HTTP headers.</p>
 * <p>The parameter is optional.
 * When the parameter value is "CLF" the Common Log Format is used, i.e. a {@code -} rather than a {@code 0}
 * when no bytes are present.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%{CLF}O</td>
 * <td>
 * <p>The size of response in bytes, excluding HTTP headers.</p>
 * <p>The parameter is optional.
 * When the parameter value is "CLF" the Common Log Format is used, i.e. a {@code -} rather than a {@code 0}
 * when no bytes are present.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%{CLF}S</td>
 * <td>
 * <p>The bytes transferred (received and sent). This is the combination of {@code %I} and {@code %O}.</p>
 * <p>The parameter is optional.
 * When the parameter value is "CLF" the Common Log Format is used, i.e. a {@code -} rather than a {@code 0}
 * when no bytes are present.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%{VARNAME}C</td>
 * <td>
 * <p>The value of the request cookie VARNAME.</p>
 * <p>The parameter is optional.
 * Only version 0 cookies are fully supported.
 * When the parameter is missing, all request cookies will be logged.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%D</td>
 * <td>
 * <p>The time taken to serve the request, in microseconds.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%{VARNAME}e</td>
 * <td>
 * <p>The value of the environment variable VARNAME.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%f</td>
 * <td>
 * <p>The file system path of the requested resource.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%H</td>
 * <td>
 * <p>The name and version of the request protocol, such as "HTTP/1.1".</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%{VARNAME}i</td>
 * <td>
 * <p>The value of the VARNAME request header.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%k</td>
 * <td>
 * <p>The number of requests handled on a connection.</p>
 * <p>The initial request on a connection yields a value 0, the first request after the initial on the same connection
 * yields the value 1, the second request on the same connection yields the value 2, etc.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%m</td>
 * <td>
 * <p>The HTTP request method.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%{VARNAME}o</td>
 * <td>
 * <p>The value of the VARNAME response header.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%q</td>
 * <td>
 * <p>The query string, prepended with a ? if a query string exists, otherwise an empty string.</p>
 * </td>
 * </tr>
 *
 * <!-- TODO ATTRIBUTE LOGGING -->
 * <tr>
 * <td>%r</td>
 * <td>
 * <p>First line of an HTTP/1.1 request (or equivalent information for HTTP/2 or later).</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%R</td>
 * <td>
 * <p>The name of the Handler or Servlet generating the response (if any).</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%s</td>
 * <td>
 * <p>The HTTP response status code.</p>
 * </td>
 * </tr>
 *
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
 *
 * <tr>
 * <td>%{UNIT}T</td>
 * <td>
 * <p>The time taken to serve the request.</p>
 * <p>The parameter UNIT is optional and defaults to "s".
 * The parameter UNIT indicates the unit of time: "s" for seconds, "ms" for milliseconds, "us" for microseconds.
 * <code>%{us}T</code> is identical to {@code %D}.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%{d}u</td>
 * <td>
 * <p>The remote user if the request was authenticated with servlet authentication.</p>
 * <p>May be an invalid value if response status code ({@code %s}) is 401 (unauthorized).</p>
 * <p>The parameter is optional.
 * When the parameter value is "d", deferred authentication will also be checked.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%U</td>
 * <td>
 * <p>The URL path requested, not including any query string.</p>
 * </td>
 * </tr>
 *
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
 *
 * <tr>
 * <td>%{VARNAME}ti</td>
 * <td>
 * <p>The value of the VARNAME request trailer.</p>
 * </td>
 * </tr>
 *
 * <tr>
 * <td>%{VARNAME}to</td>
 * <td>
 * <p>The value of the VARNAME response trailer.</p>
 * </td>
 * </tr>
 * </table>
 * <!-- end::documentation[] -->
 */
@ManagedObject("Custom format request log")
public class CustomRequestLog extends ContainerLifeCycle implements RequestLog
{
    protected static final Logger LOG = LoggerFactory.getLogger(CustomRequestLog.class);

    public static final String DEFAULT_DATE_FORMAT = "dd/MMM/yyyy:HH:mm:ss ZZZ";
    public static final String NCSA_FORMAT = "%{client}a - %u %t \"%r\" %s %O";
    public static final String EXTENDED_NCSA_FORMAT = NCSA_FORMAT + " \"%{Referer}i\" \"%{User-Agent}i\"";
    private static final ThreadLocal<StringBuilder> _buffers = ThreadLocal.withInitial(() -> new StringBuilder(256));

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
        addBean(_requestLogWriter);

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

    /**
     * Writes the request and response information to the output stream.
     *
     * @see org.eclipse.jetty.server.RequestLog#log(Request, Response)
     */
    @Override
    public void log(Request request, Response response)
    {
        try
        {
            if (_ignorePathMap != null && _ignorePathMap.getMatched(request.getHttpURI().toString()) != null)
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
     * Extract the user authentication
     *
     * @param request The request to extract from
     * @param checkDeferred Whether to check for deferred authentication
     * @return The string to log for authenticated user.
     */
    protected static String getAuthentication(Request request, boolean checkDeferred)
    {
        // TODO
//        Authentication authentication = request.getAuthentication();
//        if (checkDeferred && authentication instanceof Authentication.Deferred)
//            authentication = ((Authentication.Deferred)authentication).authenticate(request);

        String name = null;
//        if (authentication instanceof Authentication.User)
//            name = ((Authentication.User)authentication).getUserIdentity().getUserPrincipal().getName();
//
        return name;
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

    private static void append(StringBuilder buf, String s)
    {
        if (s == null || s.length() == 0)
            buf.append('-');
        else
            buf.append(s);
    }

    private static void append(String s, StringBuilder buf)
    {
        append(buf, s);
    }

    private MethodHandle getLogHandle(String formatString) throws NoSuchMethodException, IllegalAccessException
    {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle append = lookup.findStatic(CustomRequestLog.class, "append", methodType(void.class, String.class, StringBuilder.class));
        MethodHandle logHandle = lookup.findStatic(CustomRequestLog.class, "logNothing", methodType(void.class, StringBuilder.class, Request.class, Response.class));

        List<Token> tokens = getTokens(formatString);
        Collections.reverse(tokens);

        for (Token t : tokens)
        {
            if (t.isLiteralString())
                logHandle = updateLogHandle(logHandle, append, t.literal);
            else if (t.isPercentCode())
                logHandle = updateLogHandle(logHandle, append, lookup, t.code, t.arg, t.modifiers, t.negated);
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
        final Pattern PATTERN = Pattern.compile("^(?:%(?<MOD>!?[0-9,]+)?(?:\\{(?<ARG>[^}]+)})?(?<CODE>(?:(?:ti)|(?:to)|[a-zA-Z%]))|(?<LITERAL>[^%]+))(?<REMAINING>.*)", Pattern.DOTALL | Pattern.MULTILINE);

        List<Token> tokens = new ArrayList<>();
        String remaining = formatString;
        while (remaining.length() > 0)
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
                    throw new IllegalStateException("formatString parsing error");
                }

                remaining = m.group("REMAINING");
            }
            else
            {
                throw new IllegalArgumentException("Invalid format string");
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

        public final String literal;

        public Token(String code, String arg, List<Integer> modifiers, boolean negated)
        {
            this.code = code;
            this.arg = arg;
            this.modifiers = modifiers;
            this.negated = negated;
            this.literal = null;
        }

        public Token(String literal)
        {
            this.code = null;
            this.arg = null;
            this.modifiers = null;
            this.negated = false;
            this.literal = literal;
        }

        public boolean isLiteralString()
        {
            return (literal != null);
        }

        public boolean isPercentCode()
        {
            return (code != null);
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

    private MethodHandle updateLogHandle(MethodHandle logHandle, MethodHandle append, MethodHandles.Lookup lookup, String code, String arg, List<Integer> modifiers, boolean negated) throws NoSuchMethodException, IllegalAccessException
    {
        MethodType logType = methodType(void.class, StringBuilder.class, Request.class, Response.class);
        MethodType logTypeArg = methodType(void.class, String.class, StringBuilder.class, Request.class, Response.class);

        //TODO should we throw IllegalArgumentExceptions when given arguments for codes which do not take them
        MethodHandle specificHandle;
        switch (code)
        {
            case "%":
            {
                specificHandle = dropArguments(dropArguments(append.bindTo("%"), 1, Request.class), 2, Response.class);
                break;
            }

            case "a":
            {
                if (StringUtil.isEmpty(arg))
                    arg = "server";

                String method;
                switch (arg)
                {
                    case "server":
                        method = "logServerHost";
                        break;

                    case "client":
                        method = "logClientHost";
                        break;

                    case "local":
                        method = "logLocalHost";
                        break;

                    case "remote":
                        method = "logRemoteHost";
                        break;

                    default:
                        throw new IllegalArgumentException("Invalid arg for %a");
                }

                specificHandle = lookup.findStatic(CustomRequestLog.class, method, logType);
                break;
            }

            case "p":
            {
                if (StringUtil.isEmpty(arg))
                    arg = "server";

                String method;
                switch (arg)
                {

                    case "server":
                        method = "logServerPort";
                        break;

                    case "client":
                        method = "logClientPort";
                        break;

                    case "local":
                        method = "logLocalPort";
                        break;

                    case "remote":
                        method = "logRemotePort";
                        break;

                    default:
                        throw new IllegalArgumentException("Invalid arg for %p");
                }

                specificHandle = lookup.findStatic(CustomRequestLog.class, method, logType);
                break;
            }

            case "I":
            {
                String method;
                if (StringUtil.isEmpty(arg))
                    method = "logBytesReceived";
                else if (arg.equalsIgnoreCase("clf"))
                    method = "logBytesReceivedCLF";
                else
                    throw new IllegalArgumentException("Invalid argument for %I");

                specificHandle = lookup.findStatic(CustomRequestLog.class, method, logType);
                break;
            }

            case "O":
            {
                String method;
                if (StringUtil.isEmpty(arg))
                    method = "logBytesSent";
                else if (arg.equalsIgnoreCase("clf"))
                    method = "logBytesSentCLF";
                else
                    throw new IllegalArgumentException("Invalid argument for %O");

                specificHandle = lookup.findStatic(CustomRequestLog.class, method, logType);
                break;
            }

            case "S":
            {
                String method;
                if (StringUtil.isEmpty(arg))
                    method = "logBytesTransferred";
                else if (arg.equalsIgnoreCase("clf"))
                    method = "logBytesTransferredCLF";
                else
                    throw new IllegalArgumentException("Invalid argument for %S");

                specificHandle = lookup.findStatic(CustomRequestLog.class, method, logType);
                break;
            }

            case "C":
            {
                if (StringUtil.isEmpty(arg))
                {
                    specificHandle = lookup.findStatic(CustomRequestLog.class, "logRequestCookies", logType);
                }
                else
                {
                    specificHandle = lookup.findStatic(CustomRequestLog.class, "logRequestCookie", logTypeArg);
                    specificHandle = specificHandle.bindTo(arg);
                }
                break;
            }

            case "D":
            {
                specificHandle = lookup.findStatic(CustomRequestLog.class, "logLatencyMicroseconds", logType);
                break;
            }

            case "e":
            {
                if (StringUtil.isEmpty(arg))
                    throw new IllegalArgumentException("No arg for %e");

                specificHandle = lookup.findStatic(CustomRequestLog.class, "logEnvironmentVar", logTypeArg);
                specificHandle = specificHandle.bindTo(arg);
                break;
            }

            case "f":
            {
                specificHandle = lookup.findStatic(CustomRequestLog.class, "logFilename", logType);
                break;
            }

            case "H":
            {
                specificHandle = lookup.findStatic(CustomRequestLog.class, "logRequestProtocol", logType);
                break;
            }

            case "i":
            {
                if (StringUtil.isEmpty(arg))
                    throw new IllegalArgumentException("No arg for %i");

                specificHandle = lookup.findStatic(CustomRequestLog.class, "logRequestHeader", logTypeArg);
                specificHandle = specificHandle.bindTo(arg);
                break;
            }

            case "k":
            {
                specificHandle = lookup.findStatic(CustomRequestLog.class, "logKeepAliveRequests", logType);
                break;
            }

            case "m":
            {
                specificHandle = lookup.findStatic(CustomRequestLog.class, "logRequestMethod", logType);
                break;
            }

            case "o":
            {
                if (StringUtil.isEmpty(arg))
                    throw new IllegalArgumentException("No arg for %o");

                specificHandle = lookup.findStatic(CustomRequestLog.class, "logResponseHeader", logTypeArg);
                specificHandle = specificHandle.bindTo(arg);
                break;
            }

            case "q":
            {
                specificHandle = lookup.findStatic(CustomRequestLog.class, "logQueryString", logType);
                break;
            }

            case "r":
            {
                specificHandle = lookup.findStatic(CustomRequestLog.class, "logRequestFirstLine", logType);
                break;
            }

            case "R":
            {
                specificHandle = lookup.findStatic(CustomRequestLog.class, "logRequestHandler", logType);
                break;
            }

            case "s":
            {
                specificHandle = lookup.findStatic(CustomRequestLog.class, "logResponseStatus", logType);
                break;
            }

            case "t":
            {
                String format = DEFAULT_DATE_FORMAT;
                TimeZone timeZone = TimeZone.getTimeZone("GMT");
                Locale locale = Locale.getDefault();

                if (arg != null && !arg.isEmpty())
                {
                    String[] args = arg.split("\\|");
                    switch (args.length)
                    {
                        case 1:
                            format = args[0];
                            break;

                        case 2:
                            format = args[0];
                            timeZone = TimeZone.getTimeZone(args[1]);
                            break;

                        case 3:
                            format = args[0];
                            timeZone = TimeZone.getTimeZone(args[1]);
                            locale = Locale.forLanguageTag(args[2]);
                            break;

                        default:
                            throw new IllegalArgumentException("Too many \"|\" characters in %t");
                    }
                }

                DateCache logDateCache = new DateCache(format, locale, timeZone);

                MethodType logTypeDateCache = methodType(void.class, DateCache.class, StringBuilder.class, Request.class, Response.class);
                specificHandle = lookup.findStatic(CustomRequestLog.class, "logRequestTime", logTypeDateCache);
                specificHandle = specificHandle.bindTo(logDateCache);
                break;
            }

            case "T":
            {
                if (arg == null)
                    arg = "s";

                String method;
                switch (arg)
                {
                    case "s":
                        method = "logLatencySeconds";
                        break;
                    case "us":
                        method = "logLatencyMicroseconds";
                        break;
                    case "ms":
                        method = "logLatencyMilliseconds";
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid arg for %T");
                }

                specificHandle = lookup.findStatic(CustomRequestLog.class, method, logType);
                break;
            }

            case "u":
            {
                String method;
                if (StringUtil.isEmpty(arg))
                    method = "logRequestAuthentication";
                else if ("d".equals(arg))
                    method = "logRequestAuthenticationWithDeferred";
                else
                    throw new IllegalArgumentException("Invalid arg for %u: " + arg);

                specificHandle = lookup.findStatic(CustomRequestLog.class, method, logType);
                break;
            }

            case "U":
            {
                specificHandle = lookup.findStatic(CustomRequestLog.class, "logUrlRequestPath", logType);
                break;
            }

            case "X":
            {
                specificHandle = lookup.findStatic(CustomRequestLog.class, "logConnectionStatus", logType);
                break;
            }

            case "ti":
            {
                if (StringUtil.isEmpty(arg))
                    throw new IllegalArgumentException("No arg for %ti");

                specificHandle = lookup.findStatic(CustomRequestLog.class, "logRequestTrailer", logTypeArg);
                specificHandle = specificHandle.bindTo(arg);
                break;
            }

            case "to":
            {
                if (StringUtil.isEmpty(arg))
                    throw new IllegalArgumentException("No arg for %to");

                specificHandle = lookup.findStatic(CustomRequestLog.class, "logResponseTrailer", logTypeArg);
                specificHandle = specificHandle.bindTo(arg);
                break;
            }

            default:
                throw new IllegalArgumentException("Unsupported code %" + code);
        }

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

    //-----------------------------------------------------------------------------------//
    @SuppressWarnings("unused")
    private static void logNothing(StringBuilder b, Request request, Response response)
    {
    }

    @SuppressWarnings("unused")
    private static void logServerHost(StringBuilder b, Request request, Response response)
    {
        append(b, Request.getServerName(request));
    }

    @SuppressWarnings("unused")
    private static void logClientHost(StringBuilder b, Request request, Response response)
    {
        append(b, Request.getRemoteAddr(request));
    }

    @SuppressWarnings("unused")
    private static void logLocalHost(StringBuilder b, Request request, Response response)
    {
        // Unwrap to bypass any customizers
        append(b, Request.getLocalAddr(Request.unWrap(request)));
    }

    @SuppressWarnings("unused")
    private static void logRemoteHost(StringBuilder b, Request request, Response response)
    {
        // Unwrap to bypass any customizers
        append(b, Request.getRemoteAddr(Request.unWrap(request)));
    }

    @SuppressWarnings("unused")
    private static void logServerPort(StringBuilder b, Request request, Response response)
    {
        b.append(Request.getServerPort(request));
    }

    @SuppressWarnings("unused")
    private static void logClientPort(StringBuilder b, Request request, Response response)
    {
        b.append(Request.getRemotePort(request));
    }

    @SuppressWarnings("unused")
    private static void logLocalPort(StringBuilder b, Request request, Response response)
    {
        // Unwrap to bypass any customizers
        b.append(Request.getLocalPort(Request.unWrap(request)));
    }

    @SuppressWarnings("unused")
    private static void logRemotePort(StringBuilder b, Request request, Response response)
    {
        // Unwrap to bypass any customizers
        b.append(Request.getRemotePort(Request.unWrap(request)));
    }

    @SuppressWarnings("unused")
    private static void logResponseSize(StringBuilder b, Request request, Response response)
    {
        long written = Response.getContentBytesWritten(response);
        b.append(written);
    }

    @SuppressWarnings("unused")
    private static void logResponseSizeCLF(StringBuilder b, Request request, Response response)
    {
        long written = Response.getContentBytesWritten(response);
        if (written == 0)
            b.append('-');
        else
            b.append(written);
    }

    @SuppressWarnings("unused")
    private static void logBytesSent(StringBuilder b, Request request, Response response)
    {
        b.append(Response.getContentBytesWritten(response));
    }

    @SuppressWarnings("unused")
    private static void logBytesSentCLF(StringBuilder b, Request request, Response response)
    {
        long sent = Response.getContentBytesWritten(response);
        if (sent == 0)
            b.append('-');
        else
            b.append(sent);
    }

    @SuppressWarnings("unused")
    private static void logBytesReceived(StringBuilder b, Request request, Response response)
    {
        b.append(Request.getContentBytesRead(request));
    }

    @SuppressWarnings("unused")
    private static void logBytesReceivedCLF(StringBuilder b, Request request, Response response)
    {
        long received = Request.getContentBytesRead(request);
        if (received == 0)
            b.append('-');
        else
            b.append(received);
    }

    @SuppressWarnings("unused")
    private static void logBytesTransferred(StringBuilder b, Request request, Response response)
    {
        b.append(Request.getContentBytesRead(request) + Response.getContentBytesWritten(response));
    }

    @SuppressWarnings("unused")
    private static void logBytesTransferredCLF(StringBuilder b, Request request, Response response)
    {
        long transferred = Request.getContentBytesRead(request) + Response.getContentBytesWritten(response);
        if (transferred == 0)
            b.append('-');
        else
            b.append(transferred);
    }

    @SuppressWarnings("unused")
    private static void logRequestCookie(String arg, StringBuilder b, Request request, Response response)
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
    private static void logRequestCookies(StringBuilder b, Request request, Response response)
    {
        List<HttpCookie> cookies = Request.getCookies(request);
        if (cookies == null || cookies.size() == 0)
            b.append('-');
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
    private static void logEnvironmentVar(String arg, StringBuilder b, Request request, Response response)
    {
        append(b, System.getenv(arg));
    }

    @SuppressWarnings("unused")
    private static void logFilename(StringBuilder b, Request request, Response response)
    {
        b.append('-');
//      TODO UserIdentity.Scope scope = request.getUserIdentityScope();
//        if (scope == null || scope.getContextHandler() == null)
//            b.append('-');
//        else
//        {
//            ContextHandler context = scope.getContextHandler();
//            int lengthToStrip = scope.getContextPath().length() > 1 ? scope.getContextPath().length() : 0;
//            String filename = context.getServletContext().getRealPath(request.getPathInfo().substring(lengthToStrip));
//            append(b, filename);
//        }
    }

    @SuppressWarnings("unused")
    private static void logRequestProtocol(StringBuilder b, Request request, Response response)
    {
        append(b, request.getConnectionMetaData().getProtocol());
    }

    @SuppressWarnings("unused")
    private static void logRequestHeader(String arg, StringBuilder b, Request request, Response response)
    {
        append(b, request.getHeaders().get(arg));
    }

    @SuppressWarnings("unused")
    private static void logKeepAliveRequests(StringBuilder b, Request request, Response response)
    {
        long requests = request.getConnectionMetaData().getConnection().getMessagesIn();
        if (requests >= 0)
            b.append(requests);
        else
            b.append('-');
    }

    @SuppressWarnings("unused")
    private static void logRequestMethod(StringBuilder b, Request request, Response response)
    {
        append(b, request.getMethod());
    }

    @SuppressWarnings("unused")
    private static void logResponseHeader(String arg, StringBuilder b, Request request, Response response)
    {
        append(b, response.getHeaders().get(arg));
    }

    @SuppressWarnings("unused")
    private static void logQueryString(StringBuilder b, Request request, Response response)
    {
        append(b, "?" + request.getHttpURI().getQuery());
    }

    @SuppressWarnings("unused")
    private static void logRequestFirstLine(StringBuilder b, Request request, Response response)
    {
        append(b, request.getMethod());
        b.append(" ");
        append(b, request.getHttpURI().getPathQuery());
        b.append(" ");
        append(b, request.getConnectionMetaData().getProtocol());
    }

    @SuppressWarnings("unused")
    private static void logRequestHandler(StringBuilder b, Request request, Response response)
    {
        b.append('-');
//      TODO append(b, request.getServletName());
    }

    @SuppressWarnings("unused")
    private static void logResponseStatus(StringBuilder b, Request request, Response response)
    {
        b.append(response.getStatus());
    }

    @SuppressWarnings("unused")
    private static void logRequestTime(DateCache dateCache, StringBuilder b, Request request, Response response)
    {
        b.append('[');
        append(b, dateCache.format(request.getTimeStamp()));
        b.append(']');
    }

    @SuppressWarnings("unused")
    private static void logLatencyMicroseconds(StringBuilder b, Request request, Response response)
    {
        long currentTime = System.currentTimeMillis();
        long requestTime = request.getTimeStamp();

        long latencyMs = currentTime - requestTime;
        long latencyUs = TimeUnit.MILLISECONDS.toMicros(latencyMs);

        b.append(latencyUs);
    }

    @SuppressWarnings("unused")
    private static void logLatencyMilliseconds(StringBuilder b, Request request, Response response)
    {
        long latency = System.currentTimeMillis() - request.getTimeStamp();
        b.append(latency);
    }

    @SuppressWarnings("unused")
    private static void logLatencySeconds(StringBuilder b, Request request, Response response)
    {
        long latency = System.currentTimeMillis() - request.getTimeStamp();
        b.append(TimeUnit.MILLISECONDS.toSeconds(latency));
    }

    @SuppressWarnings("unused")
    private static void logRequestAuthentication(StringBuilder b, Request request, Response response)
    {
        append(b, getAuthentication(request, false));
    }

    @SuppressWarnings("unused")
    private static void logRequestAuthenticationWithDeferred(StringBuilder b, Request request, Response response)
    {
        append(b, getAuthentication(request, true));
    }

    @SuppressWarnings("unused")
    private static void logUrlRequestPath(StringBuilder b, Request request, Response response)
    {
        append(b, request.getHttpURI().getPath());
    }

    @SuppressWarnings("unused")
    private static void logConnectionStatus(StringBuilder b, Request request, Response response)
    {
        b.append(response.isCompletedSuccessfully()
            ? (request.getConnectionMetaData().isPersistent() ? '+' : '-')
            : 'X');
    }

    @SuppressWarnings("unused")
    private static void logRequestTrailer(String arg, StringBuilder b, Request request, Response response)
    {
        HttpFields trailers = response.getOrCreateTrailers();
        if (trailers != null)
            append(b, trailers.get(arg));
        else
            b.append('-');
    }

    @SuppressWarnings("unused")
    private static void logResponseTrailer(String arg, StringBuilder b, Request request, Response response)
    {
        b.append('-');
        HttpFields trailers = response.getOrCreateTrailers();
        if (trailers != null)
            append(b, trailers.get(arg));
        else
            b.append('-');
    }
}
