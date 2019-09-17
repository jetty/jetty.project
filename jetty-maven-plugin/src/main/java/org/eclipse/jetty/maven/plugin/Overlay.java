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

package org.eclipse.jetty.maven.plugin;

import org.apache.maven.artifact.Artifact;

/**
 * Overlay
 */
public class Overlay
{
    private OverlayConfig _config;
    private Artifact _artifact;
    private boolean _isMavenProject;

    public Overlay(OverlayConfig config, Artifact artifact, boolean isMavenProject)
    {
        _config = config;
        _artifact = artifact;
        _isMavenProject = isMavenProject;
    }

    public OverlayConfig getConfig()
    {
        return _config;
    }

    public Artifact getArtifact()
    {
        return _artifact;
    }

    public boolean isMavenProject()
    {
        return _isMavenProject;
    }

    @Override
    public String toString()
    {
        StringBuilder strbuff = new StringBuilder();
        if (_artifact != null)
        {
            strbuff.append(_artifact.toString());
        }
        if (_config != null)
        {
            strbuff.append(" [");
            strbuff.append(_config);
            strbuff.append("]");
        }
        return strbuff.toString();
    }
}
