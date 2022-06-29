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

package org.eclipse.jetty.ee9.maven.plugin.utils;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.jetty.ee9.maven.plugin.OverlayManager;
import org.eclipse.jetty.ee9.maven.plugin.WarPluginInfo;

/**
 * MavenProjectHelper
 *
 * A class to facilitate interacting with the build time maven environment.
 */
public class MavenProjectHelper
{
    private MavenProject project;
    private RepositorySystem repositorySystem;
    private List<ArtifactRepository> remoteRepositories;
    private MavenSession session;
    private final Map<String, MavenProject> artifactToReactorProjectMap;
    /**
     * maven-war-plugin reference
     */
    private WarPluginInfo warPluginInfo;

    /**
     * Helper for wrangling war overlays
     */
    private OverlayManager overlayManager;

    /**
     * @param project the project being built
     * @param repositorySystem a resolve for artifacts
     * @param remoteRepositories repositories from which to resolve artifacts
     * @param session the current maven build session
     */
    public MavenProjectHelper(MavenProject project, RepositorySystem repositorySystem, List<ArtifactRepository> remoteRepositories, MavenSession session)
    {
        this.project = project;
        this.repositorySystem = repositorySystem;
        this.remoteRepositories = remoteRepositories;
        this.session = session;
        //work out which dependent projects are in the reactor
        Set<MavenProject> mavenProjects = findDependenciesInReactor(project, new HashSet<>());
        artifactToReactorProjectMap = mavenProjects.stream()
            .collect(Collectors.toMap(MavenProject::getId, Function.identity()));
        artifactToReactorProjectMap.put(project.getArtifact().getId(), project);
        warPluginInfo = new WarPluginInfo(project);
        overlayManager = new OverlayManager(warPluginInfo);
    }

    public MavenProject getProject()
    {
        return this.project;
    }

    public WarPluginInfo getWarPluginInfo()
    {
        return warPluginInfo;
    }

    public OverlayManager getOverlayManager()
    {
        return overlayManager;
    }

    /**
     * Gets the maven project represented by the artifact iff it is in
     * the reactor.
     *
     * @param artifact the artifact of the project to get
     * @return {@link MavenProject} if artifact is referenced in reactor, otherwise null
     */
    public MavenProject getMavenProjectFor(Artifact artifact)
    {
        return artifactToReactorProjectMap.get(artifact.getId());
    }

    /**
     * Gets path to artifact.
     * If the artifact is referenced in the reactor, returns path to ${project.build.outputDirectory}.
     * Otherwise, returns path to location in local m2 repo.
     *
     * Cannot return null - maven will complain about unsatisfied dependency during project build.
     *
     * @param artifact maven artifact to check
     * @return path to artifact
     */
    public Path getPathFor(Artifact artifact)
    {
        Path path = artifact.getFile().toPath();
        MavenProject mavenProject = getMavenProjectFor(artifact);
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

    /**
     * Given the coordinates for an artifact, resolve the artifact from the
     * remote repositories.
     *
     * @param groupId the groupId of the artifact to resolve
     * @param artifactId the artifactId of the artifact to resolve
     * @param version the version of the artifact to resolve
     * @param type the type of the artifact to resolve
     * @return a File representing the location of the artifact or null if not resolved
     */
    public File resolveArtifact(String groupId, String artifactId, String version, String type)
        throws ArtifactResolutionException
    {
        ArtifactRequest request = new ArtifactRequest();
        request.setRepositories(RepositoryUtils.toRepos(remoteRepositories));
        request.setArtifact(new DefaultArtifact(groupId, artifactId, "", type, version));
        ArtifactResult result = repositorySystem.resolveArtifact(session.getRepositorySession(), request);

        if (result.isResolved())
            return result.getArtifact().getFile();
        return null;
    }

    /**
     * Recursively find projects in the reactor for all dependencies of the given project.
     *
     * @param project the project for which to find dependencies that are in the reactor
     * @param visitedProjects the set of projects already seen
     * @return unified set of all related projects in the reactor
     */
    private static Set<MavenProject> findDependenciesInReactor(MavenProject project, Set<MavenProject> visitedProjects)
    {
        if (visitedProjects.contains(project))
            return Collections.emptySet();

        visitedProjects.add(project);
        Collection<MavenProject> refs = project.getProjectReferences().values();
        Set<MavenProject> availableProjects = new HashSet<>(refs);
        for (MavenProject ref : refs)
        {
            availableProjects.addAll(findDependenciesInReactor(ref, visitedProjects));
        }
        return availableProjects;
    }
}
