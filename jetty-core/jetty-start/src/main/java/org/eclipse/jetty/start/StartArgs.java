//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.start;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;
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
    public static final Set<String> ALL_PARTS = Set.of("java", "opts", "path", "main", "args", "envs");
    public static final Set<String> ARG_PARTS = Set.of("args", "envs");
    public static final String ARG_ALLOW_INSECURE_HTTP_DOWNLOADS = "--allow-insecure-http-downloads";

    private static final String JETTY_VERSION_KEY = "jetty.version";
    private static final String JETTY_TAG_NAME_KEY = "jetty.tag.version";
    private static final String JETTY_BUILDNUM_KEY = "jetty.build";

    static
    {
        // Use command line versions
        String ver = System.getProperty(JETTY_VERSION_KEY);
        String tag = System.getProperty(JETTY_TAG_NAME_KEY);

        // Use META-INF/MANIFEST.MF versions
        if (Utils.isBlank(ver))
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
     * Set of enabled modules
     */
    private final Set<String> modules = new HashSet<>();

    /**
     * List of modules to skip [files] section validation
     */
    private final Set<String> skipFileValidationModules = new HashSet<>();

    /**
     * Map of enabled modules to the source of where that activation occurred
     */
    Map<String, Set<String>> sources = new HashMap<>();

    /**
     * List of all active [files] sections from enabled modules
     */
    private final List<FileArg> files = new ArrayList<>();

    /**
     * JVM arguments, found via command line and in all active [exec] sections from enabled modules
     */
    private final Map<String, String> jvmArgSources = new LinkedHashMap<>();

    private final Map<String, String> systemPropertySource = new HashMap<>();

    private static final Map<String, StartEnvironment> environments = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    // jetty.base - build out commands
    /**
     * --add-modules=[module,[module]]
     */
    private final List<String> startModules = new ArrayList<>();

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
    private List<String> showModules = null;
    private boolean listClasspath = false;
    private boolean listConfig = false;
    private boolean version = false;
    private boolean dryRun = false;
    private boolean multiLine = false;
    private final Set<String> dryRunParts = new HashSet<>();
    private boolean jpms = false;
    private boolean createStartD = false;
    private boolean createStartIni = false;
    private boolean updateIni = false;
    private String mavenBaseUri;

    private boolean exec = false;
    private String execProperties;
    private boolean allowInsecureHttpDownloads = false;
    private boolean approveAllLicenses = false;

    /**
     * The jetty environment holds the main configuration used from the primary classloader for Jetty.
     * It is never created as a real environment within the server.
     * */
    private final StartEnvironment jettyEnvironment;

    public StartArgs(BaseHome baseHome)
    {
        this.baseHome = baseHome;
        jettyEnvironment = new StartEnvironment("Jetty", baseHome);
    }

    public void expandEnvironments(List<Module> activeModules) throws IOException
    {
        expandSystemProperties();
        jettyEnvironment.resolveLibs();
        expandModules(activeModules);
        jettyEnvironment.resolve();

        for (StartEnvironment environment : environments.values())
        {
            environment.resolveLibs();
            environment.resolve();
        }
    }

    public StartEnvironment getJettyEnvironment()
    {
        return jettyEnvironment;
    }

    public Collection<StartEnvironment> getEnvironments()
    {
        return environments.values();
    }

    public StartEnvironment getEnvironment(String envName)
    {
        return environments.computeIfAbsent(envName, k -> new StartEnvironment(k, baseHome));
    }

    private void addFile(Module module, String uriLocation)
    {
        if (module != null && module.isSkipFilesValidation())
        {
            StartLog.debug("Not validating module %s [files] for %s", module, uriLocation);
            return;
        }

        StartEnvironment environment = getEnvironment(module);
        FileArg arg = new FileArg(module, environment.getProperties().expand(uriLocation));
        if (!files.contains(arg))
            files.add(arg);
    }

    private StartEnvironment getEnvironment(Module module)
    {
        String envName = module == null ? null : module.getEnvironment();
        StartEnvironment environment = envName == null ? getJettyEnvironment() : getEnvironment(envName);
        return environment;
    }

    public void dumpJavaEnvironment(PrintStream out)
    {
        // Java Details
        out.println();
        out.println("JVM Version & Properties:");
        out.println("-------------------------");
        dumpSystemProperty(out, "java.home");
        dumpSystemProperty(out, "java.vm.vendor");
        dumpSystemProperty(out, "java.vm.version");
        dumpSystemProperty(out, "java.vm.name");
        dumpSystemProperty(out, "java.vm.info");
        dumpSystemProperty(out, "java.runtime.name");
        dumpSystemProperty(out, "java.runtime.version");
        dumpSystemProperty(out, "java.io.tmpdir");
        dumpSystemProperty(out, "user.dir");
        dumpSystemProperty(out, "user.language");
        dumpSystemProperty(out, "user.country");

        // Jetty Server Environment
        out.println();
        out.println("Jetty Version & Properties:");
        out.println("---------------------------");
        StartEnvironment jettyEnvironment = getJettyEnvironment();
        jettyEnvironment.dumpProperty(out, JETTY_VERSION_KEY);
        jettyEnvironment.dumpProperty(out, JETTY_TAG_NAME_KEY);
        jettyEnvironment.dumpProperty(out, JETTY_BUILDNUM_KEY);
        jettyEnvironment.dumpProperty(out, "jetty.home");
        jettyEnvironment.dumpProperty(out, "jetty.base");

        // Jetty Configuration Environment
        out.println();
        out.println("Config Search Order:");
        out.println("--------------------");
        for (ConfigSource config : baseHome.getConfigSources())
        {
            out.printf(" %s", config.getId());
            if (config instanceof DirConfigSource)
            {
                DirConfigSource dirsource = (DirConfigSource)config;
                if (dirsource.isPropertyBased())
                {
                    out.printf(" -> %s", dirsource.getDir());
                }
            }
            out.println();
        }
    }

    public void dumpJvmArgs(PrintStream out)
    {
        if (jvmArgSources.isEmpty())
            return;

        out.println();
        out.println("Forked JVM Arguments:");
        out.println("---------------------");

        jvmArgSources.forEach((key, sourceRef) ->
        {
            String value = System.getProperty(key);
            String source = StartLog.isDebugEnabled() ? '(' + sourceRef + ')' : "";
            if (value != null)
                out.printf(" %s = %s %s%n", key, value, source);
            else
                out.printf(" %s %s%n", key, source);
        });
    }

    public void dumpSystemProperties(PrintStream out)
    {
        out.println();
        out.println("System Properties:");
        out.println("------------------");

        if (systemPropertySource.keySet().isEmpty())
        {
            out.println(" (no system properties specified)");
            return;
        }

        List<String> sortedKeys = new ArrayList<>(systemPropertySource.keySet());
        Collections.sort(sortedKeys);

        for (String key : sortedKeys)
        {
            dumpSystemProperty(out, key);
        }
    }

    private void dumpSystemProperty(PrintStream out, String key)
    {
        String value = System.getProperty(key);
        // "source" is where this property came from (jvm, command line, configuration file, etc)
        String source = "";
        if (systemPropertySource.get(key) != null)
            source = String.format(" (%s)", systemPropertySource.get(key));
        out.printf(" %s = %s%s%n", key, value, source);
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

        StartEnvironment jettyEnvironment = getJettyEnvironment();
        if (jettyEnvironment.getProperties().containsKey(key))
        {
            Prop prop = jettyEnvironment.getProperties().getProp(key);
            if (prop == null)
                return; // no value set;

            String val = jettyEnvironment.getProperties().expand(prop.value);
            // setup system property
            systemPropertySource.put(key, "property:" + prop.source);
            System.setProperty(key, val);
        }
    }

    /**
     * Expand any command line added {@code --libs} lib references.
     */
    public void expandSystemProperties()
    {
        StartLog.debug("Expanding System Properties");

        for (String key : systemPropertySource.keySet())
        {
            String value = getJettyEnvironment().getProperties().getString(key);
            if (value != null)
            {
                String expanded = getJettyEnvironment().getProperties().expand(value);
                if (!value.equals(expanded))
                    System.setProperty(key, expanded);
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
            StartEnvironment environment = getEnvironment(module);

            // Find and Expand Libraries
            for (String lib : module.getLibs())
            {
                StartLog.debug("lib = %s", lib);
                lib = environment.getProperties().expand(lib);
                StartLog.debug("expanded = %s", lib);

                for (Path libPath : baseHome.getPaths(lib))
                {
                    environment.getClasspath().addComponent(libPath);
                }
            }

            for (String jvmArg : module.getJvmArgs())
            {
                exec = true;
                jvmArgSources.put(jvmArg, String.format("module[%s|jvm]", module.getName()));
            }

            // Find and Expand XML files
            for (String xmlRef : module.getXmls())
            {
                // Straight Reference
                xmlRef = environment.getProperties().expand(xmlRef);
                Path xmlfile = baseHome.getPath(xmlRef);
                environment.addUniqueXmlFile(xmlRef, xmlfile);
            }

            environment.resolveJPMS(module);

            // Register Download operations
            for (String file : module.getFiles())
            {
                StartLog.debug("Adding module specified file: %s", file);
                addFile(module, file);
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

    /**
     * <p>
     * The list of selected Modules to enable based on configuration
     * obtained from {@code start.d/*.ini}, {@code start.ini}, and command line.
     * </p>
     *
     * <p>
     *     For full list of enabled modules, use {@link Modules#getEnabled()}
     * </p>
     *
     * @return the set of selected modules (by name) that the configuration has.
     * @see Modules#getEnabled()
     */
    public Set<String> getSelectedModules()
    {
        return this.modules;
    }

    public List<FileArg> getFiles()
    {
        return files;
    }

    /**
     * Return ordered Map of JVM arguments to Source (locations)
     *
     * @return the ordered map of JVM Argument to Source (locations)
     */
    public Map<String, String> getJvmArgSources()
    {
        return jvmArgSources;
    }

    public CommandLineBuilder getMainArgs(Set<String> parts) throws IOException
    {
        if (parts.isEmpty())
            parts = ALL_PARTS;

        CommandLineBuilder cmd = new CommandLineBuilder(multiLine);

        // Special Stop/Shutdown properties
        ensureSystemPropertySet("STOP.PORT");
        ensureSystemPropertySet("STOP.KEY");
        ensureSystemPropertySet("STOP.WAIT");

        if (parts.contains("java"))
            cmd.addArg(CommandLineBuilder.findJavaBin());

        if (parts.contains("opts"))
        {
            cmd.addOption("-D", "java.io.tmpdir", System.getProperty("java.io.tmpdir"));
            cmd.addOption("-D", "jetty.home", baseHome.getHome());
            cmd.addOption("-D", "jetty.base", baseHome.getBase());

            Props properties = jettyEnvironment.getProperties();
            for (String x : getJvmArgSources().keySet())
            {
                if (x.startsWith("-D"))
                {
                    String[] assign = x.substring(2).split("=", 2);
                    String key = assign[0];
                    String value = assign.length == 1 ? "" : assign[1];

                    Prop p = processSystemProperty(key, value, null);
                    cmd.addOption("-D", p.key, properties.expand(p.value));
                }
                else
                {
                    cmd.addArg(properties.expand(x));
                }
            }

            // System Properties
            for (String propKey : systemPropertySource.keySet())
            {
                String value = System.getProperty(propKey);
                cmd.addOption("-D", propKey, value);
            }
        }

        if (parts.contains("path"))
        {
            Classpath classpath = jettyEnvironment.getClasspath();
            StartLog.debug("classpath=%s - isJPMS=%b", classpath, isJPMS());
            if (isJPMS())
            {
                Map<Boolean, List<Path>> dirsAndFiles = StreamSupport.stream(classpath.spliterator(), false)
                    .collect(Collectors.groupingBy(Files::isDirectory));

                List<Path> paths = dirsAndFiles.get(false);
                Set<Path> files = new HashSet<>((paths == null) ? Collections.emptyList() : paths);
                if (!files.isEmpty())
                {
                    cmd.addOption("--module-path");
                    String modules = files.stream()
                        .map(Path::toAbsolutePath)
                        .map(Path::toString)
                        .collect(Collectors.joining(FS.pathSeparator()));
                    cmd.addArg(modules);
                }

                List<Path> dirs = dirsAndFiles.get(true);
                if (dirs != null && !dirs.isEmpty())
                {
                    cmd.addOption("--class-path");
                    String directories = dirs.stream()
                        .map(Path::toAbsolutePath)
                        .map(Path::toString)
                        .collect(Collectors.joining(FS.pathSeparator()));
                    cmd.addArg(directories);
                }

                // Add JPMS options such as --add-opens.
                jettyEnvironment.getJPMSArgs().toCommandLine(cmd);

                StartLog.debug("JPMS resulting cmd=%s", cmd.toCommandLine());
            }
            else if (!classpath.isEmpty())
            {
                cmd.addOption("--class-path");
                cmd.addArg(classpath.toString());
            }
        }

        if (parts.contains("main"))
        {
            if (isJPMS())
                cmd.addOption("--module");
            cmd.addArg(getMainClassname());
        }

        // do properties and xmls
        if (parts.contains("args"))
        {
            Props properties = jettyEnvironment.getProperties();
            if (dryRun && execProperties == null)
            {
                // pass properties as args
                for (Prop p : properties)
                {
                    if (!p.key.startsWith("java.version."))
                        cmd.addArg(p.key, properties.expand(p.value));
                }
            }
            else if (properties.size() > 0)
            {
                // pass properties as a temp property file
                Path propPath;
                if (execProperties == null)
                {
                    propPath = Files.createTempFile("start_", ".properties");
                    propPath.toFile().deleteOnExit();
                }
                else
                {
                    propPath = Paths.get(execProperties);
                }

                try (OutputStream out = Files.newOutputStream(propPath))
                {
                    properties.store(out, "start.jar properties");
                }
                cmd.addArg(propPath.toAbsolutePath().toString());
            }

            for (Path propertyFile : jettyEnvironment.getPropertyFiles())
            {
                cmd.addArg(propertyFile.toAbsolutePath().toString());
            }

            for (Path xml : jettyEnvironment.getXmlFiles())
            {
                cmd.addArg(xml.toAbsolutePath().toString());
            }
        }

        if (parts.contains("envs"))
        {
            for (StartEnvironment environment : getEnvironments())
            {
                if (environment == jettyEnvironment)
                    continue;
                cmd.addArg("--env");
                cmd.addArg(environment.getName());

                environment.getClasspath().getElements().stream()
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .forEach(s ->
                    {
                        cmd.addArg(isJPMS() ? "-p" : "-cp");
                        cmd.addArg(s);
                    });

                if (isJPMS())
                    environment.getJPMSArgs().toCommandLine(cmd);

                Props props = environment.getProperties();
                for (Prop property : props)
                    cmd.addArg(property.key, props.expand(property.value));

                for (Path xmlFile : environment.getXmlFiles())
                    cmd.addArg(xmlFile.toAbsolutePath().toString());
            }
        }

        return cmd;
    }

    public String getMainClassname()
    {
        String mainClass = System.getProperty("jetty.server", isJPMS() ? MODULE_MAIN_CLASS : MAIN_CLASS);
        Prop mainClassProp = getJettyEnvironment().getProperties().getProp("main.class", true);
        if (mainClassProp != null)
            return mainClassProp.value;
        return mainClass;
    }

    public String getMavenLocalRepoDir()
    {
        String localRepo = getJettyEnvironment().getProperties().getString("maven.local.repo");

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

        Path localRepoDir = Paths.get(localRepo);
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

    public Set<String> getSkipFileValidationModules()
    {
        return skipFileValidationModules;
    }

    public Set<String> getSources(String module)
    {
        return sources.get(module);
    }

    public boolean hasJvmArgs()
    {
        return !jvmArgSources.isEmpty();
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

    public Map<String, String> getSystemProperties()
    {
        return systemPropertySource;
    }

    public boolean isAllowInsecureHttpDownloads()
    {
        return allowInsecureHttpDownloads;
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

    public List<String> getShowModules()
    {
        return showModules;
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

    public boolean isCreateStartD()
    {
        return createStartD;
    }

    public boolean isCreateStartIni()
    {
        return createStartIni;
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
            // Start with the Jetty environment.
            StartEnvironment environment = getJettyEnvironment();

            ConfigSource source = iter.previous();
            for (RawArgs.Entry arg : source.getArgs())
                environment = parse(environment, arg.getLine(), arg.getOrigin());
        }
    }

    /**
     * Parse a single line of argument.
     *
     * @param arg the raw argument to parse
     * @param source the origin of this line of argument
     */
    public StartEnvironment parse(StartEnvironment environment, String arg, String source)
    {
        StartLog.debug("parse(\"%s\", \"%s\")", arg, source);

        if (arg == null)
            return environment;
        arg = arg.trim();
        if (arg.length() == 0)
            return environment;

        if (arg.startsWith("#"))
            return environment;

        if ("--help".equals(arg) || "-?".equals(arg))
        {
            help = true;
            run = false;
            return environment;
        }

        if ("--debug".equals(arg) || arg.startsWith("--start-log-file"))
        {
            // valid, but handled in StartLog instead
            return environment;
        }

        if ("--testing-mode".equals(arg))
        {
            System.setProperty("org.eclipse.jetty.start.testing", "true");
            testingMode = true;
            return environment;
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

                StartEnvironment originalEnvironment = environment;
                for (String line : file)
                    environment = parse(environment, line, s);
                environment = originalEnvironment; // environment doesn't propagate beyond command file.
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
            return environment;
        }

        if (arg.startsWith("--include-jetty-dir=") || arg.startsWith("--add-config-dir="))
        {
            // valid, but handled in ConfigSources instead
            return environment;
        }

        if ("--stop".equals(arg))
        {
            stopCommand = true;
            run = false;
            return environment;
        }

        if (arg.startsWith("--download=") || arg.startsWith("--files="))
        {
            addFile(null, Props.getValue(arg));
            run = false;
            createFiles = true;
            return environment;
        }

        if (arg.equals("--create-files"))
        {
            run = false;
            createFiles = true;
            licenseCheckRequired = true;
            return environment;
        }

        if (arg.equals("--update-ini") || arg.equals("--update-inis"))
        {
            run = false;
            updateIni = true;
            return environment;
        }

        if ("--list-classpath".equals(arg) || "--version".equals(arg) || "-v".equals(arg) || "--info".equals(arg))
        {
            listClasspath = true;
            run = false;
            return environment;
        }

        if ("--list-config".equals(arg))
        {
            listConfig = true;
            run = false;
            return environment;
        }

        if ("--jpms".equals(arg))
        {
            jpms = true;
            // Forking is simpler; otherwise we need to add the
            // JPMS directives such as "--add-modules" via API.
            exec = true;
            return environment;
        }

        if ("--dry-run".equals(arg) || "--exec-print".equals(arg))
        {
            dryRun = true;
            run = false;
            return environment;
        }

        if (arg.startsWith("--dry-run="))
        {
            int colon = arg.indexOf('=');
            for (String part : arg.substring(colon + 1).split(","))
            {
                if ("multiline".equalsIgnoreCase(part))
                {
                    multiLine = true;
                    continue;
                }

                if (!ALL_PARTS.contains(part))
                    throw new UsageException(UsageException.ERR_BAD_ARG, "Unrecognized --dry-run=\"%s\" in %s", part, source);

                dryRunParts.add(part);
            }
            dryRun = true;
            run = false;
            return environment;
        }

        // Enable forked execution of Jetty server
        if ("--exec".equals(arg))
        {
            exec = true;
            return environment;
        }

        // Assign a fixed name to the property file for exec
        if (arg.startsWith("--exec-properties="))
        {
            execProperties = Props.getValue(arg);
            if (!execProperties.endsWith(".properties"))
                throw new UsageException(UsageException.ERR_BAD_ARG, "--exec-properties filename must have .properties suffix: %s", execProperties);
            return environment;
        }

        // Allow insecure-http downloads
        if (ARG_ALLOW_INSECURE_HTTP_DOWNLOADS.equals(arg))
        {
            allowInsecureHttpDownloads = true;
            return environment;
        }

        // Enable forked execution of Jetty server
        if ("--approve-all-licenses".equals(arg))
        {
            approveAllLicenses = true;
            return environment;
        }

        // Module Management
        if ("--list-all-modules".equals(arg))
        {
            listModules = Collections.singletonList("*");
            run = false;
            return environment;
        }

        // Module Management
        if ("--list-module".equals(arg) || "--list-modules".equals(arg))
        {
            listModules = Collections.singletonList("-internal");
            run = false;
            return environment;
        }

        if (arg.startsWith("--list-module=") || arg.startsWith("--list-modules="))
        {
            listModules = Props.getValues(arg);
            run = false;
            return environment;
        }

        // Module Management
        if ("--show-module".equals(arg) || "--show-modules".equals(arg))
        {
            showModules = Collections.emptyList();
            run = false;
            return environment;
        }

        if (arg.startsWith("--show-module=") || arg.startsWith("--show-modules="))
        {
            showModules = Props.getValues(arg);
            run = false;
            return environment;
        }

        // jetty.base build-out : add to ${jetty.base}/start.ini

        if ("--create-start-ini".equals(arg))
        {
            createStartIni = true;
            run = false;
            createFiles = true;
            licenseCheckRequired = true;
            return environment;
        }
        if ("--create-startd".equals(arg) || "--create-start-d".equals(arg))
        {
            createStartD = true;
            run = false;
            createFiles = true;
            licenseCheckRequired = true;
            return environment;
        }

        if (arg.startsWith("--add-module=") || arg.startsWith("--add-modules=") || arg.startsWith("--add-to-start=") || arg.startsWith("--add-to-startd="))
        {
            if (arg.startsWith("--add-to-start=") || arg.startsWith("--add-to-startd="))
            {
                String value = Props.getValue(arg);
                StartLog.warn("Option %s is deprecated! Instead use: --add-modules=%s", arg.split("=")[0], value);
            }
            startModules.addAll(Props.getValues(arg));
            run = false;
            createFiles = true;
            licenseCheckRequired = true;
            return environment;
        }

        // Select a module to eventually be enabled
        if (arg.startsWith("--module=") || arg.startsWith("--modules="))
        {
            List<String> moduleNames = Props.getValues(arg);
            selectModules(source, moduleNames);
            Module module = getAllModules().get(moduleNames.get(moduleNames.size() - 1));
            String envName = module == null ? null : module.getEnvironment();
            return envName == null ? jettyEnvironment : getEnvironment(envName);
        }

        // Skip [files] validation on a module
        if (arg.startsWith("--skip-file-validation=") || arg.startsWith("--skip-create-files="))
        {
            List<String> moduleNames = Props.getValues(arg);
            skipFileValidationModules.addAll(moduleNames);
            return environment;
        }

        // Create graphviz output of module graph
        if (arg.startsWith("--write-module-graph="))
        {
            this.moduleGraphFilename = Props.getValue(arg);
            run = false;
            return environment;
        }

        if (environment == null)
            environment = getJettyEnvironment();

        // Arbitrary Libraries
        if (arg.startsWith("--lib=") || arg.startsWith("--libs="))
        {
            String cp = Props.getValue(arg);
            StringTokenizer t = new StringTokenizer(cp, FS.pathSeparator());
            while (t.hasMoreTokens())
                environment.addCmdLineLib(t.nextToken());
            return environment;
        }

        // Start property (syntax similar to System property)
        if (arg.startsWith("-D"))
        {
            String[] assign = arg.substring(2).split("=", 2);
            String key = assign[0];
            String value = assign.length == 1 ? "" : assign[1];

            Prop p = processSystemProperty(key, value, source);
            systemPropertySource.put(p.key, p.source);
            setProperty(environment, p.key, p.value, p.source);
            System.setProperty(p.key, p.value);
            return environment;
        }

        // Anything else with a "-" is considered a JVM argument
        if (arg.startsWith("-"))
        {
            StartLog.debug("Unrecognized Arg (possible JVM Arg): %s (from %s)", arg, source);
            // always use the latest source (overriding any past tracked source)
            jvmArgSources.put(arg, source);
            return environment;
        }

        // Is this a raw property declaration?
        int equals = arg.indexOf('=');
        if (equals >= 0)
        {
            String key = arg.substring(0, equals);
            String value = arg.substring(equals + 1);

            processAndSetProperty(environment, key, value, source);

            return environment;
        }

        // Is this an xml file?
        if (FS.isXml(arg))
        {
            environment.addCmdLineXml(arg);
            return environment;
        }

        if (FS.isPropertyFile(arg))
        {
            environment.addCmdLinePropertyFile(arg);
            return environment;
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

    private void processAndSetProperty(StartEnvironment environment, String key, String value, String source)
    {
        if (key.endsWith("+"))
        {
            key = key.substring(0, key.length() - 1);
            Prop orig = environment.getProperties().getProp(key);
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
            Prop preset = environment.getProperties().getProp(key);
            if (preset != null)
                return;

            if (source != null)
                source = source + "?=";
        }

        setProperty(environment, key, value, source);
    }

    private void selectModules(String source, List<String> moduleNames)
    {
        for (String moduleName : moduleNames)
        {
            if (modules.add(moduleName))
            {
                Set<String> set = sources.computeIfAbsent(moduleName, k -> new HashSet<>());
                set.add(source);
            }
        }
    }

    public void setAllModules(Modules allModules)
    {
        this.allModules = allModules;
    }

    public void setProperty(StartEnvironment environment, String key, String value, String source)
    {
        if (environment == null)
            environment = getJettyEnvironment();
        Props properties = environment.getProperties();

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
        return String.format("%s[enabledModules=%s, xml=%s, properties=%s, jvmArgs=%s]",
            getClass().getSimpleName(), modules, getJettyEnvironment().getXmlFiles(), getJettyEnvironment().getProperties(), jvmArgSources.keySet());
    }
}
