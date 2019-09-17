//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.maven.plugin.service;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.collect.Sets;
import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.eclipse.jetty.maven.plugin.JettyWebAppContext;
import org.eclipse.jetty.maven.plugin.Overlay;
import org.eclipse.jetty.maven.plugin.OverlayConfig;
import org.eclipse.jetty.maven.plugin.helper.MavenProjectHelper;
import org.eclipse.jetty.maven.plugin.resource.SelectivePathResource;
import org.eclipse.jetty.maven.plugin.utils.OverlayUtils;
import org.eclipse.jetty.maven.plugin.utils.PathPatternUtils;
import org.eclipse.jetty.maven.plugin.utils.ResourceUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;

public class WebAppDependencyResolutionService
{
    private final MavenProjectHelper mavenProjectHelper;
    private final boolean useTestScope;
    private final MavenProject rootProject;

    public WebAppDependencyResolutionService(MavenProject project, boolean useTestScope)
    {
        this.mavenProjectHelper = new MavenProjectHelper(project);
        this.rootProject = project;
        this.useTestScope = useTestScope;
    }

    public void configureDependencies(JettyWebAppContext webApp) throws Exception
    {
        JettyWebAppContext webAppContext = configureWebApplication(new OverlayConfig(), rootProject);
        webAppContext.getWebInfLib().stream()
            .map(ResourceUtils::flattenResourceCollection)
            .forEach(webApp.getWebInfLib()::addAll);
        List<Resource> baseResources = ResourceUtils.flattenResourceCollection(webAppContext.getBaseResource());

        // position "base resource" of current maven project
        if (!baseResources.contains(webApp.getBaseResource()) && (webApp.getBaseResource() != null && webApp.getBaseResource().exists()))
        {
            if (webApp.getBaseAppFirst())
            {
                baseResources.add(0, webApp.getBaseResource());
            }
            else
            {
                baseResources.add(webApp.getBaseResource());
            }
        }
        webApp.setBaseResource(new ResourceCollection(Sets.newLinkedHashSet(baseResources).toArray(new Resource[0])));
        webAppContext.getShiftedTargetResources().forEach((v, k) ->
        {
            k.forEach(e -> webApp.shiftTargetResource(v, Resource.newResource(e)));
        });
    }

    private JettyWebAppContext configureWebApplication(OverlayConfig config, MavenProject project) throws Exception
    {
        JettyWebAppContext projectWebApp = new JettyWebAppContext();
        List<Overlay> projectOverlays = mavenProjectHelper.getOverlaysForChildProject(project);
        Map<Overlay, Resource> overlayToUnpackedResMap = projectOverlays.stream()
            .filter(e -> !e.isMavenProject())
            .collect(Collectors.toMap(Function.identity(), e -> unpackOverlay(rootProject, e)));
        Set<Resource> webInfLibClasses = new LinkedHashSet<>();
        Set<Resource> baseResources = new LinkedHashSet<>();
        for (Overlay overlay : projectOverlays)
        {
            final Artifact overlayArtifact = overlay.getArtifact();
            final MavenProject overlayProject = mavenProjectHelper.getMavenProject(overlayArtifact);
            // expand referenced war project
            if (overlayArtifact != project.getArtifact() && "war".equals(overlayArtifact.getType()) && overlay.isMavenProject())
            {
                JettyWebAppContext childCtx = configureWebApplication(OverlayUtils.mergeOverlayConfig(config, overlay.getConfig()), overlayProject);
                childCtx.getShiftedTargetResources().forEach((v, k) ->
                {
                    k.forEach(e -> projectWebApp.shiftTargetResource(v, Resource.newResource(e)));
                });
                webInfLibClasses.addAll(childCtx.getWebInfLib());
                baseResources.add(childCtx.getBaseResource());
                continue;
            }
            // WEB-INF/lib & WEB-INF/classes
            Set<Resource> libs = configureWebInfLib(projectWebApp, overlay, OverlayUtils.mergeOverlayConfig(config, overlay.getConfig()), overlayToUnpackedResMap);
            if (config.getExcludes() != null || config.getIncludes() != null)
            {
                libs = libs.stream()
                    .map(e -> SelectivePathResource.withResource(e)
                        .rootPath("WEB-INF/lib/**")
                        .excluded(PathPatternUtils.normalizePathPattern(config.getExcludes()))
                        .included(PathPatternUtils.normalizePathPattern(config.getIncludes()))
                        .build())
                    .filter(e -> !e.getAllResources().isEmpty())
                    .collect(Collectors.toSet());
            }
            webInfLibClasses.addAll(libs);
            // WEB-INF/webapp
            List<Resource> baseResourceList = projectOverlays.stream()
                .map(o -> getBaseResource(o, overlayToUnpackedResMap))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            if (config.getExcludes() != null || config.getIncludes() != null)
            {
                baseResourceList = baseResourceList.stream()
                    .map(e ->
                        SelectivePathResource.withResource(e)
                            .rootPath("WEB-INF/webapp/**")
                            .excluded(PathPatternUtils.normalizePathPattern(config.getExcludes()))
                            .included(PathPatternUtils.normalizePathPattern(config.getIncludes()))
                            .build())
                    .filter(e -> !e.getAllResources().isEmpty())
                    .collect(Collectors.toList());
            }
            if (config.getTargetPath() != null && overlayProject != null)
            {
                Resource root = Resource.newResource(MavenProjectHelper.retrieveWebAppSourceDirectory(overlayProject));
                baseResourceList.stream()
                    .map(Resource::getAllResources)
                    .flatMap(Collection::stream)
                    .forEach(e ->
                    {
                        String context = PathPatternUtils.relativePath(root, e);
                        projectWebApp.shiftTargetResource(context, e);
                    });
            }
            baseResources.addAll(baseResourceList);
        }
        projectWebApp.getWebInfLib().addAll(webInfLibClasses);
        projectWebApp.setBaseResource(new ResourceCollection(baseResources.toArray(new Resource[0])));
        return projectWebApp;
    }

