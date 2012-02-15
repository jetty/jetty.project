package org.eclipse.jetty.start;

import java.util.ArrayList;
import java.util.List;

public class CommandLineBuilder
{
    private List<String> args;

    public CommandLineBuilder(String bin)
    {
        args = new ArrayList<String>();
        args.add(bin);
    }

    /**
     * Add a simple argument to the command line.
     * <p>
     * Will quote arguments that have a space in them.
     * 
     * @param arg
     *            the simple argument to add
     */
    public void addArg(String arg)
    {
        args.add(quote(arg));
    }

    /**
     * Similar to {@link #addArg(String)} but concats both name + value with an "=" sign, quoting were needed, and excluding the "=" portion if the value is
     * undefined or empty.
     * <p>
     * 
     * <pre>
     *   addEqualsArg("-Dname", "value") = "-Dname=value"
     *   addEqualsArg("-Djetty.home", "/opt/company inc/jetty (7)/") = "-Djetty.home=/opt/company\ inc/jetty\ (7)/"
     *   addEqualsArg("-Djenkins.workspace", "/opt/workspaces/jetty jdk7/") = "-Djenkins.workspace=/opt/workspaces/jetty\ jdk7/"
     *   addEqualsArg("-Dstress", null) = "-Dstress"
     *   addEqualsArg("-Dstress", "") = "-Dstress"
     * </pre>
     * 
     * @param name
     *            the name
     * @param value
     *            the value
     */
    public void addEqualsArg(String name, String value)
    {
        if (value != null && value.length() > 0)
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
     * @param arg
     *            the simple argument to add
     */
    public void addRawArg(String arg)
    {
        args.add(arg);
    }

    public List<String> getArgs()
    {
        return args;
    }

    /**
     * Perform an optional quoting of the argument, being intelligent with spaces and quotes as needed.
     * 
     * @param arg
     * @return
     */
    public static String quote(String arg)
    {
        boolean needsQuoting = arg.indexOf(' ') >= 0 || arg.indexOf('"') >= 0;
        if (!needsQuoting)
        {
            return arg;
        }
        StringBuilder buf = new StringBuilder();
//        buf.append('"');
        boolean escaped = false;
        for (char c : arg.toCharArray())
        {
            if (!escaped && ((c == '"') || (c == ' ')))
            {
                buf.append("\\");
            }
            escaped = (c == '\\');
            buf.append(c);
        }
//        buf.append('"');
        return buf.toString();
    }

    @Override
    public String toString()
    {
        StringBuilder buf = new StringBuilder();

        boolean delim = false;
        for (String arg : args)
        {
            if (delim)
            {
                buf.append(' ');
            }
            buf.append(arg);
            delim = true;
        }

        return buf.toString();
    }
}
