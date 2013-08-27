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

import java.io.BufferedReader;
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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.start.StartArgs.DownloadArg;

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

    private void download(DownloadArg arg)
    {
        try
        {
            File file = baseHome.getBaseFile(arg.location);

            StartLog.debug("Download to %s %s",file.getAbsolutePath(),(file.exists()?"[Exists!]":""));
            if (file.exists())
            {
                return;
            }

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
        System.out.println("      changes to the MODULES=[name,name,...] command line option will be reflected here.");

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

    private ModulePersistence loadModulePersistence() throws IOException
    {
        File file = baseHome.getBaseFile("modules/enabled");
        return new ModulePersistence(file);
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
        args.addSystemProperty("jetty.home",baseHome.getHome());
        args.addSystemProperty("jetty.base",baseHome.getBase());

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
        modules.registerAll(baseHome);

        // 6) Active Module Resolution
        for (String enabledModule : args.getEnabledModules())
        {
            List<String> sources = args.getSources(enabledModule);
            modules.enable(enabledModule,sources);
        }
        modules.enable(loadModulePersistence());

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

        // Various Downloads
        for (DownloadArg url : args.getDownloads())
        {
            download(url);
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

        // Show Command Line to execute Jetty
        if (args.isDryRun())
        {
            CommandLineBuilder cmd = args.getMainArgs(baseHome,true);
            System.out.println(cmd.toString());
        }

        // Enables/Disable
        ModulePersistence persistence = loadModulePersistence();
        if (args.isModulePersistenceChanging())
        {
            System.out.println("Persistent Module Management:");
            System.out.println("-----------------------------");
            System.out.printf("Persistence file: %s%n",baseHome.toShortForm(persistence.getFile()));
            for (String module : args.getModulePersistDisable())
            {
                persistence.disableModule(args,module);
            }
            for (String module : args.getModulePersistEnable())
            {
                persistence.enableModule(args,module);
            }
        }

        if (args.isStopCommand())
        {
            int stopPort = Integer.parseInt(args.getProperties().getProperty("STOP.PORT"));
            String stopKey = args.getProperties().getProperty("STOP.KEY");

            if (args.getProperties().getProperty("STOP.WAIT") != null)
            {
                int stopWait = Integer.parseInt(args.getProperties().getProperty("STOP.PORT"));

                stop(stopPort,stopKey,stopWait);
            }
            else
            {
                stop(stopPort,stopKey);
            }
        }

        // Enables/Disable
        for (String module : args.getDisable())
        {
            disable(args,module,true);
        }
        for (String module : args.getEnable())
        {
            enable(args,module,true);
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

    private void enable(StartArgs args, String name, boolean verbose) throws IOException
    {
        File start_d=baseHome.getFile("start.d");
        File ini=new File(start_d,name+".ini");
        
        // Is it already enabled
        if (ini.exists())
        {
            if (verbose)
                StartLog.warn("Module %s already enabled by: %s",name,baseHome.toShortForm(ini));
            return;
        }
        
        // Is there a disabled ini?
        File disabled=new File(start_d,name+".ini.disabled");
        boolean copy=false;
        if (!disabled.exists() && baseHome.isBaseDifferent())
        {
            copy=true;
            disabled=new File(new File(baseHome.getHomeDir(),"start.d"),name+".ini.disabled");
            if (!disabled.exists())
                disabled=new File(new File(baseHome.getHomeDir(),"start.d"),name+".ini");
        }
            
        if (disabled.exists())
        {
            // enable module by renaming/copying ini template
            System.err.printf("Enabling %s in %s from %s%n",name,baseHome.toShortForm(ini),baseHome.toShortForm(disabled));
            if (copy)
                Files.copy(disabled.toPath(),ini.toPath());
            else
                disabled.renameTo(ini);
            args.parse(baseHome, new StartIni(ini));
        }
        else if (args.getAllModules().resolveEnabled().contains(args.getAllModules().get(name)))
        {
            // No ini template and module is already enabled
            List<String> sources=args.getSources(name);
            if (sources!=null && sources.size()>0)
                for (String s: args.getSources(name))
                    StartLog.warn("Module %s is enabled in %s",name,s);
            else
                StartLog.warn("Module %s is already enabled (see --list-modules)",name);
                
        }
        else if (ini.createNewFile())
        {
            System.err.printf("Enabling %s in %s%n",name,baseHome.toShortForm(ini));
            // Create an ini
            try(FileOutputStream out = new FileOutputStream(ini);)
            {
                out.write(("--module="+name+"\n").getBytes("ISO-8859-1"));
            }
            args.parse(baseHome, new StartIni(ini));
        }
        else
        {
            StartLog.warn("ERROR: Module %s cannot be enabled! ",name);
            return;
        }
    
        // Process dependencies
        Modules modules = args.getAllModules();
        Module module=modules.get(name);
        if (module!=null)
            for (String parent:module.getParentNames())
                enable(args,parent,false);
    }

    private void disable(StartArgs args, String name, boolean verbose) throws IOException
    {
        File start_d=baseHome.getFile("start.d");
        File ini=new File(start_d,name+".ini");
        
        // Is it enabled?
        if (ini.exists())
        {
            File disabled=new File(start_d,name+".ini.disabled");
            
            if (disabled.exists())
            {
                StartLog.warn("ERROR: Disabled ini already exists: %s",baseHome.toShortForm(disabled));
                return;
            }

            StartLog.warn("Disabling %s from %s",name,baseHome.toShortForm(ini));
            ini.renameTo(disabled);
            
            return;
        }

        if (verbose)
            StartLog.warn("Module %s, ini file already disabled: %s",name,baseHome.toShortForm(ini));
        
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
