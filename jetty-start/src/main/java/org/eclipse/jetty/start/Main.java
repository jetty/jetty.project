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

package org.eclipse.jetty.start;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.start.config.CommandLineConfigSource;
import org.eclipse.jetty.start.config.ConfigSource;

import static org.eclipse.jetty.start.UsageException.ERR_BAD_STOP_PROPS;
import static org.eclipse.jetty.start.UsageException.ERR_INVOKE_MAIN;
import static org.eclipse.jetty.start.UsageException.ERR_NOT_STOPPED;
import static org.eclipse.jetty.start.UsageException.ERR_UNKNOWN;

/**
 * Main start class.
 * <p>
 * This class is intended to be the main class listed in the MANIFEST.MF of the start.jar archive. It allows the Jetty Application server to be started with the
 * command "java -jar start.jar".
 * <p>
 * <b>Argument processing steps:</b>
 * <ol>
 * <li>Directory Location: jetty.home=[directory] (the jetty.home location)</li>
 * <li>Directory Location: jetty.base=[directory] (the jetty.base location)</li>
 * <li>Start Logging behavior: --debug (debugging enabled)</li>
 * <li>Start Logging behavior: --start-log-file=logs/start.log (output start logs to logs/start.log location)</li>
 * <li>Module Resolution</li>
 * <li>Properties Resolution</li>
 * <li>Present Optional Informational Options</li>
 * <li>Normal Startup</li>
 * </ol>
 */
public class Main
{
    private static final int EXIT_USAGE = 1;

    public static void main(String[] args)
    {
        boolean test = false;
        try
        {
            Main main = new Main();
            StartArgs startArgs = main.processCommandLine(args);
            test = startArgs.isTestingModeEnabled();
            main.start(startArgs);
        }
        catch (UsageException e)
        {
            StartLog.error(e.getMessage());
            usageExit(e.getCause(), e.getExitCode(), test);
        }
        catch (Throwable e)
        {
            usageExit(e, UsageException.ERR_UNKNOWN, test);
        }
    }

    static void usageExit(int exit)
    {
        usageExit(null, exit, false);
    }

    static void usageExit(Throwable t, int exit, boolean test)
    {
        if (t != null)
        {
            t.printStackTrace(System.err);
        }
        System.err.println();
        System.err.println("Usage: java -jar $JETTY_HOME/start.jar [options] [properties] [configs]");
        System.err.println("       java -jar $JETTY_HOME/start.jar --help  # for more information");

        if (test)
            System.err.println("EXIT: " + exit);
        else
            System.exit(exit);
    }

    private BaseHome baseHome;
    private StartArgs jsvcStartArgs;

    public Main() throws IOException
    {
    }

