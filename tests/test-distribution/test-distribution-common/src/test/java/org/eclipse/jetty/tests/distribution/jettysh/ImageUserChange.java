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

package org.eclipse.jetty.tests.distribution.jettysh;

/**
 * A docker image with JETTY_USER set to id `jetty`.
 * JETTY_HOME is owned by `root`.
 * JETTY_BASE is owned by `jetty`
 */
public class ImageUserChange extends ImageFromDSL
{
    public ImageUserChange(ImageOS osImage)
    {
        super(osImage, "user-change", builder ->
            builder
                .from(osImage.getDockerImageName())
                // setup "jetty" user and Jetty Base directory
                .run("chmod ugo+x ${JETTY_HOME}/bin/jetty.sh ; " +
                    "mkdir -p ${JETTY_BASE} ; " +
                    "useradd --home-dir=${JETTY_BASE} --shell=/bin/bash jetty ; " +
                    "chown jetty:jetty ${JETTY_BASE} ; " +
                    "chmod a+w ${JETTY_BASE} ; " +
                    "echo \"JETTY_USER=jetty\" >> /etc/default/jetty") // user change
                .user("jetty")
                // Configure Jetty Base
                .workDir("${JETTY_BASE}")
                .build());
    }
}
