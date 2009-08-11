// ========================================================================
// Copyright (c) 2003-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.start;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.Policy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import org.eclipse.jetty.start.log.RedirectedStreamLogger;

/*-------------------------------------------*/
/**
 * <p>
 * Main start class. This class is intended to be the main class listed in the MANIFEST.MF of the start.jar archive. It
 * allows an application to be started with the command "java -jar start.jar".
 * </p>
 * 
 * <p>
 * The behaviour of Main is controlled by the parsing of the {@link Config} "org/eclipse/start/start.config" file
 * obtained as a resource or file. This can be overridden with the START system property.
 * </p>
 */
public class Main
{
    private boolean _showUsage = false;
    private boolean _dumpVersions = false;
    private boolean _listOptions = false;
    private boolean _dryRun = false;
    private boolean _secure = false;
    private boolean _fromDaemon = false;
    private Config _config = new Config();

    private String _jettyHome;

    public static void main(String[] args)
    {
        Main main = new Main();
        try
        {
            main.parseCommandLine(args);
        }
        catch (Throwable t)
        {
            t.printStackTrace(System.err);
        }
    }

    public void parseCommandLine(String[] args)
    {
        try
        {
            List<String> arguments = new ArrayList<String>();
            arguments.addAll(Arrays.asList(args)); // Add Arguments on Command Line
            arguments.addAll(loadStartIni()); // Add Arguments from start.ini (if it exists)

            // The XML Configuration Files to initialize with
            List<String> xmls = new ArrayList<String>();

            for (String arg : arguments)
            {
                if ("--help".equals(arg))
                {
                    _showUsage = true;
                    continue;
                }

                if ("--stop".equals(arg))
                {
                    int port = Integer.parseInt(System.getProperty("STOP.PORT","-1"));
                    String key = System.getProperty("STOP.KEY",null);
                    stop(port,key);
                    return;
                }

                if ("--version".equals(arg) || "-v".equals(arg) || "--info".equals(arg))
                {
                    _dumpVersions = true;
                    continue;
                }

                if ("--list-modes".equals(arg) || "--list-options".equals(arg))
                {
                    _listOptions = true;
                    continue;
                }

                if ("--exec-print".equals(arg)||"--dry-run".equals(arg))
                {
                    _dryRun = true;
                    continue;
                }

                // Special internal indicator that jetty was started by the jetty.sh Daemon
                if ("--fromDaemon".equals(arg))
                {
                    _fromDaemon = true;
                    PrintStream logger = new PrintStream(new RedirectedStreamLogger("daemon_yyyy_mm_dd.log",false,90,TimeZone.getTimeZone("GMT")));
                    System.setOut(logger);
                    System.setErr(logger);
                    continue;
                }

                if ("--secure".equals(arg))
                {
                    _secure = true;
                    continue;
                }

                // Process property spec
                if (arg.indexOf('=') >= 0)
                {
                    processProperty(arg);
                    continue;
                }

                // Anything else is considered an XML file.
                xmls.add(arg);
            }

            start(xmls);
        }
        catch (Throwable t)
        {
            t.printStackTrace(System.err);
            usage();
        }
    }

    private void processProperty(String arg)
    {
        String[] prop = arg.split("=",2);

        if (prop[0].startsWith("-D"))
        {
            // Process System Property
            if (prop.length == 2)
            {
                setSystemProperty(prop[0].substring(2),prop[1]);
            }
            else
            {
                System.err.println("Unable to set value-less System Property: " + prop[0]);
            }
        }
        else
        {
            // Process Startup Property
            if (prop.length == 2)
            {
                // Special case (the Config section id options)
                if ("OPTIONS".equals(prop[0]))
                {
                    String ids[] = prop[1].split(",");
                    for (String id : ids)
                    {
                        _config.addActiveOption(id);
                    }
                }
                else
                {
                    _config.setProperty(prop[0],prop[1]);
                }
            }
            else
            {
                _config.setProperty(prop[0],null);
            }
        }
    }