    private void copyInThread(final InputStream in, final OutputStream out)
    {
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    byte[] buf = new byte[1024];
                    int len = in.read(buf);
                    while (len > 0)
                    {
                        out.write(buf, 0, len);
                        len = in.read(buf);
                    }
                }
                catch (IOException e)
                {
                    // e.printStackTrace();
                }
            }
        }).start();
    }

    private void dumpClasspathWithVersions(PrintStream out, Classpath classpath)
    {
        StartLog.endStartLog();
        out.println();
        out.println("Jetty Server Classpath:");
        out.println("-----------------------");
        if (classpath.count() == 0)
        {
            out.println("No classpath entries and/or version information available show.");
            return;
        }

        out.println("Version Information on " + classpath.count() + " entr" + ((classpath.count() > 1) ? "ies" : "y") + " in the classpath.");
        out.println("Note: order presented here is how they would appear on the classpath.");
        out.println("      changes to the --module=name command line options will be reflected here.");

        int i = 0;
        for (File element : classpath.getElements())
        {
            out.printf("%2d: %24s | %s\n", i++, getVersion(element), baseHome.toShortForm(element));
        }
    }

    public BaseHome getBaseHome()
    {
        return baseHome;
    }

    private String getVersion(File element)
    {
        if (element.isDirectory())
        {
            return "(dir)";
        }

        if (element.isFile())
        {
            String name = element.getName().toLowerCase(Locale.ENGLISH);
            if (name.endsWith(".jar"))
            {
                return JarVersion.getVersion(element);
            }
        }

        return "";
    }

    public void invokeMain(ClassLoader classloader, StartArgs args) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException, IOException
    {
        if (args.getEnabledModules().isEmpty())
        {
            if (Files.exists(getBaseHome().getBasePath("start.jar")))
                StartLog.error("Do not start with ${jetty.base} == ${jetty.home}!");
            else
                StartLog.error("No enabled jetty modules found!");
            StartLog.info("${jetty.home} = " + getBaseHome().getHomePath());
            StartLog.info("${jetty.base} = " + getBaseHome().getBasePath());
            StartLog.error("Please create and/or configure a ${jetty.base} directory.");
            usageExit(ERR_INVOKE_MAIN);
            return;
        }

        Class<?> invokedClass = null;
        String mainclass = args.getMainClassname();
        try
        {
            invokedClass = classloader.loadClass(mainclass);
        }
        catch (ClassNotFoundException e)
        {
            StartLog.error("Unable to find: " + mainclass);
            StartLog.debug(e);
            usageExit(ERR_INVOKE_MAIN);
            return;
        }

        StartLog.debug("%s - %s", invokedClass, invokedClass.getPackage().getImplementationVersion());

        CommandLineBuilder cmd = args.getMainArgs(StartArgs.ARG_PARTS);
        String[] argArray = cmd.getArgs().toArray(new String[0]);
        StartLog.debug("Command Line Args: %s", cmd.toString());

        Class<?>[] methodParamTypes = {argArray.getClass()};

        Method main = invokedClass.getDeclaredMethod("main", methodParamTypes);
        Object[] methodParams = new Object[]{argArray};
        StartLog.endStartLog();
        main.invoke(null, methodParams);
    }

    public void listConfig(PrintStream out, StartArgs args)
    {
        StartLog.endStartLog();
        Modules modules = args.getAllModules();

        // Dump Enabled Modules
        modules.listEnabled(out);

        // Dump Jetty Home / Base
        args.dumpEnvironment(out);

        // Dump JVM Args
        args.dumpJvmArgs(out);

        // Dump System Properties
        args.dumpSystemProperties(out);

        // Dump Properties
        args.dumpProperties(out);

        // Dump Classpath
        dumpClasspathWithVersions(out, args.getClasspath());

        // Dump Resolved XMLs
        args.dumpActiveXmls(out);
    }

    public void listModules(PrintStream out, StartArgs args)
    {
        final List<String> tags = args.getListModules();
        StartLog.endStartLog();
        String t = tags.toString();
        out.printf("%nModules %s:%n", t);
        out.printf("=========%s%n", "=".repeat(t.length()));
        args.getAllModules().listModules(out, tags);

        // for default module listings, also show enabled modules
        if ("[-internal]".equals(t) || "[*]".equals(t))
            args.getAllModules().listEnabled(out);
    }

    public void showModules(PrintStream out, StartArgs args)
    {
        StartLog.endStartLog();
        args.getAllModules().showModules(out, args.getShowModules());
    }

    /**
     * Convenience for <code>processCommandLine(cmdLine.toArray(new String[cmdLine.size()]))</code>
     *
     * @param cmdLine the command line
     * @return the start args parsed from the command line
     * @throws Exception if unable to process the command line
     */
    public StartArgs processCommandLine(List<String> cmdLine) throws Exception
    {
        return this.processCommandLine(cmdLine.toArray(new String[cmdLine.size()]));
    }

    public StartArgs processCommandLine(String[] cmdLine) throws Exception
    {
        // Processing Order is important!
        // 1) Configuration Locations
        CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
        baseHome = new BaseHome(cmdLineSource);

        StartLog.debug("jetty.home=%s", baseHome.getHome());
        StartLog.debug("jetty.base=%s", baseHome.getBase());

        // 2) Parse everything provided.
        // This would be the directory information +
        // the various start inis
        // and then the raw command line arguments
        StartLog.debug("Parsing collected arguments");
        StartArgs args = new StartArgs(baseHome);
        args.parse(baseHome.getConfigSources());

        Props props = baseHome.getConfigSources().getProps();
        Prop home = props.getProp(BaseHome.JETTY_HOME);
        if (!args.getProperties().containsKey(BaseHome.JETTY_HOME))
            args.getProperties().setProperty(home);
        args.getProperties().setProperty(BaseHome.JETTY_HOME + ".uri",
            normalizeURI(baseHome.getHomePath().toUri().toString()),
            home.source);
        Prop base = props.getProp(BaseHome.JETTY_BASE);
        if (!args.getProperties().containsKey(BaseHome.JETTY_BASE))
            args.getProperties().setProperty(base);
        args.getProperties().setProperty(BaseHome.JETTY_BASE + ".uri",
            normalizeURI(baseHome.getBasePath().toUri().toString()),
            base.source);

        // 3) Module Registration
        Modules modules = new Modules(baseHome, args);
        StartLog.debug("Registering all modules");
        modules.registerAll();

        // 4) Active Module Resolution
        List<String> selectedModules = args.getEnabledModules();
        List<String> sortedSelectedModules = modules.getSortedNames(selectedModules);
        List<String> unknownModules = new ArrayList<>(selectedModules);
        unknownModules.removeAll(sortedSelectedModules);
        if (unknownModules.size() >= 1)
        {
            throw new UsageException(UsageException.ERR_UNKNOWN, "Unknown module%s=[%s] List available with --list-modules",
                unknownModules.size() > 1 ? 's' : "",
                String.join(", ", unknownModules));
        }
        for (String selectedModule : sortedSelectedModules)
        {
            for (String source : args.getSources(selectedModule))
            {
                String shortForm = baseHome.toShortForm(source);
                modules.enable(selectedModule, shortForm);
            }
        }

        args.setAllModules(modules);
        List<Module> activeModules = modules.getEnabled();

        final Version START_VERSION = new Version(StartArgs.VERSION);

        for (Module enabled : activeModules)
        {
            if (enabled.getVersion().isNewerThan(START_VERSION))
            {
                throw new UsageException(UsageException.ERR_BAD_GRAPH, "Module [" + enabled.getName() + "] specifies jetty version [" + enabled.getVersion() +
                    "] which is newer than this version of jetty [" + START_VERSION + "]");
            }
        }

        for (String name : args.getSkipFileValidationModules())
        {
            Module module = modules.get(name);
            module.setSkipFilesValidation(true);
        }

        // 5) Lib & XML Expansion / Resolution
        args.expandSystemProperties();
        args.expandLibs();
        args.expandModules(activeModules);

        // 6) Resolve Extra XMLs
        args.resolveExtraXmls();

        // 7) JPMS Expansion
        args.expandJPMS(activeModules);

        // 8) Resolve Property Files
        args.resolvePropertyFiles();

        return args;
    }

    private String normalizeURI(String uri)
    {
        if (uri.endsWith("/"))
            return uri.substring(0, uri.length() - 1);
        return uri;
    }

    public void start(StartArgs args) throws IOException, InterruptedException
    {
        StartLog.debug("StartArgs: %s", args);

        // Get Desired Classpath based on user provided Active Options.
        Classpath classpath = args.getClasspath();

        // Show the usage information and return
        if (args.isHelp())
        {
            usage(true);
        }

        // Show the version information and return
        if (args.isListClasspath())
        {
            dumpClasspathWithVersions(System.out, classpath);
        }

        // Show configuration
        if (args.isListConfig())
        {
            listConfig(System.out, args);
        }

        // List modules
        if (args.getListModules() != null)
        {
            listModules(System.out, args);
        }

        // Show modules
        if (args.getShowModules() != null)
        {
            showModules(System.out, args);
        }

        // Generate Module Graph File
        if (args.getModuleGraphFilename() != null)
        {
            Path outputFile = baseHome.getBasePath(args.getModuleGraphFilename());
            System.out.printf("Generating GraphViz Graph of Jetty Modules at %s%n", baseHome.toShortForm(outputFile));
            ModuleGraphWriter writer = new ModuleGraphWriter();
            writer.config(args.getProperties());
            writer.write(args.getAllModules(), outputFile);
        }

        // Show Command Line to execute Jetty
        if (args.isDryRun())
        {
            CommandLineBuilder cmd = args.getMainArgs(args.getDryRunParts());
            System.out.println(cmd.toString(StartLog.isDebugEnabled() ? " \\\n" : " "));
        }

        if (args.isStopCommand())
        {
            doStop(args);
        }

        if (args.isUpdateIni())
        {
            for (ConfigSource config : baseHome.getConfigSources())
            {
                for (StartIni ini : config.getStartInis())
                {
                    ini.update(baseHome, args.getProperties());
                }
            }
        }

        // Check base directory
        BaseBuilder baseBuilder = new BaseBuilder(baseHome, args);
        if (baseBuilder.build())
            StartLog.info("Base directory was modified");
        else if (args.isCreateFiles() || !args.getStartModules().isEmpty())
            StartLog.info("Base directory was not modified");

        // Check module dependencies
        args.getAllModules().checkEnabledModules();

        // Informational command line, don't run jetty
        if (!args.isRun())
        {
            return;
        }

        // execute Jetty in another JVM
        if (args.isExec())
        {
            CommandLineBuilder cmd = args.getMainArgs(StartArgs.ALL_PARTS);
            cmd.debug();

            List<String> execModules = args.getAllModules().getEnabled().stream()
                // Keep only the forking modules.
                .filter(module -> !module.getJvmArgs().isEmpty())
                .map(Module::getName)
                .collect(Collectors.toList());
            StartLog.warn("Forking second JVM due to forking module(s): %s. Use --dry-run to generate the command line to avoid forking.", execModules);

            ProcessBuilder pbuilder = new ProcessBuilder(cmd.getArgs());
            StartLog.endStartLog();
            final Process process = pbuilder.start();
            Runtime.getRuntime().addShutdownHook(new Thread()
            {
                @Override
                public void run()
                {
                    StartLog.debug("Destroying " + process);
                    process.destroy();
                }
            });

            copyInThread(process.getErrorStream(), System.err);
            copyInThread(process.getInputStream(), System.out);
            copyInThread(System.in, process.getOutputStream());
            process.waitFor();
            System.exit(0); // exit JVM when child process ends.
            return;
        }

        if (args.hasJvmArgs() || args.hasSystemProperties())
        {
            StartLog.warn("Unknown Arguments detected.  Consider using --dry-run or --exec");
            if (args.hasSystemProperties())
                args.getSystemProperties().forEach((k, v) -> StartLog.warn("  Argument: -D%s=%s (interpreted as a System property, from %s)", k, System.getProperty(k), v));
            if (args.hasJvmArgs())
                args.getJvmArgSources().forEach((jvmArg, source) -> StartLog.warn("  Argument: %s (interpreted as a JVM argument, from %s)", jvmArg, source));
        }

        ClassLoader cl = classpath.getClassLoader();
        Thread.currentThread().setContextClassLoader(cl);

        // Invoke the Main Class
        try
        {
            invokeMain(cl, args);
        }
        catch (Throwable e)
        {
            e.printStackTrace();
            usageExit(e, ERR_INVOKE_MAIN, args.isTestingModeEnabled());
        }
    }

    /* implement Apache commons daemon (jsvc) lifecycle methods (init, start, stop, destroy) */
    public void start() throws Exception
    {
        start(jsvcStartArgs);
    }

    private void doStop(StartArgs args)
    {
        final Prop stopHostProp = args.getProperties().getProp("STOP.HOST", true);
        final Prop stopPortProp = args.getProperties().getProp("STOP.PORT", true);
        final Prop stopKeyProp = args.getProperties().getProp("STOP.KEY", true);
        final Prop stopWaitProp = args.getProperties().getProp("STOP.WAIT", true);

        String stopHost = "127.0.0.1";
        int stopPort = -1;
        String stopKey = "";

        if (stopHostProp != null)
        {
            stopHost = stopHostProp.value;
        }

        if (stopPortProp != null)
        {
            stopPort = Integer.parseInt(stopPortProp.value);
        }

        if (stopKeyProp != null)
        {
            stopKey = stopKeyProp.value;
        }

        if (stopWaitProp != null)
        {
            int stopWait = Integer.parseInt(stopWaitProp.value);
            stop(stopHost, stopPort, stopKey, stopWait);
        }
        else
        {
            stop(stopHost, stopPort, stopKey);
        }
    }

    /**
     * Stop a running jetty instance.
     *
     * @param host the host
     * @param port the port
     * @param key the key
     */
    public void stop(String host, int port, String key)
    {
        stop(host, port, key, 0);
    }

    public void stop(String host, int port, String key, int timeout)
    {
        if (host == null || host.length() == 0)
        {
            host = "127.0.0.1";
        }

        try
        {
            if ((port <= 0) || (port > 65535))
            {
                System.err.println("STOP.PORT property must be specified with a valid port number");
                usageExit(ERR_BAD_STOP_PROPS);
            }
            if (key == null)
            {
                key = "";
                System.err.println("STOP.KEY property must be specified");
                System.err.println("Using empty key");
            }

            try (Socket s = new Socket(InetAddress.getByName(host), port))
            {
                if (timeout > 0)
                {
                    s.setSoTimeout(timeout * 1000);
                }

                try (OutputStream out = s.getOutputStream())
                {
                    out.write((key + "\r\nstop\r\n").getBytes());
                    out.flush();

                    if (timeout > 0)
                    {
                        StartLog.info("Waiting %,d seconds for jetty to stop%n", timeout);
                        LineNumberReader lin = new LineNumberReader(new InputStreamReader(s.getInputStream()));
                        String response;
                        while ((response = lin.readLine()) != null)
                        {
                            StartLog.debug("Received \"%s\"", response);
                            if ("Stopped".equals(response))
                            {
                                StartLog.warn("Server reports itself as Stopped");
                            }
                        }
                    }
                }
            }
        }
        catch (SocketTimeoutException e)
        {
            StartLog.warn("Timed out waiting for stop confirmation");
            System.exit(ERR_UNKNOWN);
        }
        catch (ConnectException e)
        {
            usageExit(e, ERR_NOT_STOPPED, jsvcStartArgs != null && jsvcStartArgs.isTestingModeEnabled());
        }
        catch (Exception e)
        {
            usageExit(e, ERR_UNKNOWN, jsvcStartArgs != null && jsvcStartArgs.isTestingModeEnabled());
        }
    }

    /* implement Apache commons daemon (jsvc) lifecycle methods (init, start, stop, destroy) */
    public void stop() throws Exception
    {
        doStop(jsvcStartArgs);
    }

    public void usage(boolean exit)
    {
        StartLog.endStartLog();
        if (!printTextResource("org/eclipse/jetty/start/usage.txt"))
        {
            StartLog.warn("detailed usage resource unavailable");
        }
        if (exit)
        {
            System.exit(EXIT_USAGE);
        }
    }

    public static boolean printTextResource(String resourceName)
    {
        boolean resourcePrinted = false;
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName))
        {
            if (stream != null)
            {
                try (InputStreamReader reader = new InputStreamReader(stream);
                     BufferedReader buf = new BufferedReader(reader))
                {
                    resourcePrinted = true;
                    String line;
                    while ((line = buf.readLine()) != null)
                    {
                        System.out.println(line);
                    }
                }
            }
            else
            {
                StartLog.warn("Unable to find resource: " + resourceName);
            }
        }
        catch (IOException e)
        {
            StartLog.warn(e);
        }

        return resourcePrinted;
    }

    /* implement Apache commons daemon (jsvc) lifecycle methods (init, start, stop, destroy) */
    public void init(String[] args) throws Exception
    {
        try
        {
            jsvcStartArgs = processCommandLine(args);
        }
        catch (UsageException e)
        {
            StartLog.error(e.getMessage());
            usageExit(e.getCause(), e.getExitCode(), false);
        }
        catch (Throwable e)
        {
            usageExit(e, UsageException.ERR_UNKNOWN, false);
        }
    }

    /* implement Apache commons daemon (jsvc) lifecycle methods (init, start, stop, destroy) */
    public void destroy()
    {
    }
}
