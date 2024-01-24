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

package org.eclipse.jetty.tests.testers;

import java.nio.file.Path;
import java.util.List;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MavenHelper
{
    private static final Logger LOG = LoggerFactory.getLogger(MavenHelper.class);

    private final RepositorySystemSupplier repositorySystem = new RepositorySystemSupplier();

    public Path resolveArtifact(String coordinates) throws ArtifactResolutionException
    {
        RepositorySystem repositorySystem = this.repositorySystem.get();

        Artifact artifact = new DefaultArtifact(coordinates);

        RepositorySystemSession session = newRepositorySystemSession(repositorySystem);

        ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setArtifact(artifact);
        artifactRequest.setRepositories(List.of(newCentralRepository()));
        ArtifactResult artifactResult = repositorySystem.resolveArtifact(session, artifactRequest);

        artifact = artifactResult.getArtifact();
        return artifact.getFile().toPath();
    }

    private DefaultRepositorySystemSession newRepositorySystemSession(RepositorySystem system)
    {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

        String mavenLocalRepository = System.getProperty("mavenRepoPath", System.getProperty("user.home") + "/.m2/repository");
        LocalRepository localRepository = new LocalRepository(mavenLocalRepository);
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepository));

        session.setTransferListener(new LogTransferListener());
        session.setRepositoryListener(new LogRepositoryListener());

        return session;
    }

    private static RemoteRepository newCentralRepository()
    {
        return new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2/").build();
    }

    private static class LogTransferListener extends AbstractTransferListener
    {
    }

    private static class LogRepositoryListener extends AbstractRepositoryListener
    {
        @Override
        public void artifactDownloaded(RepositoryEvent event)
        {
            LOG.debug("downloaded {}", event.getFile());
        }

        @Override
        public void artifactResolved(RepositoryEvent event)
        {
            LOG.debug("artifact resolved {}", event.getFile());
        }
    }
}
