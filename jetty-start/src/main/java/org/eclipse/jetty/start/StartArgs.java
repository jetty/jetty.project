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

import static org.eclipse.jetty.start.UsageException.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.jetty.start.Props.Prop;

/**
 * The Arguments required to start Jetty.
 */
public class StartArgs
{
    public static final String CMD_LINE_SOURCE = "<command-line>";
    public static final String VERSION;

    static
    {
        String ver = System.getProperty("jetty.version",null);

        if (ver == null)
        {
            Package pkg = StartArgs.class.getPackage();
            if ((pkg != null) && "Eclipse.org - Jetty".equals(pkg.getImplementationVendor()) && (pkg.getImplementationVersion() != null))
            {
                ver = pkg.getImplementationVersion();
            }
        }

        if (ver == null)
        {
            ver = "TEST";
        }

        VERSION = ver;
        System.setProperty("jetty.version",VERSION);
    }

    private static final String SERVER_MAIN = "org.eclipse.jetty.xml.XmlConfiguration";
    
    private List<String> commandLine = new ArrayList<>();
    
    /** List of enabled modules */
    private Set<String> modules = new HashSet<>();
    /** Map of enabled modules to the source of where that activation occurred */
    private Map<String, List<String>> sources = new HashMap<>();
    /** Map of properties to where that property was declared */
    private Map<String, String> propertySource = new HashMap<>();
    /** List of all active [files] sections from enabled modules */
    private List<FileArg> files = new ArrayList<>();
    /** List of all active [lib] sectinos from enabled modules */
    private Classpath classpath;
    /** List of all active [xml] sections from enabled modules */
    private List<File> xmls = new ArrayList<>();
    /** JVM arguments, found via commmand line and in all active [exec] sections from enabled modules */
    private List<String> jvmArgs = new ArrayList<>();

    /** List of all xml references found directly on command line or start.ini */
    private List<String> xmlRefs = new ArrayList<>();
    
    /** List of extra Start Directories referenced */
    private LinkedList<String> extraStartRefs = new LinkedList<>();

    private Props properties = new Props();
    private Set<String> systemPropertyKeys = new HashSet<>();
    private List<String> rawLibs = new ArrayList<>();

    // jetty.base - build out commands
    /** --add-to-startd=[module,[module]] */
    private List<String> addToStartdIni = new ArrayList<>();
    /** --add-to-start=[module,[module]] */
    private List<String> addToStartIni = new ArrayList<>();

    // module inspection commands
    /** --write-module-graph=[filename] */
    private String moduleGraphFilename;

    /** Collection of all modules */
    private Modules allModules;
    /** Should the server be run? */
    private boolean run = true;
    private boolean download = false;
    private boolean help = false;
    private boolean stopCommand = false;
    private boolean listModules = false;
    private boolean listClasspath = false;
    private boolean listConfig = false;
    private boolean version = false;
    private boolean dryRun = false;

    private boolean exec = false;

    public StartArgs(String[] commandLineArgs)
    {
        commandLine.addAll(Arrays.asList(commandLineArgs));
        classpath = new Classpath();
    }

    private void addFile(String uriLocation)
    {
        FileArg arg = new FileArg(uriLocation);
        if (!files.contains(arg))
        {
            files.add(arg);
        }
    }

    public void addSystemProperty(String key, String value)
    {
        this.systemPropertyKeys.add(key);
        System.setProperty(key,value);
    }

    private void addUniqueXmlFile(String xmlRef, File xmlfile) throws IOException
    {
        if (!FS.canReadFile(xmlfile))
        {
            throw new IOException("Cannot read file: " + xmlRef);
        }
        xmlfile = xmlfile.getCanonicalFile();
        if (!xmls.contains(xmlfile))
        {
            xmls.add(xmlfile);
        }
    }

    public void dumpActiveXmls(BaseHome baseHome)
    {
        System.out.println();
        System.out.println("Jetty Active XMLs:");
        System.out.println("------------------");
        if (xmls.isEmpty())
        {
            System.out.println(" (no xml files specified)");
            return;
        }

        for (File xml : xmls)
        {
            System.out.printf(" %s%n",baseHome.toShortForm(xml.getAbsolutePath()));
        }
    }

