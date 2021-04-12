//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.start.config.ConfigSource;
import org.eclipse.jetty.start.config.ConfigSources;
import org.eclipse.jetty.start.config.DirConfigSource;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.ManifestUtils;

/**
 * The Arguments required to start Jetty.
 */
public class StartArgs
{
    public static final String VERSION;
    public static final Set<String> ALL_PARTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "java",
        "opts",
        "path",
        "main",
        "args")));
    public static final Set<String> ARG_PARTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "args")));

    private static final String JETTY_VERSION_KEY = "jetty.version";
    private static final String JETTY_TAG_NAME_KEY = "jetty.tag.version";
    private static final String JETTY_BUILDNUM_KEY = "jetty.build";

    static
    {
        // Use command line versions
        String ver = System.getProperty(JETTY_VERSION_KEY);
        String tag = System.getProperty(JETTY_TAG_NAME_KEY);

        // Use META-INF/MANIFEST.MF versions
        if (ver == null)
        {
            ver = ManifestUtils.getManifest(StartArgs.class)
                .map(Manifest::getMainAttributes)
                .filter(attributes -> "Eclipse Jetty Project".equals(attributes.getValue(Attributes.Name.IMPLEMENTATION_VENDOR)))
                .map(attributes -> attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION))
                .orElse(null);
        }

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        // use old jetty-version.properties (as seen within various linux distro repackaging of Jetty)
        Props jettyVerProps = Props.load(classLoader, "jetty-version.properties");
        // use build-time properties (included in start.jar) to pull version and buildNumber
        Props buildProps = Props.load(classLoader, "org/eclipse/jetty/start/build.properties");

        String sha = buildProps.getString("buildNumber", System.getProperty(JETTY_BUILDNUM_KEY));
        if (Utils.isNotBlank(sha))
        {
            System.setProperty(JETTY_BUILDNUM_KEY, sha);
        }

        if (Utils.isBlank(ver))
        {
            ver = jettyVerProps.getString("version", buildProps.getString("version", "0.0"));
        }

        if (Utils.isBlank(tag))
        {
            tag = jettyVerProps.getString("tag", buildProps.getString("tag", "jetty-" + ver));
        }

        VERSION = ver;
        System.setProperty(JETTY_VERSION_KEY, VERSION);
        System.setProperty(JETTY_TAG_NAME_KEY, tag);
    }

    private static final String MAIN_CLASS = "org.eclipse.jetty.xml.XmlConfiguration";
    private static final String MODULE_MAIN_CLASS = "org.eclipse.jetty.xml/org.eclipse.jetty.xml.XmlConfiguration";

    private final BaseHome baseHome;

    /**
     * List of enabled modules
     */
    private List<String> modules = new ArrayList<>();

    /**
     * List of modules to skip [files] section validation
     */
    private Set<String> skipFileValidationModules = new HashSet<>();

    /**
     * Map of enabled modules to the source of where that activation occurred
     */
    Map<String, List<String>> sources = new HashMap<>();

    /**
     * List of all active [files] sections from enabled modules
     */
    private List<FileArg> files = new ArrayList<>();

    /**
     * List of all active [lib] sections from enabled modules
     */
    private Classpath classpath;

    /**
     * List of all active [xml] sections from enabled modules
     */
    private List<Path> xmls = new ArrayList<>();

    /**
     * List of all active [jpms] sections for enabled modules
     */
    private Set<String> jmodAdds = new LinkedHashSet<>();
    private Map<String, Set<String>> jmodPatch = new LinkedHashMap<>();
    private Map<String, Set<String>> jmodOpens = new LinkedHashMap<>();
    private Map<String, Set<String>> jmodExports = new LinkedHashMap<>();
    private Map<String, Set<String>> jmodReads = new LinkedHashMap<>();

    /**
     * JVM arguments, found via command line and in all active [exec] sections from enabled modules
     */
    private List<String> jvmArgs = new ArrayList<>();

    /**
     * List of all xml references found directly on command line or start.ini
     */
    private List<String> xmlRefs = new ArrayList<>();

    /**
     * List of all property references found directly on command line or start.ini
     */
    private List<String> propertyFileRefs = new ArrayList<>();

    /**
     * List of all property files
     */
    private List<Path> propertyFiles = new ArrayList<>();

    private Props properties = new Props();
    private Map<String, String> systemPropertySource = new HashMap<>();
    private List<String> rawLibs = new ArrayList<>();

    // jetty.base - build out commands
    /**
     * --add-to-start[d]=[module,[module]]
     */
    private List<String> startModules = new ArrayList<>();

    // module inspection commands
    /**
     * --write-module-graph=[filename]
     */
    private String moduleGraphFilename;

    /**
     * Collection of all modules
     */
    private Modules allModules;

    /**
     * Should the server be run?
     */
    private boolean run = true;

    /**
     * Files related args
     */
    private boolean createFiles = false;
    private boolean licenseCheckRequired = false;
    private boolean testingMode = false;

    private boolean help = false;
    private boolean stopCommand = false;
    private List<String> listModules = null;
    private boolean listClasspath = false;
    private boolean listConfig = false;
    private boolean version = false;
    private boolean dryRun = false;
    private final Set<String> dryRunParts = new HashSet<>();
    private boolean jpms = false;
    private boolean createStartd = false;
    private boolean updateIni = false;
    private String mavenBaseUri;

    private boolean exec = false;
    private String execProperties;
    private boolean approveAllLicenses = false;

    public StartArgs(BaseHome baseHome)
    {
        this.baseHome = baseHome;
        classpath = new Classpath();
    }

    private void addFile(Module module, String uriLocation)
    {
        if (module != null && module.isSkipFilesValidation())
        {
            StartLog.debug("Not validating module %s [files] for %s", module, uriLocation);
            return;
        }

        FileArg arg = new FileArg(module, properties.expand(uriLocation));
        if (!files.contains(arg))
        {
            files.add(arg);
        }
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

    public void dumpActiveXmls()
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
            System.out.printf(" %s%n", baseHome.toShortForm(xml.toAbsolutePath()));
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
        dumpProperty(JETTY_VERSION_KEY);
        dumpProperty(JETTY_TAG_NAME_KEY);
        dumpProperty(JETTY_BUILDNUM_KEY);
        dumpProperty("jetty.home");
        dumpProperty("jetty.base");

        // Jetty Configuration Environment
        System.out.println();
        System.out.println("Config Search Order:");
        System.out.println("--------------------");
        for (ConfigSource config : baseHome.getConfigSources())
        {
            System.out.printf(" %s", config.getId());
            if (config instanceof DirConfigSource)
            {
                DirConfigSource dirsource = (DirConfigSource)config;
                if (dirsource.isPropertyBased())
                {
                    System.out.printf(" -> %s", dirsource.getDir());
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
                System.out.printf(" %s = %s%n", jvmArgKey, value);
            }
            else
            {
                System.out.printf(" %s%n", jvmArgKey);
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
            if (prop.source.equals(Props.ORIGIN_SYSPROP))
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

        for (Path path : propertyFiles)
        {
            String p = baseHome.toShortForm(path);
            if (Files.isReadable(path))
            {
                Properties props = new Properties();
                try
                {
                    props.load(new FileInputStream(path.toFile()));
                    for (Object key : props.keySet())
                    {
                        System.out.printf(" %s:%s = %s%n", p, key, props.getProperty(String.valueOf(key)));
                    }
                }
                catch (Throwable ex)
                {
                    System.out.printf(" %s NOT READABLE!%n", p);
                }
            }
            else
            {

                System.out.printf(" %s NOT READABLE!%n", p);
            }
        }
    }

    private void dumpProperty(String key)
    {
        Prop prop = properties.getProp(key);
        if (prop == null)
        {
            System.out.printf(" %s (not defined)%n", key);
        }
        else
        {
            System.out.printf(" %s = %s%n", key, prop.value);
            if (StartLog.isDebugEnabled())
                System.out.printf("   origin: %s%n", prop.source);
        }
    }

    public void dumpSystemProperties()
    {
        System.out.println();
        System.out.println("System Properties:");
        System.out.println("------------------");

        if (systemPropertySource.keySet().isEmpty())
        {
            System.out.println(" (no system properties specified)");
            return;
        }

        List<String> sortedKeys = new ArrayList<>(systemPropertySource.keySet());
        Collections.sort(sortedKeys);

        for (String key : sortedKeys)
        {
            dumpSystemProperty(key);
        }
    }

    private void dumpSystemProperty(String key)
    {
        String value = System.getProperty(key);
        String source = systemPropertySource.get(key);
        System.out.printf(" %s = %s (%s)%n", key, value, source);
    }

    /**
     * Ensure that the System Properties are set (if defined as a System property, or start.config property, or start.ini property)
     *
     * @param key the key to be sure of
     */
    private void ensureSystemPropertySet(String key)
    {
        if (systemPropertySource.containsKey(key))
        {
            return; // done
        }

        if (properties.containsKey(key))
        {
            Prop prop = properties.getProp(key);
            if (prop == null)
                return; // no value set;

            String val = properties.expand(prop.value);
            // setup system property
            systemPropertySource.put(key, "property:" + prop.source);
            System.setProperty(key, val);
        }
    }

    /**
     * Expand any command line added {@code --lib} lib references.
     */
    public void expandSystemProperties()
    {
        StartLog.debug("Expanding System Properties");

        for (String key : systemPropertySource.keySet())
        {
            String value = properties.getString(key);
            if (value != null)
            {
                String expanded = properties.expand(value);
                if (!value.equals(expanded))
                    System.setProperty(key, expanded);
            }
        }
    }

    /**
     * Expand any command line added {@code --lib} lib references.
     *
     * @throws IOException if unable to expand the libraries
     */
    public void expandLibs() throws IOException
    {
        StartLog.debug("Expanding Libs");
        for (String rawlibref : rawLibs)
        {
            StartLog.debug("rawlibref = " + rawlibref);
            String libref = properties.expand(rawlibref);
            StartLog.debug("expanded = " + libref);

            // perform path escaping (needed by windows)
            libref = libref.replaceAll("\\\\([^\\\\])", "\\\\\\\\$1");

            for (Path libpath : baseHome.getPaths(libref))
            {
                classpath.addComponent(libpath.toFile());
            }
        }
    }

    /**
     * Build up the Classpath and XML file references based on enabled Module list.
     *
     * @param activeModules the active (selected) modules
     * @throws IOException if unable to expand the modules
     */
    public void expandModules(List<Module> activeModules) throws IOException
    {
        StartLog.debug("Expanding Modules");
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
                xmlRef = properties.expand(xmlRef);
                Path xmlfile = baseHome.getPath(xmlRef);
                addUniqueXmlFile(xmlRef, xmlfile);
            }

            // Register Download operations
            for (String file : module.getFiles())
            {
                StartLog.debug("Adding module specified file: %s", file);
                addFile(module, file);
            }
        }
    }

    void expandJPMS(List<Module> activeModules) throws IOException
    {
        for (Module module : activeModules)
        {
            for (String line : module.getJPMS())
            {
                line = properties.expand(line);
                String directive;
                if (line.startsWith(directive = "add-modules:"))
                {
                    String[] names = line.substring(directive.length()).split(",");
                    Arrays.stream(names).map(String::trim).collect(Collectors.toCollection(() -> jmodAdds));
                }
                else if (line.startsWith(directive = "patch-module:"))
                {
                    parseJPMSKeyValue(module, line, directive, true, jmodPatch);
                }
                else if (line.startsWith(directive = "add-opens:"))
                {
                    parseJPMSKeyValue(module, line, directive, false, jmodOpens);
                }
                else if (line.startsWith(directive = "add-exports:"))
                {
                    parseJPMSKeyValue(module, line, directive, false, jmodExports);
                }
                else if (line.startsWith(directive = "add-reads:"))
                {
                    parseJPMSKeyValue(module, line, directive, false, jmodReads);
                }
                else
                {
                    throw new IllegalArgumentException("Invalid [jpms] directive " + directive + " in module " + module.getName() + ": " + line);
                }
            }
        }
        StartLog.debug("Expanded JPMS directives:%nadd-modules: %s%npatch-modules: %s%nadd-opens: %s%nadd-exports: %s%nadd-reads: %s",
            jmodAdds, jmodPatch, jmodOpens, jmodExports, jmodReads);
    }

    private void parseJPMSKeyValue(Module module, String line, String directive, boolean valueIsFile, Map<String, Set<String>> output) throws IOException
    {
        String valueString = line.substring(directive.length());
        int equals = valueString.indexOf('=');
        if (equals <= 0)
            throw new IllegalArgumentException("Invalid [jpms] directive " + directive + " in module " + module.getName() + ": " + line);
        String delimiter = valueIsFile ? File.pathSeparator : ",";
        String key = valueString.substring(0, equals).trim();
        String[] values = valueString.substring(equals + 1).split(delimiter);
        Set<String> result = output.computeIfAbsent(key, k -> new LinkedHashSet<>());
        for (String value : values)
        {
            value = value.trim();
            if (valueIsFile)
            {
                List<Path> paths = baseHome.getPaths(value);
                paths.stream().map(Path::toAbsolutePath).map(Path::toString).collect(Collectors.toCollection(() -> result));
            }
            else
            {
                result.add(value);
            }
        }
    }

    public List<String> getStartModules()
    {
        return startModules;
    }

    public Modules getAllModules()
    {
        return allModules;
    }

    public Classpath getClasspath()
    {
        return classpath;
    }

    public List<String> getEnabledModules()
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

    public CommandLineBuilder getMainArgs(Set<String> parts) throws IOException
    {
        if (parts.isEmpty())
            parts = ALL_PARTS;

        CommandLineBuilder cmd = new CommandLineBuilder();

        // Special Stop/Shutdown properties
        ensureSystemPropertySet("STOP.PORT");
        ensureSystemPropertySet("STOP.KEY");
        ensureSystemPropertySet("STOP.WAIT");

        if (parts.contains("java"))
            cmd.addRawArg(CommandLineBuilder.findJavaBin());

        if (parts.contains("opts"))
        {
            cmd.addRawArg("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
            cmd.addRawArg("-Djetty.home=" + baseHome.getHome());
            cmd.addRawArg("-Djetty.base=" + baseHome.getBase());

            for (String x : getJvmArgs())
            {
                if (x.startsWith("-D"))
                {
                    String[] assign = x.substring(2).split("=", 2);
                    String key = assign[0];
                    String value = assign.length == 1 ? "" : assign[1];

                    Prop p = processSystemProperty(key, value, null);
                    cmd.addRawArg("-D" + p.key + "=" + getProperties().expand(p.value));

                }
                else
                {
                    cmd.addRawArg(getProperties().expand(x));
                }
            }

            // System Properties
            for (String propKey : systemPropertySource.keySet())
            {
                String value = System.getProperty(propKey);
                cmd.addEqualsArg("-D" + propKey, value);
            }
        }

        if (parts.contains("path"))
        {
            if (isJPMS())
            {
                Map<Boolean, List<File>> dirsAndFiles = StreamSupport.stream(classpath.spliterator(), false)
                    .collect(Collectors.groupingBy(File::isDirectory));
                List<File> files = dirsAndFiles.get(false);
                if (files != null && !files.isEmpty())
                {
                    cmd.addRawArg("--module-path");
                    String modules = files.stream()
                        .map(File::getAbsolutePath)
                        .collect(Collectors.joining(File.pathSeparator));
                    cmd.addRawArg(modules);
                }
                List<File> dirs = dirsAndFiles.get(true);
                if (dirs != null && !dirs.isEmpty())
                {
                    cmd.addRawArg("--class-path");
                    String directories = dirs.stream()
                        .map(File::getAbsolutePath)
                        .collect(Collectors.joining(File.pathSeparator));
                    cmd.addRawArg(directories);
                }

                if (!jmodAdds.isEmpty())
                {
                    cmd.addRawArg("--add-modules");
                    cmd.addRawArg(String.join(",", jmodAdds));
                }
                for (Map.Entry<String, Set<String>> entry : jmodPatch.entrySet())
                {
                    cmd.addRawArg("--patch-module");
                    cmd.addRawArg(entry.getKey() + "=" + String.join(File.pathSeparator, entry.getValue()));
                }
                for (Map.Entry<String, Set<String>> entry : jmodOpens.entrySet())
                {
                    cmd.addRawArg("--add-opens");
                    cmd.addRawArg(entry.getKey() + "=" + String.join(",", entry.getValue()));
                }
                for (Map.Entry<String, Set<String>> entry : jmodExports.entrySet())
                {
                    cmd.addRawArg("--add-exports");
                    cmd.addRawArg(entry.getKey() + "=" + String.join(",", entry.getValue()));
                }
                for (Map.Entry<String, Set<String>> entry : jmodReads.entrySet())
                {
                    cmd.addRawArg("--add-reads");
                    cmd.addRawArg(entry.getKey() + "=" + String.join(",", entry.getValue()));
                }
            }
            else
            {
                cmd.addRawArg("-cp");
                cmd.addRawArg(classpath.toString());
            }
        }

        if (parts.contains("main"))
        {
            if (isJPMS())
                cmd.addRawArg("--module");
            cmd.addRawArg(getMainClassname());
        }

        // pass properties as args or as a file
        if (parts.contains("args"))
        {
            if (dryRun && execProperties == null)
            {
                for (Prop p : properties)
                {
                    cmd.addRawArg(CommandLineBuilder.quote(p.key) + "=" + CommandLineBuilder.quote(p.value));
                }
            }
            else if (properties.size() > 0)
            {
                Path propPath;
                if (execProperties == null)
                {
                    propPath = Files.createTempFile("start_", ".properties");
                    propPath.toFile().deleteOnExit();
                }
                else
                    propPath = new File(execProperties).toPath();

                try (OutputStream out = Files.newOutputStream(propPath))
                {
                    properties.store(out, "start.jar properties");
                }
                cmd.addRawArg(propPath.toAbsolutePath().toString());
            }

            for (Path xml : xmls)
            {
                cmd.addRawArg(xml.toAbsolutePath().toString());
            }

            for (Path propertyFile : propertyFiles)
            {
                cmd.addRawArg(propertyFile.toAbsolutePath().toString());
            }
        }

        return cmd;
    }

    public String getMainClassname()
    {
        String mainClass = System.getProperty("jetty.server", isJPMS() ? MODULE_MAIN_CLASS : MAIN_CLASS);
        return System.getProperty("main.class", mainClass);
    }

    public String getMavenLocalRepoDir()
    {
        String localRepo = getProperties().getString("maven.local.repo");

        if (Utils.isBlank(localRepo))
            localRepo = System.getenv("JETTY_MAVEN_LOCAL_REPO");

        if (Utils.isBlank(localRepo))
            localRepo = System.getenv("MAVEN_LOCAL_REPO");

        return localRepo;
    }

    public Path findMavenLocalRepoDir()
    {
        // Try property first
        String localRepo = getMavenLocalRepoDir();

        if (Utils.isBlank(localRepo))
        {
            // Try generic env variable
            Path home = Paths.get(System.getProperty("user.home"));
            Path localMavenRepository = home.resolve(".m2/repository");
            if (Files.exists(localMavenRepository))
                localRepo = localMavenRepository.toString();
        }

        // TODO: possibly use Eclipse Aether to manage it ?
        // TODO: see https://bugs.eclipse.org/bugs/show_bug.cgi?id=449511

        // Still blank? then its not specified
        if (Utils.isBlank(localRepo))
        {
            return null;
        }

        Path localRepoDir = new File(localRepo).toPath();
        localRepoDir = localRepoDir.normalize().toAbsolutePath();
        if (Files.exists(localRepoDir) && Files.isDirectory(localRepoDir))
        {
            return localRepoDir;
        }

        StartLog.warn("Not a valid maven local repository directory: %s", localRepoDir);

        // Not a valid repository directory, skip it
        return null;
    }

    public String getModuleGraphFilename()
    {
        return moduleGraphFilename;
    }

    public Props getProperties()
    {
        return properties;
    }

    public Set<String> getSkipFileValidationModules()
    {
        return skipFileValidationModules;
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
        return !jvmArgs.isEmpty();
    }

    public boolean hasSystemProperties()
    {
        for (String key : systemPropertySource.keySet())
        {
            // ignored keys
            if ("jetty.home".equals(key) || "jetty.base".equals(key) || "main.class".equals(key))
            {
                // skip
                continue;
            }
            return true;
        }
        return false;
    }

    public boolean isApproveAllLicenses()
    {
        return approveAllLicenses;
    }

    public boolean isCreateFiles()
    {
        return createFiles;
    }

    public boolean isJPMS()
    {
        return jpms;
    }

    public boolean isDryRun()
    {
        return dryRun;
    }

    public Set<String> getDryRunParts()
    {
        return dryRunParts;
    }

    public boolean isExec()
    {
        return exec;
    }

    public boolean isLicenseCheckRequired()
    {
        return licenseCheckRequired;
    }

    public boolean isNormalMainClass()
    {
        return MAIN_CLASS.equals(getMainClassname());
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

    public List<String> getListModules()
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

    public boolean isTestingModeEnabled()
    {
        return testingMode;
    }

    public boolean isVersion()
    {
        return version;
    }

    public boolean isCreateStartd()
    {
        return createStartd;
    }

    public boolean isUpdateIni()
    {
        return updateIni;
    }

    public String getMavenBaseUri()
    {
        return mavenBaseUri;
    }

    public void parse(ConfigSources sources)
    {
        ListIterator<ConfigSource> iter = sources.reverseListIterator();
        while (iter.hasPrevious())
        {
            ConfigSource source = iter.previous();
            for (RawArgs.Entry arg : source.getArgs())
            {
                parse(arg.getLine(), arg.getOrigin());
            }
        }
    }

    /**
     * Parse a single line of argument.
     *
     * @param rawarg the raw argument to parse
     * @param source the origin of this line of argument
     */
    public void parse(final String rawarg, String source)
    {
        if (rawarg == null)
        {
            return;
        }

        StartLog.debug("parse(\"%s\", \"%s\")", rawarg, source);

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

        if ("--testing-mode".equals(arg))
        {
            System.setProperty("org.eclipse.jetty.start.testing", "true");
            testingMode = true;
            return;
        }

        if (arg.startsWith("--commands="))
        {
            Path commands = baseHome.getPath(Props.getValue(arg));

            if (!Files.exists(commands) || !Files.isReadable(commands))
                throw new UsageException(UsageException.ERR_BAD_ARG, "--commands file must be readable: %s", commands);
            try
            {
                TextFile file = new TextFile(commands);
                StartLog.info("reading commands from %s", baseHome.toShortForm(commands));
                String s = source + "|" + baseHome.toShortForm(commands);
                for (String line : file)
                {
                    parse(line, s);
                }
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
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
            addFile(null, Props.getValue(arg));
            run = false;
            createFiles = true;
            return;
        }

        if (arg.equals("--create-files"))
        {
            run = false;
            createFiles = true;
            licenseCheckRequired = true;
            return;
        }

        if (arg.equals("--update-ini") || arg.equals("--update-inis"))
        {
            run = false;
            updateIni = true;
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

        if ("--jpms".equals(arg))
        {
            jpms = true;
            // Need to fork because we cannot use JDK 9 Module APIs.
            exec = true;
            return;
        }

        if ("--dry-run".equals(arg) || "--exec-print".equals(arg))
        {
            dryRun = true;
            run = false;
            return;
        }

        if (arg.startsWith("--dry-run="))
        {
            int colon = arg.indexOf('=');
            for (String part : arg.substring(colon + 1).split(","))
            {
                if (!ALL_PARTS.contains(part))
                    throw new UsageException(UsageException.ERR_BAD_ARG, "Unrecognized --dry-run=\"%s\" in %s", part, source);

                dryRunParts.add(part);
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

        // Assign a fixed name to the property file for exec
        if (arg.startsWith("--exec-properties="))
        {
            execProperties = Props.getValue(arg);
            if (!execProperties.endsWith(".properties"))
                throw new UsageException(UsageException.ERR_BAD_ARG, "--exec-properties filename must have .properties suffix: %s", execProperties);
            return;
        }

        // Enable forked execution of Jetty server
        if ("--approve-all-licenses".equals(arg))
        {
            approveAllLicenses = true;
            return;
        }

        // Arbitrary Libraries
        if (arg.startsWith("--lib="))
        {
            String cp = Props.getValue(arg);

            if (cp != null)
            {
                StringTokenizer t = new StringTokenizer(cp, File.pathSeparator);
                while (t.hasMoreTokens())
                {
                    rawLibs.add(t.nextToken());
                }
            }
            return;
        }

        // Module Management
        if ("--list-all-modules".equals(arg))
        {
            listModules = Collections.singletonList("*");
            run = false;
            return;
        }

        // Module Management
        if ("--list-modules".equals(arg))
        {
            listModules = Collections.singletonList("-internal");
            run = false;
            return;
        }

        if (arg.startsWith("--list-modules="))
        {
            listModules = Props.getValues(arg);
            run = false;
            return;
        }

        // jetty.base build-out : add to ${jetty.base}/start.ini
        if ("--create-startd".equals(arg))
        {
            createStartd = true;
            run = false;
            createFiles = true;
            licenseCheckRequired = true;
            return;
        }
        if (arg.startsWith("--add-to-startd="))
        {
            String value = Props.getValue(arg);
            StartLog.warn("--add-to-startd is deprecated! Instead use: --create-startd --add-to-start=%s", value);
            createStartd = true;
            startModules.addAll(Props.getValues(arg));
            run = false;
            createFiles = true;
            licenseCheckRequired = true;
            return;
        }
        if (arg.startsWith("--add-to-start="))
        {
            startModules.addAll(Props.getValues(arg));
            run = false;
            createFiles = true;
            licenseCheckRequired = true;
            return;
        }

        // Enable a module
        if (arg.startsWith("--module="))
        {
            List<String> moduleNames = Props.getValues(arg);
            enableModules(source, moduleNames);
            return;
        }

        // Skip [files] validation on a module
        if (arg.startsWith("--skip-file-validation="))
        {
            List<String> moduleNames = Props.getValues(arg);
            skipFileValidationModules.addAll(moduleNames);
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
            String[] assign = arg.substring(2).split("=", 2);
            String key = assign[0];
            String value = assign.length == 1 ? "" : assign[1];

            Prop p = processSystemProperty(key, value, source);
            systemPropertySource.put(p.key, p.source);
            setProperty(p.key, p.value, p.source);
            System.setProperty(p.key, p.value);
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
        int equals = arg.indexOf('=');
        if (equals >= 0)
        {
            String key = arg.substring(0, equals);
            String value = arg.substring(equals + 1);

            processAndSetProperty(key, value, source);

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
        throw new UsageException(UsageException.ERR_BAD_ARG, "Unrecognized argument: \"%s\" in %s", arg, source);
    }

    protected Prop processSystemProperty(String key, String value, String source)
    {
        if (key.endsWith("+"))
        {
            key = key.substring(0, key.length() - 1);
            String orig = System.getProperty(key);
            if (orig == null || orig.isEmpty())
            {
                if (value.startsWith(","))
                    value = value.substring(1);
            }
            else
            {
                value = orig + value;
                if (source != null && systemPropertySource.containsKey(key))
                    source = systemPropertySource.get(key) + "," + source;
            }
        }
        else if (key.endsWith("?"))
        {
            key = key.substring(0, key.length() - 1);
            String preset = System.getProperty(key);
            if (preset != null)
            {
                value = preset;
                source = systemPropertySource.get(key);
            }
            else if (source != null)
                source = source + "?=";
        }

        return new Prop(key, value, source);
    }

    protected void processAndSetProperty(String key, String value, String source)
    {
        if (key.endsWith("+"))
        {
            key = key.substring(0, key.length() - 1);
            Prop orig = getProperties().getProp(key);
            if (orig == null)
            {
                if (value.startsWith(","))
                    value = value.substring(1);
            }
            else
            {
                value = orig.value + value;
                source = orig.source + "," + source;
            }
        }
        else if (key.endsWith("?"))
        {
            key = key.substring(0, key.length() - 1);
            Prop preset = getProperties().getProp(key);
            if (preset != null)
                return;

            if (source != null)
                source = source + "?=";
        }

        setProperty(key, value, source);
    }

    private void enableModules(String source, List<String> moduleNames)
    {
        for (String moduleName : moduleNames)
        {
            modules.add(moduleName);
            List<String> list = sources.computeIfAbsent(moduleName, k -> new ArrayList<>());
            list.add(source);
        }
    }

    public void resolveExtraXmls() throws IOException
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
            addUniqueXmlFile(xmlRef, xmlfile);
        }
    }

    public void resolvePropertyFiles() throws IOException
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
            addUniquePropertyFile(propertyFileRef, propertyFile);
        }
    }

    public void setAllModules(Modules allModules)
    {
        this.allModules = allModules;
    }

    public void setProperty(String key, String value, String source)
    {
        // Special / Prevent override from start.ini's
        if (key.equals("jetty.home"))
        {
            properties.setProperty("jetty.home", System.getProperty("jetty.home"), source);
            return;
        }

        // Special / Prevent override from start.ini's
        if (key.equals("jetty.base"))
        {
            properties.setProperty("jetty.base", System.getProperty("jetty.base"), source);
            return;
        }

        properties.setProperty(key, value, source);
        if (key.equals("java.version"))
        {
            try
            {
                JavaVersion ver = JavaVersion.parse(value);
                properties.setProperty("java.version.platform", Integer.toString(ver.getPlatform()), source);

                // @deprecated - below will be removed in Jetty 10.x
                properties.setProperty("java.version.major", Integer.toString(ver.getMajor()), "Deprecated");
                properties.setProperty("java.version.minor", Integer.toString(ver.getMinor()), "Deprecated");
                properties.setProperty("java.version.micro", Integer.toString(ver.getMicro()), "Deprecated");

                // ALPN feature exists
                properties.setProperty("runtime.feature.alpn", Boolean.toString(isMethodAvailable(javax.net.ssl.SSLParameters.class, "getApplicationProtocols", null)), source);
            }
            catch (Throwable x)
            {
                UsageException ue = new UsageException(UsageException.ERR_BAD_ARG, x.getMessage() == null ? x.toString() : x.getMessage());
                ue.initCause(x);
                throw ue;
            }
        }

        // to override default https://repo1.maven.org/maven2/
        if (key.equals("maven.repo.uri"))
        {
            this.mavenBaseUri = value;
        }
    }

    private boolean isMethodAvailable(Class<?> clazz, String methodName, Class<?>[] params)
    {
        try
        {
            clazz.getMethod(methodName, params);
            return true;
        }
        catch (NoSuchMethodException e)
        {
            return false;
        }
    }

    public void setRun(boolean run)
    {
        this.run = run;
    }

    @Override
    public String toString()
    {
        return String.format("%s[enabledModules=%s, xmlRefs=%s, properties=%s, jvmArgs=%s]",
            getClass().getSimpleName(), modules, xmlRefs, properties, jvmArgs);
    }
}
