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
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.PathCollators;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Jetty WebApp Hot Deployment Scanner.</p>
 *
 * <p>This class scans one or more directories (typically "webapps") for web applications to
 * deploy, which may be:</p>
 * <ul>
 *     <li>A standard WAR file (must end in ".war")</li>
 *     <li>A directory containing an expanded WAR file</li>
 *     <li>A directory containing static content</li>
 *     <li>An XML descriptor in {@link XmlConfiguration} format that configures a {@link ContextHandler} instance</li>
 * </ul>
 * Once a collection of files that represent a web application is found (or updated), an instance of `{@link ContextHandlerFactory}
 * is used to create a {@link ContextHandler}, which is then deployed/undeployed via an {@link Deployer} instance.
 * The instances of the {@link Deployer} and {@link ContextHandlerFactory} used can be:<ul>
 *     <li>passed into a constructor;</li>
 *     <li>or, discovered as a singleton {{@link org.eclipse.jetty.util.component.Container#getBean(Class)} bean} on
 *     the {@link Server};</li>
 *     <li>or, a default implementation instantiated by this scanner.</li>
 * </ul>
 * <p>To avoid double deployments and allow flexibility of the content of the scanned directories, the provider
 * implements some heuristics to ignore some files found in the scans:
 * </p>
 * <ul>
 *     <li>Hidden files (starting with {@code "."}) are ignored</li>
 *     <li>Directories with names ending in {@code ".d"} are ignored</li>
 *     <li>Property files with names ending in {@code ".properties"} are not deployed.</li>
 *     <li>If a directory and a WAR file exist (eg: {@code foo/} and {@code foo.war}) then the directory is assumed to be
 * the unpacked WAR and only the WAR file is deployed (which may reuse the unpacked directory)</li>
 *     <li>If a directory and a matching XML file exist (eg: {@code foo/} and {@code foo.xml}) then the directory is assumed to be
 * an unpacked WAR and only the XML file is deployed (which may use the directory in its configuration)</li>
 *     <li>If a WAR file and a matching XML file exist (eg: {@code foo.war} and {@code foo.xml}) then the WAR file is assumed to
 * be configured by the XML file and only the XML file is deployed.
 * </ul>
 * <p>For XML configured contexts, the following is available.</p>
 * <ul>
 * <li>The XML Object ID Map will have a reference to the {@link Server} instance via the ID name {@code "Server"}</li>
 * <li>The Default XML Properties are populated from a call to {@link XmlConfiguration#setJettyStandardIdsAndProperties(Object, Path)} (for things like {@code jetty.home} and {@code jetty.base})</li>
 * <li>An extra XML Property named {@code "jetty.webapps"} is available, and points to the webapps path.</li>
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
@ManagedObject("Provider for dynamic deployment of contexts (and webapps) based on presence in directory")
public class DeploymentScanner extends ContainerLifeCycle implements Scanner.BulkListener
{
    private static final Logger LOG = LoggerFactory.getLogger(DeploymentScanner.class);
    // old attributes prefix, now stripped.
    private static final String ATTRIBUTE_PREFIX = "jetty.deploy.attribute.";

    private static final Pattern EE_ENVIRONMENT_NAME_PATTERN = Pattern.compile("ee(\\d+)");

    /**
     * A comparator that ranks names matching EE_ENVIRONMENT_NAME_PATTERN higher than other names,
     * EE names are compared by EE number, otherwise simple name comparison is used.
     */
    protected static final Comparator<String> ENVIRONMENT_COMPARATOR = (e1, e2) ->
    {
        Matcher m1 = EE_ENVIRONMENT_NAME_PATTERN.matcher(e1);
        Matcher m2 = EE_ENVIRONMENT_NAME_PATTERN.matcher(e2);

        if (m1.matches())
        {
            if (m2.matches())
            {
                int n1 = Integer.parseInt(m1.group(1));
                int n2 = Integer.parseInt(m2.group(1));
                return Integer.compare(n2, n1);
            }
            return -1;
        }
        if (m2.matches())
            return 1;

        return e1.compareTo(e2);
    };

    private final Server server;
    private final FilenameFilter filenameFilter;
    private final List<Path> webappDirs = new CopyOnWriteArrayList<>();
    private final ContextHandlerFactory contextHandlerFactory;
    private final Map<String, PathsApp> trackedApps = new HashMap<>();
    private final Map<String, Attributes> environmentAttributesMap = new HashMap<>();
    private Set<String> trackedEnvironments = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    private Deployer deployer;
    private Comparator<DeployAction> actionComparator = new DeployActionComparator();
    private Path environmentsDir;
    private int scanInterval = 0;
    private Scanner scanner;
    private boolean useRealPaths;
    private boolean deferInitialScan = false;
    private String defaultEnvironmentName;

    /**
     * <p>
     * Construct a raw DeploymentScanner that will (periodically) scan specific directories for paths that can be
     * used to construct webapps that will be submitted to the {@link Deployer} for eventual deployment to
     * it's configured destination.
     * </p>
     *
     * @param server the server reference to use for any XML based deployments.
     */
    public DeploymentScanner(@Name("server") Server server)
    {
        this(server, null, null, null);
    }

    /**
     * <p>
     * Construct a raw DeploymentScanner that will (periodically) scan specific directories for paths that can be
     * used to construct webapps that will be submitted to the {@link Deployer} for eventual deployment to
     * it's configured destination.
     * </p>
     *
     * @param server the server reference to use for any XML based deployments.
     * @param deployer the {@link Deployer} to use for deploying the created {@link ContextHandler}s,
     *                 or {@code null} for a default.
     */
    public DeploymentScanner(
        @Name("server") Server server,
        @Name("deployer") Deployer deployer)
    {
        this(server, deployer, null, null);
    }

    /**
     * <p>
     * Construct a raw DeploymentScanner that will (periodically) scan specific directories for paths that can be
     * used to construct webapps that will be submitted to the {@link Deployer} for eventual deployment to
     * it's configured destination.
     * </p>
     *
     * @param server the server reference to use for any XML based deployments.
     * @param deployer the {@link Deployer} to use for deploying the created {@link ContextHandler}s,
     *                 or {@code null} for a default.
     * @param filter A custom {@link FilenameFilter} to control what files the {@link Scanner} monitors for changes,
     *               or {@code null} for a default.
     */
    public DeploymentScanner(
        @Name("server") Server server,
        @Name("deployer") Deployer deployer,
        @Name("filenameFilter") FilenameFilter filter)
    {
        this(server, deployer, filter, null);
    }

    /**
     * <p>
     * Construct a raw DeploymentScanner that will (periodically) scan specific directories for paths that can be
     * used to construct webapps that will be submitted to the {@link Deployer} for eventual deployment to
     * it's configured destination.
     * </p>
     *
     * @param server the server reference to use for any XML based deployments.
     * @param deployer the {@link Deployer} to use for deploying the created {@link ContextHandler}s,
     *                 or {@code null} for a default.
     * @param contextHandlerFactory The factory to use to create {@link ContextHandler}s,
     *                              or {@code null} for a default.
     */
    public DeploymentScanner(
        @Name("server") Server server,
        @Name("deployer") Deployer deployer,
        @Name("contextHandlerFactory") ContextHandlerFactory contextHandlerFactory)
    {
        this(server, deployer, null, contextHandlerFactory);
    }

    /**
     * <p>
     * Construct a raw DeploymentScanner that will (periodically) scan specific directories for paths that can be
     * used to construct webapps that will be submitted to the {@link Deployer} for eventual deployment to
     * it's configured destination.
     * </p>
     *
     * @param server the server reference to use for any XML based deployments.
     * @param deployer the {@link Deployer} to use for deploying the created {@link ContextHandler}s,
     *                 or {@code null} for a default.
     * @param filter A custom {@link FilenameFilter} to control what files the {@link Scanner} monitors for changes,
     *               or {@code null} for a default
     * @param contextHandlerFactory The factory to use to create {@link ContextHandler}s,
     *                              or {@code null} for a default.
     */
    public DeploymentScanner(
        @Name("server") Server server,
        @Name("deployer") Deployer deployer,
        @Name("filenameFilter") FilenameFilter filter,
        @Name("contextHandlerFactory") ContextHandlerFactory contextHandlerFactory)
    {
        this.contextHandlerFactory = contextHandlerFactory == null ? new StandardContextHandlerFactory() : contextHandlerFactory;
        installBean(this.contextHandlerFactory);
        this.server = Objects.requireNonNull(server);
        this.deployer = deployer == null ? server.getBean(Deployer.class) : deployer;
        installBean(deployer);
        this.filenameFilter = Objects.requireNonNullElse(filter, new WebappsPathFilter(webappDirs));
        installBean(new DumpableCollection("webappDirs", webappDirs));
    }

    /**
     * @param dir Directory to scan for deployable artifacts
     */
    public void addWebappsDirectory(Path dir)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Adding webapps directory: {}", dir);
        if (isStarted())
            throw new IllegalStateException("Unable to add webapps directory while running");
        webappDirs.add(Objects.requireNonNull(dir));
    }

    /**
     * Add a {@link LifeCycle.Listener} to this scanner, to be notified of files scanned.
     * Primarily used for testing.
     * @param listener The listener to add.
     */
    void addScannerListener(Scanner.Listener listener)
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
        Environment environment = Environment.get(name);
        // Check to make sure that the Environment was created before jetty-deploy is involved.
        // This is to ensure that the Environment ClassLoader is setup properly.
        if (environment == null)
            throw new IllegalStateException("Environment [" + name + "] does not exist.");
        trackedEnvironments.add(name);
        return new EnvironmentConfig(environment);
    }

    /**
     * @return The {@link Comparator} used to sort the {@link DeployAction}s before acting on them.
     */
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
     * Only uses environments that have been previously configured with {@link #configureEnvironment(String)}
     * </p>
     *
     * @return the default environment name.
     */
    public String getDefaultEnvironmentName()
    {
        if (defaultEnvironmentName == null)
        {
            return trackedEnvironments.stream()
                .min(ENVIRONMENT_COMPARATOR)
                .orElse(null);
        }
        return defaultEnvironmentName;
    }

    public void setDefaultEnvironmentName(String name)
    {
        this.defaultEnvironmentName = name;
    }

    /**
     * @return The {@link Path} of the directory to scan for environment configuration files,
     *         or {@code null}
     */
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

    /**
     * @return The {@link List} of {@link Path}s scanned for files to deploy.
     */
    public List<Path> getWebappDirectories()
    {
        return webappDirs;
    }

    public void setWebappDirectories(Collection<Path> directories)
    {
        if (isStarted())
            throw new IllegalStateException("Unable to add webapp directories while running");

        webappDirs.clear();

        for (Path dir : directories)
        {
            addWebappsDirectory(dir);
        }
    }

    /**
     * @return scan interval (in seconds) to detect changes which need reloaded
     * @see Scanner#getScanInterval()
     */
    @ManagedAttribute("scanning interval to detect changes which need reloaded")
    public int getScanInterval()
    {
        return scanInterval;
    }

    /**
     * @param scanInterval scan interval (in seconds) to detect changes which need reloaded
     * @see Scanner#setScanInterval(int)
     */
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
     * If {@link Scanner} is configured to {@code reportRealPaths}.
     *
     * @return True if the real path of the scanned files should be used for deployment.
     * @see Scanner and {@code reportRealPaths} constructor variable.
     */
    public boolean isUseRealPaths()
    {
        return useRealPaths;
    }

    /**
     * Tells {@link Scanner} to {@code reportRealPaths}.
     *
     * @param useRealPaths True if the real path of the scanned files should be used for deployment.
     * @see Scanner and {@code reportRealPaths} constructor variable.
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

            if (isWebappsPath(path))
            {
                // we have a normal path entry
                changedBaseNames.add(basename);
                PathsApp app = trackedApps.computeIfAbsent(basename, PathsApp::new);
                app.putPath(path, state);
            }
            else if (isEnvironmentConfigPath(path))
            {
                String envname = null;

                for (Environment environment : Environment.getAll())
                {
                    String name = environment.getName();
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

    @ManagedOperation(value = "Scan the webapps directories", impact = "ACTION")
    public void scan()
    {
        LOG.info("Performing scan of webapps directories: {}",
            webappDirs.stream()
                .map(Path::toUri)
                .map(URI::toASCIIString)
                .collect(Collectors.joining(", ", "[", "]"))
        );
        scanner.nudge();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[dirs=%s]", this.getClass(), hashCode(), webappDirs);
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
                    actions.add(new DeployAction(DeployAction.Type.DEPLOY, app.getName()));
                }
                case CHANGED ->
                {
                    actions.add(new DeployAction(DeployAction.Type.REDEPLOY, app.getName()));
                }
                case REMOVED ->
                {
                    actions.add(new DeployAction(DeployAction.Type.UNDEPLOY, app.getName()));
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

        if (deployer == null)
        {
            deployer = server.getBean(Deployer.class);
            if (deployer == null)
            {
                Collection<ContextHandlerCollection> chcs = server.getContainedBeans(ContextHandlerCollection.class);
                if (chcs.size() == 1)
                {
                    deployer = new StandardDeployer(chcs.iterator().next());
                    addBean(deployer, true);
                    LifeCycle.start(deployer);
                }
            }

            if (deployer == null)
                throw new IllegalStateException("No deployer available");
        }

        if (webappDirs.isEmpty())
            throw new IllegalStateException("No webapps dir specified");

        LOG.info("Deployment monitoring of {} at intervals {}s {}", webappDirs, getScanInterval(), getScanInterval() <= 0 ? "(hot-redeploy disabled)" : "");

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
        for (Path dir : webappDirs)
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
            // triggers the first scan of the webapps directories
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

        if (!isSameDir(environmentsDir, path.getParent()))
            return false;

        return FileID.isExtension(path, "xml", "properties");
    }

    protected boolean isWebappsPath(Path path)
    {
        Path parentDir = path.getParent();
        for (Path dir : webappDirs)
        {
            if (isSameDir(dir, parentDir))
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
                    case UNDEPLOY ->
                    {
                        // Track removal
                        removedApps.add(app);
                        deployer.undeploy(app.getContextHandler());
                    }
                    case DEPLOY ->
                    {
                        // Undo tracking for prior removal in this list of actions.
                        removedApps.remove(app);

                        // Load <basename>.properties into app.
                        app.loadProperties();

                        // Ensure Environment name is set
                        String envName = app.getEnvironmentName();
                        if (StringUtil.isBlank(envName))
                            envName = getDefaultEnvironmentName();
                        Environment env = Environment.get(envName);

                        if (env == null || !trackedEnvironments.contains(envName))
                            throw new IllegalArgumentException("Unable to deploy %s to environment %s.  Available Environments to deploy to %s"
                                .formatted(app.name, envName, trackedEnvironments.stream().sorted(ENVIRONMENT_COMPARATOR)
                                    .collect(Collectors.joining(", ", "[", "]"))));

                        // Create a new Attributes layer for the app deployment, which is the
                        // combination of layered Environment Attributes with app Attributes overlaying them.
                        Attributes envAttributes = environmentAttributesMap.get(envName);
                        Attributes deployAttributes = envAttributes == null ? app.getAttributes() : new Attributes.Layer(envAttributes, app.getAttributes());

                        // Create the Context Handler
                        Path mainPath = app.getMainPath();
                        if (mainPath == null)
                            throw new IllegalStateException("Unable to create ContextHandler for app with no main path defined: " + app);
                        ContextHandler contextHandler = contextHandlerFactory.newContextHandler(server, env, mainPath, app.getPaths().keySet(), deployAttributes);
                        app.setContextHandler(contextHandler);

                        // Introduce the ContextHandler to the Deployer
                        startTracking(app);
                        deployer.deploy(app.getContextHandler());
                    }

                    case REDEPLOY ->
                    {
                        // Undo tracking for prior removal in this list of actions.
                        ContextHandler oldContextHandler = app.getContextHandler();

                        // Load <basename>.properties into app.
                        app.loadProperties();

                        // Ensure Environment name is set
                        String envName = app.getEnvironmentName();
                        if (StringUtil.isBlank(envName))
                            envName = getDefaultEnvironmentName();
                        Environment env = Environment.get(envName);

                        if (env == null || !trackedEnvironments.contains(envName))
                            throw new IllegalArgumentException("Unable to deploy app [%s] to environment [%s].  Available Environments to deploy to %s"
                                .formatted(app.name, envName, trackedEnvironments.stream().sorted(ENVIRONMENT_COMPARATOR)
                                    .collect(Collectors.joining(", ", "[", "]"))));

                        // Create a new Attributes layer for the app deployment, which is the
                        // combination of layered Environment Attributes with app Attributes overlaying them.
                        Attributes envAttributes = environmentAttributesMap.get(envName);
                        Attributes deployAttributes = envAttributes == null ? app.getAttributes() : new Attributes.Layer(envAttributes, app.getAttributes());

                        // Create the Context Handler
                        Path mainPath = app.getMainPath();
                        if (mainPath == null)
                            throw new IllegalStateException("Unable to create ContextHandler for app with no main path defined: " + app);
                        ContextHandler contextHandler = contextHandlerFactory.newContextHandler(server, env, mainPath, app.getPaths().keySet(), deployAttributes);
                        app.setContextHandler(contextHandler);

                        // Introduce the ContextHandler to the Deployer
                        startTracking(app);
                        deployer.redeploy(oldContextHandler, app.getContextHandler());
                    }
                }
            }
            catch (Throwable t)
            {
                LOG.warn("Failed to to perform action {} on {}", step.type(), app, t);
                ExceptionUtil.ifExceptionThrowUnchecked(t);
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

        List<Path> envPropertyFiles = new ArrayList<>();
        List<Path> envXmlFiles = new ArrayList<>();

        // Get all environment specific xml and properties files for this environment,
        // order them according to the lexical ordering of the filenames
        try (Stream<Path> paths = Files.list(dir))
        {
            paths.filter(Files::isRegularFile)
                .filter(p -> FileID.isExtension(p, "properties", "xml"))
                .filter(p ->
                {
                    String name = p.getFileName().toString();
                    return name.startsWith(env);
                })
                .sorted(PathCollators.byName(true))
                .forEach(file ->
                {
                    if (FileID.isExtension(file, "properties"))
                        envPropertyFiles.add(file);
                    else if (FileID.isExtension(file, "xml"))
                        envXmlFiles.add(file);
                });
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Environment property files {}", envPropertyFiles);
            LOG.debug("Environment XML files {}", envXmlFiles);
        }

        Attributes envLayer = new Attributes.Layer(envAttributes);

        // Add the XML to the env layer
        envLayer.setAttribute(ContextHandlerFactory.ENVIRONMENT_XML_PATHS_ATTRIBUTE, envXmlFiles);

        // Load each *.properties file
        for (Path file : envPropertyFiles)
        {
            loadPropertiesIntoAttributes(file, envLayer);
        }

        return envLayer;
    }

    private static void loadPropertiesIntoAttributes(Path propFile, Attributes attributes)
    {
        try (InputStream inputStream = Files.newInputStream(propFile))
        {
            Properties props = new Properties();
            props.load(inputStream);
            for (String name : props.stringPropertyNames())
            {
                // Get value (before possible name cleanup)
                String value = props.getProperty(name);
                // Check (and possibly cleanup) key name
                if (name.startsWith(ATTRIBUTE_PREFIX))
                {
                    LOG.warn("Deprecated Attribute Key prefix in use: {} (will be stripped, future support not certain)", name);
                    name = name.substring(ATTRIBUTE_PREFIX.length());
                }
                if (name.startsWith("jetty.deploy.environmentXml.") || name.equals("jetty.deploy.environmentXml"))
                {
                    LOG.warn("Deprecated Attribute Key prefix in use: {} (Key ignored, use ${jetty.base}/environments/*.xml instead)", name);
                    continue; // skip, don't save this key in attributes.
                }
                attributes.setAttribute(name, value);
            }
        }
        catch (IOException e)
        {
            LOG.warn("Unable to read properties file: {}", propFile, e);
        }
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
            UNDEPLOY,
            REDEPLOY,
            DEPLOY
        }
    }

    /**
     * <p>The List of {@link DeployAction} sort.</p>
     *
     * <ul>
     *     <li>{@link DeployAction#type()} is sorted by all {@link DeployAction.Type#UNDEPLOY}
     *         actions first, followed by all {@link DeployAction.Type#DEPLOY} actions.</li>
     *     <li>{@link DeployAction.Type#UNDEPLOY} type are in descending alphabetically order.</li>
     *     <li>{@link DeployAction.Type#DEPLOY} type are in ascending alphabetically order.</li>
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
                case UNDEPLOY -> basenameComparator.compare(o2, o1);
                case REDEPLOY, DEPLOY -> basenameComparator.compare(o1, o2);
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
         * @see StandardContextHandlerFactory#CONTEXT_HANDLER_CLASS_ATTRIBUTE
         */
        public void setContextHandlerClass(String classname)
        {
            _environment.setAttribute(ContextHandlerFactory.CONTEXT_HANDLER_CLASS_ATTRIBUTE, classname);
        }

        /**
         * The name of the default class that this environment uses to create {@link ContextHandler}
         * instances (supports a class that implements {@code java.util.function.Supplier<Handler>} as well).
         *
         * <p>
         * This is the fallback class used, if the context class itself isn't defined by
         * the web application being deployed. (such as from an XML definition)
         * </p>
         * @param classname the default classname for this environment's context deployable.
         * @see StandardContextHandlerFactory#CONTEXT_HANDLER_CLASS_DEFAULT_ATTRIBUTE
         */
        public void setDefaultContextHandlerClass(String classname)
        {
            _environment.setAttribute(ContextHandlerFactory.CONTEXT_HANDLER_CLASS_DEFAULT_ATTRIBUTE, classname);
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

    public static class WebappsPathFilter implements FilenameFilter
    {
        private final List<Path> webappDirs;

        public WebappsPathFilter(List<Path> webappDirs)
        {
            this.webappDirs = webappDirs;
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

            // From this point down, we are looking for things that are possible directory deployments.
            if (!Files.isDirectory(path))
                return false;

            // Don't deploy monitored paths
            if (webappDirs.contains(path))
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

    /**
     * A representation of all the filesystem components that are used to
     * create a {@link ContextHandler}
     */
    protected static class PathsApp
    {
        public enum State
        {
            UNCHANGED,
            ADDED,
            CHANGED,
            REMOVED
        }

        private static final Logger LOG = LoggerFactory.getLogger(PathsApp.class);
        private final String name;
        private final Map<Path, PathsApp.State> paths = new HashMap<>();
        private final Attributes attributes = new Attributes.Mapped();
        private Path mainPath;
        private PathsApp.State state;
        private ContextHandler contextHandler;

        public PathsApp(String name)
        {
            this.name = name;
            this.state = calcState();
        }

        private static String asStringList(Collection<Path> paths)
        {
            return paths.stream()
                .sorted(PathCollators.byName(true))
                .map(Path::toString)
                .collect(Collectors.joining(", ", "[", "]"));
        }

        @Override
        public boolean equals(Object o)
        {
            if (o == null || getClass() != o.getClass())
                return false;
            PathsApp that = (PathsApp)o;
            return Objects.equals(name, that.name);
        }

        public Attributes getAttributes()
        {
            return this.attributes;
        }

        public ContextHandler getContextHandler()
        {
            return contextHandler;
        }

        public void setContextHandler(ContextHandler contextHandler)
        {
            this.contextHandler = contextHandler;
        }

        public String getEnvironmentName()
        {
            Object obj = this.attributes.getAttribute(ContextHandlerFactory.ENVIRONMENT_ATTRIBUTE);
            if (obj instanceof String str)
                return str;
            if (obj instanceof Environment env)
                return env.getName();
            return null;
        }

        /**
         * Get the main path used for deployment.
         * <p>
         * Applies the heuristics reference in the main
         * javadoc for {@link DeploymentScanner}
         * </p>
         *
         * @return the main deployable path
         */
        public Path getMainPath()
        {
            return mainPath;
        }

        /**
         * Arbitrarily set the Main Path for the PathApp.
         *
         * <p>
         * Note: this value can be overridden by calls to {@link #putPath(Path, State)}, and
         * subsequent recalculation of the Main Path.
         * </p>
         *
         * @param mainPath the main path.
         */
        public void setMainPath(Path mainPath)
        {
            this.mainPath = mainPath;
        }

        private Path filterPath(List<Path> paths, String type, Predicate<Path> predicate)
        {
            List<Path> hits = paths.stream()
                .filter(predicate)
                .toList();
            if (hits.size() == 1)
                return hits.get(0);
            else if (hits.size() > 1)
                throw new IllegalStateException("More than 1 " + type + " for deployable " + asStringList(hits));

            return null;
        }

        private Path calcMainPath()
        {
            List<Path> livePaths = paths
                .entrySet()
                .stream()
                .filter((e) -> e.getValue() != PathsApp.State.REMOVED)
                .map(Map.Entry::getKey)
                .sorted(PathCollators.byName(true))
                .toList();

            if (livePaths.isEmpty())
                return null;

            // XML always win.
            Path xml = filterPath(livePaths, "XML", FileID::isXml);
            if (xml != null)
                return xml;

            // WAR files are next.
            Path war = filterPath(livePaths, "WAR", FileID::isWebArchive);
            if (war != null)
                return war;

            // JAR files are next.
            Path jar = filterPath(livePaths, "JAR", FileID::isJavaArchive);
            if (jar != null)
                return jar;

            // ZIP files are next.
            Path zip = filterPath(livePaths, "ZIP", (p -> FileID.isExtension(p, "zip")));
            if (zip != null)
                return zip;

            // Directories next.
            Path dir = filterPath(livePaths, "Directory", PathsApp::isDeployableDirectory);
            if (dir != null)
                return dir;

            // Finally properties files
            Path propertyFile = filterPath(livePaths, "Property File", (p -> FileID.isExtension(p, "properties")));
            if (propertyFile != null)
                return propertyFile;

            if (LOG.isDebugEnabled())
                LOG.debug("Unable to determine main deployable for {}", this);

            return null;
        }

        private static boolean isDeployableDirectory(Path p)
        {
            if (p == null)
                return false;
            if (Files.isDirectory(p))
            {
                return !FileID.isExtension(p, "d"); // ignore nominated dirs
            }
            return false;
        }

        public String getName()
        {
            return name;
        }

        public Map<Path, PathsApp.State> getPaths()
        {
            return paths;
        }

        public PathsApp.State getState()
        {
            return state;
        }

        public void setState(PathsApp.State state)
        {
            this.state = state;
        }

        @Override
        public int hashCode()
        {
            return Objects.hashCode(name);
        }

        /**
         * Load all {@code properties} files belonging to this PathsApp
         * into its {@link Attributes}.
         *
         * @see #getAttributes()
         */
        public void loadProperties()
        {
            // look for properties file for main basename.
            String propFilename = String.format("%s.properties", getName());
            List<Path> propFiles = paths.keySet().stream()
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equalsIgnoreCase(propFilename))
                .sorted(PathCollators.byName(true))
                .toList();

            if (propFiles.isEmpty())
            {
                // No properties file found
                return;
            }

            if (propFiles.size() > 1)
            {
                LOG.warn("Multiple matching files with name [{}]: {}", propFilename,
                    asStringList(propFiles));
            }

            for (Path propFile : propFiles)
            {
                loadPropertiesIntoAttributes(propFile, getAttributes());
            }

            // Verify that environment exists
            Object envObj = getAttributes().getAttribute(ContextHandlerFactory.ENVIRONMENT_ATTRIBUTE);
            if (envObj != null)
            {
                if (envObj instanceof String environmentName)
                {
                    if (StringUtil.isNotBlank(environmentName))
                    {
                        Environment env = Environment.get(environmentName);
                        if (env == null)
                            LOG.warn("Environment not found {}", environmentName);
                    }
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Unable to use attribute {} as type {}", ContextHandlerFactory.ENVIRONMENT_ATTRIBUTE, envObj.getClass().getName());
                }
            }
        }

        public void putPath(Path path, PathsApp.State state)
        {
            this.paths.put(path, state);
            setState(calcState());
            setMainPath(calcMainPath());
        }

        public void resetStates()
        {
            // Drop paths that were removed.
            List<Path> removedPaths = paths.entrySet()
                .stream().filter(e -> e.getValue() == PathsApp.State.REMOVED)
                .map(Map.Entry::getKey)
                .toList();
            for (Path removedPath : removedPaths)
            {
                paths.remove(removedPath);
            }
            // Set all remaining path states to UNCHANGED
            paths.replaceAll((p, v) -> PathsApp.State.UNCHANGED);
            state = calcState();
        }

        @Override
        public String toString()
        {
            StringBuilder str = new StringBuilder("%s@%x".formatted(TypeUtil.toShortName(this.getClass()), hashCode()));
            str.append("[").append(name);
            str.append("|").append(getState());
            str.append(", env=").append(getEnvironmentName());
            str.append(", mainPath=").append(getMainPath());
            str.append(", paths=");
            str.append(paths.entrySet().stream()
                .map((e) -> String.format("%s|%s", e.getKey(), e.getValue()))
                .collect(Collectors.joining(", ", "[", "]"))
            );
            str.append(", contextHandler=");
            if (contextHandler == null)
                str.append("<unset>");
            else
                str.append(contextHandler);
            str.append("]");
            return str.toString();
        }

        /**
         * <p>
         * Calculate the State of the overall State based on the States in the Paths.
         * </p>
         * <dl>
         * <dt>UNCHANGED</dt>
         * <dd>All Path states are in UNCHANGED state</dd>
         * <dt>ADDED</dt>
         * <dd>All Path states are in ADDED state</dd>
         * <dt>CHANGED</dt>
         * <dd>At least one Path state is CHANGED, or there is a variety of states</dd>
         * <dt>REMOVED</dt>
         * <dd>All Path states are in REMOVED state, or there are no Paths being tracked</dd>
         * </dl>
         *
         * @return the state.
         */
        private PathsApp.State calcState()
        {
            if (paths.isEmpty())
                return PathsApp.State.REMOVED;

            // Calculate state of unit from Path states.
            PathsApp.State ret = null;
            for (PathsApp.State pathState : paths.values())
            {
                switch (pathState)
                {
                    case UNCHANGED ->
                    {
                        if (ret == null)
                            ret = PathsApp.State.UNCHANGED;
                        else if (ret != PathsApp.State.UNCHANGED)
                            ret = PathsApp.State.CHANGED;
                    }
                    case ADDED ->
                    {
                        if (ret == null)
                            ret = PathsApp.State.ADDED;
                        else if (ret != PathsApp.State.ADDED)
                            ret = PathsApp.State.CHANGED;
                    }
                    case CHANGED ->
                    {
                        ret = PathsApp.State.CHANGED;
                    }
                    case REMOVED ->
                    {
                        if (ret == null)
                            ret = PathsApp.State.REMOVED;
                        else if (ret != PathsApp.State.REMOVED)
                            ret = PathsApp.State.CHANGED;
                    }
                }
            }
            return ret != null ? ret : PathsApp.State.UNCHANGED;
        }
    }
}
