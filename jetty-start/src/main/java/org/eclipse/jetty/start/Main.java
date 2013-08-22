//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import static org.eclipse.jetty.start.UsageException.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Main start class.
 * <p>
 * This class is intended to be the main class listed in the MANIFEST.MF of the start.jar archive. It allows the Jetty Application server to be started with the
 * command "java -jar start.jar".
 * <p>
 * Argument processing steps:
 * <ol>
 * <li>Directory Locations:
 * <ul>
 * <li>jetty.home=[directory] (the jetty.home location)</li>
 * <li>jetty.base=[directory] (the jetty.base location)</li>
 * </ul>
 * </li>
 * <li>Start Logging behavior:
 * <ul>
 * <li>--debug (debugging enabled)</li>
 * <li>--start-log-file=logs/start.log (output start logs to logs/start.log location)</li>
 * </ul>
 * </li>
 * <li>Module Resolution</li>
 * <li>Properties Resolution</li>
 * <li>Execution
 * <ul>
 * <li>--list-modules</li>
 * <li>--list-classpath</li>
 * <li>--list-config</li>
 * <li>--version</li>
 * <li>--help</li>
 * <li>--dry-run</li>
 * <li>--exec</li>
 * <li>--stop</li>
 * <li>(or normal startup)</li>
 * </ul>
 * </li>
 * </ol>
 */
public class Main
{
    private static final int EXIT_USAGE = 1;
    private final BaseHome baseHome;

    public static void main(String[] args)
    {
        try
        {
            Main main = new Main();
            StartArgs startArgs = main.processCommandLine(args);
            main.start(startArgs);
        }
        catch (UsageException e)
        {
            usageExit(e.getCause(),e.getExitCode());
        }
        catch (Throwable e)
        {
            usageExit(e,UsageException.ERR_UNKNOWN);
        }
    }

    Main() throws IOException
    {
        baseHome = new BaseHome();
    }

    public StartArgs processCommandLine(String[] cmdLine) throws Exception
    {
        StartArgs args = new StartArgs(cmdLine);

        // Processing Order is important!
        // ------------------------------------------------------------
        // 1) Directory Locations

        // Set Home and Base at the start, as all other paths encountered
        // will be based off of them.
        baseHome.initialize(args);

        // ------------------------------------------------------------
        // 2) Start Logging
        StartLog.getInstance().initialize(baseHome,args);

        // ------------------------------------------------------------
        // 3) Load Inis
        File start_ini = baseHome.getBaseFile("start.ini");
        if (FS.canReadFile(start_ini))
        {
            args.parse(new StartIni(start_ini));
        }

        File start_d = baseHome.getBaseFile("start.d");
        if (FS.canReadDirectory(start_d))
        {
            List<File> files = new ArrayList<>();
            for (File file : start_d.listFiles(new FS.IniFilter()))
            {
                files.add(file);
            }

            Collections.sort(files,new NaturalSort.Files());
            for (File file : files)
            {
                args.parse(new StartIni(file));
            }
        }

        // 4) Parse everything provided.
        // This would be the directory information +
        // the various start inis
        // and then the raw command line arguments
        args.parseCommandLine();

        // 5) Module Registration
        Modules modules = new Modules();
        modules.registerAll(baseHome);

        // 6) Active Module Resolution
        for (String enabledModule : args.getEnabledModules())
        {
            modules.enable(enabledModule);
        }
        modules.buildGraph();

        List<Module> activeModules = modules.resolveEnabled();

        // 7) Lib & XML Expansion / Resolution
        args.expandModules(baseHome,activeModules);

        return args;
    }

