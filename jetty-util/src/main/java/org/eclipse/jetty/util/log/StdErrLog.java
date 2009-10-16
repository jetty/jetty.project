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
/** StdErr Logging.
 * This implementation of the Logging facade sends all logs to StdErr with minimal formatting.
 * 
 * If the system property "org.eclipse.jetty.util.log.DEBUG" is set, 
 * then debug logs are printed if stderr is being used.
 * <p>
 * For named debuggers, the system property name+".DEBUG" is checked. If it is not not set, then
 * "org.eclipse.jetty.util.log.DEBUG" is used as the default.
 * 
 */
public class StdErrLog implements Logger
{    
    private static DateCache _dateCache;
    
    private final static boolean __debug = 
        Boolean.parseBoolean(System.getProperty("org.eclipse.jetty.util.log.DEBUG",System.getProperty("org.eclipse.jetty.util.log.stderr.DEBUG","false")));
    private boolean _debug = __debug;
    private final String _name;
    private boolean _hideStacks=false;
    StringBuilder _buffer = new StringBuilder();
    
    static
    {
        try
        {
            _dateCache=new DateCache("yyyy-MM-dd HH:mm:ss");
        }
        catch(Exception e)
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
        this._name=name==null?"":name;

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
        _debug=enabled;
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
        String d=_dateCache.now();
        int ms=_dateCache.lastMs();
        synchronized(_buffer)
        {
            tag(d,ms,":INFO:");
            format(msg);
            System.err.println(_buffer.toString());
        }
    }

    public void info(String msg,Object arg0, Object arg1)
    {
        String d=_dateCache.now();
        int ms=_dateCache.lastMs();
        synchronized(_buffer)
        {
            tag(d,ms,":INFO:");
            format(msg,arg0,arg1);
            System.err.println(_buffer.toString());
        }
    }
    
    public void debug(String msg,Throwable th)
    {
        if (_debug)
        {
            String d=_dateCache.now();
            int ms=_dateCache.lastMs();
            synchronized(_buffer)
            {
                tag(d,ms,":DBUG:");
                format(msg);
                if (_hideStacks)
                    format(th.toString());
                else
                    format(th);
                System.err.println(_buffer.toString());
            }
        }
    }
    
    public void debug(String msg)
    {
        if (_debug)
        {
            String d=_dateCache.now();
            int ms=_dateCache.lastMs();
            synchronized(_buffer)
            {
                tag(d,ms,":DBUG:");
                format(msg);
                System.err.println(_buffer.toString());
            }
        }
    }
    
    public void debug(String msg,Object arg0, Object arg1)
    {
        if (_debug)
        {
            String d=_dateCache.now();
            int ms=_dateCache.lastMs();
            synchronized(_buffer)
            {
                tag(d,ms,":DBUG:");
                format(msg,arg0,arg1);
                System.err.println(_buffer.toString());
            }
        }
    }
    
    public void warn(String msg)
    {
        String d=_dateCache.now();
        int ms=_dateCache.lastMs();
        synchronized(_buffer)
        {
            tag(d,ms,":WARN:");
            format(msg);
            System.err.println(_buffer.toString());
        }
    }
    
    public void warn(String msg,Object arg0, Object arg1)
    {
        String d=_dateCache.now();
        int ms=_dateCache.lastMs();
        synchronized(_buffer)
        {
            tag(d,ms,":WARN:");
            format(msg,arg0,arg1);
            System.err.println(_buffer.toString());
        }
    }
    
    public void warn(String msg, Throwable th)
    {
        String d=_dateCache.now();
        int ms=_dateCache.lastMs();  
        synchronized(_buffer)
        {
            tag(d,ms,":WARN:");
            format(msg);
            if (_hideStacks)
                format(th.toString());
            else
                format(th);
            System.err.println(_buffer.toString());
        }    
    }

    
    private void tag(String d,int ms,String tag)
    {
        _buffer.setLength(0);
        _buffer.append(d);
        if (ms>99)
            _buffer.append('.');
        else if (ms>9)
            _buffer.append(".0");
        else
            _buffer.append(".00");
        _buffer.append(ms).append(tag).append(_name).append(':');
    }
    
    private void format(String msg, Object arg0, Object arg1)
    {
        int i0=msg.indexOf("{}");
        int i1=i0<0?-1:msg.indexOf("{}",i0+2);
        
        if (i0>=0)
        {
            format(msg.substring(0,i0));
            format(String.valueOf(arg0));
            
            if (i1>=0)
            {
                format(msg.substring(i0+2,i1));
                format(String.valueOf(arg1));
                format(msg.substring(i1+2));
            }
            else
            {
                format(msg.substring(i0+2));
                if (arg1!=null)
                {
                    _buffer.append(' ');
                    format(String.valueOf(arg1));
                }
            }
        }
        else
        {
            format(msg);
            if (arg0!=null)
            {
                _buffer.append(' ');
                format(String.valueOf(arg0));
            }
            if (arg1!=null)
            {
                _buffer.append(' ');
                format(String.valueOf(arg1));
            }
        }
    }
    
    private void format(String msg)
    {
        for (int i=0;i<msg.length();i++)
        {
            char c=msg.charAt(i);
            if (Character.isISOControl(c))
            {
                if (c=='\n')
                    _buffer.append('|');
                else if (c=='\r')
                    _buffer.append('<');
                else
                    _buffer.append('?');
            }
            else
                _buffer.append(c);
        }
    }
    
    private void format(Throwable th)
    {
        _buffer.append('\n');
        format(th.toString());
        StackTraceElement[] elements = th.getStackTrace();
        for (int i=0;elements!=null && i<elements.length;i++)
        {
            _buffer.append("\n\tat ");
            format(elements[i].toString());
        }
    }
    
    public Logger getLogger(String name)
    {
        if ((name==null && this._name==null) ||
            (name!=null && name.equals(this._name)))
            return this;
        return new StdErrLog(_name==null||_name.length()==0?name:_name+"."+name);
    }
    
    @Override
    public String toString()
    {
        return "StdErrLog:"+_name+":DEBUG="+_debug;
    }

}

