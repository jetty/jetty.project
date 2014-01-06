//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import static org.eclipse.jetty.start.UsageException.ERR_INVOKE_MAIN;
import static org.eclipse.jetty.start.UsageException.ERR_NOT_STOPPED;
import static org.eclipse.jetty.start.UsageException.ERR_UNKNOWN;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <li>Present Optional Informational Options</li>
 * <li>Normal Startup</li>
 * </li>
 * </ol>
 */
public class Main
{
    private static final int EXIT_USAGE = 1;

    public static String join(Collection<?> objs, String delim)
    {
        StringBuilder str = new StringBuilder();
        boolean needDelim = false;
        for (Object obj : objs)
        {
            if (needDelim)
            {
                str.append(delim);
            }
            str.append(obj);
            needDelim = true;
        }
        return str.toString();
    }

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
            System.err.println(e.getMessage());
            usageExit(e.getCause(),e.getExitCode());
        }
        catch (Throwable e)
        {
            usageExit(e,UsageException.ERR_UNKNOWN);
        }
    }

    static void usageExit(int exit)
    {
        usageExit(null,exit);
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

    private final BaseHome baseHome;

    Main() throws IOException
    {
        baseHome = new BaseHome();
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

    private void initFile(FileArg arg)
    {
        try
        {
            File file = baseHome.getBaseFile(arg.location);

            StartLog.debug("Module file %s %s",file.getAbsolutePath(),(file.exists()?"[Exists!]":""));
            if (file.exists())
            {
                return;
            }

            if (arg.uri!=null)
            {
                URL url = new URL(arg.uri);

                System.err.println("DOWNLOAD: " + url + " to " + arg.location);

                FS.ensureDirectoryExists(file.getParentFile());

                byte[] buf = new byte[8192];
                try (InputStream in = url.openStream(); OutputStream out = new FileOutputStream(file);)
                {
                    while (true)
                    {
                        int len = in.read(buf);

                        if (len > 0)
                        {
                            out.write(buf,0,len);
                        }
                        if (len < 0)
                        {
                            break;
                        }
                    }
                }
            }
            else if (arg.location.endsWith("/"))
            {
                System.err.println("MKDIR: " + baseHome.toShortForm(file));
                file.mkdirs();
            }
            else
                StartLog.warn("MISSING: required file "+ baseHome.toShortForm(file));
            
        }
        catch (Exception e)
        {
            StartLog.warn("ERROR: processing %s%n%s",arg,e);
            StartLog.warn(e);
            usageExit(EXIT_USAGE);
        }
    }

    private void dumpClasspathWithVersions(Classpath classpath)
    {
        System.out.println();
        System.out.println("Jetty Server Classpath:");
        System.out.println("-----------------------");
        if (classpath.count() == 0)
        {
            System.out.println("No classpath entries and/or version information available show.");
            return;
        }

        System.out.println("Version Information on " + classpath.count() + " entr" + ((classpath.count() > 1)?"ies":"y") + " in the classpath.");
        System.out.println("Note: order presented here is how they would appear on the classpath.");
        System.out.println("      changes to the --module=name command line options will be reflected here.");

        int i = 0;
        for (File element : classpath.getElements())
        {
            System.out.printf("%2d: %24s | %s\n",i++,getVersion(element),baseHome.toShortForm(element));
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
            System.out.println("WARNING: Nothing to start, exiting ...");
            StartLog.debug(e);
            usageExit(ERR_INVOKE_MAIN);
            return;
        }

        StartLog.debug("%s - %s",invoked_class,invoked_class.getPackage().getImplementationVersion());

        CommandLineBuilder cmd = args.getMainArgs(baseHome,false);
        String argArray[] = cmd.getArgs().toArray(new String[0]);
        StartLog.debug("Command Line Args: %s",cmd.toString());

        Class<?>[] method_param_types = new Class[]
        { argArray.getClass() };

        Method main = invoked_class.getDeclaredMethod("main",method_param_types);
        Object[] method_params = new Object[]
        { argArray };
        main.invoke(null,method_params);
    }

    public void listConfig(StartArgs args)
    {
        // Dump Jetty Home / Base
        args.dumpEnvironment();

        // Dump JVM Args
        args.dumpJvmArgs();

        // Dump System Properties
        args.dumpSystemProperties();

        // Dump Properties
        args.dumpProperties();

        // Dump Classpath
        dumpClasspathWithVersions(args.getClasspath());

        // Dump Resolved XMLs
        args.dumpActiveXmls(baseHome);
    }

    private void listModules(StartArgs args)
    {
        System.out.println();
        System.out.println("Jetty All Available Modules:");
        System.out.println("----------------------------");
        args.getAllModules().dump();

        // Dump Enabled Modules
        System.out.println();
        System.out.println("Jetty Active Module Tree:");
        System.out.println("-------------------------");
        Modules modules = args.getAllModules();
        modules.dumpEnabledTree();
    }

    private void moduleIni(StartArgs args, String name, boolean topLevel, boolean appendStartIni) throws IOException
    {
        // Find the start.d relative to the base directory only.
        File start_d = baseHome.getBaseFile("start.d");

        // Is this a module?
        Modules modules = args.getAllModules();
        Module module = modules.get(name);
        if (module == null)
        {
            StartLog.warn("ERROR: No known module for %s",name);
            return;
        }

        // Find any named ini file and check it follows the convention
        File start_ini = baseHome.getBaseFile("start.ini");
        String short_start_ini = baseHome.toShortForm(start_ini);
        File ini = new File(start_d,name + ".ini");
        String short_ini = baseHome.toShortForm(ini);
        StartIni module_ini = null;
        if (ini.exists())
        {
            module_ini = new StartIni(ini);
            if (module_ini.getLineMatches(Pattern.compile("--module=(.*, *)*" + name)).size() == 0)
            {
                StartLog.warn("ERROR: %s is not enabled in %s!",name,short_ini);
                return;
            }
        }

        boolean transitive = module.isEnabled() && (module.getSources().size() == 0);
        boolean has_ini_lines = module.getInitialise().size() > 0;

        // If it is not enabled or is transitive with ini template lines or toplevel and doesn't exist
        if (!module.isEnabled() || (transitive && has_ini_lines) || (topLevel && !ini.exists() && !appendStartIni))
        {
            String source = null;
            PrintWriter out = null;
            try
            {
                if (appendStartIni)
                {
                    if ((!start_ini.exists() && !start_ini.createNewFile()) || !start_ini.canWrite())
                    {
                        StartLog.warn("ERROR: Bad %s! ",start_ini);
                        return;
                    }
                    source = short_start_ini;
                    StartLog.info("%-15s initialised in %s (appended)",name,source);
                    out = new PrintWriter(new FileWriter(start_ini,true));
                }
                else
                {
                    // Create the directory if needed
                    FS.ensureDirectoryExists(start_d);
                    FS.ensureDirectoryWritable(start_d);
                    try
                    {
                        // Create a new ini file for it
                        if (!ini.createNewFile())
                        {
                            StartLog.warn("ERROR: %s cannot be initialised in %s! ",name,short_ini);
                            return;
                        }
                    }
                    catch (IOException e)
                    {
                        StartLog.warn("ERROR: Unable to create %s!",ini);
                        StartLog.warn(e);
                        return;
                    }
                    source = short_ini;
                    StartLog.info("%-15s initialised in %s (created)",name,source);
                    out = new PrintWriter(ini);
                }

                if (appendStartIni)
                {
                    out.println();
                }
                out.println("#");
                out.println("# Initialize module " + name);
                out.println("#");
                Pattern p = Pattern.compile("--module=([^,]+)(,([^,]+))*");

                out.println("--module=" + name);
                args.parse("--module=" + name,source);
                modules.enable(name,Collections.singletonList(source));
                for (String line : module.getInitialise())
                {
                    out.println(line);
                    args.parse(line,source);
                    Matcher m = p.matcher(line);
                    if (m.matches())
                    {
                        for (int i = 1; i <= m.groupCount(); i++)
                        {
                            String n = m.group(i);
                            if (n == null)
                            {
                                continue;
                            }
                            n = n.trim();
                            if ((n.length() == 0) || n.startsWith(","))
                            {
                                continue;
                            }

                            modules.enable(n,Collections.singletonList(source));
                        }
                    }
                }
            }
            finally
            {
                if (out != null)
                {
                    out.close();
                }
            }
        }
        else if (ini.exists())
        {
            StartLog.info("%-15s initialised in %s",name,short_ini);
        }

        // Also list other places this module is enabled
        for (String source : module.getSources())
        {
            if (!short_ini.equals(source))
            {
                StartLog.info("%-15s enabled in     %s",name,baseHome.toShortForm(source));
            }
        }

        // Do downloads now
        for (String file : module.getFiles())
        {
            initFile(new FileArg(file));
        }

        // Process dependencies from top level only
        if (topLevel)
        {
            List<Module> parents = new ArrayList<>();
            for (String parent : modules.resolveParentModulesOf(name))
            {
                if (!name.equals(parent))
                {
                    Module m = modules.get(parent);
                    m.setEnabled(true);
                    parents.add(m);
                }
            }
            Collections.sort(parents,Collections.reverseOrder(new Module.DepthComparator()));
            for (Module m : parents)
            {
                moduleIni(args,m.getName(),false,appendStartIni);
            }
        }
    }

    /**
     * Convenience for <code>processCommandLine(cmdLine.toArray(new String[cmdLine.size()]))</code>
     */
    public StartArgs processCommandLine(List<String> cmdLine) throws Exception
    {
        return this.processCommandLine(cmdLine.toArray(new String[cmdLine.size()]));
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

        StartLog.debug("jetty.home=%s",baseHome.getHome());
        StartLog.debug("jetty.base=%s",baseHome.getBase());

        // ------------------------------------------------------------
        // 3) Load Inis
        File start_ini = baseHome.getBaseFile("start.ini");
        if (FS.canReadFile(start_ini))
        {
            StartLog.debug("Reading ${jetty.base}/start.ini - %s",start_ini);
            args.parse(baseHome,new StartIni(start_ini));
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
                StartLog.debug("Reading ${jetty.base}/start.d/%s - %s",file.getName(),file);
                args.parse(baseHome,new StartIni(file));
            }
        }

        // 4) Parse everything provided.
        // This would be the directory information +
        // the various start inis
        // and then the raw command line arguments
        StartLog.debug("Parsing collected arguments");
        args.parseCommandLine();

        // 5) Module Registration
        Modules modules = new Modules();
        StartLog.debug("Registering all modules");
        modules.registerAll(baseHome, args);

        // 6) Active Module Resolution
        for (String enabledModule : args.getEnabledModules())
        {
            List<String> sources = args.getSources(enabledModule);
            modules.enable(enabledModule,sources);
        }

        StartLog.debug("Building Module Graph");
        modules.buildGraph();

        args.setAllModules(modules);
        List<Module> activeModules = modules.resolveEnabled();

        // 7) Lib & XML Expansion / Resolution
        args.expandModules(baseHome,activeModules);

        // 8) Resolve Extra XMLs
        args.resolveExtraXmls(baseHome);

        return args;
    }

    public void start(StartArgs args) throws IOException, InterruptedException
    {
        StartLog.debug("StartArgs: %s",args);

        // Get Desired Classpath based on user provided Active Options.
        Classpath classpath = args.getClasspath();

        System.setProperty("java.class.path",classpath.toString());

        // Show the usage information and return
        if (args.isHelp())
        {
            usage(true);
        }

        // Show the version information and return
        if (args.isListClasspath())
        {
            dumpClasspathWithVersions(classpath);
        }

        // Show configuration
        if (args.isListConfig())
        {
            listConfig(args);
        }

        // Show modules
        if (args.isListModules())
        {
            listModules(args);
        }
        
        // Generate Module Graph File
        if (args.getModuleGraphFilename() != null)
        {
            File outputFile = baseHome.getBaseFile(args.getModuleGraphFilename());
            System.out.printf("Generating GraphViz Graph of Jetty Modules at %s%n",baseHome.toShortForm(outputFile));
            ModuleGraphWriter writer = new ModuleGraphWriter();
            writer.config(args.getProperties());
            writer.write(args.getAllModules(),outputFile);
        }

        // Show Command Line to execute Jetty
        if (args.isDryRun())
        {
            CommandLineBuilder cmd = args.getMainArgs(baseHome,true);
            System.out.println(cmd.toString());
        }

        if (args.isStopCommand())
        {
            int stopPort = Integer.parseInt(args.getProperties().getString("STOP.PORT"));
            String stopKey = args.getProperties().getString("STOP.KEY");

            if (args.getProperties().getString("STOP.WAIT") != null)
            {
                int stopWait = Integer.parseInt(args.getProperties().getString("STOP.PORT"));

                stop(stopPort,stopKey,stopWait);
            }
            else
            {
                stop(stopPort,stopKey);
            }
        }

        // Initialize
        for (String module : args.getModuleStartIni())
        {
            moduleIni(args,module,true,true);
        }

        // Initialize
        for (String module : args.getModuleStartdIni())
        {
            moduleIni(args,module,true,false);
        }

        // Check files
        for (FileArg arg : args.getFiles())
        {
            File file = baseHome.getBaseFile(arg.location);
            if (!file.exists() && args.isDownload())
                initFile(arg);

            if (!file.exists())
            {
                args.setRun(false);
                String type=arg.location.endsWith("/")?"directory":"file";
                if (arg.uri==null)
                    StartLog.warn("Required %s '%s' does not exist. Run with --create-files to create",type,baseHome.toShortForm(file));
                else
                    StartLog.warn("Required %s '%s' not downloaded from %s.  Run with --create-files to download",type,baseHome.toShortForm(file),arg.uri);
            }
        }
        
        // Informational command line, don't run jetty
        if (!args.isRun())
        {
            return;
        }

        // execute Jetty in another JVM
        if (args.isExec())
        {
            CommandLineBuilder cmd = args.getMainArgs(baseHome,true);
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
        ClassLoader cl = classpath.getClassLoader();
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

    public void usage(boolean exit)
    {
        String usageResource = "org/eclipse/jetty/start/usage.txt";
        boolean usagePresented = false;
        try (InputStream usageStream = getClass().getClassLoader().getResourceAsStream(usageResource))
        {
            if (usageStream != null)
            {
                try (InputStreamReader reader = new InputStreamReader(usageStream); BufferedReader buf = new BufferedReader(reader))
                {
                    usagePresented = true;
                    String line;
                    while ((line = buf.readLine()) != null)
                    {
                        System.out.println(line);
                    }
                }
            }
            else
            {
                System.out.println("No usage.txt ??");
            }
        }
        catch (IOException e)
        {
            StartLog.warn(e);
        }
        if (!usagePresented)
        {
            System.err.println("ERROR: detailed usage resource unavailable");
        }
        if (exit)
        {
            System.exit(EXIT_USAGE);
        }
    }
}
