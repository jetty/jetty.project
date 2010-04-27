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

import org.eclipse.jetty.util.DateCache;

/**
 * StdErr Logging. This implementation of the Logging facade sends all logs to
 * StdErr with minimal formatting.
 *
 * If the system property "org.eclipse.jetty.util.log.DEBUG" is set, then debug
 * logs are printed if stderr is being used.
 * <p>
 * For named debuggers, the system property name+".DEBUG" is checked. If it is
 * not not set, then "org.eclipse.jetty.util.log.DEBUG" is used as the default.
 */
public class StdErrLog implements Logger
{
    private static DateCache _dateCache;

    private final static boolean __debug = Boolean.parseBoolean(
            System.getProperty("org.eclipse.jetty.util.log.DEBUG",
                    System.getProperty("org.eclipse.jetty.util.log.stderr.DEBUG", "false")));

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
    private final String _name;
    private boolean _hideStacks = false;

    public StdErrLog()
    {
        this(null);
    }

    public StdErrLog(String name)
    {
        this._name = name == null ? "" : name;

        try
        {
            _debug = Boolean.parseBoolean(System.getProperty(_name + ".DEBUG", Boolean.toString(__debug)));
        }
        catch (AccessControlException ace)
        {
            _debug = __debug;
        }
    }

    public String getName()
    {
        return _name;
    }

    public boolean isHideStacks()
    {
        return _hideStacks;
    }

    public void setHideStacks(boolean hideStacks)
    {
        _hideStacks = hideStacks;
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
        format(buffer, ":INFO:", msg, thrown);
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
        buffer.append(ms).append(tag).append(_name).append(':');
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
                break;
            escape(builder, msg.substring(start, bracesIndex));
            builder.append(String.valueOf(arg));
            start = bracesIndex + braces.length();
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
        }
    }

    public Logger getLogger(String name)
    {
        if ((name == null && this._name == null) || (name != null && name.equals(this._name)))
            return this;
        return new StdErrLog(_name == null || _name.length() == 0?name:_name + "." + name);
    }

    @Override
    public String toString()
    {
        return "StdErrLog:" + _name + ":DEBUG=" + _debug;
    }
}