    /**
     * If a start.ini is present in the CWD, then load it into the argument list.
     */
    private List<String> loadStartIni()
    {
        File startIniFile = new File("start.ini");
        if (!startIniFile.exists())
        {
            // No start.ini found, skip load.
            return Collections.emptyList();
        }

        List<String> args = new ArrayList<String>();

        FileReader reader = null;
        BufferedReader buf = null;
        try
        {
            reader = new FileReader(startIniFile);
            buf = new BufferedReader(reader);

            String arg;
            while ((arg = buf.readLine()) != null)
            {
                // Is this a Property?
                if (arg.indexOf('=') >= 0)
                {
                    // A System Property?
                    if (arg.startsWith("-D"))
                    {
                        String[] assign = arg.substring(2).split("=",2);

                        if (assign.length == 2)
                        {
                            System.setProperty(assign[0],assign[1]);
                        }
                        else
                        {
                            System.err.printf("Unable to set System Property '%s', no value provided%n",assign[0]);
                        }
                    }
                    else
                        // Nah, it's a normal property
                    {
                        String[] assign = arg.split("=",2);

                        if (assign.length == 2)
                        {
                            this._config.setProperty(assign[0],assign[1]);
                        }
                        else
                        {
                            this._config.setProperty(assign[0],null);
                        }
                    }
                }
                else
                    // A normal argument
                {
                    args.add(arg);
                }
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        finally
        {
            close(buf);
            close(reader);
        }

        return args;
    }

    private void setSystemProperty(String key, String value)
    {
        _config.setProperty(key,value);
    }

    private void usage()
    {
        String usageResource = "org/eclipse/jetty/start/usage.txt";
        InputStream usageStream = getClass().getClassLoader().getResourceAsStream(usageResource);

        if (usageStream == null)
        {
            System.err.println("Usage: java -jar start.jar [options] [properties] [configs]");
            System.err.println("ERROR: detailed usage resource unavailable");
            System.exit(1);
        }

        BufferedReader buf = null;
        try
        {
            buf = new BufferedReader(new InputStreamReader(usageStream));
            String line;

            while ((line = buf.readLine()) != null)
            {
                if (line.startsWith("@OPTIONS@"))
                {
                    List<String> sortedOptions = new ArrayList<String>();
                    sortedOptions.addAll(_config.getSectionIds());
                    Collections.sort(sortedOptions);

                    System.err.println("      Available OPTIONS: ");

                    for (String option : sortedOptions)
                    {
                        System.err.println("         [" + option + "]");
                    }
                }
                else if (line.startsWith("@CONFIGS@"))
                {
                    System.err.println("    Configurations Available in ${jetty.home}/etc/: ");
                    File etc = new File(System.getProperty("jetty.home","."),"etc");
                    if (!etc.exists())
                    {
                        System.err.println("      Unable to find " + etc);
                        continue;
                    }

                    if (!etc.isDirectory())
                    {
                        System.err.println("      Unable list dir " + etc);
                        continue;
                    }

                    File configs[] = etc.listFiles(new FileFilter()
                    {
                        public boolean accept(File path)
                        {
                            if (!path.isFile())
                            {
                                return false;
                            }

                            String name = path.getName().toLowerCase();
                            return (name.startsWith("jetty") && name.endsWith(".xml"));
                        }
                    });

                    List<File> configFiles = new ArrayList<File>();
                    configFiles.addAll(Arrays.asList(configs));
                    Collections.sort(configFiles);

                    for (File configFile : configFiles)
                    {
                        System.err.println("         etc/" + configFile.getName());
                    }
                }
                else
                {
                    System.err.println(line);
                }
            }
        }
        catch (IOException e)
        {
            e.printStackTrace(System.err);
        }
        finally
        {
            if (buf != null)
            {
                try
                {
                    buf.close();
                }
                catch (IOException ignore)
                {
                    /* ignore */
                }
            }
        }
        System.exit(1);
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
            //ignored
        }

        if (Config.isDebug() || invoked_class == null)
        {
            if (invoked_class == null)
                System.err.println("ClassNotFound: " + classname);
            else
                System.err.println(classname + " " + invoked_class.getPackage().getImplementationVersion());

            if (invoked_class == null)
            {
                usage();
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

    /* ------------------------------------------------------------ */
    public static void close(Reader reader)
    {
        if (reader == null)
        {
            return;
        }
        try
        {
            reader.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    /* ------------------------------------------------------------ */
    public static void close(InputStream stream)
    {
        if (stream == null)
        {
            return;
        }
        try
        {
            stream.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    /* ------------------------------------------------------------ */
    public void start(List<String> xmls) throws FileNotFoundException
    {
        // Setup Start / Stop Monitoring
        startMonitor();

        // Add mandatory options for secure mode
        if (_secure)
        {
            _config.addActiveOption("secure");
            _config.addActiveOption("security");
        }

        // Default XMLs (if not specified)
        if (xmls.isEmpty())
        {
            // Do not rely on _jettyHome yet, as initConfig(xmls) defines _jettyHome, and
            // resolveXmlConfigs(xmls) normalizes the xmls based off it.
            if (_fromDaemon)
            {
                xmls.add("etc/jetty-logging.xml");
            }
            xmls.add("etc/jetty.xml");
        }

        // Initialize the Config (start.config)
        initConfig(xmls);

        // Normalize the XML config options passed on the command line.
        xmls = resolveXmlConfigs(xmls);

        // Get Desired Classpath based on user provided Active Options.
        Classpath classpath = _config.getActiveClasspath();

        System.setProperty("java.class.path",classpath.toString());
        ClassLoader cl = classpath.getClassLoader();
        if (Config.isDebug())
        {
            System.err.println("java.class.path=" + System.getProperty("java.class.path"));
            System.err.println("jetty.home=" + System.getProperty("jetty.home"));
            System.err.println("java.home=" + System.getProperty("java.home"));
            System.err.println("java.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
            System.err.println("java.class.path=" + classpath);
            System.err.println("classloader=" + cl);
            System.err.println("classloader.parent=" + cl.getParent());
        }

        // Show the usage information and return
        if (_showUsage)
        {
            usage();
            return;
        }

        // Show the version information and return
        if (_dumpVersions)
        {
            showClasspathWithVersions(classpath);
            showActiveSecurityPolicies();
            return;
        }

        // Show all options with version information
        if (_listOptions)
        {
            showAllOptionsWithVersions(classpath);
            return;
        }

        // Show Command Line to execute Jetty
        if (_dryRun)
        {
            showDryRun(classpath,xmls);
            return;
        }

        // Set current context class loader to what is selected.
        Thread.currentThread().setContextClassLoader(cl);

        // Initialize the Security
        initSecurity(cl);

        // Invoke the Main Class
        try
        {
            // Get main class as defined in start.config
            String classname = _config.getMainClassname();

            // Check for override of start class (via "jetty.server" property)
            String mainClass = System.getProperty("jetty.server");
            if (mainClass != null)
                classname = mainClass;

            // Check for override of start class (via "main.class" property)
            mainClass = System.getProperty("main.class");
            if (mainClass != null)
                classname = mainClass;

            Config.debug("main.class=" + classname);

            invokeMain(cl,classname,xmls);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private String resolveXmlConfig(String xmlFilename) throws FileNotFoundException
    {
        File xml = new File(xmlFilename);
        if (xml.exists() && xml.isFile())
        {
            return xml.getAbsolutePath();
        }

        xml = new File(_jettyHome,fixPath(xmlFilename));
        if (xml.exists() && xml.isFile())
        {
            return xml.getAbsolutePath();
        }

        xml = new File(_jettyHome,fixPath("etc/" + xmlFilename));
        if (xml.exists() && xml.isFile())
        {
            return xml.getAbsolutePath();
        }

        throw new FileNotFoundException("Unable to find XML Config: " + xmlFilename);
    }

    private void showDryRun(Classpath classpath, List<String> xmls)
    {
        StringBuffer cmd = new StringBuffer();

        cmd.append(findJavaBin());
        cmd.append(" -cp ").append(classpath.toString());
        cmd.append(" -Djetty.home=").append(_jettyHome);
        cmd.append(" ").append(_config.getMainClassname());
        for (String xml : xmls)
        {
            cmd.append(" ").append(xml);
        }

        System.out.println(cmd);
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

    private void showAllOptionsWithVersions(Classpath classpath)
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
        System.out.printf("${jetty.home} = %s%n",_jettyHome);
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
            {
                String elementPath = element.getAbsolutePath();
                if (elementPath.startsWith(_jettyHome))
                {
                    elementPath = "${jetty.home}" + elementPath.substring(_jettyHome.length());
                }
                System.out.printf("%2d: %20s | %s\n",i++,getVersion(element),elementPath);
            }

            System.out.println();
        }
    }

    private void showClasspathWithVersions(Classpath classpath)
    {
        // Iterate through active classpath, and fetch Implementation Version from each entry (if present)
        // to dump to end user.

        System.out.println("Active Options: " + _config.getActiveOptions());

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
            String elementPath = element.getAbsolutePath();
            if (elementPath.startsWith(_jettyHome))
            {
                elementPath = "${jetty.home}" + elementPath.substring(_jettyHome.length());
            }
            System.out.printf("%2d: %20s | %s\n",i++,getVersion(element),elementPath);
        }
    }

    private void showActiveSecurityPolicies()
    {
        // System.out.println("Active Security Policies: ");

        // TODO: put security policy dump here
    }

    private String fixPath(String path)
    {
        return path.replace('/',File.separatorChar);
    }

    private String getVersion(File element)
    {
        if (element.isDirectory())
        {
            return "(dir)";
        }

        if (element.isFile())
        {
            String name = element.getName().toLowerCase();
            if (name.endsWith(".jar"))
            {
                return JarVersion.getVersion(element);
            }

            if (name.endsWith(".zip"))
            {
                return getZipVersion(element);
            }
        }

        return "";
    }

    private String getZipVersion(File element)
    {
        // TODO - find version in zip file.  Look for META-INF/MANIFEST.MF ?
        return "";
    }

    private void initSecurity(ClassLoader cl)
    {
        // Init the Security Policies
        try
        {
            if (_secure)
            {
                Policy.setPolicy(_config.getPolicyInstance(cl));
                System.setSecurityManager(new SecurityManager());
            }
            else
            {
                Policy policy = Policy.getPolicy();
                if (policy != null)
                    policy.refresh();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
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

    private void initConfig(List<String> xmls)
    {
        InputStream cfgstream = null;
        try
        {
            _config.setArgCount(xmls.size());

            // What start.config should we use?
            String cfgName = System.getProperty("START","org/eclipse/jetty/start/start.config");
            Config.debug("config=" + cfgName);

            // Look up config as resource first.
            cfgstream = getClass().getClassLoader().getResourceAsStream(cfgName);

            // resource not found, try filesystem next
            if (cfgstream == null)
                cfgstream = new FileInputStream(cfgName);

            // parse the config
            _config.parse(cfgstream);

            _jettyHome = _config.getProperty("jetty.home");
            if (_jettyHome != null)
            {
                _jettyHome = new File(_jettyHome).getCanonicalPath();
                System.setProperty("jetty.home",_jettyHome);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            System.exit(1);
        }
        finally
        {
            close(cfgstream);
        }
    }

    private void startMonitor()
    {
        int port = Integer.parseInt(System.getProperty("STOP.PORT","-1"));
        String key = System.getProperty("STOP.KEY",null);

        Monitor.monitor(port,key);
    }

    /**
     * Stop a running jetty instance.
     */
    public void stop(int port, String key)
    {
        int _port = port;
        String _key = key;

        try
        {
            if (_port <= 0)
                System.err.println("STOP.PORT system property must be specified");
            if (_key == null)
            {
                _key = "";
                System.err.println("STOP.KEY system property must be specified");
                System.err.println("Using empty key");
            }

            Socket s = new Socket(InetAddress.getByName("127.0.0.1"),_port);
            OutputStream out = s.getOutputStream();
            out.write((_key + "\r\nstop\r\n").getBytes());
            out.flush();
            s.close();
        }
        catch (ConnectException e)
        {
            System.err.println("ERROR: Not running!");
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
