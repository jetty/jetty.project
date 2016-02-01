//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.util.Properties;

/* ------------------------------------------------------------ */
/** Abstract Logger.
 * Manages the atomic registration of the logger by name.
 */
public abstract class AbstractLogger implements Logger
{
    public static final int LEVEL_DEFAULT = -1;
    public static final int LEVEL_ALL = 0;
    public static final int LEVEL_DEBUG = 1;
    public static final int LEVEL_INFO = 2;
    public static final int LEVEL_WARN = 3;
    public static final int LEVEL_OFF = 10;
        
    @Override
    public final Logger getLogger(String name)
    {
        if (isBlank(name))
            return this;

        final String basename = getName();
        final String fullname = (isBlank(basename) || Log.getRootLogger()==this)?name:(basename + "." + name);
        
        Logger logger = Log.getLoggers().get(fullname);
        if (logger == null)
        {
            Logger newlog = newLogger(fullname);
            
            logger = Log.getMutableLoggers().putIfAbsent(fullname,newlog);
            if (logger == null)
                logger=newlog;
        }

        return logger;
    }
    

    protected abstract Logger newLogger(String fullname);

    /**
     * A more robust form of name blank test. Will return true for null names, and names that have only whitespace
     *
     * @param name
     *            the name to test
     * @return true for null or blank name, false if any non-whitespace character is found.
     */
    private static boolean isBlank(String name)
    {
        if (name == null)
        {
            return true;
        }
        int size = name.length();
        char c;
        for (int i = 0; i < size; i++)
        {
            c = name.charAt(i);
            if (!Character.isWhitespace(c))
            {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Get the Logging Level for the provided log name. Using the FQCN first, then each package segment from longest to
     * shortest.
     *
     * @param props
     *            the properties to check
     * @param name
     *            the name to get log for
     * @return the logging level
     */
    public static int lookupLoggingLevel(Properties props, final String name)
    {
        if ((props == null) || (props.isEmpty()) || name==null )
            return LEVEL_DEFAULT;
        
        // Calculate the level this named logger should operate under.
        // Checking with FQCN first, then each package segment from longest to shortest.
        String nameSegment = name;
    
        while ((nameSegment != null) && (nameSegment.length() > 0))
        {
            String levelStr = props.getProperty(nameSegment + ".LEVEL");
            // System.err.printf("[StdErrLog.CONFIG] Checking for property [%s.LEVEL] = %s%n",nameSegment,levelStr);
            int level = getLevelId(nameSegment + ".LEVEL",levelStr);
            if (level != (-1))
            {
                return level;
            }
    
            // Trim and try again.
            int idx = nameSegment.lastIndexOf('.');
            if (idx >= 0)
            {
                nameSegment = nameSegment.substring(0,idx);
            }
            else
            {
                nameSegment = null;
            }
        }
    
        // Default Logging Level
        return LEVEL_DEFAULT;
    }


    public static String getLoggingProperty(Properties props, String name, String property)
    {
        // Calculate the level this named logger should operate under.
        // Checking with FQCN first, then each package segment from longest to shortest.
        String nameSegment = name;
    
        while ((nameSegment != null) && (nameSegment.length() > 0))
        {
            String s = props.getProperty(nameSegment+"."+property);
            if (s!=null)
                return s;
    
            // Trim and try again.
            int idx = nameSegment.lastIndexOf('.');
            nameSegment = (idx >= 0)?nameSegment.substring(0,idx):null;
        }
    
        return null;
    }


    protected static int getLevelId(String levelSegment, String levelName)
    {
        if (levelName == null)
        {
            return -1;
        }
        String levelStr = levelName.trim();
        if ("ALL".equalsIgnoreCase(levelStr))
        {
            return LEVEL_ALL;
        }
        else if ("DEBUG".equalsIgnoreCase(levelStr))
        {
            return LEVEL_DEBUG;
        }
        else if ("INFO".equalsIgnoreCase(levelStr))
        {
            return LEVEL_INFO;
        }
        else if ("WARN".equalsIgnoreCase(levelStr))
        {
            return LEVEL_WARN;
        }
        else if ("OFF".equalsIgnoreCase(levelStr))
        {
            return LEVEL_OFF;
        }
    
        System.err.println("Unknown StdErrLog level [" + levelSegment + "]=[" + levelStr + "], expecting only [ALL, DEBUG, INFO, WARN, OFF] as values.");
        return -1;
    }


    /**
     * Condenses a classname by stripping down the package name to just the first character of each package name
     * segment.Configured
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


    public void debug(String msg, long arg)
    {
        if (isDebugEnabled())
        {
            debug(msg,new Object[] { new Long(arg) });
        }
    }
}
