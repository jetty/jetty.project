//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.util.DateCache;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodType.methodType;

/**
 <table>
 <tr>
 <td width="20%"><b>Format String</b></td>
 <td><b>Description</b></td>
 </tr>

 <tr>
 <td>%%</td>
 <td>The percent sign.</td>
 </tr>

 <tr>
 <td valign="top">%a</td>
 <td>Client IP address of the request.</td>
 </tr>

 <tr>
 <td valign="top">%{c}a</td>
 <td>Underlying peer IP address of the connection.</td>
 </tr>

 <tr>
 <td valign="top">%A</td>
 <td>Local IP-address.</td>
 </tr>

 <tr>
 <td valign="top">%B</td>
 <td>Size of response in bytes, excluding HTTP headers.</td>
 </tr>

 <tr>
 <td valign="top">%b</td>
 <td>Size of response in bytes, excluding HTTP headers. In CLF format, i.e. a '-' rather than a 0 when no bytes are sent.</td>
 </tr>

 <tr>
 <td valign="top">%{VARNAME}C</td>
 <td>The contents of cookie VARNAME in the request sent to the server. Only version 0 cookies are fully supported.</td>
 </tr>

 <tr>
 <td valign="top">%D</td>
 <td>The time taken to serve the request, in microseconds.</td>
 </tr>

 <tr>
 <td valign="top">%{VARNAME}e</td>
 <td>The contents of the environment variable VARNAME.</td>
 </tr>

 <tr>
 <td valign="top">%f</td>
 <td>Filename.</td>
 </tr>

 <tr>
 <td valign="top">%h</td>
 <td>Remote hostname. Will log the IP address if HostnameLookups is set to Off, which is the default. If it logs the hostname for only a few hosts, you probably have access control directives mentioning them by name. See the Require host documentation.</td>
 </tr>

 <tr>
 <td valign="top">%H</td>
 <td>The request protocol.</td>
 </tr>

 <tr>
 <td valign="top">%{VARNAME}i</td>
 <td>The contents of VARNAME: header line(s) in the request sent to the server. Changes made by other modules (e.g. mod_headers) affect this. If you're interested in what the request header was prior to when most modules would have modified it, use mod_setenvif to copy the header into an internal environment variable and log that value with the %{VARNAME}e described above.</td>
 </tr>

 <tr>
 <td valign="top">%k</td>
 <td>Number of keepalive requests handled on this connection. Interesting if KeepAlive is being used, so that, for example, a '1' means the first keepalive request after the initial one, '2' the second, etc...; otherwise this is always 0 (indicating the initial request).</td>
 </tr>

 <tr>
 <td valign="top">%l</td>
 <td>Remote logname (from identd, if supplied). This will return a dash unless mod_ident is present and IdentityCheck is set On.</td>
 </tr>

 <tr>
 <td valign="top">%L</td>
 <td>The request log ID from the error log (or '-' if nothing has been logged to the error log for this request). Look for the matching error log line to see what request caused what error.</td>
 </tr>

 <tr>
 <td valign="top">%m</td>
 <td>The request method.</td>
 </tr>

 <tr>
 <td valign="top">%{VARNAME}n</td>
 <td>The contents of note VARNAME from another module.</td>
 </tr>

 <tr>
 <td valign="top">%{VARNAME}o</td>
 <td>The contents of VARNAME: header line(s) in the reply.</td>
 </tr>

 <tr>
 <td valign="top">%p</td>
 <td>The canonical port of the server serving the request.</td>
 </tr>

 <tr>
 <td valign="top">%{format}p</td>
 <td>The canonical port of the server serving the request, or the server's actual port, or the client's actual port. Valid formats are canonical, local, or remote.</td>
 </tr>

 <tr>
 <td valign="top">%P</td>
 <td>The process ID of the child that serviced the request.</td>
 </tr>

 <tr>
 <td valign="top">%{format}P</td>
 <td>The process ID or thread ID of the child that serviced the request. Valid formats are pid, tid, and hextid. hextid requires APR 1.2.0 or higher.</td>
 </tr>

 <tr>
 <td valign="top">%q</td>
 <td>The query string (prepended with a ? if a query string exists, otherwise an empty string).</td>
 </tr>

 <tr>
 <td valign="top">%r</td>
 <td>First line of request.</td>
 </tr>

 <tr>
 <td valign="top">%R</td>
 <td>The handler generating the response (if any).</td>
 </tr>

 <tr>
 <td valign="top">%s</td>
 <td>Status. For requests that have been internally redirected, this is the status of the original request. Use %>s for the final status.</td>
 </tr>

 <tr>
 <td valign="top">%t</td>
 <td>Time the request was received, in the format [18/Sep/2011:19:18:28 -0400]. The last number indicates the timezone offset from GMT</td>
 </tr>

 <tr>
 <td valign="top">%{format}t</td>
 <td>
 The time, in the form given by format, which should be in an extended strftime(3) format (potentially localized). If the format starts with begin: (default) the time is taken at the beginning of the request processing. If it starts with end: it is the time when the log entry gets written, close to the end of the request processing.

 <br><br>In addition to the formats supported by strftime(3), the following format tokens are supported:

 <pre>
 sec         number of seconds since the Epoch
 msec        number of milliseconds since the Epoch
 usec        number of microseconds since the Epoch
 msec_frac   millisecond fraction
 usec_frac   microsecond fraction
 </pre>

 These tokens can not be combined with each other or strftime(3) formatting in the same format string. You can use multiple %{format}t tokens instead.
 </td>
 </tr>

 <tr>
 <td valign="top">%T</td>
 <td>The time taken to serve the request, in seconds.</td>
 </tr>

 <tr>
 <td valign="top">%{UNIT}T</td>
 <td>The time taken to serve the request, in a time unit given by UNIT. Valid units are ms for milliseconds, us for microseconds, and s for seconds. Using s gives the same result as %T without any format; using us gives the same result as %D. Combining %T with a unit is available in 2.4.13 and later.</td>
 </tr>

 <tr>
 <td valign="top">%u</td>
 <td>Remote user if the request was authenticated. May be bogus if return status (%s) is 401 (unauthorized).</td>
 </tr>

 <tr>
 <td valign="top">%U</td>
 <td>The URL path requested, not including any query string.</td>
 </tr>

 <tr>
 <td valign="top">%v</td>
 <td>The canonical ServerName of the server serving the request.</td>
 </tr>

 <tr>
 <td valign="top">%V</td>
 <td>The server name according to the UseCanonicalName setting.</td>
 </tr>

 <tr>
 <td valign="top">%X</td>
 <td>Connection status when response is completed:
 X = Connection aborted before the response completed.
 + = Connection may be kept alive after the response is sent.
 - = Connection will be closed after the response is sent.</td>
 </tr>

 <tr>
 <td valign="top">%I</td>
 <td>Bytes received, including request and headers. Cannot be zero. You need to enable mod_logio to use this.</td>
 </tr>

 <tr>
 <td valign="top">%O</td>
 <td>Bytes sent, including headers. May be zero in rare cases such as when a request is aborted before a response is sent. You need to enable mod_logio to use this.</td>
 </tr>

 <tr>
 <td valign="top">%S</td>
 <td>Bytes transferred (received and sent), including request and headers, cannot be zero. This is the combination of %I and %O. You need to enable mod_logio to use this.</td>
 </tr>

 <tr>
 <td valign="top">%{VARNAME}^ti</td>
 <td>The contents of VARNAME: trailer line(s) in the request sent to the server.</td>
 </tr>

 <tr>
 <td>%{VARNAME}^to</td>
 <td>The contents of VARNAME: trailer line(s) in the response sent from the server.</td>
 </tr>

 </table>
 */
