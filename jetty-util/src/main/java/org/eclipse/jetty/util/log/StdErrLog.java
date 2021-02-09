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

package org.eclipse.jetty.util.log;

import java.io.PrintStream;
import java.security.AccessControlException;
import java.util.Properties;

import org.eclipse.jetty.util.DateCache;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * StdErr Logging implementation.
 * <p>
 * A Jetty {@link Logger} that sends all logs to STDERR ({@link System#err}) with basic formatting.
 * <p>
 * Supports named loggers, and properties based configuration.
 * <p>
 * Configuration Properties:
 * <dl>
 * <dt>${name|hierarchy}.LEVEL=(ALL|DEBUG|INFO|WARN|OFF)</dt>
 * <dd>
 * Sets the level that the Logger should log at.<br>
 * Names can be a package name, or a fully qualified class name.<br>
 * Default: INFO<br>
 * <br>
 * Examples:
 * <dl>
 * <dt>org.eclipse.jetty.LEVEL=WARN</dt>
 * <dd>indicates that all of the jetty specific classes, in any package that
 * starts with <code>org.eclipse.jetty</code> should log at level WARN.</dd>
 * <dt>org.eclipse.jetty.io.ChannelEndPoint.LEVEL=ALL</dt>
 * <dd>indicates that the specific class, ChannelEndPoint, should log all
 * logging events that it can generate, including DEBUG, INFO, WARN (and even special
 * internally ignored exception cases).</dd>
 * </dl>
 * </dd>
 *
 * <dt>${name}.SOURCE=(true|false)</dt>
 * <dd>
 * Logger specific, attempt to print the java source file name and line number
 * where the logging event originated from.<br>
 * Name must be a fully qualified class name (package name hierarchy is not supported
 * by this configurable)<br>
 * Warning: this is a slow operation and will have an impact on performance!<br>
 * Default: false
 * </dd>
 *
 * <dt>${name}.STACKS=(true|false)</dt>
 * <dd>
 * Logger specific, control the display of stacktraces.<br>
 * Name must be a fully qualified class name (package name hierarchy is not supported
 * by this configurable)<br>
 * Default: true
 * </dd>
 *
 * <dt>org.eclipse.jetty.util.log.stderr.SOURCE=(true|false)</dt>
 * <dd>Special Global Configuration, attempt to print the java source file name and line number
 * where the logging event originated from.<br>
 * Default: false
 * </dd>
 *
 * <dt>org.eclipse.jetty.util.log.stderr.LONG=(true|false)</dt>
 * <dd>Special Global Configuration, when true, output logging events to STDERR using
 * long form, fully qualified class names.  when false, use abbreviated package names<br>
 * Default: false
 * </dd>
 * <dt>org.eclipse.jetty.util.log.stderr.ESCAPE=(true|false)</dt>
 * <dd>Global Configuration, when true output logging events to STDERR are always
 * escaped so that control characters are replaced with '?";  '\r' with '&lt;' and '\n' replaced '|'<br>
 * Default: true
 * </dd>
 * </dl>
 */
@ManagedObject("Jetty StdErr Logging Implementation")
public class StdErrLog extends AbstractLogger
{
    private static final String EOL = System.lineSeparator();
    private static final Object[] EMPTY_ARGS = new Object[0];
    // Do not change output format lightly, people rely on this output format now.
    private static int __tagpad = Integer.parseInt(Log.__props.getProperty("org.eclipse.jetty.util.log.StdErrLog.TAG_PAD", "0"));
    private static DateCache _dateCache;

    private static final boolean __source = Boolean.parseBoolean(Log.__props.getProperty("org.eclipse.jetty.util.log.SOURCE",
        Log.__props.getProperty("org.eclipse.jetty.util.log.stderr.SOURCE", "false")));
    private static final boolean __long = Boolean.parseBoolean(Log.__props.getProperty("org.eclipse.jetty.util.log.stderr.LONG", "false"));
    private static final boolean __escape = Boolean.parseBoolean(Log.__props.getProperty("org.eclipse.jetty.util.log.stderr.ESCAPE", "true"));

    static
    {
        String[] deprecatedProperties =
            {"DEBUG", "org.eclipse.jetty.util.log.DEBUG", "org.eclipse.jetty.util.log.stderr.DEBUG"};

        // Toss a message to users about deprecated system properties
        for (String deprecatedProp : deprecatedProperties)
        {
            if (System.getProperty(deprecatedProp) != null)
            {
                System.err.printf("System Property [%s] has been deprecated! (Use org.eclipse.jetty.LEVEL=DEBUG instead)%n", deprecatedProp);
            }
        }

        try
        {
            _dateCache = new DateCache("yyyy-MM-dd HH:mm:ss");
        }
        catch (Exception x)
        {
            x.printStackTrace(System.err);
        }
    }

    public static void setTagPad(int pad)
    {
        __tagpad = pad;
    }

    private int _level;
    // Level that this Logger was configured as (remembered in special case of .setDebugEnabled())
    private int _configuredLevel;
    // The alternate stream to print to (if set)
    private PrintStream _altStream;
    private boolean _source;
    // Print the long form names, otherwise use abbreviated
    private boolean _printLongNames = __long;
    // The full log name, as provided by the system.
    private final String _name;
    // The abbreviated log name (used by default, unless _long is specified)
    protected final String _abbrevname;
    private boolean _hideStacks = false;

    public static int getLoggingLevel(Properties props, String name)
    {
        int level = lookupLoggingLevel(props, name);
        if (level == LEVEL_DEFAULT)
        {
            level = lookupLoggingLevel(props, "log");
            if (level == LEVEL_DEFAULT)
                level = LEVEL_INFO;
        }
        return level;
    }

    /**
     * Obtain a StdErrLog reference for the specified class, a convenience method used most often during testing to allow for control over a specific logger.
     * <p>
     * Must be actively using StdErrLog as the Logger implementation.
     *
     * @param clazz the Class reference for the logger to use.
     * @return the StdErrLog logger
     * @throws RuntimeException if StdErrLog is not the active Logger implementation.
     */
    public static StdErrLog getLogger(Class<?> clazz)
    {
        Logger log = Log.getLogger(clazz);
        if (log instanceof StdErrLog)
        {
            return (StdErrLog)log;
        }
        throw new RuntimeException("Logger for " + clazz + " is not of type StdErrLog");
    }

    /**
     * Construct an anonymous StdErrLog (no name).
     * <p>
     * NOTE: Discouraged usage!
     */
    public StdErrLog()
    {
        this(null);
    }

    /**
     * Construct a named StdErrLog using the {@link Log} defined properties
     *
     * @param name the name of the logger
     */
    public StdErrLog(String name)
    {
        this(name, null);
    }

    /**
     * Construct a named Logger using the provided properties to configure logger.
     *
     * @param name the name of the logger
     * @param props the configuration properties
     */
    public StdErrLog(String name, Properties props)
    {
        @SuppressWarnings("ReferenceEquality")
        boolean sameObject = (props != Log.__props);
        if (props != null && sameObject)
            Log.__props.putAll(props);
        _name = name == null ? "" : name;
        _abbrevname = condensePackageString(this._name);
        _level = getLoggingLevel(Log.__props, this._name);
        _configuredLevel = _level;

        try
        {
            String source = getLoggingProperty(Log.__props, _name, "SOURCE");
            _source = source == null ? __source : Boolean.parseBoolean(source);
        }
        catch (AccessControlException ace)
        {
            _source = __source;
        }

        try
        {
            // allow stacktrace display to be controlled by properties as well
            String stacks = getLoggingProperty(Log.__props, _name, "STACKS");
            _hideStacks = stacks != null && !Boolean.parseBoolean(stacks);
        }
        catch (AccessControlException ignore)
        {
            /* ignore */
        }
    }

    @Override
    public String getName()
    {
        return _name;
    }

    public void setPrintLongNames(boolean printLongNames)
    {
        this._printLongNames = printLongNames;
    }

    public boolean isPrintLongNames()
    {
        return this._printLongNames;
    }

    public boolean isHideStacks()
    {
        return _hideStacks;
    }

    public void setHideStacks(boolean hideStacks)
    {
        _hideStacks = hideStacks;
    }

    /**
     * Is the source of a log, logged
     *
     * @return true if the class, method, file and line number of a log is logged.
     */
    public boolean isSource()
    {
        return _source;
    }

    /**
     * Set if a log source is logged.
     *
     * @param source true if the class, method, file and line number of a log is logged.
     */
    public void setSource(boolean source)
    {
        _source = source;
    }

    @Override
    public void warn(String msg, Object... args)
    {
        if (_level <= LEVEL_WARN)
        {
            StringBuilder builder = new StringBuilder(64);
            format(builder, ":WARN:", msg, args);
            println(builder);
        }
    }

    @Override
    public void warn(Throwable thrown)
    {
        warn("", thrown);
    }

    @Override
    public void warn(String msg, Throwable thrown)
    {
        if (_level <= LEVEL_WARN)
        {
            StringBuilder builder = new StringBuilder(64);
            format(builder, ":WARN:", msg, thrown);
            println(builder);
        }
    }

    @Override
    public void info(String msg, Object... args)
    {
        if (_level <= LEVEL_INFO)
        {
            StringBuilder builder = new StringBuilder(64);
            format(builder, ":INFO:", msg, args);
            println(builder);
        }
    }

    @Override
    public void info(Throwable thrown)
    {
        info("", thrown);
    }

    @Override
    public void info(String msg, Throwable thrown)
    {
        if (_level <= LEVEL_INFO)
        {
            StringBuilder builder = new StringBuilder(64);
            format(builder, ":INFO:", msg, thrown);
            println(builder);
        }
    }

    @ManagedAttribute("is debug enabled for root logger Log.LOG")
    @Override
    public boolean isDebugEnabled()
    {
        return (_level <= LEVEL_DEBUG);
    }

    /**
     * Legacy interface where a programmatic configuration of the logger level
     * is done as a wholesale approach.
     */
    @Override
    public void setDebugEnabled(boolean enabled)
    {
        int level = enabled ? LEVEL_DEBUG : this.getConfiguredLevel();
        this.setLevel(level);

        String name = getName();
        for (Logger log : Log.getLoggers().values())
        {
            if (log.getName().startsWith(name) && log instanceof StdErrLog)
            {
                StdErrLog logger = (StdErrLog)log;
                level = enabled ? LEVEL_DEBUG : logger.getConfiguredLevel();
                logger.setLevel(level);
            }
        }
    }

    private int getConfiguredLevel()
    {
        return _configuredLevel;
    }

    public int getLevel()
    {
        return _level;
    }

    /**
     * Set the level for this logger.
     * <p>
     * Available values ({@link StdErrLog#LEVEL_ALL}, {@link StdErrLog#LEVEL_DEBUG}, {@link StdErrLog#LEVEL_INFO},
     * {@link StdErrLog#LEVEL_WARN})
     *
     * @param level the level to set the logger to
     */
    public void setLevel(int level)
    {
        this._level = level;
    }

    /**
     * The alternate stream to use for STDERR.
     *
     * @param stream the stream of choice, or {@code null} to use {@link System#err}
     */
    public void setStdErrStream(PrintStream stream)
    {
        this._altStream = stream;
    }

    @Override
    public void debug(String msg, Object... args)
    {
        if (isDebugEnabled())
        {
            StringBuilder builder = new StringBuilder(64);
            format(builder, ":DBUG:", msg, args);
            println(builder);
        }
    }

    @Override
    public void debug(String msg, long arg)
    {
        if (isDebugEnabled())
        {
            StringBuilder builder = new StringBuilder(64);
            format(builder, ":DBUG:", msg, arg);
            println(builder);
        }
    }

    @Override
    public void debug(Throwable thrown)
    {
        debug("", thrown);
    }

    @Override
    public void debug(String msg, Throwable thrown)
    {
        if (isDebugEnabled())
        {
            StringBuilder builder = new StringBuilder(64);
            format(builder, ":DBUG:", msg, thrown);
            println(builder);
        }
    }

    private void println(StringBuilder builder)
    {
        if (_altStream != null)
            _altStream.println(builder);
        else
        {
            // We always use the PrintStream stored in System.err,
            // just in case someone has replaced it with a call to System.setErr(PrintStream)
            System.err.println(builder);
        }
    }

    private void format(StringBuilder builder, String level, String msg, Object... inArgs)
    {
        long now = System.currentTimeMillis();
        int ms = (int)(now % 1000);
        String d = _dateCache.formatNow(now);
        tag(builder, d, ms, level);

        Object[] msgArgs = EMPTY_ARGS;
        int msgArgsLen = 0;
        Throwable cause = null;

        if (inArgs != null)
        {
            msgArgs = inArgs;
            msgArgsLen = inArgs.length;
            if (msgArgsLen > 0 && inArgs[msgArgsLen - 1] instanceof Throwable)
            {
                cause = (Throwable)inArgs[msgArgsLen - 1];
                msgArgsLen--;
            }
        }

        if (msg == null)
        {
            msg = "";
            for (int i = 0; i < msgArgsLen; i++)
            {
                //noinspection StringConcatenationInLoop
                msg += "{} ";
            }
        }
        String braces = "{}";
        int start = 0;
        for (int i = 0; i < msgArgsLen; i++)
        {
            Object arg = msgArgs[i];
            int bracesIndex = msg.indexOf(braces, start);
            if (bracesIndex < 0)
            {
                escape(builder, msg.substring(start));
                builder.append(" ");
                if (arg != null)
                    builder.append(arg);
                start = msg.length();
            }
            else
            {
                escape(builder, msg.substring(start, bracesIndex));
                if (arg != null)
                    builder.append(arg);
                start = bracesIndex + braces.length();
            }
        }
        escape(builder, msg.substring(start));

        if (cause != null)
        {
            if (isHideStacks())
            {
                builder.append(": ").append(cause);
            }
            else
            {
                formatCause(builder, cause, "");
            }
        }
    }

    private void formatCause(StringBuilder builder, Throwable cause, String indent)
    {
        builder.append(EOL).append(indent);
        escape(builder, cause.toString());
        StackTraceElement[] elements = cause.getStackTrace();
        for (int i = 0; elements != null && i < elements.length; i++)
        {
            builder.append(EOL).append(indent).append("\tat ");
            escape(builder, elements[i].toString());
        }

        for (Throwable suppressed : cause.getSuppressed())
        {
            builder.append(EOL).append(indent).append("Suppressed: ");
            formatCause(builder, suppressed, "\t|" + indent);
        }

        Throwable by = cause.getCause();
        if (by != null && by != cause)
        {
            builder.append(EOL).append(indent).append("Caused by: ");
            formatCause(builder, by, indent);
        }
    }

    private void escape(StringBuilder builder, String str)
    {
        if (__escape)
        {
            for (int i = 0; i < str.length(); ++i)
            {
                char c = str.charAt(i);
                if (Character.isISOControl(c))
                {
                    if (c == '\n')
                    {
                        builder.append('|');
                    }
                    else if (c == '\r')
                    {
                        builder.append('<');
                    }
                    else
                    {
                        builder.append('?');
                    }
                }
                else
                {
                    builder.append(c);
                }
            }
        }
        else
            builder.append(str);
    }

    private void tag(StringBuilder builder, String d, int ms, String tag)
    {
        builder.setLength(0);
        builder.append(d);
        if (ms > 99)
        {
            builder.append('.');
        }
        else if (ms > 9)
        {
            builder.append(".0");
        }
        else
        {
            builder.append(".00");
        }
        builder.append(ms).append(tag);

        String name = _printLongNames ? _name : _abbrevname;
        String tname = Thread.currentThread().getName();

        int p = __tagpad > 0 ? (name.length() + tname.length() - __tagpad) : 0;

        if (p < 0)
        {
            builder
                .append(name)
                .append(':')
                .append("                                                  ", 0, -p)
                .append(tname);
        }
        else if (p == 0)
        {
            builder.append(name).append(':').append(tname);
        }
        builder.append(':');

        if (_source)
        {
            Throwable source = new Throwable();
            StackTraceElement[] frames = source.getStackTrace();
            for (final StackTraceElement frame : frames)
            {
                String clazz = frame.getClassName();
                if (clazz.equals(StdErrLog.class.getName()) || clazz.equals(Log.class.getName()))
                {
                    continue;
                }
                if (!_printLongNames && clazz.startsWith("org.eclipse.jetty."))
                {
                    builder.append(condensePackageString(clazz));
                }
                else
                {
                    builder.append(clazz);
                }
                builder.append('#').append(frame.getMethodName());
                if (frame.getFileName() != null)
                {
                    builder.append('(').append(frame.getFileName()).append(':').append(frame.getLineNumber()).append(')');
                }
                builder.append(':');
                break;
            }
        }

        builder.append(' ');
    }

    /**
     * Create a Child Logger of this Logger.
     */
    @Override
    protected Logger newLogger(String fullname)
    {
        StdErrLog logger = new StdErrLog(fullname);
        // Preserve configuration for new loggers configuration
        logger.setPrintLongNames(_printLongNames);
        logger._altStream = this._altStream;

        // Force the child to have any programmatic configuration
        if (_level != _configuredLevel)
            logger._level = _level;

        return logger;
    }

    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder();
        s.append("StdErrLog:");
        s.append(_name);
        s.append(":LEVEL=");
        switch (_level)
        {
            case LEVEL_ALL:
                s.append("ALL");
                break;
            case LEVEL_DEBUG:
                s.append("DEBUG");
                break;
            case LEVEL_INFO:
                s.append("INFO");
                break;
            case LEVEL_WARN:
                s.append("WARN");
                break;
            default:
                s.append("?");
                break;
        }
        return s.toString();
    }

    @Override
    public void ignore(Throwable ignored)
    {
        if (_level <= LEVEL_ALL)
        {
            StringBuilder builder = new StringBuilder(64);
            format(builder, ":IGNORED:", "", ignored);
            println(builder);
        }
    }
}