    public void dumpEnvironment()
    {
        // Java Details
        System.out.println();
        System.out.println("Java Environment:");
        System.out.println("-----------------");
        dumpSystemProperty("java.home");
        dumpSystemProperty("java.vm.vendor");
        dumpSystemProperty("java.vm.version");
        dumpSystemProperty("java.vm.name");
        dumpSystemProperty("java.vm.info");
        dumpSystemProperty("java.runtime.name");
        dumpSystemProperty("java.runtime.version");
        dumpSystemProperty("java.io.tmpdir");
        dumpSystemProperty("user.dir");
        dumpSystemProperty("user.language");
        dumpSystemProperty("user.country");

        // Jetty Environment
        System.out.println();
        System.out.println("Jetty Environment:");
        System.out.println("-----------------");

        dumpProperty("jetty.home");
        dumpProperty("jetty.base");
        dumpProperty("jetty.version");
    }

    public void dumpJvmArgs()
    {
        System.out.println();
        System.out.println("JVM Arguments:");
        System.out.println("--------------");
        if (jvmArgs.isEmpty())
        {
            System.out.println(" (no jvm args specified)");
            return;
        }

        for (String jvmArgKey : jvmArgs)
        {
            String value = System.getProperty(jvmArgKey);
            if (value != null)
            {
                System.out.printf(" %s = %s%n",jvmArgKey,value);
            }
            else
            {
                System.out.printf(" %s%n",jvmArgKey);
            }
        }
    }

    public void dumpProperties()
    {
        System.out.println();
        System.out.println("Properties:");
        System.out.println("-----------");

        List<String> sortedKeys = new ArrayList<>();
        for (Prop prop : properties)
        {
            if (prop.origin.equals(Props.ORIGIN_SYSPROP))
            {
                continue; // skip
            }
            sortedKeys.add(prop.key);
        }

        if (sortedKeys.isEmpty())
        {
            System.out.println(" (no properties specified)");
            return;
        }

        Collections.sort(sortedKeys);

        for (String key : sortedKeys)
        {
            dumpProperty(key);
        }
    }

    public void dumpSystemProperties()
    {
        System.out.println();
        System.out.println("System Properties:");
        System.out.println("------------------");

        if (systemPropertyKeys.isEmpty())
        {
            System.out.println(" (no system properties specified)");
            return;
        }

        List<String> sortedKeys = new ArrayList<>();
        sortedKeys.addAll(systemPropertyKeys);
        Collections.sort(sortedKeys);

        for (String key : sortedKeys)
        {
            String value = System.getProperty(key);
            System.out.printf(" %s = %s%n",key,properties.expand(value));
        }
    }

    private void dumpSystemProperty(String key)
    {
        System.out.printf(" %s = %s%n",key,System.getProperty(key));
    }

    private void dumpProperty(String key)
    {
        Prop prop = properties.getProp(key);
        if (prop == null)
        {
            System.out.printf(" %s (not defined)%n",key);
        }
        else
        {
            System.out.printf(" %s = %s%n",key,properties.expand(prop.value));
            if (StartLog.isDebugEnabled())
            {
                System.out.printf("   origin: %s%n",prop.origin);
                while (prop.overrides != null)
                {
                    prop = prop.overrides;
                    System.out.printf("   (overrides)%n");
                    System.out.printf("     %s = %s%n",key,properties.expand(prop.value));
                    System.out.printf("     origin: %s%n",prop.origin);
                }
            }
        }
    }

    /**
     * Ensure that the System Properties are set (if defined as a System property, or start.config property, or start.ini property)
     * 
     * @param key
     *            the key to be sure of
     */
    private void ensureSystemPropertySet(String key)
    {
        if (systemPropertyKeys.contains(key))
        {
            return; // done
        }

        if (properties.containsKey(key))
        {
            String val = properties.expand(properties.getString(key));
            if (val == null)
            {
                return; // no value to set
            }
            // setup system property
            systemPropertyKeys.add(key);
            System.setProperty(key,val);
        }
    }

    /**
     * Expand any command line added <code>--lib</code> lib references.
     * 
     * @param baseHome
     * @throws IOException
     */
    public void expandLibs(BaseHome baseHome) throws IOException
    {
        for (String rawlibref : rawLibs)
        {
            StartLog.debug("rawlibref = " + rawlibref);
            String libref = properties.expand(rawlibref);
            StartLog.debug("expanded = " + libref);

            for (Path libpath : baseHome.getPaths(libref))
            {
                classpath.addComponent(libpath.toFile());
            }
        }
    }

