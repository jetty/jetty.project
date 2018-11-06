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
import java.util.ArrayList;
import java.util.List;
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
    private final String _format;

    public CustomRequestLog(String formatString)
    {
        try
        {
            _format = formatString;
            _logHandle = getLogHandle(formatString);
        }
        catch (Throwable t)
        {
            throw new IllegalStateException();
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

    public static void main(String[] args) throws Throwable
    {
        Request request = new Request(null, null);


        String formatString = "clientIP: %a | ";
        MethodHandle logHandle = getLogHandle(formatString);


        StringBuilder b = new StringBuilder();
        logHandle.invoke(b, request);
        System.err.println(b.toString());

    }

    private static MethodHandle getLogHandle(String formatString) throws NoSuchMethodException, IllegalAccessException
    {
        //TODO add response to signature
        MethodType logType = methodType(Void.TYPE, StringBuilder.class, Request.class);
        MethodHandle append = MethodHandles.lookup().findStatic(CustomRequestLog.class, "append", methodType(Void.TYPE, String.class, StringBuilder.class));
        MethodHandle logHandle = dropArguments(append.bindTo("\n"), 1, Request.class);

        for (Token s : tokenize(formatString))
        {
            if (s.isLiteralString())
            {
                logHandle = foldArguments(logHandle, dropArguments(append.bindTo(s.literal), 1, Request.class));
            }
            else
            {
                switch (s.code)
                {

                    case "a":
                    {
                        String method = "logClientIP";
                        MethodHandle specificHandle = MethodHandles.lookup().findStatic(CustomRequestLog.class, method, logType);
                        logHandle = foldArguments(logHandle, specificHandle);
                        break;
                    }
                }
            }
        }

        return logHandle;
    }

    private static List<Token> tokenize(String value)
    {
        List<Token> tokens = new ArrayList<>();

        final Pattern PERCENT_CODE = Pattern.compile("(?<remaining>.*)%(?:\\{(?<arg>[^{}]+)})?(?<code>[a-zA-Z%])");
        final Pattern LITERAL = Pattern.compile("(?<remaining>.*%(?:\\{[^{}]+})?[a-zA-Z%])(?<literal>.*)");

        while(value.length()>0)
        {
            Matcher m = PERCENT_CODE.matcher(value);
            Matcher m2 = LITERAL.matcher(value);
            if (m.matches())
            {
                String code = m.group("code");
                String arg = m.group("arg");

                tokens.add(new Token(code, arg));
                value = m.group("remaining");
                continue;
            }

            String literal;
            if (m2.matches())
            {
                literal = m2.group("literal");
                value = m2.group("remaining");
            }
            else
            {
                literal = value;
                value = "";
            }
            tokens.add(new Token(literal));

        }
        return tokens;
    }




    private static class Token
    {
        public boolean isLiteralString()
        {
            return(literal != null);
        }

        public boolean isPercentCode()
        {
            return(code != null);
        }

        public String code = null;
        public String arg = null;
        public String literal = null;

        public Token(String code, String arg)
        {
            this.code = code;
            this.arg = arg;
        }

        public Token(String literal)
        {
            this.literal = literal;
        }
    }
}
