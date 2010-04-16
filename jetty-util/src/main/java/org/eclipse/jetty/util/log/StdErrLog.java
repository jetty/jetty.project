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

/*-----------------------------------------------------------------------*/
/**
 * StdErr Logging. This implementation of the Logging facade sends all logs to
 * StdErr with minimal formatting.
 * 
 * If the system property "org.eclipse.jetty.util.log.DEBUG" is set, then debug
 * logs are printed if stderr is being used.
 * <p>
 * For named debuggers, the system property name+".DEBUG" is checked. If it is
 * not not set, then "org.eclipse.jetty.util.log.DEBUG" is used as the default.
 * 
 */
public class StdErrLog implements Logger
{
    private static DateCache _dateCache;

    private final static String LN = System.getProperty("line.separator");
    private final static boolean __debug = Boolean.parseBoolean(System.getProperty("org.eclipse.jetty.util.log.DEBUG",System.getProperty(
            "org.eclipse.jetty.util.log.stderr.DEBUG","false")));
    private boolean _debug = __debug;
    private final String _name;
    private boolean _hideStacks = false;

    static
    {
        try
        {
            _dateCache = new DateCache("yyyy-MM-dd HH:mm:ss");
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

    }

    public StdErrLog()
    {
        this(null);
    }

    public StdErrLog(String name)
    {
        this._name = name == null?"":name;

        try
        {
            _debug = Boolean.parseBoolean(System.getProperty(_name + ".DEBUG",Boolean.toString(__debug)));
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

    public boolean isDebugEnabled()
    {
        return _debug;
    }

    public void setDebugEnabled(boolean enabled)
    {
        _debug = enabled;
    }

    public boolean isHideStacks()
    {
        return _hideStacks;
    }

    public void setHideStacks(boolean hideStacks)
    {
        _hideStacks = hideStacks;
    }

    public void info(String msg)
    {
        String d = _dateCache.now();
        int ms = _dateCache.lastMs();
        StringBuilder buffer = new StringBuilder(64);
        tag(buffer,d,ms, ":INFO:");
        format(buffer, msg);
        System.err.println(buffer);

    }

    public void info(String msg, Object arg0, Object arg1)
    {
        String d = _dateCache.now();
        int ms = _dateCache.lastMs();
        StringBuilder buffer = new StringBuilder(64);
        tag(buffer,d,ms, ":INFO:");
        format(buffer,msg,arg0, arg1);
        System.err.println(buffer);

    }

    public void debug(String msg, Throwable th)
    {
        if (_debug)
        {
            String d = _dateCache.now();
            int ms = _dateCache.lastMs();
            StringBuilder buffer = new StringBuilder(64);
            tag(buffer,d,ms, ":DBUG:");
            format(buffer, msg);
            if (_hideStacks)
                format(buffer, String.valueOf(th));
            else
                format(th, buffer);
            System.err.println(buffer);
        }
    }

    public void debug(String msg)
    {
        if (_debug)
        {
            String d = _dateCache.now();
            int ms = _dateCache.lastMs();
            StringBuilder buffer = new StringBuilder(64);
            tag(buffer,d,ms, ":DBUG:");
            format(buffer, msg);
            System.err.println(buffer);
        }
    }

    public void debug(String msg, Object arg0, Object arg1)
    {
        if (_debug)
        {
            String d = _dateCache.now();
            int ms = _dateCache.lastMs();
            StringBuilder buffer = new StringBuilder(64);
            tag(buffer,d,ms, ":DBUG:");
            format(buffer,msg,arg0, arg1);
            System.err.println(buffer);
        }
    }

    public void warn(String msg)
    {
        String d = _dateCache.now();
        int ms = _dateCache.lastMs();
        StringBuilder buffer = new StringBuilder(64);
        tag(buffer,d,ms, ":WARN:");
        format(buffer, msg);
        System.err.println(buffer);
    }

    public void warn(String msg, Object arg0, Object arg1)
    {
        String d = _dateCache.now();
        int ms = _dateCache.lastMs();
        StringBuilder buffer = new StringBuilder(64);
        tag(buffer,d,ms, ":WARN:");
        format(buffer,msg,arg0, arg1);
        System.err.println(buffer);
    }

    public void warn(String msg, Throwable th)
    {
        String d = _dateCache.now();
        int ms = _dateCache.lastMs();
        StringBuilder buffer = new StringBuilder(64);
        tag(buffer,d,ms, ":WARN:");
        format(buffer, msg);
        if (_hideStacks)
            format(buffer, String.valueOf(th));
        else
            format(th, buffer);
        System.err.println(buffer);

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

    private void format(StringBuilder buffer, String msg, Object arg0, Object arg1)
    {
        int i0 = msg == null?-1:msg.indexOf("{}");
        int i1 = i0 < 0?-1:msg.indexOf("{}",i0 + 2);

        if (i0 >= 0)
        {
            format(buffer, msg.substring(0,i0));
            format(buffer, String.valueOf(arg0 == null?"null":arg0));

            if (i1 >= 0)
            {
                format(buffer, msg.substring(i0 + 2,i1));
                format(buffer, String.valueOf(arg1 == null?"null":arg1));
                format(buffer, msg.substring(i1 + 2));
            }
            else
            {
                format(buffer, msg.substring(i0 + 2));
                if (arg1 != null)
                {
                    buffer.append(' ');
                    format(buffer, String.valueOf(arg1));
                }
            }
        }
        else
        {
            format(buffer, msg);
            if (arg0 != null)
            {
                buffer.append(' ');
                format(buffer, String.valueOf(arg0));
            }
            if (arg1 != null)
            {
                buffer.append(' ');
                format(buffer, String.valueOf(arg1));
            }
        }
    }

    private void format(StringBuilder buffer, String msg)
    {
        if (msg == null)
            buffer.append("null");
        else
            for (int i = 0; i < msg.length(); i++)
            {
                char c = msg.charAt(i);
                if (Character.isISOControl(c))
                {
                    if (c == '\n')
                        buffer.append('|');
                    else if (c == '\r')
                        buffer.append('<');
                    else
                        buffer.append('?');
                }
                else
                    buffer.append(c);
            }
    }

    private void format(Throwable th, StringBuilder buffer)
    {
        if (th == null)
            buffer.append("null");
        else
        {
            buffer.append('\n');
            format(buffer, th.toString());
            StackTraceElement[] elements = th.getStackTrace();
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