    /**
     * Build up the Classpath and XML file references based on enabled Module list.
     * 
     * @param baseHome
     * @param activeModules
     * @throws IOException
     */
    public void expandModules(BaseHome baseHome, List<Module> activeModules) throws IOException
    {
        for (Module module : activeModules)
        {
            // Find and Expand Libraries
            for (String rawlibref : module.getLibs())
            {
                StartLog.debug("rawlibref = " + rawlibref);
                String libref = properties.expand(rawlibref);
                StartLog.debug("expanded = " + libref);

                for (Path libpath : baseHome.getPaths(libref))
                {
                    classpath.addComponent(libpath.toFile());
                }
            }

            for (String jvmArg : module.getJvmArgs())
            {
                exec = true;
                jvmArgs.add(jvmArg);
            }

            // Find and Expand XML files
            for (String xmlRef : module.getXmls())
            {
                // Straight Reference
                File xmlfile = baseHome.getFile(xmlRef);
                addUniqueXmlFile(xmlRef,xmlfile);
            }

            // Register Download operations
            for (String file : module.getFiles())
            {
                StartLog.debug("Adding module specified file: %s",file);
                addFile(file);
            }
        }
    }

    public Modules getAllModules()
    {
        return allModules;
    }

    public Classpath getClasspath()
    {
        return classpath;
    }

    public List<String> getCommandLine()
    {
        return this.commandLine;
    }
    
    public LinkedList<String> getExtraStartRefs()
    {
        return extraStartRefs;
    }

    public List<FileArg> getFiles()
    {
        return files;
    }

    public Set<String> getEnabledModules()
    {
        return this.modules;
    }

    public List<String> getJvmArgs()
    {
        return jvmArgs;
    }

    public CommandLineBuilder getMainArgs(BaseHome baseHome, boolean addJavaInit) throws IOException
    {
        CommandLineBuilder cmd = new CommandLineBuilder();

        if (addJavaInit)
        {
            cmd.addRawArg(CommandLineBuilder.findJavaBin());

            for (String x : jvmArgs)
            {
                cmd.addRawArg(x);
            }

            cmd.addRawArg("-Djetty.home=" + baseHome.getHome());
            cmd.addRawArg("-Djetty.base=" + baseHome.getBase());

            // System Properties
            for (String propKey : systemPropertyKeys)
            {
                String value = System.getProperty(propKey);
                cmd.addEqualsArg("-D" + propKey,value);
            }

            cmd.addRawArg("-cp");
            cmd.addRawArg(classpath.toString());
            cmd.addRawArg(getMainClassname());
        }

        // Special Stop/Shutdown properties
        ensureSystemPropertySet("STOP.PORT");
        ensureSystemPropertySet("STOP.KEY");
        ensureSystemPropertySet("STOP.WAIT");

        // Check if we need to pass properties as a file
        if (properties.size() > 0)
        {
            File prop_file = File.createTempFile("start",".properties");
            if (!dryRun)
            {
                prop_file.deleteOnExit();
            }
            try (FileOutputStream out = new FileOutputStream(prop_file))
            {
                properties.store(out,"start.jar properties");
            }
            cmd.addRawArg(prop_file.getAbsolutePath());
        }

        for (File xml : xmls)
        {
            cmd.addRawArg(xml.getAbsolutePath());
        }

        return cmd;
    }

    public String getMainClassname()
    {
        String mainclass = System.getProperty("jetty.server",SERVER_MAIN);
        return System.getProperty("main.class",mainclass);
    }

    public String getModuleGraphFilename()
    {
        return moduleGraphFilename;
    }

    public List<String> getAddToStartdIni()
    {
        return addToStartdIni;
    }

    public List<String> getAddToStartIni()
    {
        return addToStartIni;
    }

    public Props getProperties()
    {
        return properties;
    }

    public List<String> getSources(String module)
    {
        return sources.get(module);
    }

    private String getValue(String arg)
    {
        int idx = arg.indexOf('=');
        if (idx == (-1))
        {
            throw new UsageException(ERR_BAD_ARG,"Argument is missing a required value: %s",arg);
        }
        String value = arg.substring(idx + 1).trim();
        if (value.length() <= 0)
        {
            throw new UsageException(ERR_BAD_ARG,"Argument is missing a required value: %s",arg);
        }
        return value;
    }

    private List<String> getValues(String arg)
    {
        String v = getValue(arg);
        ArrayList<String> l = new ArrayList<>();
        for (String s : v.split(","))
        {
            if (s != null)
            {
                s = s.trim();
                if (s.length() > 0)
                {
                    l.add(s);
                }
            }
        }
        return l;
    }

    public List<File> getXmlFiles()
    {
        return xmls;
    }

