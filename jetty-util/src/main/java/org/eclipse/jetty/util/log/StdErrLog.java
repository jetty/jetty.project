// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.util.log;

import java.security.AccessControlException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.util.DateCache;

/**
 * StdErr Logging. This implementation of the Logging facade sends all logs to
 * StdErr with minimal formatting.
 * <p>
 * If the system property "org.eclipse.jetty.util.log.DEBUG" is set, then debug
 * logs are printed if stderr is being used. For named debuggers, the system 
 * property name+".DEBUG" is checked. If it is not not set, then 
 * "org.eclipse.jetty.util.log.DEBUG" is used as the default.
 * <p>
 * If the system property "org.eclipse.jetty.util.log.SOURCE" is set, then the
 * source method/file of a log is logged. For named debuggers, the system 
 * property name+".SOURCE" is checked. If it is not not set, then 
 * "org.eclipse.jetty.util.log.SOURCE" is used as the default.
 * <p>
 * If the system property "org.eclipse.jetty.util.log.LONG" is set, then the
 * full, unabbreviated name of the logger is used for logging. 
 * For named debuggers, the system property name+".LONG" is checked. 
 * If it is not not set, then "org.eclipse.jetty.util.log.LONG" is used as the default.
 */
public class StdErrLog implements Logger
{
    private static DateCache _dateCache;

    private final static boolean __debug = Boolean.parseBoolean(
            System.getProperty("org.eclipse.jetty.util.log.DEBUG",
                    System.getProperty("org.eclipse.jetty.util.log.stderr.DEBUG", "false")));
    private final static boolean __source = Boolean.parseBoolean(
            System.getProperty("org.eclipse.jetty.util.log.SOURCE",
                    System.getProperty("org.eclipse.jetty.util.log.stderr.SOURCE", "false")));
    private final static boolean __long = Boolean.parseBoolean(
            System.getProperty("org.eclipse.jetty.util.log.stderr.LONG", "false"));

    private final static ConcurrentMap<String,StdErrLog> __loggers = new ConcurrentHashMap<String, StdErrLog>();
    
    static
    {
        try
        {
            _dateCache = new DateCache("yyyy-MM-dd HH:mm:ss");
        }
        catch (Exception x)
        {
            x.printStackTrace();
        }
    }

    private boolean _debug = __debug;
    private boolean _source = __source;
    // Print the long form names, otherwise use abbreviated
    private boolean _printLongNames = __long;
    // The full log name, as provided by the system.
    private final String _name; 
    // The abbreviated log name (used by default, unless _long is specified)
    private final String _abbrevname;
    private boolean _hideStacks = false;

    public StdErrLog()
    {
        this(null);
    }

    public StdErrLog(String name)
    {
        this._name = name == null ? "" : name;
        this._abbrevname = condensePackageString(this._name);

        try
        {
            _debug = Boolean.parseBoolean(System.getProperty(_name + ".DEBUG", Boolean.toString(_debug)));
        }
        catch (AccessControlException ace)
        {
            _debug = __debug;
        }
        
        try
        {
            _source = Boolean.parseBoolean(System.getProperty(_name + ".SOURCE", Boolean.toString(_source)));
        }
        catch (AccessControlException ace)
        {
            _source = __source;
        }
    }
    
    /**
     * Condenses a classname by stripping down the package name to just the first character of each package name
     * segment.
     * <p>
     * 
     * <pre>
     * Examples:
     * "org.eclipse.jetty.test.FooTest"           = "oejt.FooTest"
     * "org.eclipse.jetty.server.logging.LogTest" = "orjsl.LogTest"
     * </pre>
     * 
     * @param classname
     *            the fully qualified class name
     * @return the condensed name
     */
    protected static String condensePackageString(String classname)
    {
        String parts[] = classname.split("\\.");
        StringBuilder dense = new StringBuilder();
        for (int i = 0; i < (parts.length - 1); i++)
        {
            dense.append(parts[i].charAt(0));
        }
        if (dense.length() > 0)
        {
            dense.append('.');
        }
        dense.append(parts[parts.length - 1]);
        return dense.toString();
    }

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

    /* ------------------------------------------------------------ */
    /** Is the source of a log, logged
     * @return true if the class, method, file and line number of a log is logged.
     */
    public boolean isSource()
    {
        return _source;
    }

    /* ------------------------------------------------------------ */
    /** Set if a log source is logged.
     * @param source true if the class, method, file and line number of a log is logged.
     */
    public void setSource(boolean source)
    {
        _source = source;
    }

    public void warn(String msg, Object... args)
    {
        StringBuilder buffer = new StringBuilder(64);
        format(buffer, ":WARN:", msg, args);
        System.err.println(buffer);
    }

    public void warn(Throwable thrown)
    {
        warn("", thrown);
    }

    public void warn(String msg, Throwable thrown)
    {
        StringBuilder buffer = new StringBuilder(64);
        format(buffer, ":WARN:", msg, thrown);
        System.err.println(buffer);
    }

    public void info(String msg, Object... args)
    {
        StringBuilder buffer = new StringBuilder(64);
        format(buffer, ":INFO:", msg, args);
        System.err.println(buffer);
    }

    public void info(Throwable thrown)
    {
        info("", thrown);
    }