public class CustomRequestLog extends AbstractLifeCycle implements RequestLog
{
    protected static final Logger LOG = Log.getLogger(CustomRequestLog.class);

    private static ThreadLocal<StringBuilder> _buffers = ThreadLocal.withInitial(() -> new StringBuilder(256));

    private String[] _ignorePaths;
    private boolean _extended;
    private transient PathMappings<String> _ignorePathMap;
    private boolean _preferProxiedForAddress;
    private transient DateCache _logDateCache;
    private String _logDateFormat = "dd/MMM/yyyy:HH:mm:ss Z";
    private Locale _logLocale = Locale.getDefault();
    private String _logTimeZone = "GMT";

    private final MethodHandle _logHandle;

    public CustomRequestLog(String formatString)
    {
        try
        {
            _logHandle = getLogHandle(formatString);
        }
        catch (Throwable t)
        {
            throw new IllegalStateException(t);
        }
    }

    /* ------------------------------------------------------------ */

    /**
     * Is logging enabled
     * @return true if logging is enabled
     */
    protected boolean isEnabled()
    {
        return true;
    }

    /* ------------------------------------------------------------ */

    /**
     * Write requestEntry out. (to disk or slf4j log)
     * @param requestEntry the request entry
     * @throws IOException if unable to write the entry
     */
    protected void write(String requestEntry) throws IOException
    {
    }

    /* ------------------------------------------------------------ */


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
            if (_ignorePathMap != null && _ignorePathMap.getMatch(request.getRequestURI()) != null)
                return;

            if (!isEnabled())
                return;

            StringBuilder sb = _buffers.get();
            sb.setLength(0);

            _logHandle.invoke(sb, request);

