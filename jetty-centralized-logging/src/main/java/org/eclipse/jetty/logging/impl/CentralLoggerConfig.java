// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.logging.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;

/**
 * Represents the CentralLoggerConfig
 */
public class CentralLoggerConfig
{
    private static String between(String line, String start, String end)
    {
        if (line.startsWith(start) && line.endsWith(end))
        {
            return line.substring(start.length(),line.length() - end.length());
        }
        return null;
    }

    private static void configureAppender(Properties props, String id, Appender appender)
    {
        appender.setId(id);

        // Collect configuration fields for appender id
        Pattern appenderIdRegex = Pattern.compile("^appender\\." + id + "\\.([^\\.]*)$");
        Matcher match;

        @SuppressWarnings("unchecked")
        Enumeration<String> enNames = (Enumeration<String>)props.propertyNames();
        while (enNames.hasMoreElements())
        {
            String name = enNames.nextElement();
            match = appenderIdRegex.matcher(name);
            if (match.matches())
            {
                String fieldName = match.group(1);
                if (fieldName.equals("class"))
                {
                    continue; // Not meant to be set.
                }

                String value = props.getProperty(name);

                try
                {
                    appender.setProperty(fieldName,value);
                }
                catch (Exception e)
                {
                    System.err.printf("Unable to set property %s on appender %s to %s%n",fieldName,appender.getClass().getName(),value);
                    e.printStackTrace(System.err);
                }
            }
        }
    }

    private static void dumpDetails(PrintStream out, String prefix, CentralLoggerConfig cl)
    {
        out.printf("%sName: %s%n",prefix,cl.name);
        out.printf("%sLevel: %s%n",prefix,cl.level.name());
        out.printf("%sAppenders: ",prefix);
        for (Iterator<Appender> it = cl.getAppenders().iterator(); it.hasNext();)
        {
            Appender ap = it.next();
            // out.printf("(%s) %s",ap.getClass().getSimpleName(),ap);
            out.print(ap);
            if (it.hasNext())
            {
                out.print(", ");
            }
        }
        out.println();
        if (cl.children != null)
        {
            out.printf("%sChildren.count: %d%n",prefix,cl.children.size());
            String childPrefix = prefix + "  ";
            for (Map.Entry<String, CentralLoggerConfig> entry : cl.children.entrySet())
            {
                out.printf("%sChild[%s]%n",prefix,entry.getKey());
                dumpDetails(out,childPrefix,entry.getValue());
            }
        }
        else
        {
            out.printf("%sChildren: <null>%n",prefix);
        }
    }

    private static Set<String> getAppenderIds(Properties props, String key)
    {
        Set<String> ids = new TreeSet<String>();

        String value = props.getProperty(key);
        if (value == null)
        {
            return ids;
        }

        String parts[] = value.split(",");
        for (int i = 0, n = parts.length; i < n; i++)
        {
            ids.add(parts[i].trim());
        }

        return ids;
    }

    private static List<Appender> getAppenders(Properties props, String key, Map<String, Appender> declaredAppenders)
    {
        Set<String> ids = getAppenderIds(props,key);
        List<Appender> appenders = new ArrayList<Appender>();
        for (String id : ids)
        {
            if (declaredAppenders.containsKey(id))
            {
                appenders.add(declaredAppenders.get(id));
            }
            else
            {
                System.err.println("No such Appender: " + id);
            }
        }

        return appenders;
    }

    private static Map<String, Appender> getDeclaredAppenders(Properties props)
    {
        Set<String> ids = new TreeSet<String>();

        // Collect IDs
        Pattern appenderIdRegex = Pattern.compile("appender\\.([^\\.]*).class");
        Matcher match;

        @SuppressWarnings("unchecked")
        Enumeration<String> enNames = (Enumeration<String>)props.propertyNames();
        while (enNames.hasMoreElements())
        {
            String name = enNames.nextElement();
            match = appenderIdRegex.matcher(name);
            if (match.matches())
            {
                ids.add(match.group(1));
            }
        }

        Map<String, Appender> appenders = new HashMap<String, Appender>();

        if (ids.isEmpty())
        {
            return appenders;
        }

        // Instantiate > Configure > Open the Appenders
        for (String id : ids)
        {
            String clazzName = props.getProperty("appender." + id + ".class");
            if (clazzName == null)
            {
                continue; // skip
            }

            try
            {
                // Instantiate Appender
                Class<?> clazzAppender = Class.forName(clazzName);
                Appender appender = (Appender)clazzAppender.newInstance();

                // Configure Appender
                configureAppender(props,id,appender);

                // Open Appender
                appender.open();

                appenders.put(id,appender);
            }
            catch (ClassNotFoundException e)
            {
                System.err.println("Unable to find class: " + clazzName);
                e.printStackTrace(System.err);
            }
            catch (InstantiationException e)
            {
                System.err.println("Unable to Instantiate: " + clazzName);
                e.printStackTrace(System.err);
            }
            catch (IllegalAccessException e)
            {
                System.err.println("Unable to Access: " + clazzName);
                e.printStackTrace(System.err);
            }
            catch (IOException e)
            {
                System.err.println("Unable to open Appender: " + clazzName);
                e.printStackTrace(System.err);
            }
        }

        return appenders;
    }

