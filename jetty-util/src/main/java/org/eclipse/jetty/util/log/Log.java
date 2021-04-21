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

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.Uptime;
import org.eclipse.jetty.util.annotation.ManagedAttribute;

/**
 * Logging.
 * This class provides a static logging interface.  If an instance of the
 * org.slf4j.Logger class is found on the classpath, the static log methods
 * are directed to a slf4j logger for "org.eclipse.log".   Otherwise the logs
 * are directed to stderr.
 * <p>
 * The "org.eclipse.jetty.util.log.class" system property can be used
 * to select a specific logging implementation.
 * <p>
 * If the system property org.eclipse.jetty.util.log.IGNORED is set,
 * then ignored exceptions are logged in detail.
 *
 * @see StdErrLog
 * @see Slf4jLog
 */
public class Log
{
    public static final String EXCEPTION = "EXCEPTION ";
    public static final String IGNORED = "IGNORED EXCEPTION ";
    /**
     * The {@link Logger} implementation class name
     */
    public static String __logClass;
    /**
     * Legacy flag indicating if {@link Logger#ignore(Throwable)} methods produce any output in the {@link Logger}s
     */
    public static boolean __ignored;
    /**
     * Logging Configuration Properties
     */
    protected static final Properties __props = new Properties();
    private static final ConcurrentMap<String, Logger> __loggers = new ConcurrentHashMap<>();
    private static boolean __initialized;
    private static Logger LOG;

    static
    {
        AccessController.doPrivileged(new PrivilegedAction<Object>()
        {
            @Override
            public Object run()
            {
                // First see if the jetty-logging.properties object exists in the classpath.
                // * This is an optional feature used by embedded mode use, and test cases to allow for early
                // * configuration of the Log class in situations where access to the System.properties are
                // * either too late or just impossible.
                loadProperties("jetty-logging.properties", __props);

                 // Next see if an OS specific jetty-logging.properties object exists in the classpath.
                 // This really for setting up test specific logging behavior based on OS.
                String osName = System.getProperty("os.name");
                if (osName != null && osName.length() > 0)
                {
                    // NOTE: cannot use jetty-util's StringUtil.replace() as it may initialize logging itself.
                    osName = osName.toLowerCase(Locale.ENGLISH).replace(' ', '-');
                    loadProperties("jetty-logging-" + osName + ".properties", __props);
                }

                // Now load the System.properties as-is into the __props,
                // these values will override any key conflicts in __props.
                @SuppressWarnings("unchecked")
                Enumeration<String> systemKeyEnum = (Enumeration<String>)System.getProperties().propertyNames();
                while (systemKeyEnum.hasMoreElements())
                {
                    String key = systemKeyEnum.nextElement();
                    String val = System.getProperty(key);
                    // Protect against application code insertion of non-String values (returned as null).
                    if (val != null)
                        __props.setProperty(key, val);
                }

                // Now use the configuration properties to configure the Log statics.
                __logClass = __props.getProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.Slf4jLog");
                __ignored = Boolean.parseBoolean(__props.getProperty("org.eclipse.jetty.util.log.IGNORED", "false"));
                return null;
            }
        });
    }

    private static void loadProperties(String resourceName, Properties props)
    {
        URL testProps = Loader.getResource(resourceName);
        if (testProps != null)
        {
            try (InputStream in = testProps.openStream())
            {
                Properties p = new Properties();
                p.load(in);
                for (Object key : p.keySet())
                {
                    Object value = p.get(key);
                    if (value != null)
                        props.put(key, value);
                }
            }
            catch (IOException e)
            {
                System.err.println("[WARN] Error loading logging config: " + testProps);
                e.printStackTrace();
            }
        }
    }

