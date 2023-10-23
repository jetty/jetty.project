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
                .run("mkdir -p ${JETTY_BASE} ; " +
                    "chmod u+x ${JETTY_HOME}/bin/jetty.sh ; " +
                    "chmod a+w ${JETTY_BASE}")
                // Configure Jetty Base
                .workDir("${JETTY_BASE}")
                .build());
    }
}
