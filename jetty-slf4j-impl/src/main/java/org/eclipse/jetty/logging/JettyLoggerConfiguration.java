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

package org.eclipse.jetty.logging;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;

/**
 * JettyLogger specific configuration:
 * <ul>
 *  <li>{@code <name>.LEVEL=(String:LevelName)}</li>
 *  <li>{@code <name>.STACKS=(boolean)}</li>
 * </ul>
 */
public class JettyLoggerConfiguration
{
    private static final JettyLevel DEFAULT_LEVEL = JettyLevel.INFO;
    private static final boolean DEFAULT_HIDE_STACKS = false;
    private static final String SUFFIX_LEVEL = ".LEVEL";
    private static final String SUFFIX_STACKS = ".STACKS";

    private final Properties properties = new Properties();

    /**
     * Default JettyLogger configuration (empty)
     */
    public JettyLoggerConfiguration()
    {
    }

    /**
     * JettyLogger configuration from provided Properties
     *
     * @param props A set of properties to base this configuration off of
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
            startName = startName.substring(0, startName.length() - SUFFIX_STACKS.length());

        Boolean hideStacks = JettyLoggerFactory.walkParentLoggerNames(startName, key ->
        {
            String stacksBool = properties.getProperty(key + SUFFIX_STACKS);
            if (stacksBool != null)
                return Boolean.parseBoolean(stacksBool);
            return null;
        });

        return hideStacks != null ? hideStacks : DEFAULT_HIDE_STACKS;
    }

    /**
     * <p>Returns the Logging Level for the provided log name.</p>
     * <p>Uses the FQCN first, then each package segment from longest to shortest.</p>
     *
     * @param name the name to get log for
     * @return the logging level int
     */
    public JettyLevel getLevel(String name)
    {
        if (properties.isEmpty())
            return DEFAULT_LEVEL;

        String startName = name != null ? name : "";

        // Strip trailing dot.
        while (startName.endsWith("."))
        {
            startName = startName.substring(0, startName.length() - 1);
        }

        // Strip ".LEVEL" suffix (if present).
        if (startName.endsWith(SUFFIX_LEVEL))
            startName = startName.substring(0, startName.length() - SUFFIX_LEVEL.length());

        JettyLevel level = JettyLoggerFactory.walkParentLoggerNames(startName, key ->
        {
            String levelStr = properties.getProperty(key + SUFFIX_LEVEL);
            return toJettyLevel(key, levelStr);
        });

        if (level == null)
        {
            // Try slf4j root logging config.
            String levelStr = properties.getProperty(JettyLogger.ROOT_LOGGER_NAME + SUFFIX_LEVEL);
            level = toJettyLevel(JettyLogger.ROOT_LOGGER_NAME, levelStr);
        }

        if (level == null)
        {
            // Try legacy root logging config.
            String levelStr = properties.getProperty("log" + SUFFIX_LEVEL);
            level = toJettyLevel("log", levelStr);
        }

        return level != null ? level : DEFAULT_LEVEL;
    }

    static JettyLevel toJettyLevel(String loggerName, String levelStr)
    {
        if (levelStr == null)
            return null;
        JettyLevel level = JettyLevel.strToLevel(levelStr);
        if (level == null)
        {
            System.err.printf("Unknown JettyLogger/SLF4J Level [%s]=[%s], expecting only %s as values.%n",
                loggerName, levelStr, Arrays.toString(JettyLevel.values()));
        }
        return level;
    }

    public TimeZone getTimeZone(String key)
    {
        String zoneIdStr = properties.getProperty(key);
        if (zoneIdStr == null)
            return null;
        return TimeZone.getTimeZone(zoneIdStr);
    }

    /**
     * Load the Configuration from the ClassLoader
     *
     * @param loader the classloader to use when finding the {@code jetty-logging.properties} resources in.
     * Passing {@code null} means the {@link ClassLoader#getSystemClassLoader()} is used.
     * @return the configuration
     */
    public JettyLoggerConfiguration load(ClassLoader loader)
    {
        return AccessController.doPrivileged((PrivilegedAction<JettyLoggerConfiguration>)() ->
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
            return this;
        });
    }

    public String getString(String key, String defValue)
    {
        return properties.getProperty(key, defValue);
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
            return defValue;
        try
        {
            return Integer.parseInt(val);
        }
        catch (NumberFormatException e)
        {
            return defValue;
        }
    }

    private URL getResource(ClassLoader loader, String resourceName)
    {
        if (loader == null)
            return ClassLoader.getSystemResource(resourceName);
        else
            return loader.getResource(resourceName);
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
    }

    private Properties readProperties(ClassLoader loader, String resourceName)
    {
        URL propsUrl = getResource(loader, resourceName);
        if (propsUrl == null)
            return null;

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
}
