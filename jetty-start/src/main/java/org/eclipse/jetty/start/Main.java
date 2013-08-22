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
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
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
import java.net.URL;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
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
    private static final String START_LOG_FILENAME = "start.log";
    private static final SimpleDateFormat START_LOG_ROLLOVER_DATEFORMAT = new SimpleDateFormat("yyyy_MM_dd-HHmmSSSSS.'" + START_LOG_FILENAME + "'");
    private static final Pattern NNN_MODULE_INI = Pattern.compile("^(\\d\\d\\d-)(.*?\\.ini)(\\.disabled)?$",Pattern.CASE_INSENSITIVE);

    private static final int EXIT_USAGE = 1;
    private boolean _showUsage = false;
    private boolean _dumpVersions = false;
    private boolean _listConfig = false;
    private boolean _listOptions = false;
    private boolean _noRun = false;
    private boolean _dryRun = false;
    private boolean _exec = false;
    private final Config _config;
    private final Set<String> _sysProps = new HashSet<>();
    private final List<String> _jvmArgs = new ArrayList<>();
    private final List<String> _enable = new ArrayList<>();
    private final List<String> _disable = new ArrayList<>();
    private String _startConfig = null;

    public static void main(String[] args)
    {
        try
        {
            Main main = new Main();
            List<String> xmls = main.processCommandLine(args);
            if (xmls != null)
                main.start(xmls);
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
        _config = new Config();
    }

    Config getConfig()
    {
        return _config;
    }

    public List<String> processCommandLine(String[] cmdLine) throws Exception
    {
        StartArgs args = new StartArgs(cmdLine);
        BaseHome baseHome = _config.getBaseHome();

        // Processing Order is important!
        // ------------------------------------------------------------
        // 1) Directory Locations

        // Set Home and Base at the start, as all other paths encountered
        // will be based off of them.
        _config.getBaseHome().initialize(args);

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

        File start_d = _config.getBaseHome().getBaseFile("start.d");
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
        args.expandModules(baseHome, activeModules);

        /*
        // Do we have a start.ini?
        File start_ini = _config.getBaseHome().getFile("start.ini");
        if (start_ini.exists() && start_ini.canRead() && !start_ini.isDirectory())
            inis.add(new StartIni(start_ini));

        // Do we have a start.d?
        File start_d = _config.getBaseHome().getFile("start.d");
        if (start_d.exists() && start_d.canRead() && start_d.isDirectory())
        {
            List<File> files = new ArrayList<>();
            for (File file : start_d.listFiles(new FS.IniFilter()))
                files.add(file);

            Collections.sort(files,new NaturalSort.Files());
            for (File file : files)
                inis.add(new StartIni(file));
        }

        // Add the commandline last
        inis.add(new StartIni(cmd_line));

        // TODO - we could sort the StartIni files by dependency here

        // The XML Configuration Files to initialize with
        List<String> xmls = new ArrayList<String>();

        // Expand arguments
        for (StartIni ini : inis)
        {
            String source = "<cmdline>";
            if (ini.getFile() != null)
                source = ini.getFile().getAbsolutePath();

            for (String arg : ini.getLines())
            {

                if ("--help".equals(arg) || "-?".equals(arg))
                {
                    _showUsage = true;
                    _noRun = true;
                    continue;
                }

                if ("--stop".equals(arg))
                {
                    int port = Integer.parseInt(_config.getProperty("STOP.PORT","-1"));
                    String key = _config.getProperty("STOP.KEY",null);
                    int timeout = Integer.parseInt(_config.getProperty("STOP.WAIT","0"));
                    stop(port,key,timeout);
                    _noRun = true;
                }

                if (arg.startsWith("--download="))
                {
                    download(arg);
                    continue;
                }

                if ("--version".equals(arg) || "-v".equals(arg) || "--info".equals(arg))
                {
                    _dumpVersions = true;
                    _noRun = true;
                    continue;
                }

                if ("--list-modes".equals(arg) || "--list-options".equals(arg))
                {
                    _listOptions = true;
                    _noRun = true;
                    continue;
                }

                if ("--list-config".equals(arg))
                {
                    _listConfig = true;
                    _noRun = true;
                    continue;
                }

                if ("--exec-print".equals(arg) || "--dry-run".equals(arg))
                {
                    _dryRun = true;
                    _noRun = true;
                    continue;
                }

                if ("--exec".equals(arg))
                {
                    _exec = true;
                    continue;
                }

                if (arg.startsWith("--enable="))
                {
                    String module = arg.substring(9);
                    _noRun = true;
                    _enable.add(module);
                }

                if (arg.startsWith("--disable="))
                {
                    String module = arg.substring(10);
                    _noRun = true;
                    _disable.add(module);
                }

                // Alternative start.config file
                if (arg.startsWith("--config="))
                {
                    _startConfig = arg.substring(9);
                    continue;
                }

                // Special internal indicator that jetty was started by the jetty.sh Daemon
                // All this does is setup a start.log that captures startup console output
                // in the tiny window of time before the real logger kicks in.
                // Useful for capturing when things go horribly wrong
                if ("--daemon".equals(arg))
                {
                    File startDir = new File(System.getProperty("jetty.logs","logs"));
                    if (!startDir.exists() || !startDir.canWrite())
                        startDir = new File(".");

                    File startLog = new File(startDir,START_LOG_ROLLOVER_DATEFORMAT.format(new Date()));

                    if (!startLog.exists() && !startLog.createNewFile())
                    {
                        // Output about error is lost in majority of cases.
                        System.err.println("Unable to create: " + startLog.getAbsolutePath());
                        // Toss a unique exit code indicating this failure.
                        usageExit(ERR_LOGGING);
                    }

                    if (!startLog.canWrite())
                    {
                        // Output about error is lost in majority of cases.
                        System.err.println("Unable to write to: " + startLog.getAbsolutePath());
                        // Toss a unique exit code indicating this failure.
                        usageExit(ERR_LOGGING);
                    }
                    PrintStream logger = new PrintStream(new FileOutputStream(startLog,false));
                    System.setOut(logger);
                    System.setErr(logger);
                    System.out.println("Establishing " + START_LOG_FILENAME + " on " + new Date());
                    continue;
                }

                // Start Property (syntax similar to System Property)
                if (arg.startsWith("-D"))
                {
                    String[] assign = arg.substring(2).split("=",2);
                    _sysProps.add(assign[0]);
                    switch (assign.length)
                    {
                        case 2:
                            System.setProperty(assign[0],assign[1]);
                            break;
                        case 1:
                            System.setProperty(assign[0],"");
                            break;
                        default:
                            break;
                    }
                    continue;
                }

                // Anything else is a JVM argument
                if (arg.startsWith("-"))
                {
                    _jvmArgs.add(arg);
                    continue;
                }

                // Is this a Property?
                if (arg.indexOf('=') >= 0)
                {
                    String[] assign = arg.split("=",2);

                    switch (assign.length)
                    {
                        case 2:
                            if ("DEFINE".equals(assign[0]))
                            {
                                String opts[] = assign[1].split(",");
                                for (String opt : opts)
                                    _config.defineOption(opt.trim());
                            }
                            else if ("DEPEND".equals(assign[0]))
                            {
                                String opts[] = assign[1].split(",");
                                for (String opt : opts)
                                {
                                    opt = opt.trim();
                                    if (!_config.getOptions().contains(opt))
                                    {
                                        System.err.printf("ERROR: Missing Dependency: %s DEPEND %s%n",path(source),opt);
                                        _noRun = true;
                                    }
                                }
                            }
                            else if ("EXCLUDE".equals(assign[0]))
                            {
                                String opts[] = assign[1].split(",");
                                for (String opt : opts)
                                {
                                    opt = opt.trim();
                                    if (_config.getOptions().contains(opt))
                                    {
                                        System.err.printf("ERROR: Excluded Dependency: %s EXCLUDE %s%n",path(source),opt);
                                        _noRun = true;
                                    }
                                }
                            }
                            else if ("OPTION".equals(assign[0]))
                            {
                                String opts[] = assign[1].split(",");
                                for (String opt : opts)
                                    _config.addOption(opt.trim());
                            }
                            else if ("OPTIONS".equals(assign[0]))
                            {
                                this._config.setProperty(assign[0],assign[1]);
                            }
                            else
                            {
                                this._config.setProperty(assign[0],assign[1]);
                            }
                            break;

                        case 1:
                            this._config.setProperty(assign[0],null);
                            break;
                        default:
                            break;
                    }

                    continue;
                }

                // Anything else is considered an XML file.
                if (xmls.contains(arg))
                {
                    System.err.println("WARN: Argument '" + arg + "' specified multiple times. Check start.ini?");
                    System.err.println("Use \"java -jar start.jar --help\" for more information.");
                }
                xmls.add(arg);
            }
        }
        */

        return null;
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

            Config.debug("Download to %s %s",file.getAbsolutePath(),(file.exists()?"[Exists!]":""));
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

        BufferedReader buf = null;
        try
        {
            buf = new BufferedReader(new InputStreamReader(usageStream));
            String line;

            while ((line = buf.readLine()) != null)
            {
                if (line.endsWith("@") && line.indexOf('@') != line.lastIndexOf('@'))
                {
                    String indent = line.substring(0,line.indexOf("@"));
                    String info = line.substring(line.indexOf('@'),line.lastIndexOf('@'));

                    if (info.equals("@OPTIONS"))
                    {
                        List<String> sortedOptions = new ArrayList<String>();
                        sortedOptions.addAll(_config.getSectionIds());
                        Collections.sort(sortedOptions);

                        for (String option : sortedOptions)
                        {
                            if ("*".equals(option) || option.trim().length() == 0)
                                continue;
                            System.out.print(indent);
                            System.out.println(option);
                        }
                    }
                    else if (info.equals("@CONFIGS"))
                    {
                        FileFilter filter = new FileFilter()
                        {
                            public boolean accept(File path)
                            {
                                if (!path.isFile())
                                {
                                    return false;
                                }

                                String name = path.getName().toLowerCase(Locale.ENGLISH);
                                return (name.startsWith("jetty") && name.endsWith(".xml"));
                            }
                        };

                        // list etc
                        List<File> configFiles = _config.getBaseHome().listFiles("etc",filter);

                        for (File configFile : configFiles)
                        {
                            System.out.printf("%s%s%n",indent,path(configFile));
                        }
                    }
                    else if (info.equals("@STARTINI"))
                    {
                        BaseHome hb = _config.getBaseHome();
                        File start_d = hb.getFile("start.d");
                        if (start_d.exists() && start_d.isDirectory())
                        {
                            File[] files = start_d.listFiles(new FS.FilenameRegexFilter("(\\d\\d\\d-)?.*\\.ini(\\.disabled)?"));
                            Arrays.sort(files,new NaturalSort.Files());
                            for (File file : files)
                            {
                                String path = _config.getBaseHome().toShortForm(file);
                                System.out.printf("%s%s%n",indent,path);

                                if (Config.isDebug())
                                {
                                    StartIni ini = new StartIni(file);
                                    for (String arg : ini)
                                    {
                                        System.out.printf("%s +-- %s%n",indent,arg);
                                    }
                                }
                            }
                        }
                    }
                }
                else
                {
                    System.out.println(line);
                }
            }
        }
        catch (IOException e)
        {
            usageExit(e,EXIT_USAGE);
        }
        finally
        {
            FS.close(buf);
        }
        System.exit(EXIT_USAGE);
    }

    private String path(String path)
    {
        return _config.getBaseHome().toShortForm(path);
    }

    private String path(File file)
    {
        return _config.getBaseHome().toShortForm(file);
    }

    public void invokeMain(ClassLoader classloader, String classname, List<String> args) throws IllegalAccessException, InvocationTargetException,
            NoSuchMethodException, ClassNotFoundException
    {
        Class<?> invoked_class = null;

        try
        {
            invoked_class = classloader.loadClass(classname);
        }
        catch (ClassNotFoundException e)
        {
            e.printStackTrace();
        }

        if (Config.isDebug() || invoked_class == null)
        {
            if (invoked_class == null)
            {
                System.err.println("ClassNotFound: " + classname);
            }
            else
            {
                System.err.println(classname + " " + invoked_class.getPackage().getImplementationVersion());
            }

            if (invoked_class == null)
            {
                usageExit(ERR_INVOKE_MAIN);
                return;
            }
        }

        String argArray[] = args.toArray(new String[0]);

        Class<?>[] method_param_types = new Class[]
        { argArray.getClass() };

        Method main = invoked_class.getDeclaredMethod("main",method_param_types);
        Object[] method_params = new Object[]
        { argArray };
        main.invoke(null,method_params);
    }

    public void start(List<String> xmls) throws IOException, InterruptedException
    {
        // Load potential Config (start.config)
        List<String> configuredXmls = loadConfig(xmls);

        // No XML defined in start.config or command line. Can't execute.
        if (configuredXmls.isEmpty())
        {
            throw new FileNotFoundException("No XML configuration files specified in start.config or command line.");
        }

        // Normalize the XML config options passed on the command line.
        configuredXmls = resolveXmlConfigs(configuredXmls);

        // Get Desired Classpath based on user provided Active Options.
        Classpath classpath = _config.getActiveClasspath();

        System.setProperty("java.class.path",classpath.toString());
        ClassLoader cl = classpath.getClassLoader();
        if (Config.isDebug())
        {
            System.err.println("java.class.path=" + System.getProperty("java.class.path"));
            System.err.println("jetty.home=" + System.getProperty("jetty.home"));
            System.err.println("jetty.base=" + System.getProperty("jetty.base"));
            System.err.println("java.home=" + System.getProperty("java.home"));
            System.err.println("java.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
            System.err.println("java.class.path=" + classpath);
            System.err.println("classloader=" + cl);
            System.err.println("classloader.parent=" + cl.getParent());
            System.err.println("properties=" + Config.getProperties());
        }

        for (String m : _enable)
            enable(m,true);
        for (String m : _disable)
            disable(m,true);

        // Show the usage information and return
        if (_showUsage)
            usage();

        // Show the version information and return
        if (_dumpVersions)
            showClasspathWithVersions(classpath);

        // Show all options with version information
        if (_listOptions)
            showAllOptionsWithVersions();

        if (_listConfig)
            listConfig();

        // Show Command Line to execute Jetty
        if (_dryRun)
        {
            CommandLineBuilder cmd = buildCommandLine(classpath,configuredXmls);
            System.out.println(cmd.toString());
        }

        // Informational command line, don't run jetty
        if (_noRun)
            return;

        // execute Jetty in another JVM
        if (_exec)
        {
            CommandLineBuilder cmd = buildCommandLine(classpath,configuredXmls);

            ProcessBuilder pbuilder = new ProcessBuilder(cmd.getArgs());
            final Process process = pbuilder.start();
            Runtime.getRuntime().addShutdownHook(new Thread()
            {
                @Override
                public void run()
                {
                    Config.debug("Destroying " + process);
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

        if (_jvmArgs.size() > 0 || _sysProps.size() > 0)
        {
            System.err.println("WARNING: System properties and/or JVM args set.  Consider using --dry-run or --exec");
        }

        // Set current context class loader to what is selected.
        Thread.currentThread().setContextClassLoader(cl);

        // Invoke the Main Class
        try
        {
            // Get main class as defined in start.config
            String classname = _config.getMainClassname();

            // Check for override of start class (via "jetty.server" property)
            String mainClass = System.getProperty("jetty.server");
            if (mainClass != null)
            {
                classname = mainClass;
            }

            // Check for override of start class (via "main.class" property)
            mainClass = System.getProperty("main.class");
            if (mainClass != null)
            {
                classname = mainClass;
            }

            Config.debug("main.class=" + classname);

            invokeMain(cl,classname,configuredXmls);
        }
        catch (Exception e)
        {
            usageExit(e,ERR_INVOKE_MAIN);
        }
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

    private String resolveXmlConfig(String xmlFilename) throws FileNotFoundException
    {
        if (!FS.isXml(xmlFilename))
        {
            // Nothing to resolve.
            return xmlFilename;
        }

        // Try normal locations
        File xml = _config.getBaseHome().getFile(xmlFilename);
        if (FS.isFile(xml))
        {
            return xml.getAbsolutePath();
        }

        // Try again, but prefixed with "etc/"
        xml = _config.getBaseHome().getFile("etc/" + xmlFilename);
        if (FS.isFile(xml))
        {
            return xml.getAbsolutePath();
        }

        throw new FileNotFoundException("Unable to find XML Config: " + xmlFilename);
    }

    CommandLineBuilder buildCommandLine(Classpath classpath, List<String> xmls) throws IOException
    {
        CommandLineBuilder cmd = new CommandLineBuilder(findJavaBin());

        for (String x : _jvmArgs)
        {
            cmd.addArg(x);
        }
        cmd.addRawArg("-Djetty.home=" + _config.getBaseHome().getHome());
        cmd.addRawArg("-Djetty.base=" + _config.getBaseHome().getBase());

        // Special Stop/Shutdown properties
        ensureSystemPropertySet("STOP.PORT");
        ensureSystemPropertySet("STOP.KEY");

        // System Properties
        for (String p : _sysProps)
        {
            String v = System.getProperty(p);
            cmd.addEqualsArg("-D" + p,v);
        }

        cmd.addArg("-cp");
        cmd.addRawArg(classpath.toString());
        cmd.addRawArg(_config.getMainClassname());

        // Check if we need to pass properties as a file
        Properties properties = Config.getProperties();
        if (properties.size() > 0)
        {
            File prop_file = File.createTempFile("start",".properties");
            if (!_dryRun)
                prop_file.deleteOnExit();
            properties.store(new FileOutputStream(prop_file),"start.jar properties");
            cmd.addArg(prop_file.getAbsolutePath());
        }

        for (String xml : xmls)
        {
            cmd.addRawArg(xml);
        }
        return cmd;
    }

    /**
     * Ensure that the System Properties are set (if defined as a System property, or start.config property, or start.ini property)
     * 
     * @param key
     *            the key to be sure of
     */
    private void ensureSystemPropertySet(String key)
    {
        if (_sysProps.contains(key))
        {
            return; // done
        }

        Properties props = Config.getProperties();
        if (props.containsKey(key))
        {
            String val = props.getProperty(key,null);
            if (val == null)
            {
                return; // no value to set
            }
            // setup system property
            _sysProps.add(key);
            System.setProperty(key,val);
        }
    }

    private String findJavaBin()
    {
        File javaHome = new File(System.getProperty("java.home"));
        if (!javaHome.exists())
        {
            return null;
        }

        File javabin = findExecutable(javaHome,"bin/java");
        if (javabin != null)
        {
            return javabin.getAbsolutePath();
        }

        javabin = findExecutable(javaHome,"bin/java.exe");
        if (javabin != null)
        {
            return javabin.getAbsolutePath();
        }

        return "java";
    }

    private File findExecutable(File root, String path)
    {
        String npath = path.replace('/',File.separatorChar);
        File exe = new File(root,npath);
        if (!exe.exists())
        {
            return null;
        }
        return exe;
    }

    private void showAllOptionsWithVersions()
    {
        Set<String> sectionIds = _config.getSectionIds();

        StringBuffer msg = new StringBuffer();
        msg.append("There ");
        if (sectionIds.size() > 1)
        {
            msg.append("are ");
        }
        else
        {
            msg.append("is ");
        }
        msg.append(String.valueOf(sectionIds.size()));
        msg.append(" OPTION");
        if (sectionIds.size() > 1)
        {
            msg.append("s");
        }
        msg.append(" available to use.");
        System.out.println(msg);
        System.out.println("Each option is listed along with associated available classpath entries,  in the order that they would appear from that mode.");
        System.out.println("Note: If using multiple options (eg: 'Server,servlet,webapp,jms,jmx') "
                + "then overlapping entries will not be repeated in the eventual classpath.");
        System.out.println();
        System.out.printf("${jetty.home} = %s%n",_config.getBaseHome().getHome());
        System.out.printf("${jetty.base} = %s%n",_config.getBaseHome().getBase());
        System.out.println();

        for (String sectionId : sectionIds)
        {
            if (Config.DEFAULT_SECTION.equals(sectionId))
            {
                System.out.println("GLOBAL option (Prepended Entries)");
            }
            else if ("*".equals(sectionId))
            {
                System.out.println("GLOBAL option (Appended Entries) (*)");
            }
            else
            {
                System.out.printf("Option [%s]",sectionId);
                if (Character.isUpperCase(sectionId.charAt(0)))
                {
                    System.out.print(" (Aggregate)");
                }
                System.out.println();
            }
            System.out.println("-------------------------------------------------------------");

            Classpath sectionCP = _config.getSectionClasspath(sectionId);

            if (sectionCP.isEmpty())
            {
                System.out.println("Empty option, no classpath entries active.");
                System.out.println();
                continue;
            }

            int i = 0;
            for (File element : sectionCP.getElements())
                System.out.printf("%2d: %20s | %s\n",i++,getVersion(element),path(element));

            System.out.println();
        }
    }

    private void showClasspathWithVersions(Classpath classpath)
    {
        // Iterate through active classpath, and fetch Implementation Version from each entry (if present)
        // to dump to end user.

        System.out.println("Active Options: " + _config.getOptions());

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
            System.out.printf("%2d: %20s | %s\n",i++,getVersion(element),path(element));
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

    private List<String> resolveXmlConfigs(List<String> xmls) throws FileNotFoundException
    {
        List<String> ret = new ArrayList<String>();
        for (String xml : xmls)
        {
            ret.add(resolveXmlConfig(xml));
        }

        return ret;
    }

    private void listConfig()
    {
        InputStream cfgstream = null;
        try
        {
            cfgstream = getConfigStream();
            byte[] buf = new byte[4096];

            int len = 0;

            while (len >= 0)
            {
                len = cfgstream.read(buf);
                if (len > 0)
                    System.out.write(buf,0,len);
            }
        }
        catch (Exception e)
        {
            usageExit(e,ERR_UNKNOWN);
        }
        finally
        {
            FS.close(cfgstream);
        }
    }

    /**
     * Load Configuration.
     * 
     * No specific configuration is real until a {@link Config#getCombinedClasspath(java.util.Collection)} is used to execute the {@link Class} specified by
     * {@link Config#getMainClassname()} is executed.
     * 
     * @param xmls
     *            the command line specified xml configuration options.
     * @return the list of xml configurations arriving via command line and start.config choices.
     */
    private List<String> loadConfig(List<String> xmls)
    {
        InputStream cfgstream = null;
        try
        {
            // Pass in xmls.size into Config so that conditions based on "nargs" work.
            _config.setArgCount(xmls.size());

            cfgstream = getConfigStream();

            // parse the config
            _config.parse(cfgstream);

            // Collect the configured xml configurations.
            List<String> ret = new ArrayList<String>();
            ret.addAll(xmls); // add command line provided xmls first.
            for (String xmlconfig : _config.getXmlConfigs())
            {
                // add xmlconfigs arriving via start.config
                if (!ret.contains(xmlconfig))
                {
                    ret.add(xmlconfig);
                }
            }

            return ret;
        }
        catch (Exception e)
        {
            usageExit(e,ERR_UNKNOWN);
            return null; // never executed (just here to satisfy javac compiler)
        }
        finally
        {
            FS.close(cfgstream);
        }
    }

    private InputStream getConfigStream() throws FileNotFoundException
    {
        String config = _startConfig;
        if (config == null || config.length() == 0)
        {
            config = System.getProperty("START","org/eclipse/jetty/start/start.config");
        }

        Config.debug("config=" + config);

        // Look up config as resource first.
        InputStream cfgstream = getClass().getClassLoader().getResourceAsStream(config);

        // resource not found, try filesystem next
        if (cfgstream == null)
        {
            cfgstream = new FileInputStream(config);
        }

        return cfgstream;
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

            Socket s = new Socket(InetAddress.getByName("127.0.0.1"),_port);
            if (timeout > 0)
                s.setSoTimeout(timeout * 1000);
            try
            {
                OutputStream out = s.getOutputStream();
                out.write((_key + "\r\nstop\r\n").getBytes());
                out.flush();

                if (timeout > 0)
                {
                    System.err.printf("Waiting %,d seconds for jetty to stop%n",timeout);
                    LineNumberReader lin = new LineNumberReader(new InputStreamReader(s.getInputStream()));
                    String response;
                    while ((response = lin.readLine()) != null)
                    {
                        Config.debug("Received \"" + response + "\"");
                        if ("Stopped".equals(response))
                            System.err.println("Server reports itself as Stopped");
                    }
                }
            }
            finally
            {
                s.close();
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

    void addJvmArgs(List<String> jvmArgs)
    {
        _jvmArgs.addAll(jvmArgs);
    }

    private void enable(final String module, boolean verbose) throws IOException
    {
        final String mini = module + ".ini";
        final String disable = module + ".ini.disabled";

        BaseHome hb = _config.getBaseHome();
        File start_d = hb.getFile("start.d");
        boolean found = false;
        File enabled = null;
        if (start_d.exists() && start_d.isDirectory())
        {
            for (File file : start_d.listFiles(new FS.FilenameRegexFilter("(\\d\\d\\d-)?" + Pattern.quote(module) + "\\.ini(\\.disabled)?")))
            {
                String n = file.getName();
                if (n.equalsIgnoreCase(mini))
                {
                    if (verbose)
                        System.err.printf("Module %s already enabled in %s%n",module,hb.toShortForm(file.getParent()));
                    found = true;
                    break;
                }

                if (n.equalsIgnoreCase(disable))
                {
                    enabled = new File(file.getParentFile(),mini);
                    System.err.printf("Enabling Module %s as %s%n",module,hb.toShortForm(enabled));
                    file.renameTo(enabled);
                    found = true;
                    break;
                }

                Matcher matcher = NNN_MODULE_INI.matcher(n);
                if (matcher.matches())
                {
                    if (matcher.group(3) == null)
                    {
                        if (verbose)
                            System.err.printf("Module %s already enabled in %s as %s%n",module,hb.toShortForm(file.getParent()),n);
                        found = true;
                    }
                    else
                    {
                        enabled = new File(file.getParentFile(),matcher.group(1) + mini);
                        System.err.printf("Enabling Module %s as %s%n",module,hb.toShortForm(enabled));
                        file.renameTo(enabled);
                        found = true;
                    }
                }
            }
        }

        // Shall we look for a template in home?
        if (!found && hb.isBaseDifferent())
        {
            File start_home = new File(hb.getHomeDir(),"start.d");

            if (start_home.exists() && start_home.isDirectory())
            {
                for (File file : start_home.listFiles(new FS.FilenameRegexFilter("(\\d\\d\\d-)?" + Pattern.quote(module) + "\\.ini(\\.disabled)?")))
                {
                    try
                    {
                        String n = file.getName();
                        if (n.equalsIgnoreCase(mini) || n.equalsIgnoreCase(disable))
                        {
                            enabled = new File(start_d,mini);
                            Files.copy(file.toPath(),enabled.toPath());
                            System.err.printf("Enabling Module %s as %s%n",module,hb.toShortForm(enabled));
                            found = true;
                            break;
                        }

                        Matcher matcher = NNN_MODULE_INI.matcher(n);
                        if (matcher.matches())
                        {
                            enabled = new File(start_d,matcher.group(1) + mini);
                            Files.copy(file.toPath(),enabled.toPath());
                            System.err.printf("Enabling Module %s as %s%n",module,hb.toShortForm(enabled));
                            found = true;
                            break;
                        }
                    }
                    catch (IOException e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        }

        if (!found)
        {
            System.err.printf("Module %s not found!%n",module);
        }
        else if (enabled != null)
        {
            // handle dependencies
            StartIni ini = new StartIni(enabled);
            for (String line : ini.getLineMatches(Pattern.compile("^DEPEND=.*$")))
            {
                String depend = line.trim().split("=")[1];
                for (String m : depend.split(","))
                    enable(m,false);
            }
            for (String line : ini.getLineMatches(Pattern.compile("^EXCLUDE=.*$")))
            {
                String depend = line.trim().split("=")[1];
                for (String m : depend.split(","))
                    disable(m,false);
            }
        }
    }

    private void disable(final String module, boolean verbose)
    {
        final String mini = module + ".ini";
        final String disable = module + ".ini.disabled";

        BaseHome hb = _config.getBaseHome();
        File start_d = hb.getFile("start.d");
        boolean found = false;
        if (start_d.exists() && start_d.isDirectory())
        {
            for (File file : start_d.listFiles(new FS.FilenameRegexFilter("(\\d\\d\\d-)?" + Pattern.quote(module) + "\\.ini(\\.disabled)?")))
            {
                String n = file.getName();
                if (n.equalsIgnoreCase(disable))
                {
                    if (verbose)
                        System.err.printf("Module %s already disabled in %s%n",module,hb.toShortForm(file.getParent()));
                    found = true;
                }
                else if (n.equalsIgnoreCase(mini))
                {
                    System.err.printf("Disabling Module %s in %s%n",module,hb.toShortForm(file.getParent()));
                    file.renameTo(new File(file.getParentFile(),disable));
                    found = true;
                }
                else
                {
                    Matcher matcher = NNN_MODULE_INI.matcher(n);
                    if (matcher.matches())
                    {
                        if (matcher.group(3) != null)
                        {
                            if (verbose)
                                System.err.printf("Module %s already disabled in %s as %s%n",module,hb.toShortForm(file.getParent()),n);
                            found = true;
                        }
                        else
                        {
                            String disabled = matcher.group(1) + disable;
                            System.err.printf("Disabling Module %s in %s as %s%n",module,hb.toShortForm(file.getParent()),disabled);
                            file.renameTo(new File(file.getParentFile(),disabled));
                            found = true;
                        }
                    }
                }
            }
        }

        if (!found && verbose)
        {
            System.err.printf("Module %s not found!%n",module);
        }
    }
}
