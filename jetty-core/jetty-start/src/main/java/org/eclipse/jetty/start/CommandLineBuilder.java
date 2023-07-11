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
     * Quote a string suitable for use with a command line shell using double quotes.
     * <p>This method applies doubles quoting as described for the unix {@code sh} commands:
     * Enclosing characters within double quotes preserves the literal meaning of all characters except
     * dollarsign ($), backquote (`), and backslash (\).
     * The backslash inside double quotes is historically weird, and serves
     * to quote only the following characters: {@code $ ` " \ newline}.
     * Otherwise, it remains literal.
     *
     * @param input The string to quote if needed
     * @return The quoted string or the original string if quotes are not necessary
     */
    public static String shellQuoteIfNeeded(String input)
    {
        if (input == null || input.length() == 0)
            return input;

        int i = 0;
        boolean needsQuoting = false;
        while (!needsQuoting && i < input.length())
        {
            char c = input.charAt(i++);

            // needs quoting unless a limited set of known good characters
            needsQuoting = !(
                (c >= 'A' && c <= 'Z') ||
                (c >= 'a' && c <= 'z') ||
                (c >= '0' && c <= '9') ||
                c == '/' ||
                c == ':' ||
                c == '.' ||
                c == '-' ||
                c == '_'
            );
        }

        if (!needsQuoting)
            return input;

        StringBuilder builder = new StringBuilder(input.length() * 2);
        builder.append('"');
        builder.append(input, 0, --i);

        while (i < input.length())
        {
            char c = input.charAt(i++);
            switch (c)
            {
                case '"', '\\', '`', '$' -> builder.append('\\').appendCodePoint(c);
                default -> builder.appendCodePoint(c);
            }
        }

        builder.append('"');

        return builder.toString();
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
            commandLine.append(shellQuoteIfNeeded(arg));
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
        commandLine.append(shellQuoteIfNeeded(name));
        if ((value != null) && (value.length() > 0))
        {
            args.add(name + "=" + value);
            commandLine.append('=').append(shellQuoteIfNeeded(value));
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
        if (name == null || name.length() == 0)
        {
            args.add(option);
        }
        else
        {
            commandLine.append(shellQuoteIfNeeded(name));
            if ((value != null) && (value.length() > 0))
            {
                args.add(option + name + "=" + value);
                commandLine.append(shellQuoteIfNeeded(name)).append('=').append(shellQuoteIfNeeded(value));
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