    private Set<Resource> configureWebInfLib(JettyWebAppContext ctx, Overlay overlay, OverlayConfig overlayConfig,
                                             Map<Overlay, Resource> overlayToUnpackedResMap)
        throws IOException
    {
        Set<Resource> webInfLib = new LinkedHashSet<>();
        Set<Resource> webInfClasses = new LinkedHashSet<>();

        Artifact artifact = overlay.getArtifact();
        if (overlay.isMavenProject())
        {
            MavenProject project = mavenProjectHelper.getMavenProject(artifact);
            for (Artifact libArtifact : getWebInfLibArtifacts(project))
            {
                SelectivePathResource libArtifactRes = wrapLibInSelectivePathResource(libArtifact, overlayConfig.getExcludes(), overlayConfig.getIncludes());
                if (libArtifactRes == null)
                {
                    continue;
                }
                if (overlayConfig.getTargetPath() != null)
                {
                    Resource root = Resource.newResource(mavenProjectHelper.getArtifactPath(libArtifact));
                    root.getAllResources().forEach(e ->
                    {
                        String context = PathPatternUtils.relativePath(root, e);
                        ctx.shiftTargetResource(overlayConfig.getTargetPath() + context, e);
                    });
                }
                webInfLib.add(libArtifactRes);
            }
            SelectivePathResource res = SelectivePathResource
                .withResource(Resource.newResource(mavenProjectHelper.getArtifactPath(artifact).toFile()))
                .rootPath("WEB-INF/classes/")
                .excluded(PathPatternUtils.normalizePathPattern(overlayConfig.getExcludes()))
                .included(PathPatternUtils.normalizePathPattern(overlayConfig.getIncludes()))
                .build();
            Collection<Resource> allResources = res.getAllResources();
            if (res.isDirectory() && !allResources.isEmpty())
            {
                if (overlayConfig.getTargetPath() != null)
                {
                    Resource root = Resource.newResource(project.getBuild().getOutputDirectory());
                    allResources.forEach(e ->
                    {
                        String context = PathPatternUtils.relativePath(root, e);
                        ctx.shiftTargetResource(overlayConfig.getTargetPath() + context, e);
                    });
                }
                webInfClasses.add(res);
            }
        }
        else
        {
            Resource resource = overlayToUnpackedResMap.get(overlay);
            if (resource == null)
            {
                resource = Resource.newResource(mavenProjectHelper.getArtifactPath(artifact).toFile());
            }
            SelectivePathResource res = SelectivePathResource
                .withResource(resource)
                .rootPath("WEB-INF/classes/")
                .excluded(PathPatternUtils.normalizePathPattern(overlay.getConfig().getExcludes()))
                .included(PathPatternUtils.normalizePathPattern(overlay.getConfig().getIncludes()))
                .build();
            webInfClasses.add(res);
        }
        webInfLib.addAll(webInfClasses);
        return webInfLib;
    }