    public boolean hasJvmArgs()
    {
        return jvmArgs.size() > 0;
    }

    public boolean hasSystemProperties()
    {
        for (String key : systemPropertyKeys)
        {
            // ignored keys
            if ("jetty.home".equals(key) || "jetty.base".equals(key))
            {
                // skip
                continue;
            }
            return true;
        }
        return false;
    }

    public boolean isDownload()
    {
        return download;
    }

    public boolean isDryRun()
    {
        return dryRun;
    }

    public boolean isExec()
    {
        return exec;
    }

    public boolean isHelp()
    {
        return help;
    }

    public boolean isListClasspath()
    {
        return listClasspath;
    }

    public boolean isListConfig()
    {
        return listConfig;
    }

    public boolean isListModules()
    {
        return listModules;
    }

    private void setProperty(String key, String value, String source)
    {
        // Special / Prevent override from start.ini's
        if (key.equals("jetty.home"))
        {
            properties.setProperty("jetty.home",System.getProperty("jetty.home"),source);
            return;
        }

        // Special / Prevent override from start.ini's
        if (key.equals("jetty.base"))
        {
            properties.setProperty("jetty.base",System.getProperty("jetty.base"),source);
            return;
        }

        // Normal
        properties.setProperty(key,value,source);
    }

    public void setRun(boolean run)
    {
        this.run = run;
    }

    public boolean isRun()
    {
        return run;
    }

    public boolean isStopCommand()
    {
        return stopCommand;
    }

    public boolean isVersion()
    {
        return version;
    }

    public void parse(BaseHome baseHome, StartIni ini)
    {
        String source;
        try
        {
            source = baseHome.toShortForm(ini.getFile());
        }
        catch (Exception e)
        {
            throw new UsageException(ERR_BAD_ARG,"Bad file: %s",ini);
        }
        
        for (String line : ini)
        {
            parse(line,source);
        }
    }