    public void info(String msg, Throwable thrown)
    {
        StringBuilder buffer = new StringBuilder(64);
        format(buffer, ":INFO:", msg, thrown);
        System.err.println(buffer);
    }

    public boolean isDebugEnabled()
    {
        return _debug;
    }

    public void setDebugEnabled(boolean enabled)
    {
        _debug = enabled;
    }

    public void debug(String msg, Object... args)
    {
        if (!_debug)
            return;
        StringBuilder buffer = new StringBuilder(64);
        format(buffer, ":DBUG:", msg, args);
        System.err.println(buffer);
    }

    public void debug(Throwable thrown)
    {
        debug("", thrown);
    }

    public void debug(String msg, Throwable thrown)
    {
        if (!_debug)
            return;
        StringBuilder buffer = new StringBuilder(64);
        format(buffer, ":DBUG:", msg, thrown);
        System.err.println(buffer);
    }

    private void format(StringBuilder buffer, String level, String msg, Object... args)
    {
        String d = _dateCache.now();
        int ms = _dateCache.lastMs();
        tag(buffer, d, ms, level);
        format(buffer, msg, args);
    }

    private void format(StringBuilder buffer, String level, String msg, Throwable thrown)
    {
        format(buffer, level, msg);
        if (isHideStacks())
            format(buffer, String.valueOf(thrown));
        else
            format(buffer, thrown);
    }

    private void tag(StringBuilder buffer, String d, int ms, String tag)
    {
        buffer.setLength(0);
        buffer.append(d);
        if (ms > 99)
            buffer.append('.');
        else if (ms > 9)
            buffer.append(".0");
        else
            buffer.append(".00");
        buffer.append(ms).append(tag);
        if(_printLongNames) {
            buffer.append(_name);
        } else {
            buffer.append(_abbrevname);
        }
        buffer.append(':');
        if (_source)
        {
            Throwable source = new Throwable();
            StackTraceElement[] frames =  source.getStackTrace();
            for (int i=0;i<frames.length;i++)
            {
                final StackTraceElement frame = frames[i];
                String clazz = frame.getClassName();
                if (clazz.equals(StdErrLog.class.getName())|| clazz.equals(Log.class.getName()))
                    continue;
                if (!_printLongNames && clazz.startsWith("org.eclipse.jetty.")) {
                    buffer.append(condensePackageString(clazz));
                } else {
                    buffer.append(clazz);
                }
                buffer.append('#').append(frame.getMethodName());
                if (frame.getFileName()!=null)
                    buffer.append('(').append(frame.getFileName()).append(':').append(frame.getLineNumber()).append(')');
                buffer.append(':');
                break;
            }
        }
    }

    private void format(StringBuilder builder, String msg, Object... args)
    {
        msg = String.valueOf(msg); // Avoids NPE
        String braces = "{}";
        int start = 0;
        for (Object arg : args)
        {
            int bracesIndex = msg.indexOf(braces, start);
            if (bracesIndex < 0)
            {
                escape(builder, msg.substring(start));
                builder.append(" ");
                builder.append(arg);
                start = msg.length();
            }
            else
            {
                escape(builder, msg.substring(start, bracesIndex));
                builder.append(String.valueOf(arg));
                start = bracesIndex + braces.length();
            }
        }
        escape(builder, msg.substring(start));
    }

    private void escape(StringBuilder builder, String string)
    {
        for (int i = 0; i < string.length(); ++i)
        {
            char c = string.charAt(i);
            if (Character.isISOControl(c))
            {
                if (c == '\n')
                    builder.append('|');
                else if (c == '\r')
                    builder.append('<');
                else
                    builder.append('?');
            }
            else
                builder.append(c);
        }
    }

    private void format(StringBuilder buffer, Throwable thrown)
    {
        if (thrown == null)
        {
            buffer.append("null");
        }
        else
        {
            buffer.append('\n');
            format(buffer, thrown.toString());
            StackTraceElement[] elements = thrown.getStackTrace();
            for (int i = 0; elements != null && i < elements.length; i++)
            {
                buffer.append("\n\tat ");
                format(buffer, elements[i].toString());
            }
            
            Throwable cause = thrown.getCause();
            if (cause!=null && cause!=thrown)
            {
                buffer.append("\nCaused by: ");
                format(buffer,cause);
            }
        }
    }

    public Logger getLogger(String name)
    {
        String fullname=_name == null || _name.length() == 0?name:_name + "." + name;
        
        if ((name == null && this._name == null) || fullname.equals(_name))
            return this;
        
        StdErrLog logger = __loggers.get(name);
        if (logger==null)
        {
            StdErrLog sel=new StdErrLog(fullname);
            // Preserve configuration for new loggers configuration
            sel.setPrintLongNames(_printLongNames);
            sel.setDebugEnabled(_debug);
            sel.setSource(_source);
            logger=__loggers.putIfAbsent(fullname,sel);
            if (logger==null)
                logger=sel;
        }
        
        return logger;
    }

    @Override
    public String toString()
    {
        return "StdErrLog:" + _name + ":DEBUG=" + _debug;
    }

    public void ignore(Throwable ignored)
    {
        if (Log.isIgnored())
        {
            warn(Log.IGNORED, ignored);
        }
    }
}
