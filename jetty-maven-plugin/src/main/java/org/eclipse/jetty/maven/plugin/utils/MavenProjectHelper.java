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

package org.eclipse.jetty.maven.plugin.utils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;

public class MavenProjectHelper
{

    private final Map<String, MavenProject> artifactToLocalProjectMap;

    public MavenProjectHelper(MavenProject project)
    {
        Set<MavenProject> mavenProjects = resolveProjectDependencies(project, new HashSet<>());
        artifactToLocalProjectMap = mavenProjects.stream()
            .collect(Collectors.toMap(MavenProject::getId, Function.identity()));
        artifactToLocalProjectMap.put(project.getArtifact().getId(), project);
    }

    /**
     * Gets maven project if referenced in reactor
     *
     * @param artifact - maven artifact
     * @return {@link MavenProject} if artifact is referenced in reactor, otherwise null
     */
    public MavenProject getMavenProject(Artifact artifact)
    {
        return artifactToLocalProjectMap.get(artifact.getId());
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
}
