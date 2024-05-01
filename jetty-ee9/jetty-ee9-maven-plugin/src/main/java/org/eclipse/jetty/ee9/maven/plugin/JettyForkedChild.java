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

package org.eclipse.jetty.ee9.maven.plugin;

import org.eclipse.jetty.maven.AbstractForkedChild;
import org.eclipse.jetty.maven.AbstractJettyEmbedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JettyForkedChild
 *
 * This is the class that is executed when the jetty maven plugin 
 * forks a process when DeploymentMode=FORKED.
 */
public class JettyForkedChild extends AbstractForkedChild
{
    private static final Logger LOG = LoggerFactory.getLogger(JettyForkedChild.class);

    /**
     * @param args arguments that were passed to main
     * @throws Exception if unable to configure
     */
    public JettyForkedChild(String[] args)
        throws Exception
    {
       super(args);
    }

    @Override
    protected AbstractJettyEmbedder newJettyEmbedder()
    {
        return new JettyEmbedder();
    }

    public static void main(String[] args)
        throws Exception
    {
        if (args == null)
            System.exit(1);

        JettyForkedChild child = new JettyForkedChild(args);
        child.start();
    }
}
