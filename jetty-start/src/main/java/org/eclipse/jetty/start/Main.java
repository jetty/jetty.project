//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.jetty.start.config.CommandLineConfigSource;

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
    private static final String EXITING_LICENSE_NOT_ACKNOWLEDGED = "Exiting: license not acknowledged!";
    private static final int EXIT_USAGE = 1;

    public static String join(Collection<?> objs, String delim)
    {
        if (objs==null)
        {
            return "";
        }
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

    private BaseHome baseHome;
    private StartArgs startupArgs;

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

    private void initFile(StartArgs args, FileArg farg)
    {
        try
        {
            Path file = baseHome.getBasePath(farg.location);
            
            StartLog.debug("[init-file] %s module specified file %s",file.toAbsolutePath(),(FS.exists(file)?"[Exists!]":""));
            if (FS.exists(file))
            {
                // file already initialized / downloaded, skip it
                return;
            }

            if (farg.uri!=null)
            {
                URL url = new URL(farg.uri);

                StartLog.log("DOWNLOAD", "%s to %s", url, farg.location);

                FS.ensureDirectoryExists(file.getParent());
                
                if (args.isTestingModeEnabled())
                {
                    StartLog.log("TESTING MODE", "Skipping download of " + url);
                    return;
                }

                byte[] buf = new byte[8192];
                try (InputStream in = url.openStream(); 
                     OutputStream out = Files.newOutputStream(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE))
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
            else if (farg.location.endsWith("/"))
            {
                StartLog.log("MKDIR",baseHome.toShortForm(file));
                FS.ensureDirectoryExists(file);
            }
            else
            {
                String shortRef = baseHome.toShortForm(file);
                if (args.isTestingModeEnabled())
                {
                    StartLog.log("TESTING MODE","Skipping required file check on: %s",shortRef);
                    return;
                }
                StartLog.warn("MISSING: Required file %s",shortRef);
            }
        }
        catch (Exception e)
        {
            StartLog.warn("ERROR: processing %s%n%s",farg,e);
            StartLog.warn(e);
            usageExit(EXIT_USAGE);
        }
    }

    private void dumpClasspathWithVersions(Classpath classpath)
    {
        StartLog.endStartLog();
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

    public void invokeMain(ClassLoader classloader, StartArgs args) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException, ClassNotFoundException, IOException
    {
        Class<?> invoked_class = null;
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
        Object[] method_params = new Object[] { argArray };
        StartLog.endStartLog();
        main.invoke(null,method_params);
    }

    public void listConfig(StartArgs args)
    {
        StartLog.endStartLog();
        
        // Dump Jetty Home / Base
        args.dumpEnvironment(baseHome);

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
        StartLog.endStartLog();
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

    /**
     * Build out INI file.
     * <p>
     * This applies equally for either <code>${jetty.base}/start.ini</code> or
     * <code>${jetty.base}/start.d/${name}.ini</code> 
     * 
     * @param args the arguments of what modules are enabled
     * @param name the name of the module to based the build of the ini
     * @param topLevel 
     * @param appendStartIni true to append to <code>${jetty.base}/start.ini</code>, 
     * false to create a <code>${jetty.base}/start.d/${name}.ini</code> entry instead.
     * @throws IOException
     */
    private void buildIni(StartArgs args, String name, boolean topLevel, boolean appendStartIni) throws IOException
    {        
        // Find the start.d relative to the base directory only.
        Path start_d = baseHome.getBasePath("start.d");

        // Is this a module?
        Modules modules = args.getAllModules();
        Module module = modules.get(name);
        if (module == null)
        {
            StartLog.warn("ERROR: No known module for %s",name);
            return;
        }
        
        boolean transitive = module.isEnabled() && (module.getSources().size() == 0);

        // Find any named ini file and check it follows the convention
        Path start_ini = baseHome.getBasePath("start.ini");
        String short_start_ini = baseHome.toShortForm(start_ini);
        Path startd_ini = start_d.resolve(name + ".ini");
        String short_startd_ini = baseHome.toShortForm(startd_ini);
        StartIni module_ini = null;
        if (FS.exists(startd_ini))
        {
            module_ini = new StartIni(startd_ini);
            if (module_ini.getLineMatches(Pattern.compile("--module=(.*, *)*" + name)).size() == 0)
            {
                StartLog.warn("ERROR: %s is not enabled in %s!",name,short_startd_ini);
                return;
            }
        }

        if (!args.isApproveAllLicenses())
        {
            if (!module.hasFiles(baseHome) && !module.acknowledgeLicense())
            {
                StartLog.warn(EXITING_LICENSE_NOT_ACKNOWLEDGED);
                System.exit(1);
            }
        }
        
        boolean buildIni=false;
        if (module.isEnabled())
        {
            // is it an explicit request to create an ini file?
            if (topLevel && !FS.exists(startd_ini) && !appendStartIni)
            {
                buildIni=true;
            }
            // else is it transitive
            else if (transitive)
            {
                if (module.hasDefaultConfig())
                {
                    buildIni = true;
                    StartLog.info("%-15s initialised transitively",name);
                }
            }
            // else must be initialized explicitly
            else 
            {
                for (String source : module.getSources())
                {
                    StartLog.info("%-15s initialised in %s",name,baseHome.toShortForm(source));
                }
            }
        }
        else 
        {
            buildIni=true;
        }
        
        String source = "<transitive>";

        // If we need an ini
        if (buildIni)
        {
            // File BufferedWriter
            BufferedWriter writer = null;
            PrintWriter out = null;
            try
            {
                if (appendStartIni)
                {
                    source = short_start_ini;
                    StartLog.info("%-15s initialised in %s (appended)",name,source);
                    writer = Files.newBufferedWriter(start_ini,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    out = new PrintWriter(writer);
                }
                else
                {
                    // Create the directory if needed
                    FS.ensureDirectoryExists(start_d);
                    FS.ensureDirectoryWritable(start_d);
                    source = short_startd_ini;
                    StartLog.info("%-15s initialised in %s (created)",name,source);
                    writer = Files.newBufferedWriter(startd_ini,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                    out = new PrintWriter(writer);
                }

                if (appendStartIni)
                {
                    out.println();
                }
                out.println("# --------------------------------------- ");
                out.println("# Module: " + name);

                out.println("--module=" + name);
                
                args.parse("--module=" + name,source);
                args.parseModule(module);
                
                for (String line : module.getDefaultConfig())
                {
                    out.println(line);
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
        
        modules.enable(name,Collections.singletonList(source));
        
        // Also list other places this module is enabled
        for (String src : module.getSources())
        {
            StartLog.debug("also enabled in: %s",src);
            if (!short_start_ini.equals(src))
            {
                StartLog.info("%-15s enabled in     %s",name,baseHome.toShortForm(src));
            }
        }

        // Do downloads now
        for (String file : module.getFiles())
        {
            initFile(args, new FileArg(module,file));
        }

        // Process dependencies
        module.expandProperties(args.getProperties());
        modules.registerParentsIfMissing(module);
        modules.buildGraph();
        
        // process new ini modules
        if (topLevel)
        {
            List<Module> depends = new ArrayList<>();
            for (String depend : modules.resolveParentModulesOf(name))
            {
                if (!name.equals(depend))
                {
                    Module m = modules.get(depend);
                    m.setEnabled(true);
                    depends.add(m);
                }
            }
            Collections.sort(depends,Collections.reverseOrder(new Module.DepthComparator()));
            
            Set<String> done = new HashSet<>(0);
            while (true)
            {
                // initialize known dependencies
                boolean complete=true;
                for (Module m : depends)
                {
                    if (!done.contains(m.getName()))
                    {
                        complete=false;
                        buildIni(args,m.getName(),false,appendStartIni);
                        done.add(m.getName());
                    }
                }
                
                if (complete)
                {
                    break;
                }
                
                // look for any new ones resolved via expansion
                depends.clear();
                for (String depend : modules.resolveParentModulesOf(name))
                {
                    if (!name.equals(depend))
                    {
                        Module m = modules.get(depend);
                        m.setEnabled(true);
                        depends.add(m);
                    }
                }
                Collections.sort(depends,Collections.reverseOrder(new Module.DepthComparator()));
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
        // Processing Order is important!
        // ------------------------------------------------------------
        // 1) Configuration Locations
        CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
        baseHome = new BaseHome(cmdLineSource);

        StartLog.debug("jetty.home=%s",baseHome.getHome());
        StartLog.debug("jetty.base=%s",baseHome.getBase());

        // ------------------------------------------------------------
        // 2) Parse everything provided.
        // This would be the directory information +
        // the various start inis
        // and then the raw command line arguments
        StartLog.debug("Parsing collected arguments");
        StartArgs args = new StartArgs();
        args.parse(baseHome.getConfigSources());

        // ------------------------------------------------------------
        // 3) Module Registration
        Modules modules = new Modules(baseHome,args);
        StartLog.debug("Registering all modules");
        modules.registerAll();

        // ------------------------------------------------------------
        // 4) Active Module Resolution
        for (String enabledModule : args.getEnabledModules())
        {
            List<String> msources = args.getSources(enabledModule);
            modules.enable(enabledModule,msources);
        }
        
        StartLog.debug("Building Module Graph");
        modules.buildGraph();

        args.setAllModules(modules);
        List<Module> activeModules = modules.resolveEnabled();
        
        // ------------------------------------------------------------
        // 5) Lib & XML Expansion / Resolution
        args.expandLibs(baseHome);
        args.expandModules(baseHome,activeModules);

        // ------------------------------------------------------------
        // 6) Resolve Extra XMLs
        args.resolveExtraXmls(baseHome);
        
        // ------------------------------------------------------------
        // 9) Resolve Property Files
        args.resolvePropertyFiles(baseHome);

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
            Path outputFile = baseHome.getBasePath(args.getModuleGraphFilename());
            System.out.printf("Generating GraphViz Graph of Jetty Modules at %s%n",baseHome.toShortForm(outputFile));
            ModuleGraphWriter writer = new ModuleGraphWriter();
            writer.config(args.getProperties());
            writer.write(args.getAllModules(),outputFile);
        }

        // Show Command Line to execute Jetty
        if (args.isDryRun())
        {
            CommandLineBuilder cmd = args.getMainArgs(baseHome,true);
            System.out.println(cmd.toString(File.separatorChar=='/'?" \\\n":" "));
        }

        if (args.isStopCommand())
        {
            doStop(args);
        }
        
        boolean rebuildGraph = false;

        // Initialize start.ini
        for (String module : args.getAddToStartIni())
        {
            buildIni(args,module,true,true);
            rebuildGraph = true;
        }

        // Initialize start.d
        for (String module : args.getAddToStartdIni())
        {
            buildIni(args,module,true,false);
            rebuildGraph = true;
        }
        
        if (rebuildGraph)
        {
            args.getAllModules().clearMissing();
            args.getAllModules().buildGraph();
        }
        
        // If in --create-files, check licenses
        if(args.isDownload())
        {
            if (!args.isApproveAllLicenses())
            {
                for (Module module : args.getAllModules().resolveEnabled())
                {
                    if (!module.hasFiles(baseHome) && !module.acknowledgeLicense())
                    {
                        StartLog.warn(EXITING_LICENSE_NOT_ACKNOWLEDGED);
                        System.exit(1);
                    }
                }
            }
        }

        // Check ini files for download possibilities
        for (FileArg arg : args.getFiles())
        {
            Path file = baseHome.getBasePath(arg.location);
            if (!FS.exists(file) && args.isDownload())
            {
                initFile(args, arg);
            }

            if (!FS.exists(file))
            {
                boolean isDir = arg.location.endsWith("/");
                if (isDir)
                {
                    StartLog.log("MKDIR", baseHome.toShortForm(file));
                    FS.ensureDirectoryExists(file);
                    /* Startup should not fail to run on missing directories.
                     * See Bug #427204
                     */
                    // args.setRun(false);
                }
                else
                {
                    String shortRef = baseHome.toShortForm(file);
                    if (args.isTestingModeEnabled())
                    {
                        StartLog.log("TESTING MODE","Skipping required file check on: %s",shortRef);
                        return;
                    }

                    StartLog.warn("Missing Required File: %s",baseHome.toShortForm(file));
                    args.setRun(false);
                    if (arg.uri != null)
                    {
                        StartLog.warn("  Can be downloaded From: %s",arg.uri);
                        StartLog.warn("  Run start.jar --create-files to download");
                    }
                }
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
            cmd.debug();
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

        ClassLoader cl = classpath.getClassLoader();
        Thread.currentThread().setContextClassLoader(cl);

        // Invoke the Main Class
        try
        {
            invokeMain(cl, args);
        }
        catch (Exception e)
        {
            usageExit(e,ERR_INVOKE_MAIN);
        }
    }

    private void doStop(StartArgs args)
    {
        String stopHost = args.getProperties().getString("STOP.HOST");
        int stopPort = Integer.parseInt(args.getProperties().getString("STOP.PORT"));
        String stopKey = args.getProperties().getString("STOP.KEY");

        if (args.getProperties().getString("STOP.WAIT") != null)
        {
            int stopWait = Integer.parseInt(args.getProperties().getString("STOP.WAIT"));

            stop(stopHost,stopPort,stopKey,stopWait);
        }
        else
        {
            stop(stopHost,stopPort,stopKey);
        }
    }

    /**
     * Stop a running jetty instance.
     */
    public void stop(String host, int port, String key)
    {
        stop(host,port,key,0);
    }

    public void stop(String host, int port, String key, int timeout)
    {
        if (host==null || host.length()==0)
            host="127.0.0.1";
        
        try
        {
            if (port <= 0)
            {
                System.err.println("STOP.PORT system property must be specified");
            }
            if (key == null)
            {
                key = "";
                System.err.println("STOP.KEY system property must be specified");
                System.err.println("Using empty key");
            }

            try (Socket s = new Socket(InetAddress.getByName(host),port))
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
        StartLog.endStartLog();
        if(!printTextResource("org/eclipse/jetty/start/usage.txt"))
        {
            System.err.println("ERROR: detailed usage resource unavailable");
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
                try (InputStreamReader reader = new InputStreamReader(stream); BufferedReader buf = new BufferedReader(reader))
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
                System.out.println("Unable to find resource: " + resourceName);
            }
        }
        catch (IOException e)
        {
            StartLog.warn(e);
        }

        return resourcePrinted;
    }

    // ------------------------------------------------------------
    // implement Apache commons daemon (jsvc) lifecycle methods (init, start, stop, destroy)
    public void init(String[] args) throws Exception
    {
        try
        {
            startupArgs = processCommandLine(args);
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

    public void start() throws Exception
    {
        start(startupArgs);
    }

    public void stop() throws Exception
    {
        doStop(startupArgs);
    }

    public void destroy()
    {
    }
}
