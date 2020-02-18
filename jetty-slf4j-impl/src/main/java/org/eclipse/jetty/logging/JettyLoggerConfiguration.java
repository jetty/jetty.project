//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.logging;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;

import org.slf4j.event.Level;

/**
 * Properties to look for
 * - org.eclipse.jetty.logging.THREAD_PADDING (number, default "0") - used for minimum padding width of the Thread.name
 * - org.eclipse.jetty.logging.SOURCE (boolean, default "false") - used for showing of source (filename and line number) in stacktraces
 * - org.eclipse.jetty.logging.NAME_CONDENSE (boolean, default "true") - used for condensing the logger name package
 * - org.eclipse.jetty.logging.MESSAGE_ESCAPE (boolean, default "true") - used for escaping the output of the logger message
 * - org.eclipse.jetty.logging.STRICT_SLF4J_SYNTAX (boolean, default "true") - use strict slf4j message formatting when arguments are provided
 *
 * JettyLogger specific configuration
 * - <name>.LEVEL=(String:LevelName)
 * - <name>.STACKS=(boolean)
 */
public class JettyLoggerConfiguration
{
    public static final String NAME_CONDENSE_KEY = "org.eclipse.jetty.logging.NAME_CONDENSE";
    public static final String THREAD_PADDING_KEY = "org.eclipse.jetty.logging.THREAD_PADDING";
    public static final String SOURCE_KEY = "org.eclipse.jetty.logging.SOURCE";
    public static final String MESSAGE_ESCAPE_KEY = "org.eclipse.jetty.logging.MESSAGE_ESCAPE";
    public static final String STRICT_SLF4J_FORMAT_KEY = "org.eclipse.jetty.logging.STRICT_SLF4J_SYNTAX";

    protected static final int DEFAULT_LEVEL = Level.INFO.toInt();
    private static final boolean DEFAULT_HIDE_STACKS = false;

    private static final String SUFFIX_LEVEL = ".LEVEL";
    private static final String SUFFIX_STACKS = ".STACKS";

    private final Properties properties = new Properties();
    private boolean nameCondense = true;
    private int threadPadding = 0;
    private boolean source = false;
    private boolean escapeMessages = true;
    private boolean strictFormatSyntax = true;

    /**
     * Default JettyLogger configuration (empty)
     */
    public JettyLoggerConfiguration()
    {
    }

    /**
     * JettyLogger configuration from provided Properties
     */
    public JettyLoggerConfiguration(Properties props)
    {
        load(props);
    }

    public boolean getHideStacks(String name)
    {
        if (properties.isEmpty())
            return DEFAULT_HIDE_STACKS;

        String startName = name;

        // strip trailing dot
        while (startName.endsWith("."))
        {
            startName = startName.substring(0, startName.length() - 1);
        }

        // strip ".STACKS" suffix (if present)
        if (startName.endsWith(SUFFIX_STACKS))
        {
            startName = startName.substring(0, startName.length() - SUFFIX_STACKS.length());
        }

        Boolean hideStacks = walkParentLoggerNames(startName, (key) ->
        {
            String stacksBool = properties.getProperty(key + SUFFIX_STACKS);
            if (stacksBool != null)
            {
                return Boolean.parseBoolean(stacksBool);
            }
            return null;
        });

        if (hideStacks != null)
            return hideStacks;

        return DEFAULT_HIDE_STACKS;
    }

    /**
     * Get the Logging Level for the provided log name. Using the FQCN first, then each package segment from longest to
     * shortest.
     *
     * @param name the name to get log for
     * @return the logging level int
     */
    public int getLevel(String name)
    {
        if (properties.isEmpty())
            return DEFAULT_LEVEL;

        String startName = name != null ? name : "";

        // strip trailing dot
        while (startName.endsWith("."))
        {
            startName = startName.substring(0, startName.length() - 1);
        }

        // strip ".LEVEL" suffix (if present)
        if (startName.endsWith(SUFFIX_LEVEL))
        {
            startName = startName.substring(0, startName.length() - SUFFIX_LEVEL.length());
        }

        Integer level = walkParentLoggerNames(startName, (key) ->
        {
            String levelStr = properties.getProperty(key + SUFFIX_LEVEL);
            if (levelStr != null)
            {
                return getLevelId(key, levelStr);
            }
            return null;
        });

        if (level == null)
        {
            // try legacy root logging config
            String levelStr = properties.getProperty("log" + SUFFIX_LEVEL);
            if (levelStr != null)
            {
                level = getLevelId("log", levelStr);
            }
        }

        if (level != null)
            return level;

        return DEFAULT_LEVEL;
    }

    public int getThreadPadding()
    {
        return threadPadding;
    }

    public boolean isEscapeMessages()
    {
        return escapeMessages;
    }

    public boolean isNameCondense()
    {
        return nameCondense;
    }

    public boolean isSource()
    {
        return source;
    }

    public void setSource(boolean source)
    {
        this.source = source;
    }