    public void parse(final String rawarg, String source)
    {
        if (rawarg == null)
        {
            return;
        }

        final String arg = rawarg.trim();

        if (arg.length() <= 0)
        {
            return;
        }

        if (arg.startsWith("#"))
        {
            return;
        }

        if ("--help".equals(arg) || "-?".equals(arg))
        {
            if (!CMD_LINE_SOURCE.equals(source))
            {
                throw new UsageException(ERR_BAD_ARG,"%s not allowed in %s",arg,source);
            }

            help = true;
            run = false;
            return;
        }

        if ("--debug".equals(arg))
        {
            // valid, but handled in StartLog instead
            return;
        }

        if ("--stop".equals(arg))
        {
            if (!CMD_LINE_SOURCE.equals(source))
            {
                throw new UsageException(ERR_BAD_ARG,"%s not allowed in %s",arg,source);
            }
            stopCommand = true;
            run = false;
            return;
        }

        if (arg.startsWith("--download="))
        {
            addFile(getValue(arg));
            run = false;
            download = true;
            return;
        }

        if (arg.equals("--create-files"))
        {
            run = false;
            download = true;
            return;
        }

        if ("--list-classpath".equals(arg) || "--version".equals(arg) || "-v".equals(arg) || "--info".equals(arg))
        {
            listClasspath = true;
            run = false;
            return;
        }

        if ("--list-config".equals(arg))
        {
            listConfig = true;
            run = false;
            return;
        }

        if ("--dry-run".equals(arg) || "--exec-print".equals(arg))
        {
            if (!CMD_LINE_SOURCE.equals(source))
            {
                throw new UsageException(ERR_BAD_ARG,"%s not allowed in %s",arg,source);
            }
            dryRun = true;
            run = false;
            return;
        }

        // Enable forked execution of Jetty server
        if ("--exec".equals(arg))
        {
            exec = true;
            return;
        }

        // Add extra start dir
        if (arg.startsWith("--extra-start-dir="))
        {
            String dirRef = getValue(arg);
            extraStartRefs.add(dirRef);
            return;
        }

        // Arbitrary Libraries
        if (arg.startsWith("--lib="))
        {
            String cp = getValue(arg);

            if (cp != null)
            {
                StringTokenizer t = new StringTokenizer(cp,File.pathSeparator);
                while (t.hasMoreTokens())
                {
                    rawLibs.add(t.nextToken());
                }
            }
            return;
        }

        // Module Management
        if ("--list-modules".equals(arg))
        {
            listModules = true;
            run = false;
            return;
        }

        // jetty.base build-out : add to ${jetty.base}/start.d/
        if (arg.startsWith("--add-to-startd="))
        {
            if (!CMD_LINE_SOURCE.equals(source))
            {
                throw new UsageException(ERR_BAD_ARG,"%s not allowed in %s",arg,source);
            }
            addToStartdIni.addAll(getValues(arg));
            run = false;
            download = true;
            return;
        }

        // jetty.base build-out : add to ${jetty.base}/start.ini
        if (arg.startsWith("--add-to-start="))
        {
            if (!CMD_LINE_SOURCE.equals(source))
            {
                throw new UsageException(ERR_BAD_ARG,"%s not allowed in %s",arg,source);
            }
            addToStartIni.addAll(getValues(arg));
            run = false;
            download = true;
            return;
        }

        // Enable a module
        if (arg.startsWith("--module="))
        {
            for (String moduleName : getValues(arg))
            {
                modules.add(moduleName);
                List<String> list = sources.get(moduleName);
                if (list == null)
                {
                    list = new ArrayList<String>();
                    sources.put(moduleName,list);
                }
                list.add(source);
            }
            return;
        }

        // Create graphviz output of module graph
        if (arg.startsWith("--write-module-graph="))
        {
            this.moduleGraphFilename = getValue(arg);
            run = false;
            return;
        }

        // Start property (syntax similar to System property)
        if (arg.startsWith("-D"))
        {
            String[] assign = arg.substring(2).split("=",2);
            systemPropertyKeys.add(assign[0]);
            switch (assign.length)
            {
                case 2:
                    System.setProperty(assign[0],assign[1]);
                    setProperty(assign[0],assign[1],source);
                    break;
                case 1:
                    System.setProperty(assign[0],"");
                    setProperty(assign[0],"",source);
                    break;
                default:
                    break;
            }
            return;
        }

        // Anything else with a "-" is considered a JVM argument
        if (arg.startsWith("-"))
        {
            // Only add non-duplicates
            if (!jvmArgs.contains(arg))
            {
                jvmArgs.add(arg);
            }
            return;
        }

        // Is this a raw property declaration?
        int idx = arg.indexOf('=');
        if (idx >= 0)
        {
            String key = arg.substring(0,idx);
            String value = arg.substring(idx + 1);

            if (source != CMD_LINE_SOURCE)
            {
                if (propertySource.containsKey(key))
                {
                    throw new UsageException(ERR_BAD_ARG,"Property %s in %s already set in %s",key,source,propertySource.get(key));
                }
                propertySource.put(key,source);
            }

            if ("OPTION".equals(key) || "OPTIONS".equals(key))
            {
                StringBuilder warn = new StringBuilder();
                warn.append("The behavior of the argument ");
                warn.append(arg).append(" (seen in ").append(source);
                warn.append(") has changed, and is now considered a normal property.  ");
                warn.append(key).append(" no longer controls what libraries are on your classpath,");
                warn.append(" use --module instead. See --help for details.");
                StartLog.warn(warn.toString());
            }

            setProperty(key,value,source);
            return;
        }

        // Is this an xml file?
        if (FS.isXml(arg))
        {
            // only add non-duplicates
            if (!xmlRefs.contains(arg))
            {
                xmlRefs.add(arg);
            }
            return;
        }

        // Anything else is unrecognized
        throw new UsageException(ERR_BAD_ARG,"Unrecognized argument: \"%s\" in %s",arg,source);
    }

    public StartArgs parseCommandLine()
    {
        for (String line : commandLine)
        {
            parse(line,StartArgs.CMD_LINE_SOURCE);
        }

        return this;
    }

    public void resolveExtraXmls(BaseHome baseHome) throws IOException
    {
        // Find and Expand XML files
        for (String xmlRef : xmlRefs)
        {
            // Straight Reference
            File xmlfile = baseHome.getFile(xmlRef);
            if (!xmlfile.exists())
            {
                xmlfile = baseHome.getFile("etc/" + xmlRef);
            }
            addUniqueXmlFile(xmlRef,xmlfile);
        }
    }

    public void setAllModules(Modules allModules)
    {
        this.allModules = allModules;
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("StartArgs [commandLine=");
        builder.append(commandLine);
        builder.append(", enabledModules=");
        builder.append(modules);
        builder.append(", xmlRefs=");
        builder.append(xmlRefs);
        builder.append(", properties=");
        builder.append(properties);
        builder.append(", jvmArgs=");
        builder.append(jvmArgs);
        builder.append("]");
        return builder.toString();
    }

}