            String log = sb.toString();
            write(log);
        }
        catch (Throwable e)
        {
            LOG.warn(e);
        }
    }

    /**
     * Extract the user authentication
     * @param request The request to extract from
     * @return The string to log for authenticated user.
     */
    protected String getAuthentication(Request request)
    {
        Authentication authentication = request.getAuthentication();

        if (authentication instanceof Authentication.User)
            return ((Authentication.User)authentication).getUserIdentity().getUserPrincipal().getName();

        // TODO extract the user name if it is Authentication.Deferred and return as '?username'

        return null;
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
     * Controls whether the actual IP address of the connection or the IP address from the X-Forwarded-For header will
     * be logged.
     *
     * @param preferProxiedForAddress true - IP address from header will be logged, false - IP address from the
     *                                connection will be logged
     */
    public void setPreferProxiedForAddress(boolean preferProxiedForAddress)
    {
        _preferProxiedForAddress = preferProxiedForAddress;
    }

    /**
     * Retrieved log X-Forwarded-For IP address flag.
     *
     * @return value of the flag
     */
    public boolean getPreferProxiedForAddress()
    {
        return _preferProxiedForAddress;
    }

    /**
     * Set the extended request log format flag.
     *
     * @param extended true - log the extended request information, false - do not log the extended request information
     */
    public void setExtended(boolean extended)
    {
        _extended = extended;
    }

    /**
     * Retrieve the extended request log format flag.
     *
     * @return value of the flag
     */
    @ManagedAttribute("use extended NCSA format")
    public boolean isExtended()
    {
        return _extended;
    }

    /**
     * Set up request logging and open log file.
     *
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    @Override
    protected synchronized void doStart() throws Exception
    {
        if (_logDateFormat != null)
        {
            _logDateCache = new DateCache(_logDateFormat, _logLocale ,_logTimeZone);
        }

        if (_ignorePaths != null && _ignorePaths.length > 0)
        {
            _ignorePathMap = new PathMappings<>();
            for (int i = 0; i < _ignorePaths.length; i++)
                _ignorePathMap.put(_ignorePaths[i], _ignorePaths[i]);
        }
        else
            _ignorePathMap = null;

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        _logDateCache = null;
        super.doStop();
    }

    /**
     * Set the timestamp format for request log entries in the file. If this is not set, the pre-formated request
     * timestamp is used.
     *
     * @param format timestamp format string
     */
    public void setLogDateFormat(String format)
    {
        _logDateFormat = format;
    }

    /**
     * Retrieve the timestamp format string for request log entries.
     *
     * @return timestamp format string.
     */
    public String getLogDateFormat()
    {
        return _logDateFormat;
    }

    /**
     * Set the locale of the request log.
     *
     * @param logLocale locale object
     */
    public void setLogLocale(Locale logLocale)
    {
        _logLocale = logLocale;
    }

    /**
     * Retrieve the locale of the request log.
     *
     * @return locale object
     */
    public Locale getLogLocale()
    {
        return _logLocale;
    }

    /**
     * Set the timezone of the request log.
     *
     * @param tz timezone string
     */
    public void setLogTimeZone(String tz)
    {
        _logTimeZone = tz;
    }

    /**
     * Retrieve the timezone of the request log.
     *
     * @return timezone string
     */
    @ManagedAttribute("the timezone")
    public String getLogTimeZone()
    {
        return _logTimeZone;
    }


    private static void append(StringBuilder buf, String s)
    {
        if (s==null || s.length()==0)
            buf.append('-');
        else
            buf.append(s);
    }

    private static void append(String s, StringBuilder buf)
    {
        append(buf, s);
    }

    public static void logClientIP(StringBuilder b, Request request)
    {
        b.append(request.getRemoteAddr());
    }


    //TODO add response to signature
    private static final MethodType LOG_TYPE = methodType(Void.TYPE, StringBuilder.class, Request.class);

    private MethodHandle getLogHandle(String formatString) throws Throwable
    {
        MethodHandle append = MethodHandles.lookup().findStatic(CustomRequestLog.class, "append", methodType(Void.TYPE, String.class, StringBuilder.class));
        MethodHandle logHandle = dropArguments(append.bindTo("\n"), 1, Request.class);

        final Pattern PERCENT_CODE = Pattern.compile("(?<remaining>.*)%(?:\\{(?<arg>[^{}]+)})?(?<code>[a-zA-Z%])");
        final Pattern LITERAL = Pattern.compile("(?<remaining>.*%(?:\\{[^{}]+})?[a-zA-Z%])(?<literal>.*)");

        String remaining = formatString;
        while(remaining.length()>0)
        {
            Matcher m = PERCENT_CODE.matcher(remaining);
            if (m.matches())
            {
                String code = m.group("code");
                String arg = m.group("arg");

                logHandle = updateLogHandle(logHandle, code, arg);
                remaining = m.group("remaining");
                continue;
            }

            Matcher m2 = LITERAL.matcher(remaining);
            String literal;
            if (m2.matches())
            {
                literal = m2.group("literal");
                remaining = m2.group("remaining");
            }
            else
            {
                literal = remaining;
                remaining = "";
            }
            logHandle = updateLogHandle(logHandle, append, literal);
        }

        return logHandle;
    }


    private MethodHandle updateLogHandle(MethodHandle logHandle, MethodHandle append, String literal)
    {
        return foldArguments(logHandle, dropArguments(append.bindTo(literal), 1, Request.class));
    }


    private MethodHandle updateLogHandle(MethodHandle logHandle, String code, String arg) throws Throwable
    {
        switch (code)
        {
            case "a":
            {
                String method = "logClientIP";
                MethodHandle specificHandle = MethodHandles.lookup().findStatic(CustomRequestLog.class, method, LOG_TYPE);
                return foldArguments(logHandle, specificHandle);
            }

            default:
                LOG.warn("Unsupported code %{}", code);
                return logHandle;
        }
    }
}