    public boolean isStrictFormatSyntax()
    {
        return strictFormatSyntax;
    }

    /**
     * Load the Configuration from the ClassLoader
     *
     * @return the configuration
     */
    public JettyLoggerConfiguration loadRuntime(ClassLoader loader)
    {
        AccessController.doPrivileged((PrivilegedAction<Object>)() ->
        {
            // First see if the jetty-logging.properties object exists in the classpath.
            // * This is an optional feature used by embedded mode use, and test cases to allow for early
            // * configuration of the Log class in situations where access to the System.properties are
            // * either too late or just impossible.
            load(readProperties(loader, "jetty-logging.properties"));

            // Next see if an OS specific jetty-logging.properties object exists in the classpath.
            // This really for setting up test specific logging behavior based on OS.
            String osName = System.getProperty("os.name");
            if (osName != null && osName.length() > 0)
            {
                // NOTE: cannot use jetty-util's StringUtil.replace() as it may initialize logging itself.
                osName = osName.toLowerCase(Locale.ENGLISH).replace(' ', '-');
                load(readProperties(loader, "jetty-logging-" + osName + ".properties"));
            }

            // Now load the System.properties as-is into the properties,
            // these values will override any key conflicts in properties.
            load(System.getProperties());
            return null;
        });

        return this;
    }

    public boolean getBoolean(String key, boolean defValue)
    {
        String val = properties.getProperty(key, Boolean.toString(defValue));
        return Boolean.parseBoolean(val);
    }

    public int getInt(String key, int defValue)
    {
        String val = properties.getProperty(key, Integer.toString(defValue));
        if (val == null)
        {
            return defValue;
        }
        try
        {
            return Integer.parseInt(val);
        }
        catch (NumberFormatException e)
        {
            return defValue;
        }
    }

    private Integer getLevelId(String levelSegment, String levelStr)
    {
        if (levelStr == null)
        {
            return null;
        }

        String levelName = levelStr.trim().toUpperCase(Locale.ENGLISH);
        switch (levelName)
        {
            case "ALL":
                return JettyLogger.ALL;
            case "TRACE":
                return Level.TRACE.toInt();
            case "DEBUG":
                return Level.DEBUG.toInt();
            case "INFO":
                return Level.INFO.toInt();
            case "WARN":
                return Level.WARN.toInt();
            case "ERROR":
                return Level.ERROR.toInt();
            case "OFF":
                return JettyLogger.OFF;
            default:
                System.err.println("Unknown JettyLogger/Slf4J Level [" + levelSegment + "]=[" + levelName + "], expecting only [ALL, TRACE, DEBUG, INFO, WARN, ERROR, OFF] as values.");
                return null;
        }
    }

    private URL getResource(ClassLoader loader, String resourceName)
    {
        if (loader == null)
        {
            return ClassLoader.getSystemResource(resourceName);
        }
        else
        {
            return loader.getResource(resourceName);
        }
    }

    /**
     * Overlay existing properties with provided properties.
     *
     * @param props the properties to load
     */
    private void load(Properties props)
    {
        if (props == null)
            return;

        for (String name : props.stringPropertyNames())
        {
            if (name.startsWith("org.eclipse.jetty.logging.") ||
                name.endsWith(".LEVEL") ||
                name.endsWith(".STACKS"))
            {
                String val = props.getProperty(name);
                // Protect against application code insertion of non-String values (returned as null).
                if (val != null)
                    properties.setProperty(name, val);
            }
        }

        nameCondense = getBoolean(NAME_CONDENSE_KEY, nameCondense);
        threadPadding = getInt(THREAD_PADDING_KEY, threadPadding);
        source = getBoolean(SOURCE_KEY, source);
        escapeMessages = getBoolean(MESSAGE_ESCAPE_KEY, escapeMessages);
        strictFormatSyntax = getBoolean(STRICT_SLF4J_FORMAT_KEY, strictFormatSyntax);
    }

    private Properties readProperties(ClassLoader loader, String resourceName)
    {
        URL propsUrl = getResource(loader, resourceName);
        if (propsUrl == null)
        {
            return null;
        }

        try (InputStream in = propsUrl.openStream())
        {
            Properties p = new Properties();
            p.load(in);
            return p;
        }
        catch (IOException e)
        {
            System.err.println("[WARN] Error loading logging config: " + propsUrl);
            e.printStackTrace();
        }
        return null;
    }

    private <T> T walkParentLoggerNames(String startName, Function<String, T> nameFunction)
    {
        String nameSegment = startName;

        // Checking with FQCN first, then each package segment from longest to shortest.
        while ((nameSegment != null) && (nameSegment.length() > 0))
        {
            T ret = nameFunction.apply(nameSegment);
            if (ret != null)
                return ret;

            // Trim and try again.
            int idx = nameSegment.lastIndexOf('.');
            if (idx >= 0)
            {
                nameSegment = nameSegment.substring(0, idx);
            }
            else
            {
                nameSegment = null;
            }
        }

        return null;
    }
}
