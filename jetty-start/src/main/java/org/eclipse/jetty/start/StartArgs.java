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
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

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
    }

    // TODO: might make sense to declare this in modules/base.mod
    private static final String SERVER_MAIN = "org.eclipse.jetty.xml.XmlConfiguration.class";

    private List<String> commandLine = new ArrayList<>();
    private List<String> enabledModules = new ArrayList<>();
    private List<String> downloads = new ArrayList<>();
    private Classpath classpath;
    private List<String> xmlRefs = new ArrayList<>();
    private List<File> xmls = new ArrayList<>();
    private Properties properties = new Properties();
    private Set<String> systemPropertyKeys = new HashSet<>();
    private List<String> jvmArgs = new ArrayList<>();

    // Should the server be run?
    private boolean run = true;
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
            String val = properties.getProperty(key,null);
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
                String libref = rawlibref.replace("${jetty.version}",VERSION);
                libref = FS.separators(libref);

                if (libref.contains("*"))
                {
                    // Glob Reference
                    int idx = libref.lastIndexOf(File.separatorChar);

                    String relativePath = "/";
                    String filenameRef = libref;
                    if (idx >= 0)
                    {
                        relativePath = libref.substring(0,idx);
                        filenameRef = libref.substring(idx + 1);
                    }

                    StringBuilder regex = new StringBuilder();
                    regex.append('^');
                    for (char c : filenameRef.toCharArray())
                    {
                        switch (c)
                        {
                            case '*':
                                regex.append(".*");
                                break;
                            case '.':
                                regex.append("\\.");
                                break;
                            default:
                                regex.append(c);
                        }
                    }
                    regex.append('$');

                    FileFilter filter = new FS.FilenameRegexFilter(regex.toString());

                    for (File libfile : baseHome.listFiles(relativePath,filter))
                    {
                        classpath.addComponent(libfile);
                    }
                }
                else
                {
                    // Straight Reference
                    File libfile = baseHome.getFile(libref);
                    classpath.addComponent(libfile);
                }
            }

            // Find and Expand XML files
            for (String xmlRef : module.getXmls())
            {
                // Straight Reference
                File xmlfile = baseHome.getFile(xmlRef);
                addUniqueXmlFile(xmlRef,xmlfile);
            }
        }
    }

    public Classpath getClasspath()
    {
        return classpath;
    }

    public List<String> getCommandLine()
    {
        return this.commandLine;
    }

    public List<String> getDownloads()
    {
        return downloads;
    }

    public List<String> getEnabledModules()
    {
        return this.enabledModules;
    }

    public CommandLineBuilder getMainArgs(BaseHome baseHome) throws IOException
    {
        CommandLineBuilder cmd = new CommandLineBuilder();

        for (String x : jvmArgs)
        {
            cmd.addArg(x);
        }

        cmd.addRawArg("-Djetty.home=" + baseHome.getHome());
        cmd.addRawArg("-Djetty.base=" + baseHome.getBase());

        // Special Stop/Shutdown properties
        ensureSystemPropertySet("STOP.PORT");
        ensureSystemPropertySet("STOP.KEY");

        // System Properties
        for (String propKey : systemPropertyKeys)
        {
            String value = System.getProperty(propKey);
            cmd.addEqualsArg("-D" + propKey,value);
        }

        cmd.addArg("-cp");
        cmd.addRawArg(classpath.toString());
        cmd.addRawArg(getMainClassname());

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
            cmd.addArg(prop_file.getAbsolutePath());
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

    public Properties getProperties()
    {
        return properties;
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
        return systemPropertyKeys.size() > 0;
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

    public void parse(String arg)
    {
        if ("--help".equals(arg) || "-?".equals(arg))
        {
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
            stopCommand = true;
            run = false;
            //
            // int port = Integer.parseInt(_config.getProperty("STOP.PORT","-1"));
            // String key = _config.getProperty("STOP.KEY",null);
            // int timeout = Integer.parseInt(_config.getProperty("STOP.WAIT","0"));
            // stop(port,key,timeout);
            //
            return;
        }

        if (arg.startsWith("--download="))
        {
            downloads.add(getValue(arg));
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

        if ("--exec".equals(arg))
        {
            exec = true;
            return;
        }

        if (arg.startsWith("--enable="))
        {
            String moduleName = getValue(arg);
            // TODO:
            run = false;
            return;
        }

        if (arg.startsWith("--disable="))
        {
            String moduleName = getValue(arg);
            // TODO:
            run = false;
            return;
        }

        if (arg.startsWith("MODULE="))
        {
            enabledModules.add(getValue(arg));
            return;
        }

        if (arg.startsWith("MODULES="))
        {
            for (String moduleName : getValue(arg).split(","))
            {
                if (moduleName == null)
                {
                    continue; // skip
                }
                enabledModules.add(moduleName.trim());
            }
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
                    break;
                case 1:
                    System.setProperty(assign[0],"");
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
            properties.setProperty(key,value);
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
        throw new UsageException(ERR_BAD_ARG,"Unrecognized argument: %s",arg);
    }

    public void parse(TextFile file)
    {
        for (String line : file)
        {
            parse(line);
        }
    }

    public void parseCommandLine()
    {
        for (String line : commandLine)
        {
            parse(line);
        }
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

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("StartArgs [commandLine=");
        builder.append(commandLine);
        builder.append(", enabledModules=");
        builder.append(enabledModules);
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
