//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.start;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.StringUtil;

public class CommandLineBuilder
{
    public static String findJavaBin()
    {
        Path javaHome = Paths.get(System.getProperty("java.home"));
        if (!Files.exists(javaHome))
            return null;

        Path javabin = javaHome.resolve("bin/java");
        if (Files.exists(javabin))
            return javabin.toAbsolutePath().toString();

        javabin = javaHome.resolve("bin/java.exe");
        if (Files.exists(javabin))
            return javabin.toAbsolutePath().toString();

        return "java";
    }

    private final StringBuilder commandLine = new StringBuilder();
    private final List<String> args = new ArrayList<>();
    private final String separator;

    public CommandLineBuilder()
    {
        this(false);
    }

    public CommandLineBuilder(boolean multiline)
    {
        separator = multiline ? (" \\" + System.lineSeparator() + "  ") : " ";
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
            args.add(arg);
            if (commandLine.length() > 0)
                commandLine.append(separator);
            commandLine.append(StringUtil.shellQuoteIfNeeded(arg));
        }
    }

    /**
     * @param name the name
     * @param value the value
     */
    public void addArg(String name, String value)
    {
        if (commandLine.length() > 0)
            commandLine.append(separator);
        commandLine.append(StringUtil.shellQuoteIfNeeded(name));
        if ((value != null) && (value.length() > 0))
        {
            args.add(name + "=" + value);
            commandLine.append('=').append(StringUtil.shellQuoteIfNeeded(value));
        }
        else
        {
            args.add(name);
        }
    }

    /**
     * @param option the option
     * @param name the name
     * @param value the value
     */
    public void addArg(String option, String name, String value)
    {
        if (commandLine.length() > 0)
            commandLine.append(separator);
        commandLine.append(option);
        if (StringUtil.isBlank(name))
        {
            args.add(option);
        }
        else
        {
            commandLine.append(StringUtil.shellQuoteIfNeeded(name));
            if ((value != null) && (value.length() > 0))
            {
                args.add(option + name + "=" + value);
                commandLine.append(StringUtil.shellQuoteIfNeeded(name)).append('=').append(StringUtil.shellQuoteIfNeeded(value));
            }
            else
            {
                args.add(option + name);
            }
        }
    }

    public List<String> getArgs()
    {
        return args;
    }

    @Override
    public String toString()
    {
        StringBuilder buf = new StringBuilder();
        for (String arg : args)
        {
            if (buf.length() > 0)
                buf.append(' ');
            buf.append(arg); // we assume escaping has occurred during addArg
        }

        return buf.toString();
    }

    /**
     * A version of {@link #toString()} where every arg is evaluated for potential {@code '} (single-quote tick) wrapping.
     *
     * @return the toString but each arg that has spaces is surrounded by {@code '} (single-quote tick)
     */
    public String toCommandLine()
    {
        return commandLine.toString();
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