    public static CentralLoggerConfig load(InputStream stream) throws IOException
    {
        Properties props = new Properties();
        props.load(stream);
        return load(props);
    }

    public static CentralLoggerConfig load(Properties props) throws IOException
    {
        CentralLoggerConfig root = new CentralLoggerConfig(Logger.ROOT_LOGGER_NAME);

        // Default for root
        root.level = Severity.INFO;

        // Collect all possible appenders, by id.
        Map<String, Appender> declaredAppenders = getDeclaredAppenders(props);

        root.appenders = getAppenders(props,"root.appenders",declaredAppenders);
        if (root.appenders.isEmpty())
        {
            // Default (if not specified for root)
            root.appenders.add(new ConsoleAppender());
        }

        // Set logger & level of ROOT
        root.logger = new CentralLogger(root.name,root.appenders.toArray(new Appender[] {}),root.level);
        root.level = Severity.valueOf(props.getProperty("root.level"));

        // Collect other configured loggers
        Set<String> ids = new TreeSet<String>(); // Use TreeSet to get sort order that we need.

        @SuppressWarnings("unchecked")
        Enumeration<String> enNames = (Enumeration<String>)props.propertyNames();
        while (enNames.hasMoreElements())
        {
            String name = enNames.nextElement();
            String id = between(name,"logger.",".level");
            if (id == null)
            {
                id = between(name,"logger.",".appenders");
                if (id == null)
                {
                    continue; // not something we care about
                }
            }

            ids.add(id);
        }

        // Set loggers & levels of OTHER nodes
        for (String id : ids)
        {
            CentralLoggerConfig childlog = root.getConfiguredLogger(id);
            childlog.level = Severity.valueOf(props.getProperty("logger." + id + ".level","INFO"));
            Set<String> appenderIds = getAppenderIds(props,"logger." + id + ".appenders");
            for (String appenderId : appenderIds)
            {
                if (appenderId.startsWith("-"))
                {
                    // Remove an appender
                    childlog.removeAppenderById(appenderId.substring(1));
                }
                else
                {
                    // Add an appender
                    if (declaredAppenders.containsKey(appenderId))
                    {
                        childlog.addAppender(declaredAppenders.get(appenderId));
                    }
                    else
                    {
                        System.err.println("No such Appender: " + appenderId);
                    }
                }
            }
        }

        return root;
    }

    private String name;
    private List<Appender> appenders = new ArrayList<Appender>();
    private Severity level;
    private CentralLogger logger;
    private Map<String, CentralLoggerConfig> children;

    private CentralLoggerConfig(CentralLoggerConfig copyLogger, String name)
    {
        if (copyLogger.name.equals(CentralLogger.ROOT_LOGGER_NAME))
        {
            this.name = name;
        }
        else
        {
            this.name = copyLogger.name + "." + name;
        }

        this.appenders.addAll(copyLogger.appenders);
        this.level = copyLogger.level;
        this.logger = new CentralLogger(this.name,appenders.toArray(new Appender[] {}),level);
    }

    private CentralLoggerConfig(String name)
    {
        this.name = name;
    }

    private void addAppender(Appender appender)
    {
        getAppenders().add(appender);
    }

    private void removeAppenderById(String id)
    {
        ListIterator<Appender> it = appenders.listIterator();
        while (it.hasNext())
        {
            Appender appender = it.next();
            if (id.equals(appender.getId()))
            {
                it.remove();
            }
        }
    }

    public void dumpTree(PrintStream out)
    {
        dumpDetails(out,"",this);
    }

    public Appender findAppender(Class<? extends Appender> clazz)
    {
        for (Appender appender : appenders)
        {
            if (clazz.isAssignableFrom(appender.getClass()))
            {
                return appender;
            }
        }
        return null;
    }

    public List<Appender> getAppenders()
    {
        return appenders;
    }

    private CentralLoggerConfig getChildLogger(String name)
    {
        CentralLoggerConfig child = getChildren().get(name);
        if (child == null)
        {
            child = new CentralLoggerConfig(this,name);
            this.children.put(name,child);
        }
        return child;
    }

    public Map<String, CentralLoggerConfig> getChildren()
    {
        if (children == null)
        {
            children = new HashMap<String, CentralLoggerConfig>();
        }
        return children;
    }

    public CentralLoggerConfig getConfiguredLogger(String name)
    {
        String parts[] = name.split("\\.");
        CentralLoggerConfig ret = this;
        for (String part : parts)
        {
            ret = ret.getChildLogger(part);
        }
        return ret;
    }

    public Severity getLevel()
    {
        return level;
    }

    public CentralLogger getLogger()
    {
        return logger;
    }

    public String getName()
    {
        return name;
    }
}