    private void download(String arg)
    {
        try
        {
            String[] split = arg.split(":",3);
            if (split.length != 3 || "http".equalsIgnoreCase(split[0]) || !split[1].startsWith("//"))
                throw new IllegalArgumentException("Not --download=<http uri>:<location>");

            String location = split[2];
            if (File.separatorChar != '/')
                location.replaceAll("/",File.separator);
            File file = new File(location);

            StartLog.debug("Download to %s %s",file.getAbsolutePath(),(file.exists()?"[Exists!]":""));
            if (file.exists())
                return;

            URL url = new URL(split[0].substring(11) + ":" + split[1]);

            System.err.println("DOWNLOAD: " + url + " to " + location);

            if (!file.getParentFile().exists())
                file.getParentFile().mkdirs();

            byte[] buf = new byte[8192];
            try (InputStream in = url.openStream(); OutputStream out = new FileOutputStream(file);)
            {
                while (true)
                {
                    int len = in.read(buf);

                    if (len > 0)
                        out.write(buf,0,len);
                    if (len < 0)
                        break;
                }
            }
        }
        catch (Exception e)
        {
            System.err.println("ERROR: processing " + arg + "\n" + e);
            e.printStackTrace();
            usageExit(EXIT_USAGE);
        }
    }

    private void usage()
    {
        String usageResource = "org/eclipse/jetty/start/usage.txt";
        InputStream usageStream = getClass().getClassLoader().getResourceAsStream(usageResource);

        if (usageStream == null)
        {
            System.err.println("ERROR: detailed usage resource unavailable");
            usageExit(EXIT_USAGE);
        }
        System.exit(EXIT_USAGE);
    }

    public void invokeMain(StartArgs args) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException, ClassNotFoundException, IOException
    {
        Class<?> invoked_class = null;
        ClassLoader classloader = args.getClasspath().getClassLoader();
        String mainclass = args.getMainClassname();

        try
        {
            invoked_class = classloader.loadClass(mainclass);
        }
        catch (ClassNotFoundException e)
        {
            StartLog.warn(e);
            usageExit(ERR_INVOKE_MAIN);
            return;
        }

        StartLog.debug("%s - %s",invoked_class,invoked_class.getPackage().getImplementationVersion());

        CommandLineBuilder cmd = args.getMainArgs(baseHome);
        String argArray[] = cmd.getArgs().toArray(new String[0]);

        Class<?>[] method_param_types = new Class[]
        { argArray.getClass() };

        Method main = invoked_class.getDeclaredMethod("main",method_param_types);
        Object[] method_params = new Object[]
        { argArray };
        main.invoke(null,method_params);
    }

