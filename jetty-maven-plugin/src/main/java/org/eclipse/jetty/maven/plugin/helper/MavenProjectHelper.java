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

package org.eclipse.jetty.maven.plugin.helper;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.eclipse.jetty.maven.plugin.Overlay;
import org.eclipse.jetty.maven.plugin.OverlayConfig;
import org.eclipse.jetty.maven.plugin.WarPluginInfo;

public class MavenProjectHelper
{

    public static final String DEFAULT_WEBAPP_SRC = "src" + File.separator + "main" + File.separator + "webapp";
    public static final String FAKE_WEBAPP = "webapp-tmp";

    private final Map<Artifact, MavenProject> artifactToLocalProjectMap;

    public MavenProjectHelper(MavenProject project)
    {
        Set<MavenProject> mavenProjects = resolveProjectDependencies(project, new HashSet<>());
        artifactToLocalProjectMap = mavenProjects.stream()
            .collect(Collectors.toMap(MavenProject::getArtifact, Function.identity()));
        artifactToLocalProjectMap.put(project.getArtifact(), project);
    }

    /**
     * Gets maven project if referenced in reactor
     *
     * @param artifact - maven artifact
     * @return {@link MavenProject} if artifact is referenced in reactor, otherwise null
     */
    public MavenProject getMavenProject(Artifact artifact)
    {
        return artifactToLocalProjectMap.get(artifact);
    }

    /**
     * Gets path to artifact.
     * If artifact is referenced in reactor, returns path to ${project.build.outputDirectory}.
     * Otherwise, returns path to location in local m2 repo.
     *
     * Cannot return null - maven will complain about unsatisfied dependency during project built.
     *
     * @param artifact maven artifact
     * @return path to artifact
     */
    public Path getArtifactPath(Artifact artifact)
    {
        Path path = artifact.getFile().toPath();
        MavenProject mavenProject = getMavenProject(artifact);
        if (mavenProject != null)
        {
            if ("test-jar".equals(artifact.getType()))
            {
                path = Paths.get(mavenProject.getBuild().getTestOutputDirectory());
            }
            else
            {
                path = Paths.get(mavenProject.getBuild().getOutputDirectory());
            }
        }
        return path;
    }

    public List<Overlay> getOverlaysForChildProject(MavenProject childProject)
        throws Exception
    {
        //get copy of a list of war artifacts
        Set<Artifact> matchedWarArtifacts = new HashSet<>();
        List<Overlay> overlays = new ArrayList<>();
        WarPluginInfo warPluginInfo = new WarPluginInfo(childProject);
        for (OverlayConfig config : warPluginInfo.getMavenWarOverlayConfigs())
        {
            //overlays can be individually skipped
            if (config.isSkip())
                continue;

            //an empty overlay refers to the current project - important for ordering
            if (config.isCurrentProject())
            {
                Overlay overlay = new Overlay(config, childProject.getArtifact(), true);
                overlays.add(overlay);
                continue;
            }

            //if a war matches an overlay config
            Artifact a = getArtifactForOverlay(config, findWarOrZipArtifacts(childProject));
            if (a != null)
            {
                matchedWarArtifacts.add(a);
                Overlay overlay = new Overlay(config, a, getMavenProject(a) != null);
                overlays.add(overlay);
            }
        }

        //iterate over the left over war artifacts and unpack them (without include/exclude processing) as necessary
        for (Artifact a : findWarOrZipArtifacts(childProject))
        {
            if (!matchedWarArtifacts.contains(a))
            {
                Overlay overlay = new Overlay(new OverlayConfig(), a, getMavenProject(a) != null);
                overlays.add(overlay);
            }
        }

        // if no "wars", just add current project (which MUST be war file)
        if (overlays.isEmpty())
        {
            Overlay overlay = new Overlay(new OverlayConfig(), childProject.getArtifact(), true);
            overlays.add(overlay);
        }
        return overlays;
    }

    private static Artifact getArtifactForOverlay(OverlayConfig o, List<Artifact> warArtifacts)
    {
        if (o == null || warArtifacts == null || warArtifacts.isEmpty())
            return null;

        for (Artifact a : warArtifacts)
        {
            if (o.matchesArtifact(a.getGroupId(), a.getArtifactId(), a.getClassifier()))
            {
                return a;
            }
        }

        return null;
    }

    public static List<Artifact> findWarOrZipArtifacts(MavenProject project)
    {
        return project.getArtifacts().stream()
            .filter(artifact -> artifact.getType().equals("war") || artifact.getType().equals("zip"))
            .collect(Collectors.toList());
    }

    private static Set<MavenProject> resolveProjectDependencies(MavenProject project, Set<MavenProject> visitedProjects)
    {
        if (visitedProjects.contains(project))
        {
            return Collections.emptySet();
        }
        visitedProjects.add(project);
        Set<MavenProject> availableProjects = new HashSet<>(project.getProjectReferences().values());
        for (MavenProject ref : project.getProjectReferences().values())
        {
            availableProjects.addAll(resolveProjectDependencies(ref, visitedProjects));
        }
        return availableProjects;
    }

    public static File retrieveWebAppSourceDirectory(MavenProject project)
    {
        File webAppSourceDirectory = new File(project.getBasedir(), DEFAULT_WEBAPP_SRC);
        if (!webAppSourceDirectory.exists())
        {
            File target = new File(project.getBuild().getDirectory());
            webAppSourceDirectory = new File(target, FAKE_WEBAPP);
            if (!webAppSourceDirectory.exists())
                webAppSourceDirectory.mkdirs();
        }
        return webAppSourceDirectory;
    }
}