    private SelectivePathResource wrapLibInSelectivePathResource(Artifact artifact, Collection<String> excludes, Collection<String> includes)
    {
        Path libArtifactPath = mavenProjectHelper.getArtifactPath(artifact);
        String rootPath = "WEB-INF/lib/";

        excludes = PathPatternUtils.normalizePathPattern(excludes);
        includes = PathPatternUtils.normalizePathPattern(includes);
        boolean isDirectory = libArtifactPath.toFile().isDirectory();
        if (isDirectory)
        {
            rootPath += artifact.getArtifactId() + "-" + artifact.getVersion() + "." + artifact.getType() + "/";
            excludes = PathPatternUtils.addFolderContentPathPattern(excludes);
            includes = PathPatternUtils.addFolderContentPathPattern(includes);
        }
        if (!isDirectory)
        {   // it's a jar, grab dir and set include filter so excludes can be applied if needed
            if (includes == null)
            {
                includes = new ArrayList<>();
            }
            // include only maven naming convention
            includes.add(rootPath + libArtifactPath.getFileName().toString());
            libArtifactPath = libArtifactPath.getParent();
        }

        SelectivePathResource libArtifactRes = SelectivePathResource
            .withResource(Resource.newResource(libArtifactPath))
            .rootPath(rootPath)
            .excluded(excludes).included(includes)
            .build();
        if (libArtifactRes.getAllResources().isEmpty())
        {   // skip empty (or filtered) directories
            return null;
        }
        return libArtifactRes;
    }

    private Collection<Artifact> getWebInfLibArtifacts(MavenProject mavenProject)
    {
        String type = mavenProject.getArtifact().getType();
        if (!"war".equalsIgnoreCase(type) && !"zip".equalsIgnoreCase(type))
        {
            return Collections.emptyList();
        }
        return mavenProject.getArtifacts().stream()
            .filter(artifact ->
            {
                // check if artifact can be put in WEB-INF/lib
                if ("war".equalsIgnoreCase(artifact.getType()))
                {
                    return false;
                }
                if (Artifact.SCOPE_PROVIDED.equals(artifact.getScope()))
                {
                    return false;
                }
                return !Artifact.SCOPE_TEST.equals(artifact.getScope()) || useTestScope;
            })
            .collect(Collectors.toList());
    }

    private Resource getBaseResource(Overlay overlay, Map<Overlay, Resource> overlayToUnpackedResMap)
    {
        MavenProject project = mavenProjectHelper.getMavenProject(overlay.getArtifact());
        SelectivePathResource resource;
        if (project != null)
        {
            File webAppSourceDirectory = MavenProjectHelper.retrieveWebAppSourceDirectory(project);
            resource = SelectivePathResource.withResource(Resource.newResource(webAppSourceDirectory))
                .rootPath("WEB-INF/classes/")
                .excluded(PathPatternUtils.normalizePathPattern(overlay.getConfig().getExcludes()))
                .included(PathPatternUtils.normalizePathPattern(overlay.getConfig().getIncludes()))
                .build();
        }
        else
        {
            resource = SelectivePathResource.withResource(overlayToUnpackedResMap.get(overlay))
                .excluded(PathPatternUtils.normalizePathPattern(overlay.getConfig().getExcludes()))
                .included(PathPatternUtils.normalizePathPattern(overlay.getConfig().getIncludes()))
                .build();
        }
        if (resource.getAllResources().isEmpty())
        {
            return null;
        }
        return resource;
    }

    private Resource unpackOverlay(MavenProject project, Overlay overlay)
    {
        final Path overlaysDir = Paths.get(project.getBuild().getDirectory(), "jetty_overlays");

        Artifact a = overlay.getArtifact();
        Resource res;
        try
        {
            URL artifactUrl = new URL("jar:" + Resource.toURL(a.getFile()).toString() + "!/");
            res = Resource.newResource(artifactUrl);
        }
        catch (MalformedURLException e)
        {
            throw new IllegalStateException("Failed to retrieve URL for file: " + a.getFile().getAbsolutePath(), e);
        }
        //Get the name of the overlayed war and unpack it to a dir of the
        final String unpackDirName = String.join("__", a.getArtifactId(), a.getVersion(), a.getType()).replaceAll("\\.", "_");
        final File webAppDir = Paths.get(overlaysDir.toString(), unpackDirName).toFile();
        // remove if content exists and is older
        if (webAppDir.exists() && (res.lastModified() > webAppDir.lastModified()))
        {
            try
            {
                FileUtils.forceDelete(webAppDir);
            }
            catch (IOException e)
            {
                throw new IllegalStateException("Failed to delete old overlay directory: " + webAppDir);
            }
        }

        // unpack if does not exist
        if (!webAppDir.exists())
        {
            File copyToDir = webAppDir;
            if (overlay.getConfig().getTargetPath() != null)
            {
                copyToDir = new File(copyToDir, overlay.getConfig().getTargetPath());
            }
            copyToDir.mkdirs();
            if (!copyToDir.exists())
            {
                throw new IllegalStateException("Overlay directory does not exist: " + webAppDir);
            }
            try
            {
                res.copyTo(copyToDir);
            }
            catch (IOException e)
            {
                throw new IllegalStateException("Failed to copy content " + res.toString() + " to directory: " + webAppDir, e);
            }
        }

        try
        {
            //use top level of unpacked content
            String canonicalPath = webAppDir.getCanonicalPath();
            return Resource.newResource(canonicalPath);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Failed to get canonical path: " + webAppDir.getAbsolutePath(), e);
        }
    }
}
