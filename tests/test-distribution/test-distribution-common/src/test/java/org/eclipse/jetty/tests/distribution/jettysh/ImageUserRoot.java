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
 * A docker image with no JETTY_USER set, everything executes as `root`.
 */
public class ImageUserRoot extends ImageFromDSL
{
    public ImageUserRoot(ImageOS osImage)
    {
        super(osImage, "user-root", builder ->
            builder
                .from(osImage.getDockerImageName())
                // setup Jetty Home
                .copy("/opt/jetty/", "${JETTY_HOME}/")
                // JETTY_BASE is created and owned by root; everything runs as root, so it needs
                // no extra write grant (jetty.sh refuses a group/world-writable JETTY_RUN).
                .run("mkdir -p ${JETTY_BASE} ; " +
                    "chmod a+x ${JETTY_HOME}/bin/jetty.sh")
                // Configure Jetty Base
                .workDir("${JETTY_BASE}")
                .build());
        withFileFromFile("/opt/jetty/", osImage.getJettyHomeDir());
    }
}
