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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.start.config.ConfigSource;
import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.DirConfigSource;

/**
 * The Arguments required to start Jetty.
 */
public class StartArgs
{
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
    private List<Path> xmls = new ArrayList<>();
    /** JVM arguments, found via commmand line and in all active [exec] sections from enabled modules */
    private List<String> jvmArgs = new ArrayList<>();

    /** List of all xml references found directly on command line or start.ini */
    private List<String> xmlRefs = new ArrayList<>();

    /** List of all property references found directly on command line or start.ini */
    private List<String> propertyFileRefs = new ArrayList<>();
    
    /** List of all property files */
    private List<Path> propertyFiles = new ArrayList<>();

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

    public StartArgs()
    {
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

    private void addUniqueXmlFile(String xmlRef, Path xmlfile) throws IOException
    {
        if (!FS.canReadFile(xmlfile))
        {
            throw new IOException("Cannot read file: " + xmlRef);
        }
        xmlfile = FS.toRealPath(xmlfile);
        if (!xmls.contains(xmlfile))
        {
            xmls.add(xmlfile);
        }
    }
    
    private void addUniquePropertyFile(String propertyFileRef, Path propertyFile) throws IOException
    {
        if (!FS.canReadFile(propertyFile))
        {
            throw new IOException("Cannot read file: " + propertyFileRef);
        }
        propertyFile = FS.toRealPath(propertyFile);
        if (!propertyFiles.contains(propertyFile))
        {
            propertyFiles.add(propertyFile);
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

        for (Path xml : xmls)
        {
            System.out.printf(" %s%n",baseHome.toShortForm(xml.toAbsolutePath()));
        }
    }

    public void dumpEnvironment(BaseHome baseHome)
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
        dumpProperty("jetty.version");
        dumpProperty("jetty.home");
        dumpProperty("jetty.base");
        
        // Jetty Configuration Environment
        System.out.println();
        System.out.println("Config Search Order:");
        System.out.println("--------------------");
        for (ConfigSource config : baseHome.getConfigSources())
        {
            System.out.printf(" %s",config.getId());
            if (config instanceof DirConfigSource)
            {
                DirConfigSource dirsource = (DirConfigSource)config;
                if (dirsource.isPropertyBased())
                {
                    System.out.printf(" -> %s",dirsource.getDir());
                }
            }
            System.out.println();
        }
        
        // Jetty Se
        System.out.println();
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
            
            // perform path escaping (needed by windows)
            libref = libref.replaceAll("\\\\([^\\\\])","\\\\\\\\$1");
            
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
                Path xmlfile = baseHome.getPath(xmlRef);
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

    public List<String> getAddToStartdIni()
    {
        return addToStartdIni;
    }

    public List<String> getAddToStartIni()
    {
        return addToStartIni;
    }

    public Modules getAllModules()
    {
        return allModules;
    }

    public Classpath getClasspath()
    {
        return classpath;
    }

    public Set<String> getEnabledModules()
    {
        return this.modules;
    }

    public List<FileArg> getFiles()
    {
        return files;
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

            cmd.addRawArg("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
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

        // pass properties as args or as a file
        if (dryRun || isExec())
        {
            for (Prop p : properties)
                cmd.addRawArg(CommandLineBuilder.quote(p.key)+"="+CommandLineBuilder.quote(p.value));
        }
        else if (properties.size() > 0)
        {
            File prop_file = File.createTempFile("start",".properties");
            prop_file.deleteOnExit();
            try (FileOutputStream out = new FileOutputStream(prop_file))
            {
                properties.store(out,"start.jar properties");
            }
            cmd.addRawArg(prop_file.getAbsolutePath());
        }

        for (Path xml : xmls)
        {
            cmd.addRawArg(xml.toAbsolutePath().toString());
        }
        
        for (Path propertyFile : propertyFiles)
        {
            cmd.addRawArg(propertyFile.toAbsolutePath().toString());
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

    public Props getProperties()
    {
        return properties;
    }

    public List<String> getSources(String module)
    {
        return sources.get(module);
    }

    public List<Path> getXmlFiles()
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

    public void parse(ConfigSources sources)
    {
        ListIterator<ConfigSource> iter = sources.reverseListIterator();
        while (iter.hasPrevious())
        {
            ConfigSource source = iter.previous();
            for (RawArgs.Entry arg : source.getArgs())
            {
                parse(arg.getLine(),arg.getOrigin());
            }
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
            help = true;
            run = false;
            return;
        }

        if ("--debug".equals(arg) || arg.startsWith("--start-log-file"))
        {
            // valid, but handled in StartLog instead
            return;
        }

        if (arg.startsWith("--include-jetty-dir="))
        {
            // valid, but handled in ConfigSources instead
            return;
        }

        if ("--stop".equals(arg))
        {
            stopCommand = true;
            run = false;
            return;
        }

        if (arg.startsWith("--download="))
        {
            addFile(Props.getValue(arg));
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

        // Arbitrary Libraries
        if (arg.startsWith("--lib="))
        {
            String cp = Props.getValue(arg);

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
            addToStartdIni.addAll(Props.getValues(arg));
            run = false;
            download = true;
            return;
        }

        // jetty.base build-out : add to ${jetty.base}/start.ini
        if (arg.startsWith("--add-to-start="))
        {
            addToStartIni.addAll(Props.getValues(arg));
            run = false;
            download = true;
            return;
        }

        // Enable a module
        if (arg.startsWith("--module="))
        {
            for (String moduleName : Props.getValues(arg))
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
            this.moduleGraphFilename = Props.getValue(arg);
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

            if (propertySource.containsKey(key))
            {
                StartLog.warn("Property %s in %s already set in %s",key,source,propertySource.get(key));
            }
            propertySource.put(key,source);

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
        
        if (FS.isPropertyFile(arg))
        {
            // only add non-duplicates
            if (!propertyFileRefs.contains(arg))
            {
            	propertyFileRefs.add(arg);
            }
        	return;
        }

        // Anything else is unrecognized
        throw new UsageException(ERR_BAD_ARG,"Unrecognized argument: \"%s\" in %s",arg,source);
    }

    public void resolveExtraXmls(BaseHome baseHome) throws IOException
    {
        // Find and Expand XML files
        for (String xmlRef : xmlRefs)
        {
            // Straight Reference
            Path xmlfile = baseHome.getPath(xmlRef);
            if (!FS.exists(xmlfile))
            {
                xmlfile = baseHome.getPath("etc/" + xmlRef);
            }
            addUniqueXmlFile(xmlRef,xmlfile);
        }
    }
    
    public void resolvePropertyFiles(BaseHome baseHome) throws IOException
    {
        // Find and Expand property files
        for (String propertyFileRef : propertyFileRefs)
        {
            // Straight Reference
            Path propertyFile = baseHome.getPath(propertyFileRef);
            if (!FS.exists(propertyFile))
            {
            	propertyFile = baseHome.getPath("etc/" + propertyFileRef);
            }
            addUniquePropertyFile(propertyFileRef,propertyFile);
        }
    }

    public void setAllModules(Modules allModules)
    {
        this.allModules = allModules;
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

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("StartArgs [enabledModules=");
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
