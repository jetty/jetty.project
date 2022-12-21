//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.deploy.providers;

import java.io.FilenameFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.ee.Deployable;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject("Abstract Provider for loading webapps")
public abstract class ScanningAppProvider extends ContainerLifeCycle implements AppProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(ScanningAppProvider.class);

    private final Map<Path, App> _appMap = new HashMap<>();

    private DeploymentManager _deploymentManager;
    private FilenameFilter _filenameFilter;
    private final List<Resource> _monitored = new CopyOnWriteArrayList<>();
    private int _scanInterval = 10;
    private Scanner _scanner;
    private boolean _useRealPaths;
    private String _environmentName;

    private final Scanner.DiscreteListener _scannerListener = new Scanner.DiscreteListener()
    {
        @Override
        public void pathAdded(Path path) throws Exception
        {
            ScanningAppProvider.this.pathAdded(path);
        }

        @Override
        public void pathChanged(Path path) throws Exception
        {
            ScanningAppProvider.this.pathChanged(path);
        }

        @Override
        public void pathRemoved(Path path) throws Exception
        {
            ScanningAppProvider.this.pathRemoved(path);
        }
    };

    protected ScanningAppProvider()
    {
        this(null);
    }

    protected ScanningAppProvider(FilenameFilter filter)
    {
        _filenameFilter = filter;
        addBean(_appMap);
    }

    @Override
    public String getEnvironmentName()
    {
        return _environmentName;
    }

    public void setEnvironmentName(String environmentName)
    {
        _environmentName = environmentName;
    }

    /**
     * @return True if the real path of the scanned files should be used for deployment.
     */
    public boolean isUseRealPaths()
    {
        return _useRealPaths;
    }

    /**
     * @param useRealPaths True if the real path of the scanned files should be used for deployment.
     */
    public void setUseRealPaths(boolean useRealPaths)
    {
        _useRealPaths = useRealPaths;
    }

    protected void setFilenameFilter(FilenameFilter filter)
    {
        if (isRunning())
            throw new IllegalStateException();
        _filenameFilter = filter;
    }

    /**
     * @return The index of currently deployed applications.
     */
    protected Map<Path, App> getDeployedApps()
    {
        return _appMap;
    }

    /**
     * Called by the Scanner.DiscreteListener to create a new App object.
     * Isolated in a method so that it is possible to override the default App
     * object for specialized implementations of the AppProvider.
     *
     * @param path The file that is the context.xml. It is resolved by
     * {@link org.eclipse.jetty.util.resource.ResourceFactory#newResource(String)}
     * @return The App object for this particular context definition file.
     */
    protected App createApp(Path path)
    {
        App app = new App(_deploymentManager, this, path);
        if (LOG.isDebugEnabled())
            LOG.debug("{} creating {}", this, app);

        String environmentName = app.getEnvironmentName();

        if (StringUtil.isBlank(environmentName))
        {
            // We may be able to default the environmentName
            String basename = FileID.getBasename(path);
            boolean isWebapp = FileID.isWebArchive(path) ||
                Files.isDirectory(path) && Files.exists(path.resolve("WEB-INF")) ||
                FileID.isXml(path) && (
                    Files.exists(path.getParent().resolve(basename + ".war")) ||
                        Files.exists(path.getParent().resolve(basename + ".WAR")) ||
                        Files.exists(path.getParent().resolve(basename + "/WEB-INF")));
            boolean coreProvider = _deploymentManager.getAppProviders().stream()
                .map(AppProvider::getEnvironmentName).anyMatch(Environment.CORE.getName()::equals);

            // TODO review these heuristics... or even if we should have them at all
            if (isWebapp || (Files.isDirectory(path) && _deploymentManager.getDefaultEnvironmentName() != null))
                environmentName = _deploymentManager.getDefaultEnvironmentName();
            else if (coreProvider)
                environmentName = Environment.CORE.getName();

            if (StringUtil.isNotBlank(environmentName))
            {
                app.getProperties().put(Deployable.ENVIRONMENT, environmentName);
                if (LOG.isDebugEnabled())
                    LOG.debug("{} default environment for {}", this, app);
            }
        }

        if (StringUtil.isNotBlank(environmentName))
        {
            // If the app specifies the environment for this provider, then this deployer will deploy it.
            if (Objects.equals(environmentName, getEnvironmentName()))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} created {}", this, app);
                return app;
            }

            // If we are the default provider then we may warn
            if (Objects.equals(getEnvironmentName(), _deploymentManager.getDefaultEnvironmentName()))
            {
                // if the app specified an environment name, then produce warning if there is no provider for it.
                boolean appProvider4env = _deploymentManager.getAppProviders().stream()
                    .map(AppProvider::getEnvironmentName).anyMatch(environmentName::equals);
                if (!appProvider4env)
                    LOG.warn("No AppProvider with environment {} for {}", environmentName, app);
                return null;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} ignored {}", this, app);
        return null;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}.doStart()", this.getClass().getSimpleName());
        if (_monitored.size() == 0)
            throw new IllegalStateException("No configuration dir specified");
        if (_environmentName == null)
        {
            List<Environment> nonCore = Environment.getAll().stream().filter(environment -> !environment.equals(Environment.CORE)).toList();
            if (nonCore.size() != 1)
                throw new IllegalStateException("No environment configured");
            _environmentName = nonCore.get(0).getName();
        }

        Environment environment = Environment.get(_environmentName);
        if (environment == null)
            throw new IllegalStateException("Unknown environment " + _environmentName);

        LOG.info("Deployment monitor {} in {} at intervals {}s", getEnvironmentName(), _monitored, getScanInterval());
        List<Path> files = new ArrayList<>();
        for (Resource resource : _monitored)
        {
            if (Resources.missing(resource))
            {
                LOG.warn("Does not exist: {}", resource);
                continue; // skip
            }

            // handle resource smartly
            for (Resource r: resource)
            {
                Path path = r.getPath();
                if (path == null)
                {
                    LOG.warn("Not based on FileSystem Path: {}", r);
                    continue; // skip
                }
                if (Files.isDirectory(path) || Files.isReadable(path))
                    files.add(resource.getPath());
                else
                    LOG.warn("Unsupported Path (not a directory and/or not readable): {}", r);
            }
        }

        _scanner = new Scanner(null, _useRealPaths);
        _scanner.setScanDirs(files);
        _scanner.setScanInterval(_scanInterval);
        _scanner.setFilenameFilter(_filenameFilter);
        _scanner.setReportDirs(true);
        _scanner.setScanDepth(1); //consider direct dir children of monitored dir
        _scanner.addListener(_scannerListener);

        addBean(_scanner);

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        if (_scanner != null)
        {
            removeBean(_scanner);
            _scanner.removeListener(_scannerListener);
            _scanner = null;
        }
    }

    protected boolean exists(String path)
    {
        return _scanner.exists(path);
    }

    protected void pathAdded(Path path) throws Exception
    {
        App app = ScanningAppProvider.this.createApp(path);
        if (LOG.isDebugEnabled())
            LOG.debug("fileAdded {}: {}", path, app);

        if (app != null)
        {
            _appMap.put(path, app);
            _deploymentManager.addApp(app);
        }
    }

    protected void pathChanged(Path path) throws Exception
    {
        App oldApp = _appMap.remove(path);
        if (oldApp != null)
            _deploymentManager.removeApp(oldApp);
        App app = ScanningAppProvider.this.createApp(path);
        if (LOG.isDebugEnabled())
            LOG.debug("fileChanged {}: {}", path, app);
        if (app != null)
        {
            _appMap.put(path, app);
            _deploymentManager.addApp(app);
        }
    }

    protected void pathRemoved(Path path) throws Exception
    {
        App app = _appMap.remove(path);
        if (LOG.isDebugEnabled())
            LOG.debug("fileRemoved {}: {}", path, app);
        if (app != null)
            _deploymentManager.removeApp(app);
    }

    /**
     * Get the deploymentManager.
     *
     * @return the deploymentManager
     */
    public DeploymentManager getDeploymentManager()
    {
        return _deploymentManager;
    }

    public Resource getMonitoredDirResource()
    {
        if (_monitored.size() == 0)
            return null;
        if (_monitored.size() > 1)
            throw new IllegalStateException();
        return _monitored.get(0);
    }

    public String getMonitoredDirName()
    {
        Resource resource = getMonitoredDirResource();
        return resource == null ? null : resource.toString();
    }

    @ManagedAttribute("scanning interval to detect changes which need reloaded")
    public int getScanInterval()
    {
        return _scanInterval;
    }

    @Override
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _deploymentManager = deploymentManager;
    }

    public void setMonitoredResources(List<Resource> resources)
    {
        _monitored.clear();
        if (resources == null)
            return;
        resources.stream().filter(Objects::nonNull).forEach(_monitored::add);
    }

    public List<Resource> getMonitoredResources()
    {
        return Collections.unmodifiableList(_monitored);
    }

    public void setMonitoredDirResource(Resource resource)
    {
        setMonitoredResources(Collections.singletonList(resource));
    }

    public void addScannerListener(Scanner.Listener listener)
    {
        _scanner.addListener(listener);
    }

    /**
     * @param dir Directory to scan for context descriptors or war files
     */
    public void setMonitoredDirName(String dir)
    {
        setMonitoredDirectories(Collections.singletonList(dir));
    }

    public void setMonitoredDirectories(Collection<String> directories)
    {
        try
        {
            List<Resource> resources = new ArrayList<>();
            for (String dir : directories)
            {
                resources.add(ResourceFactory.of(this).newResource(dir));
            }
            setMonitoredResources(resources);
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    public void setScanInterval(int scanInterval)
    {
        _scanInterval = scanInterval;
    }

    @ManagedOperation(value = "Scan the monitored directories", impact = "ACTION")
    public void scan()
    {
        LOG.info("Performing scan of monitored directories: {}",
            getMonitoredResources().stream().map((r) -> r.getURI().toASCIIString())
                .collect(Collectors.joining(", ", "[", "]"))
        );
        _scanner.nudge();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x%s", this.getClass(), hashCode(), _monitored);
    }
}
