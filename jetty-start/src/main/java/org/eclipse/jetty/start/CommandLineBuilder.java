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

package org.eclipse.jetty.start;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CommandLineBuilder
{
    public static File findExecutable(File root, String path)
    {
        String npath = path.replace('/', File.separatorChar);
        File exe = new File(root, npath);
        if (!exe.exists())
        {
            return null;
        }
        return exe;
    }

    public static String findJavaBin()
    {
        File javaHome = new File(System.getProperty("java.home"));
        if (!javaHome.exists())
        {
            return null;
        }

        File javabin = findExecutable(javaHome, "bin/java");
        if (javabin != null)
        {
            return javabin.getAbsolutePath();
        }

        javabin = findExecutable(javaHome, "bin/java.exe");
        if (javabin != null)
        {
            return javabin.getAbsolutePath();
        }

        return "java";
    }

    /**
     * Perform an optional quoting of the argument, being intelligent with spaces and quotes as needed. If a subString is set in quotes it won't the subString
     * won't be escaped.
     *
     * @param arg the argument to quote
     * @return the quoted and escaped argument
     */
    public static String quote(String arg)
    {
        boolean needsQuoting = (arg.indexOf(' ') >= 0) || (arg.indexOf('"') >= 0);
        if (!needsQuoting)
        {
            return arg;
        }
        StringBuilder buf = new StringBuilder();
        // buf.append('"');
        boolean escaped = false;
        boolean quoted = false;
        for (char c : arg.toCharArray())
        {
            if (!quoted && !escaped && ((c == '"') || (c == ' ')))
            {
                buf.append("\\");
            }
            // don't quote text in single quotes
            if (!escaped && (c == '\''))
            {
                quoted = !quoted;
            }
            escaped = (c == '\\');
            buf.append(c);
        }
        // buf.append('"');
        return buf.toString();
    }

    private List<String> args;

    public CommandLineBuilder()
    {
        args = new ArrayList<String>();
    }

    public CommandLineBuilder(String bin)
    {
        this();
        args.add(bin);
    }

    /**
     * Add a simple argument to the command line.
     * <p>
     * Will quote arguments that have a space in them.
     *
     * @param arg the simple argument to add
     */
    public void addArg(String arg)
    {
        if (arg != null)
        {
            args.add(quote(arg));
        }
    }

    /**
     * Similar to {@link #addArg(String)} but concats both name + value with an "=" sign, quoting were needed, and excluding the "=" portion if the value is
     * undefined or empty.
     *
     * <pre>
     *   addEqualsArg("-Dname", "value") = "-Dname=value"
     *   addEqualsArg("-Djetty.home", "/opt/company inc/jetty (7)/") = "-Djetty.home=/opt/company\ inc/jetty\ (7)/"
     *   addEqualsArg("-Djenkins.workspace", "/opt/workspaces/jetty jdk7/") = "-Djenkins.workspace=/opt/workspaces/jetty\ jdk7/"
     *   addEqualsArg("-Dstress", null) = "-Dstress"
     *   addEqualsArg("-Dstress", "") = "-Dstress"
     * </pre>
     *
     * @param name the name
     * @param value the value
     */
    public void addEqualsArg(String name, String value)
    {
        if ((value != null) && (value.length() > 0))
        {
            args.add(quote(name + "=" + value));
        }
        else
        {
            args.add(quote(name));
        }
    }

    /**
     * Add a simple argument to the command line.
     * <p>
     * Will <b>NOT</b> quote/escape arguments that have a space in them.
     *
     * @param arg the simple argument to add
     */
    public void addRawArg(String arg)
    {
        if (arg != null)
        {
            args.add(arg);
        }
    }

    public List<String> getArgs()
    {
        return args;
    }

    @Override
    public String toString()
    {
        return toString(" ");
    }

    public String toString(String delim)
    {
        StringBuilder buf = new StringBuilder();

        for (String arg : args)
        {
            if (buf.length() > 0)
            {
                buf.append(delim);
            }
            buf.append(quote(arg));
        }

        return buf.toString();
    }

    public void debug()
    {
        if (!StartLog.isDebugEnabled())
        {
            return;
        }

        int len = args.size();
        StartLog.debug("Command Line: %,d entries", args.size());
        for (int i = 0; i < len; i++)
        {
            StartLog.debug(" [%d]: \"%s\"", i, args.get(i));
        }
    }
}