    public void start(StartArgs args) throws IOException, InterruptedException
    {
        // Get Desired Classpath based on user provided Active Options.
        Classpath classpath = args.getClasspath();

        System.setProperty("java.class.path",classpath.toString());
        ClassLoader cl = classpath.getClassLoader();

        StartLog.debug("java.class.path=" + System.getProperty("java.class.path"));
        StartLog.debug("jetty.home=" + System.getProperty("jetty.home"));
        StartLog.debug("jetty.base=" + System.getProperty("jetty.base"));
        StartLog.debug("java.home=" + System.getProperty("java.home"));
        StartLog.debug("java.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
        StartLog.debug("java.class.path=" + classpath);
        StartLog.debug("classloader=" + cl);
        StartLog.debug("classloader.parent=" + cl.getParent());
        StartLog.debug("properties=" + args.getProperties());

        // Show the usage information and return
        if (args.isHelp())
        {
            usage();
        }

        // Show the version information and return
        if (args.isVersion())
        {
            showClasspathWithVersions(classpath);
        }

        // Show configuration
        if (args.isListConfig())
        {
            listConfig();
        }

        // Show modules
        if (args.isListModules())
        {
            listModules();
        }

        // Show Command Line to execute Jetty
        if (args.isDryRun())
        {
            CommandLineBuilder cmd = args.getMainArgs(baseHome);
            System.out.println(cmd.toString());
        }

        // Various Downloads
        for (String url : args.getDownloads())
        {
            download(url);
        }

        // Informational command line, don't run jetty
        if (!args.isRun())
        {
            return;
        }

        // execute Jetty in another JVM
        if (args.isExec())
        {
            CommandLineBuilder cmd = args.getMainArgs(baseHome);
            ProcessBuilder pbuilder = new ProcessBuilder(cmd.getArgs());
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

            copyInThread(process.getErrorStream(),System.err);
            copyInThread(process.getInputStream(),System.out);
            copyInThread(System.in,process.getOutputStream());
            process.waitFor();
            System.exit(0); // exit JVM when child process ends.
            return;
        }

        if (args.hasJvmArgs() || args.hasSystemProperties())
        {
            System.err.println("WARNING: System properties and/or JVM args set.  Consider using --dry-run or --exec");
        }

        // Set current context class loader to what is selected.
        Thread.currentThread().setContextClassLoader(cl);

        // Invoke the Main Class
        try
        {
            invokeMain(args);
        }
        catch (Exception e)
        {
            usageExit(e,ERR_INVOKE_MAIN);
        }
    }

    private void listModules()
    {
        // TODO Auto-generated method stub
    }

    private void copyInThread(final InputStream in, final OutputStream out)
    {
        new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    byte[] buf = new byte[1024];
                    int len = in.read(buf);
                    while (len > 0)
                    {
                        out.write(buf,0,len);
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

    private void showAllOptionsWithVersions()
    {
        // TODO
    }

    private void showClasspathWithVersions(Classpath classpath)
    {
        // Iterate through active classpath, and fetch Implementation Version from each entry (if present)
        // to dump to end user.

        // TODO: modules instead
        // System.out.println("Active Options: " + _config.getOptions());

        if (classpath.count() == 0)
        {
            System.out.println("No version information available show.");
            return;
        }

        System.out.println("Version Information on " + classpath.count() + " entr" + ((classpath.count() > 1)?"ies":"y") + " in the classpath.");
        System.out.println("Note: order presented here is how they would appear on the classpath.");
        System.out.println("      changes to the OPTIONS=[option,option,...] command line option will be reflected here.");

        int i = 0;
        for (File element : classpath.getElements())
        {
            System.out.printf("%2d: %20s | %s\n",i++,getVersion(element),baseHome.toShortForm(element));
        }
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

    private void listConfig()
    {
        // TODO
    }

    /**
     * Stop a running jetty instance.
     */
    public void stop(int port, String key)
    {
        stop(port,key,0);
    }

    public void stop(int port, String key, int timeout)
    {
        int _port = port;
        String _key = key;

        try
        {
            if (_port <= 0)
            {
                System.err.println("STOP.PORT system property must be specified");
            }
            if (_key == null)
            {
                _key = "";
                System.err.println("STOP.KEY system property must be specified");
                System.err.println("Using empty key");
            }

            try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"),_port))
            {
                if (timeout > 0)
                {
                    s.setSoTimeout(timeout * 1000);
                }

                try (OutputStream out = s.getOutputStream())
                {
                    out.write((_key + "\r\nstop\r\n").getBytes());
                    out.flush();

                    if (timeout > 0)
                    {
                        System.err.printf("Waiting %,d seconds for jetty to stop%n",timeout);
                        LineNumberReader lin = new LineNumberReader(new InputStreamReader(s.getInputStream()));
                        String response;
                        while ((response = lin.readLine()) != null)
                        {
                            StartLog.debug("Received \"%s\"",response);
                            if ("Stopped".equals(response))
                                StartLog.warn("Server reports itself as Stopped");
                        }
                    }
                }
            }
        }
        catch (SocketTimeoutException e)
        {
            System.err.println("Timed out waiting for stop confirmation");
            System.exit(ERR_UNKNOWN);
        }
        catch (ConnectException e)
        {
            usageExit(e,ERR_NOT_STOPPED);
        }
        catch (Exception e)
        {
            usageExit(e,ERR_UNKNOWN);
        }
    }

    static void usageExit(Throwable t, int exit)
    {
        if (t != null)
        {
            t.printStackTrace(System.err);
        }
        System.err.println();
        System.err.println("Usage: java -jar start.jar [options] [properties] [configs]");
        System.err.println("       java -jar start.jar --help  # for more information");
        System.exit(exit);
    }

    static void usageExit(int exit)
    {
        usageExit(null,exit);
    }
}