    public static void initialized()
    {
        synchronized (Log.class)
        {
            if (__initialized)
                return;
            __initialized = true;

            boolean announce = Boolean.parseBoolean(__props.getProperty("org.eclipse.jetty.util.log.announce", "true"));
            try
            {
                Class<?> logClass = Loader.loadClass(Log.class, __logClass);
                if (LOG == null || !LOG.getClass().equals(logClass))
                {
                    LOG = (Logger)logClass.getDeclaredConstructor().newInstance();
                    if (announce)
                        LOG.debug("Logging to {} via {}", LOG, logClass.getName());
                }
            }
            catch (Throwable e)
            {
                // Unable to load specified Logger implementation, default to standard logging.
                initStandardLogging(e);
            }

            if (announce && LOG != null)
                LOG.info(String.format("Logging initialized @%dms to %s", Uptime.getUptime(), LOG.getClass().getName()));
        }
        Objects.requireNonNull(LOG, "Root Logger may not be null");
    }

    private static void initStandardLogging(Throwable e)
    {
        if (__ignored)
            e.printStackTrace();

        if (LOG == null)
            LOG = new StdErrLog();
    }

    public static Logger getLog()
    {
        initialized();
        return LOG;
    }

    /**
     * Set the root logger.
     * <p>
     * Note that if any classes have statically obtained their logger instance prior to this call, their Logger will not
     * be affected by this call.
     *
     * @param log the root logger implementation to set
     */
    public static void setLog(Logger log)
    {
        Log.LOG = Objects.requireNonNull(log, "Root Logger may not be null");
        __logClass = null;
    }

    /**
     * Get the root logger.
     *
     * @return the root logger
     */
    public static Logger getRootLogger()
    {
        initialized();
        return LOG;
    }

    static boolean isIgnored()
    {
        return __ignored;
    }

    /**
     * Set Log to parent Logger.
     * <p>
     * If there is a different Log class available from a parent classloader,
     * call {@link #getLogger(String)} on it and construct a {@link LoggerLog} instance
     * as this Log's Logger, so that logging is delegated to the parent Log.
     * <p>
     * This should be used if a webapp is using Log, but wishes the logging to be
     * directed to the containers log.
     * <p>
     * If there is not parent Log, then this call is equivalent to<pre>
     *   Log.setLog(Log.getLogger(name));
     * </pre>
     *
     * @param name Logger name
     */
    public static void setLogToParent(String name)
    {
        ClassLoader loader = Log.class.getClassLoader();
        if (loader != null && loader.getParent() != null)
        {
            try
            {
                Class<?> uberlog = loader.getParent().loadClass("org.eclipse.jetty.util.log.Log");
                Method getLogger = uberlog.getMethod("getLogger", String.class);
                Object logger = getLogger.invoke(null, name);
                setLog(new LoggerLog(logger));
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        else
        {
            setLog(getLogger(name));
        }
    }

    /**
     * Obtain a named Logger based on the fully qualified class name.
     *
     * @param clazz the class to base the Logger name off of
     * @return the Logger with the given name
     */
    public static Logger getLogger(Class<?> clazz)
    {
        return getLogger(clazz.getName());
    }

    /**
     * Obtain a named Logger or the default Logger if null is passed.
     *
     * @param name the Logger name
     * @return the Logger with the given name
     */
    public static Logger getLogger(String name)
    {
        initialized();

        Logger logger = null;

        // Return root
        if (name == null)
            logger = LOG;

        // use cache
        if (logger == null)
            logger = __loggers.get(name);

        // create new logger
        if (logger == null && LOG != null)
            logger = LOG.getLogger(name);

        Objects.requireNonNull(logger, "Logger with name [" + name + "]");

        return logger;
    }

    static ConcurrentMap<String, Logger> getMutableLoggers()
    {
        return __loggers;
    }

    /**
     * Get a map of all configured {@link Logger} instances.
     *
     * @return a map of all configured {@link Logger} instances
     */
    @ManagedAttribute("list of all instantiated loggers")
    public static Map<String, Logger> getLoggers()
    {
        return Collections.unmodifiableMap(__loggers);
    }

    public static Properties getProperties()
    {
        return __props;
    }
}
