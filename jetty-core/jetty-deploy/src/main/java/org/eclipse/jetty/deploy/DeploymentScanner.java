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

package org.eclipse.jetty.deploy;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.deploy.internal.PathsApp;
import org.eclipse.jetty.deploy.internal.PathsContextHandlerFactory;
import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.PathCollators;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Default Jetty Environment WebApp Hot Deployment Provider.</p>
 *
 * <p>This provider scans one or more directories (typically "webapps") for contexts to
 * deploy, which may be:</p>
 * <ul>
 * <li>A standard WAR file (must end in ".war")</li>
 * <li>A directory containing an expanded WAR file</li>
 * <li>A directory containing static content</li>
 * <li>An XML descriptor in {@link XmlConfiguration} format that configures a {@link ContextHandler} instance</li>
 * </ul>
 * <p>To avoid double deployments and allow flexibility of the content of the scanned directories, the provider
 * implements some heuristics to ignore some files found in the scans:
 * </p>
 * <ul>
 * <li>Hidden files (starting with {@code "."}) are ignored</li>
 * <li>Directories with names ending in {@code ".d"} are ignored</li>
 * <li>Property files with names ending in {@code ".properties"} are not deployed.</li>
 * <li>If a directory and a WAR file exist (eg: {@code foo/} and {@code foo.war}) then the directory is assumed to be
 * the unpacked WAR and only the WAR file is deployed (which may reuse the unpacked directory)</li>
 * <li>If a directory and a matching XML file exist (eg: {@code foo/} and {@code foo.xml}) then the directory is assumed to be
 * an unpacked WAR and only the XML file is deployed (which may use the directory in its configuration)</li>
 * <li>If a WAR file and a matching XML file exist (eg: {@code foo.war} and {@code foo.xml}) then the WAR file is assumed to
 * be configured by the XML file and only the XML file is deployed.
 * </ul>
 * <p>For XML configured contexts, the following is available.</p>
 * <ul>
 * <li>The XML Object ID Map will have a reference to the {@link Server} instance via the ID name {@code "Server"}</li>
 * <li>The Default XML Properties are populated from a call to {@link XmlConfiguration#setJettyStandardIdsAndProperties(Object, Path)} (for things like {@code jetty.home} and {@code jetty.base})</li>
 * <li>An extra XML Property named {@code "jetty.webapps"} is available, and points to the monitored path.</li>
 * </ul>
 * <p>
 * Context Deployment properties will be initialized with:
 * </p>
 * <ul>
 * <li>The properties set on the application via embedded calls modifying {@link PathsApp#getAttributes()}</li>
 * <li>The app specific properties file {@code webapps/<webapp-name>.properties}</li>
 * <li>The environment specific properties file {@code webapps/<environment-name>[-zzz].properties}</li>
 * <li>The {@link Attributes} from the {@link Environment}</li>
 * </ul>
 *
 * <p>
 * To configure Environment specific deployment {@link Attributes},
 * either set the appropriate {@link Deployable} attribute via {@link Attributes#setAttribute(String, Object)},
 * or use the convenience class {@link EnvironmentConfig}.
 * </p>
 *
 * <pre>{@code
 * DeploymentScanner provider = new DeploymentScanner();
 * EnvironmentConfig env10config = provider.configureEnvironment("ee10");
 * env10config.setExtractWars(true);
 * env10config.setParentLoaderPriority(false);
 * }</pre>
 */
// TODO: fix dumpable to show details about monitored dirs, environments dir, configured environment attributes, scan interval, etc ...
@ManagedObject("Provider for dynamic deployment of contexts (and webapps) based on presence in directory")
public class DeploymentScanner extends ContainerLifeCycle implements Scanner.BulkListener
{
    private static final Logger LOG = LoggerFactory.getLogger(DeploymentScanner.class);
    // old attributes prefix, now stripped.
    private static final String ATTRIBUTE_PREFIX = "jetty.deploy.attribute.";

    private final Server server;
    private final ContextHandlerDeployer contextHandlerDeployer;
    private final FilenameFilter filenameFilter;
    private final List<Path> monitoredDirs = new CopyOnWriteArrayList<>();
    private final PathsContextHandlerFactory contextHandlerFactory = new PathsContextHandlerFactory();
    private final Map<String, PathsApp> trackedApps = new HashMap<>();
    private final Map<String, Attributes> environmentAttributesMap = new HashMap<>();

    private Comparator<DeployAction> actionComparator = new DeployActionComparator();
    private Path environmentsDir;
    private int scanInterval = 10;
    private Scanner scanner;
    private boolean useRealPaths;
    private boolean deferInitialScan = false;
    private String defaultEnvironmentName;

    /**
     * <p>
     * Convenience constructor to have DeploymentScanner participate in DeploymentManager startup behaviors,
     * allowing failures in DeploymentScanner.start() to trigger a failure to start DeploymentManager.
     * </p>
     *
     * @param server the server reference to use for any XML based deployments.
     * @param deploymentManager the DeploymentManager instance that is being used by this DeploymentScanner.
     */
    public DeploymentScanner(
        @Name("server") Server server,
        @Name("deploymentManager") DeploymentManager deploymentManager)
    {
        this(server, deploymentManager, null);
        deploymentManager.addBean(this);
    }

    /**
     * <p>
     * Construct a raw DeploymentScanner that will (periodically) scan specific directories for paths that can be
     * used to construct webapps that will be submitted to the DeploymentManager for eventual deployment to
     * it's configured destination.
     * </p>
     *
     * @param server the server reference to use for any XML based deployments.
     * @param contextHandlerDeployer the ContextHandlerDeployer to use for deploying the created ContextHandlers.
     */
    public DeploymentScanner(
        @Name("server") Server server,
        @Name("contextHandlerDeployer") ContextHandlerDeployer contextHandlerDeployer)
    {
        this(server, contextHandlerDeployer, null);
    }

    /**
     * <p>
     * Construct a raw DeploymentScanner that will (periodically) scan specific directories for paths that can be
     * used to construct webapps that will be submitted to the DeploymentManager for eventual deployment to
     * it's configured destination.
     * </p>
     *
     * @param server the server reference to use for any XML based deployments.
     * @param contextHandlerDeployer the ContextHandlerDeployer to use for deploying the created ContextHandlers.
     * @param filter A custom FilenameFilter to control what files the {@link Scanner} monitors for changes.
     */
    public DeploymentScanner(
        @Name("server") Server server,
        @Name("contextHandlerDeployer") ContextHandlerDeployer contextHandlerDeployer,
        @Name("filenameFilter") FilenameFilter filter)
    {
        this.server = server;
        this.contextHandlerDeployer = Objects.requireNonNull(contextHandlerDeployer);
        this.filenameFilter = Objects.requireNonNullElse(filter, new MonitoredPathFilter(monitoredDirs));
    }

    /**
     * Strip old {@code jetty.deploy.attribute.} prefix if found.
     * We no longer limit the properties to only those prefixed keys, we allow all keys through now.
     *
     * @param key the key to possibly strip
     * @return the stripped key
     */
    public static String stripOldAttributePrefix(String key)
    {
        if (key.startsWith(ATTRIBUTE_PREFIX))
            return key.substring(ATTRIBUTE_PREFIX.length());
        else
            return key;
    }

    /**
     * @param dir Directory to scan for deployable artifacts
     */
    public void addMonitoredDirectory(Path dir)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Adding monitored directory: {}", dir);
        if (isStarted())
            throw new IllegalStateException("Unable to add monitored directory while running");
        monitoredDirs.add(Objects.requireNonNull(dir));
    }

    public void addScannerListener(Scanner.Listener listener)
    {
        scanner.addListener(listener);
    }

    /**
     * Configure the Environment specific Deploy settings.
     *
     * @param name the name of the environment.
     * @return the deployment configuration for the {@link Environment}.
     */
    public EnvironmentConfig configureEnvironment(String name)
    {
        return new EnvironmentConfig(Environment.ensure(name));
    }

    public Comparator<DeployAction> getActionComparator()
    {
        return actionComparator;
    }

    public void setActionComparator(Comparator<DeployAction> actionComparator)
    {
        this.actionComparator = actionComparator;
    }

    /**
     * Get the default {@link Environment} name for discovered web applications that
     * do not declare the {@link Environment} that they belong to.
     *
     * <p>
     * Falls back to {@link Environment#getAll()} list, and returns
     * the first name returned after sorting with {@link Deployable#ENVIRONMENT_COMPARATOR}
     * </p>
     *
     * @return the default environment name.
     */
    public String getDefaultEnvironmentName()
    {
        if (defaultEnvironmentName == null)
        {
            return Environment.getAll().stream()
                .map(Environment::getName)
                .max(Deployable.ENVIRONMENT_COMPARATOR)
                .orElse(null);
        }
        return defaultEnvironmentName;
    }

    public void setDefaultEnvironmentName(String name)
    {
        this.defaultEnvironmentName = name;
    }

    public Path getEnvironmentsDirectory()
    {
        return environmentsDir;
    }

    public void setEnvironmentsDirectory(Path dir)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Setting Environments directory: {}", dir);
        if (isStarted())
            throw new IllegalStateException("Unable to add environments directory while running");
        environmentsDir = dir;
    }

    public List<Path> getMonitoredDirectories()
    {
        return monitoredDirs;
    }

    public void setMonitoredDirectories(Collection<Path> directories)
    {
        if (isStarted())
            throw new IllegalStateException("Unable to add monitored directories while running");

        monitoredDirs.clear();

        for (Path dir : directories)
        {
            addMonitoredDirectory(dir);
        }
    }

    @ManagedAttribute("scanning interval to detect changes which need reloaded")
    public int getScanInterval()
    {
        return scanInterval;
    }

    public void setScanInterval(int scanInterval)
    {
        this.scanInterval = scanInterval;
    }

    /**
     * Test if initial scan should be deferred.
     *
     * @return true if initial scan is deferred, false to have initial scan occur on startup of {@code DeploymentScanner}.
     */
    public boolean isDeferInitialScan()
    {
        return deferInitialScan;
    }

    /**
     * Flag to control initial scan behavior.
     *
     * <ul>
     *     <li>{@code true} - to have initial scan deferred until the {@link Server} component
     *     has reached it's STARTED state.<br>
     *     Note: any failures in a deployment will not fail the Server startup in this mode.</li>
     *     <li>{@code false} - (default value) to have initial scan occur as normal on
     *     {@code DeploymentScanner} startup.</li>
     * </ul>
     *
     * @param defer true to defer initial scan, false to have initial scan occur on startup of {@code DeploymentScanner}.
     */
    public void setDeferInitialScan(boolean defer)
    {
        deferInitialScan = defer;
    }

    /**
     * @return True if the real path of the scanned files should be used for deployment.
     */
    public boolean isUseRealPaths()
    {
        return useRealPaths;
    }

    /**
     * @param useRealPaths True if the real path of the scanned files should be used for deployment.
     */
    public void setUseRealPaths(boolean useRealPaths)
    {
        this.useRealPaths = useRealPaths;
    }

    /**
     * This is the listener event for Scanner to report changes.
     *
     * @param changeSet the changeset from the Scanner.
     */
    @Override
    public void pathsChanged(Map<Path, Scanner.Notification> changeSet)
    {
        Objects.requireNonNull(changeSet);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("pathsChanged: {}",
                changeSet.entrySet()
                    .stream()
                    .map((e) -> String.format("%s|%s", e.getKey(), e.getValue()))
                    .collect(Collectors.joining(", ", "[", "]"))
            );
        }

        Set<String> changedBaseNames = new HashSet<>();
        Set<String> availableEnvironmentNames = Environment.getAll().stream()
            .map(Environment::getName).collect(Collectors.toUnmodifiableSet());
        Set<String> changedEnvironments = new HashSet<>();

        for (Map.Entry<Path, Scanner.Notification> entry : changeSet.entrySet())
        {
            Path path = entry.getKey();
            PathsApp.State state = switch (entry.getValue())
            {
                case ADDED -> PathsApp.State.ADDED;
                case CHANGED -> PathsApp.State.CHANGED;
                case REMOVED -> PathsApp.State.REMOVED;
            };

            // Using lower-case as defined by System Locale, as the files themselves from System FS.
            String basename = FileID.getBasename(path).toLowerCase();

            // Strip the ".d" extension on directory basenames
            if (Files.isDirectory(path) && FileID.isExtension(path, "d"))
            {
                basename = basename.substring(0, basename.length() - 2);
            }

            if (isMonitoredPath(path))
            {
                // we have a normal path entry
                changedBaseNames.add(basename);
                PathsApp app = trackedApps.computeIfAbsent(basename, PathsApp::new);
                app.putPath(path, state);
            }
            else if (isEnvironmentConfigPath(path))
            {
                String envname = null;
                for (String name : availableEnvironmentNames)
                {
                    if (basename.startsWith(name))
                        envname = name;
                }
                if (StringUtil.isBlank(envname))
                {
                    LOG.warn("Unable to determine Environment for file: {}", path);
                    continue;
                }
                changedEnvironments.add(envname);
            }
        }

        // Now we know the PathsApp instances that are changed by processing
        // the incoming Scanner changes.
        // Now we want to convert this list of changes to a DeployAction list
        // that will perform the add/remove logic in a consistent way.

        List<PathsApp> changedApps = changedBaseNames
            .stream()
            .map(this::findApp)
            .collect(Collectors.toList());

        if (!changedEnvironments.isEmpty())
        {
            // We have incoming environment configuration changes
            // We need to add any missing PathsApp that have changed
            // due to incoming environment configuration changes,
            // along with loading any ${jetty.base}/environments/<name>-*.properties
            // into a layer for that Environment.

            for (String changedEnvName : changedEnvironments)
            {
                // Add any missing apps to changedApps list
                for (PathsApp app : trackedApps.values())
                {
                    if (changedBaseNames.contains(app.getName()))
                        continue; // skip app that's already in the change list.

                    if (changedEnvName.equalsIgnoreCase(app.getEnvironmentName()))
                    {
                        if (app.getState() == PathsApp.State.UNCHANGED)
                            app.setState(PathsApp.State.CHANGED);
                        changedApps.add(app);
                        changedBaseNames.add(app.getName());
                    }
                }

                // Replace current tracked Environment Attributes, with a new Attributes.Layer.
                this.environmentAttributesMap.remove(changedEnvName);
                try
                {
                    Attributes envAttributes = loadEnvironmentAttributes(changedEnvName);
                    this.environmentAttributesMap.put(changedEnvName, envAttributes);
                }
                catch (IOException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Unable to load environment properties for environment [{}]", changedEnvName, e);
                }
            }
        }
        else
        {
            Environment.getAll().forEach((env) -> environmentAttributesMap.put(env.getName(), env));
        }

        List<DeployAction> actions = buildActionList(changedApps);
        performActions(actions);
    }

    public void resetAppState(String name)
    {
        PathsApp app = findApp(name);
        if (app == null)
            return;
        app.resetStates();
    }

    @ManagedOperation(value = "Scan the monitored directories", impact = "ACTION")
    public void scan()
    {
        LOG.info("Performing scan of monitored directories: {}",
            monitoredDirs.stream()
                .map(Path::toUri)
                .map(URI::toASCIIString)
                .collect(Collectors.joining(", ", "[", "]"))
        );
        scanner.nudge();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[dirs=%s]", this.getClass(), hashCode(), monitoredDirs);
    }

    protected List<DeployAction> buildActionList(List<PathsApp> changedApps)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("buildActionList: {}", changedApps);

        List<DeployAction> actions = new ArrayList<>();
        for (PathsApp app : changedApps)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("changed app: {}", app);

            switch (app.getState())
            {
                case ADDED ->
                {
                    // new paths are not being tracked yet.
                    startTracking(app);
                    actions.add(new DeployAction(DeployAction.Type.ADD, app.getName()));
                }
                case CHANGED ->
                {
                    actions.add(new DeployAction(DeployAction.Type.REMOVE, app.getName()));
                    actions.add(new DeployAction(DeployAction.Type.ADD, app.getName()));
                }
                case REMOVED ->
                {
                    actions.add(new DeployAction(DeployAction.Type.REMOVE, app.getName()));
                }
            }
        }
        return sortActions(actions);
    }

    @Override
    protected void doStart() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} doStart()", this);

        if (monitoredDirs.isEmpty())
            throw new IllegalStateException("No monitored dir specified");

        LOG.info("Deployment monitor in {} at intervals {}s", monitoredDirs, getScanInterval());

        Predicate<Path> validDir = (path) ->
        {
            if (!Files.exists(path))
            {
                LOG.warn("Does not exist: {}", path);
                return false;
            }

            if (!Files.isDirectory(path))
            {
                LOG.warn("Is not a directory: {}", path);
                return false;
            }

            return true;
        };

        List<Path> dirs = new ArrayList<>();
        for (Path dir : monitoredDirs)
        {
            if (validDir.test(dir))
                dirs.add(dir);
        }

        if (environmentsDir != null)
        {
            if (validDir.test(environmentsDir))
                dirs.add(environmentsDir);
        }

        scanner = new Scanner(null, useRealPaths);
        scanner.setScanDirs(dirs);
        scanner.setScanInterval(scanInterval);
        scanner.setFilenameFilter(filenameFilter);
        scanner.setReportDirs(true);
        scanner.setScanDepth(1);
        scanner.addListener(this);
        scanner.setReportExistingFilesOnStartup(true);
        scanner.setAutoStartScanning(!deferInitialScan);
        addBean(scanner);

        if (isDeferInitialScan())
        {
            if (server == null)
                throw new IllegalStateException("Cannot defer initial scan with a null Server");
            // Setup listener to wait for Server in STARTED state, which
            // triggers the first scan of the monitored directories
            server.addEventListener(
                new LifeCycle.Listener()
                {
                    @Override
                    public void lifeCycleStarted(LifeCycle event)
                    {
                        if (event instanceof Server)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Triggering Deferred Scan of {}", dirs);
                            scanner.startScanning();
                        }
                    }
                });
        }

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        if (scanner != null)
        {
            removeBean(scanner);
            scanner.removeListener(this);
            scanner = null;
        }
    }

    protected boolean exists(String path)
    {
        return scanner.exists(path);
    }

    protected PathsApp findApp(String name)
    {
        return trackedApps.get(name);
    }

    protected boolean isEnvironmentConfigPath(Path path)
    {
        if (environmentsDir == null)
            return false;

        return isSameDir(environmentsDir, path.getParent());
    }

    protected boolean isMonitoredPath(Path path)
    {
        Path parentDir = path.getParent();
        for (Path monitoredDir : monitoredDirs)
        {
            if (isSameDir(monitoredDir, parentDir))
                return true;
        }
        return false;
    }

    protected boolean isSameDir(Path dirA, Path dirB)
    {
        try
        {
            return Files.isSameFile(dirA, dirB);
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Ignoring: Unable to use Files.isSameFile({}, {})", dirA, dirB, e);
            return false;
        }
    }

    protected void performActions(List<DeployAction> actions)
    {
        // Track apps that have been removed as a result of executing the
        // full set of actions.
        Set<PathsApp> removedApps = new HashSet<>();

        // Process each step in the actions list
        for (DeployAction step : actions)
        {
            PathsApp app = findApp(step.name());
            if (app == null)
                throw new IllegalStateException("Unable to find app [" + step.name() + "]");

            try
            {
                switch (step.type())
                {
                    case REMOVE ->
                    {
                        // Track removal
                        removedApps.add(app);
                        contextHandlerDeployer.undeploy(app.getContextHandler());
                    }
                    case ADD ->
                    {
                        // Undo tracking for prior removal in this list of actions.
                        removedApps.remove(app);

                        // Load <basename>.properties into app.
                        app.loadProperties();

                        // Ensure Environment name is set
                        String appEnvironment = app.getEnvironmentName();
                        if (StringUtil.isBlank(appEnvironment))
                            appEnvironment = getDefaultEnvironmentName();
                        app.setEnvironment(Environment.get(appEnvironment));

                        // Create a new Attributes layer for the app deployment, which is the
                        // combination of layered Environment Attributes with app Attributes overlaying them.
                        Attributes envAttributes = environmentAttributesMap.get(appEnvironment);
                        Attributes.Layer deployAttributes = new Attributes.Layer(envAttributes, app.getAttributes());

                        // Ensure that Environment configuration XMLs are listed in deployAttributes
                        List<Path> envXmlPaths = findEnvironmentXmlPaths(deployAttributes);
                        envXmlPaths.sort(PathCollators.byName(true));
                        PathsContextHandlerFactory.setEnvironmentXmlPaths(deployAttributes, envXmlPaths);

                        // Create the Context Handler
                        ContextHandler contextHandler = contextHandlerFactory.newContextHandler(server, app, deployAttributes);
                        app.setContextHandler(contextHandler);

                        // Introduce the ContextHandler to the DeploymentManager
                        startTracking(app);
                        contextHandlerDeployer.deploy(app.getContextHandler());
                    }
                }
            }
            catch (Throwable t)
            {
                LOG.warn("Failed to to perform action {} on {}", step.type(), app, t);
                if (isStarting())
                    contextHandlerDeployer.reportStartupFailure(t);
            }
            finally
            {
                app.resetStates();
            }
        }

        // Fully stop tracking apps that have been removed, but not re-added.
        for (PathsApp removed : removedApps)
        {
            stopTracking(removed);
        }
    }

    protected List<DeployAction> sortActions(List<DeployAction> actions)
    {
        Comparator<DeployAction> deployActionComparator = getActionComparator();
        if (deployActionComparator != null)
            actions.sort(deployActionComparator);
        return actions;
    }

    private List<Path> findEnvironmentXmlPaths(Attributes deployAttributes)
    {
        List<Path> rawEnvXmlPaths = deployAttributes.getAttributeNameSet()
            .stream()
            .filter(k -> k.startsWith(PathsContextHandlerFactory.ENVIRONMENT_XML))
            .map(k -> Path.of((String)deployAttributes.getAttribute(k)))
            .toList();

        List<Path> ret = new ArrayList<>();
        for (Path rawPath : rawEnvXmlPaths)
        {
            if (Files.exists(rawPath))
            {
                if (Files.isRegularFile(rawPath))
                {
                    // just add it, nothing else to do.
                    if (rawPath.isAbsolute())
                        ret.add(rawPath);
                    else
                        ret.add(rawPath.toAbsolutePath());
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Ignoring non-file reference to environment xml: {}", rawPath);
                }
            }
            else if (!rawPath.isAbsolute())
            {
                // we have a relative defined path, try to resolve it from known locations
                if (LOG.isDebugEnabled())
                    LOG.debug("Resolving environment xml path relative reference: {}", rawPath);
                boolean found = false;
                for (Path monitoredDir : getMonitoredDirectories())
                {
                    Path resolved = monitoredDir.resolve(rawPath);
                    if (Files.isRegularFile(resolved))
                    {
                        found = true;
                        // add resolved path
                        ret.add(resolved);
                    }
                    else
                    {
                        // try resolve from parent (this is backward compatible with 12.0.0)
                        resolved = monitoredDir.getParent().resolve(rawPath);
                        if (Files.isRegularFile(resolved))
                        {
                            found = true;
                            // add resolved path
                            ret.add(resolved);
                        }
                    }
                }
                if (!found && LOG.isDebugEnabled())
                    LOG.debug("Ignoring relative environment xml path that doesn't exist: {}", rawPath);
            }
        }

        return ret;
    }

    /**
     * Load all of the {@link Environment} specific {@code <env-name>[-<name>].properties} files
     * found in the directory provided.
     *
     * <p>
     * All found properties files are first sorted by filename, then loaded one by one into
     * a single {@link Properties} instance.
     * </p>
     *
     * @param env the environment name
     */
    private Attributes loadEnvironmentAttributes(String env) throws IOException
    {
        Attributes envAttributes = Environment.get(env);
        if (envAttributes == null)
        {
            LOG.warn("Not an environment: {}", env);
            return Attributes.NULL;
        }

        Path dir = getEnvironmentsDirectory();
        if (dir == null)
        {
            // nothing to load
            return envAttributes;
        }

        if (!Files.isDirectory(dir))
        {
            LOG.warn("Not an environments directory: {}", dir);
            return envAttributes;
        }

        List<Path> envPropertyFiles;

        // Get all environment specific properties files for this environment,
        // order them according to the lexical ordering of the filenames
        try (Stream<Path> paths = Files.list(dir))
        {
            envPropertyFiles = paths.filter(Files::isRegularFile)
                .filter(p -> FileID.isExtension(p, "properties"))
                .filter(p ->
                {
                    String name = p.getFileName().toString();
                    return name.startsWith(env);
                })
                .sorted(PathCollators.byName(true))
                .toList();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Environment property files {}", envPropertyFiles);

        Attributes attributesLayer = envAttributes;

        // Load each *.properties file
        for (Path file : envPropertyFiles)
        {
            try (InputStream stream = Files.newInputStream(file))
            {
                Properties tmp = new Properties();
                tmp.load(stream);

                Attributes.Layer layer = new Attributes.Layer(attributesLayer);
                //put each property into our substitution pool
                tmp.stringPropertyNames().forEach(name ->
                {
                    String value = tmp.getProperty(name);
                    String key = stripOldAttributePrefix(name);
                    layer.setAttribute(key, value);
                });
                attributesLayer = layer;
            }
        }

        return attributesLayer;
    }

    private void startTracking(PathsApp app)
    {
        trackedApps.put(app.getName(), app);
    }

    private void stopTracking(PathsApp app)
    {
        trackedApps.remove(app.getName());
    }

    public record DeployAction(DeployAction.Type type, String name)
    {
        public enum Type
        {
            REMOVE,
            ADD
        }
    }

    /**
     * <p>The List of {@link DeployAction} sort.</p>
     *
     * <ul>
     *     <li>{@link DeployAction#type()} is sorted by all {@link DeployAction.Type#REMOVE}
     *         actions first, followed by all {@link DeployAction.Type#ADD} actions.</li>
     *     <li>{@link DeployAction.Type#REMOVE} type are in descending alphabetically order.</li>
     *     <li>{@link DeployAction.Type#ADD} type are in ascending alphabetically order.</li>
     * </ul>>
     */
    public static class DeployActionComparator implements Comparator<DeployAction>
    {
        private final Comparator<DeployAction> typeComparator;
        private final Comparator<DeployAction> basenameComparator;

        public DeployActionComparator()
        {
            typeComparator = Comparator.comparing(DeployAction::type);
            basenameComparator = Comparator.comparing(DeployAction::name);
        }

        @Override
        public int compare(DeployAction o1, DeployAction o2)
        {
            int diff = typeComparator.compare(o1, o2);
            if (diff != 0)
                return diff;
            return switch (o1.type())
            {
                case REMOVE ->
                {
                    yield basenameComparator.compare(o2, o1);
                }
                case ADD ->
                {
                    yield basenameComparator.compare(o1, o2);
                }
            };
        }
    }

    /**
     * Builder of a deployment configuration for a specific {@link Environment}.
     *
     * <p>
     * Results in {@link Attributes} for {@link Environment} containing the
     * deployment configuration (as {@link Deployable} keys) that is applied to all deployable
     * apps belonging to that {@link Environment}.
     * </p>
     */
    public static class EnvironmentConfig
    {
        // Using setters in this class to allow jetty-xml <Set name="" property="">
        // syntax to skip setting of an environment attribute if property is unset,
        // allowing the in code values to be same defaults as they are in embedded-jetty.

        private final Environment _environment;

        private EnvironmentConfig(Environment environment)
        {
            this._environment = environment;
        }

        /**
         * Load a java properties file as a set of Attributes for this Environment.
         *
         * @param path the path of the properties file
         * @throws IOException if unable to read the properties file
         */
        public void loadProperties(Path path) throws IOException
        {
            Properties props = new Properties();
            try (InputStream inputStream = Files.newInputStream(path))
            {
                props.load(inputStream);
                props.forEach((key, value) -> _environment.setAttribute((String)key, value));
            }
        }

        /**
         * Convenience method for {@code loadProperties(Path.of(pathName));}
         *
         * @param pathName the name of the path to load.
         * @throws IOException if unable to read the properties file
         * @see #loadProperties(Path)
         */
        public void loadPropertiesFromPathName(String pathName) throws IOException
        {
            loadProperties(Path.of(pathName));
        }

        /**
         * This is equivalent to setting the {@link Deployable#CONFIGURATION_CLASSES} attribute.
         *
         * @param configurations The configuration class names as a comma separated list
         * @see Deployable#CONFIGURATION_CLASSES
         */
        public void setConfigurationClasses(String configurations)
        {
            setConfigurationClasses(StringUtil.isBlank(configurations) ? null : configurations.split(","));
        }

        /**
         * This is equivalent to setting the {@link Deployable#CONFIGURATION_CLASSES} property.
         *
         * @param configurations The configuration class names.
         * @see Deployable#CONFIGURATION_CLASSES
         */
        public void setConfigurationClasses(String[] configurations)
        {
            if (configurations == null)
                _environment.removeAttribute(Deployable.CONFIGURATION_CLASSES);
            else
                _environment.setAttribute(Deployable.CONFIGURATION_CLASSES, configurations);
        }

        /**
         * This is equivalent to setting the {@link Deployable#CONTAINER_SCAN_JARS} property.
         *
         * @param pattern The regex pattern to use when bytecode scanning container jars
         * @see Deployable#CONTAINER_SCAN_JARS
         */
        public void setContainerScanJarPattern(String pattern)
        {
            _environment.setAttribute(Deployable.CONTAINER_SCAN_JARS, pattern);
        }

        /**
         * The name of the class that this environment uses to create {@link ContextHandler}
         * instances (supports a class that implements {@code java.util.function.Supplier<Handler>} as well).
         *
         * <p>
         * This is the class used to create a ContextHandler for the environment before
         * any XML files are loaded to configure the context.
         * </p>
         *
         * @param classname the classname for this environment's context deployable.
         * @see PathsContextHandlerFactory#CONTEXT_HANDLER_CLASS
         */
        public void setContextHandlerClass(String classname)
        {
            _environment.setAttribute(PathsContextHandlerFactory.CONTEXT_HANDLER_CLASS, classname);
        }

        /**
         * The name of the default class that this environment uses to create {@link ContextHandler}
         * instances (supports a class that implements {@code java.util.function.Supplier<Handler>} as well).
         *
         * <p>
         * This is the fallback class used, if the context class itself isn't defined by
         * the web application being deployed. (such as from an XML definition)
         * </p>
         *
         * @param classname the default classname for this environment's context deployable.
         * @see PathsContextHandlerFactory#CONTEXT_HANDLER_CLASS_DEFAULT
         */
        public void setDefaultContextHandlerClass(String classname)
        {
            _environment.setAttribute(PathsContextHandlerFactory.CONTEXT_HANDLER_CLASS_DEFAULT, classname);
        }

        /**
         * Set the defaultsDescriptor.
         * This is equivalent to setting the {@link Deployable#DEFAULTS_DESCRIPTOR} attribute.
         *
         * @param defaultsDescriptor the defaultsDescriptor to set
         * @see Deployable#DEFAULTS_DESCRIPTOR
         */
        public void setDefaultsDescriptor(String defaultsDescriptor)
        {
            _environment.setAttribute(Deployable.DEFAULTS_DESCRIPTOR, defaultsDescriptor);
        }

        /**
         * This is equivalent to setting the {@link Deployable#EXTRACT_WARS} attribute.
         *
         * @param extractWars the extractWars to set
         * @see Deployable#EXTRACT_WARS
         */
        public void setExtractWars(boolean extractWars)
        {
            _environment.setAttribute(Deployable.EXTRACT_WARS, extractWars);
        }

        /**
         * This is equivalent to setting the {@link Deployable#PARENT_LOADER_PRIORITY} attribute.
         *
         * @param parentLoaderPriority the parentLoaderPriority to set
         * @see Deployable#PARENT_LOADER_PRIORITY
         */
        public void setParentLoaderPriority(boolean parentLoaderPriority)
        {
            _environment.setAttribute(Deployable.PARENT_LOADER_PRIORITY, parentLoaderPriority);
        }

        /**
         * This is equivalent to setting the {@link Deployable#SCI_EXCLUSION_PATTERN} property.
         *
         * @param pattern The regex pattern to exclude ServletContainerInitializers from executing
         * @see Deployable#SCI_EXCLUSION_PATTERN
         */
        public void setServletContainerInitializerExclusionPattern(String pattern)
        {
            _environment.setAttribute(Deployable.SCI_EXCLUSION_PATTERN, pattern);
        }

        /**
         * This is equivalent to setting the {@link Deployable#SCI_ORDER} property.
         *
         * @param order The ordered list of ServletContainerInitializer classes to run
         * @see Deployable#SCI_ORDER
         */
        public void setServletContainerInitializerOrder(String order)
        {
            _environment.setAttribute(Deployable.SCI_ORDER, order);
        }

        /**
         * This is equivalent to setting the {@link Deployable#WEBINF_SCAN_JARS} property.
         *
         * @param pattern The regex pattern to use when bytecode scanning web-inf jars
         * @see Deployable#WEBINF_SCAN_JARS
         */
        public void setWebInfScanJarPattern(String pattern)
        {
            _environment.setAttribute(Deployable.WEBINF_SCAN_JARS, pattern);
        }
    }

    public static class MonitoredPathFilter implements FilenameFilter
    {
        private final List<Path> monitoredDirs;

        public MonitoredPathFilter(List<Path> monitoredDirs)
        {
            this.monitoredDirs = monitoredDirs;
        }

        @Override
        public boolean accept(File dir, String name)
        {
            if (dir == null || !dir.canRead())
                return false;

            Path path = dir.toPath().resolve(name);

            try
            {
                // ignore traditional "hidden" path entries.
                if (name.startsWith("."))
                    return false;
                // ignore path tagged as hidden by FS
                if (Files.isHidden(path))
                    return false;
            }
            catch (IOException ignore)
            {
                // ignore
            }

            if (Files.isRegularFile(path) && FileID.isExtension(name, "jar", "war", "xml", "properties"))
                return true;

            // From this point down, we looking for things that are possible directory deployments.
            if (!Files.isDirectory(path))
                return false;

            // Don't deploy monitored paths
            if (monitoredDirs.contains(path))
                return false;

            String lowerName = name.toLowerCase(Locale.ENGLISH);

            // is it a nominated config directory
            if (lowerName.endsWith(".d"))
                return true;

            // ignore source control directories
            if ("cvs".equals(lowerName) || "cvsroot".equals(lowerName))
                return false;

            return true;
        }
    }
}
