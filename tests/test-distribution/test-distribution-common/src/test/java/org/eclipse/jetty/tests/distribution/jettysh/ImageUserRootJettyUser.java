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
 * A docker image that runs {@code jetty.sh} as {@code root} but with {@code JETTY_USER=jetty}
 * configured, so {@code jetty.sh} drops privileges to {@code jetty} at launch (via
 * {@code start-stop-daemon --chuid} when available, otherwise {@code su - jetty}).
 * <p>
 * Unlike {@link ImageUserChange} (which runs the whole script as {@code jetty}), here the
 * current user at launch is {@code root} and differs from {@code JETTY_USER}, so this is the
 * image that exercises the {@code chown -h "$JETTY_USER"} of the generated {@code @argument-file}
 * (issue #14005): root creates the 0600 file and hands ownership to {@code jetty} so the
 * dropped-to user can read it.
 * <p>
 * {@code JETTY_HOME} is owned by {@code root}; {@code JETTY_BASE} (also used as {@code JETTY_RUN})
 * is owned by {@code jetty} so the dropped-to process can write its pid/state files there, and so
 * jetty.sh's safe-directory check accepts it (it refuses a group/world-writable {@code JETTY_RUN}).
 */
public class ImageUserRootJettyUser extends ImageFromDSL
{
    public ImageUserRootJettyUser(ImageOS osImage)
    {
        super(osImage, "user-root-jetty-user", builder ->
            builder
                .from(osImage.getDockerImageName())
                // setup Jetty Home, owned by root
                .copy("/opt/jetty/", "${JETTY_HOME}/")
                // create the "jetty" user and a jetty-owned Jetty Base (so the dropped-to process
                // can write pid/state there), and configure JETTY_USER so jetty.sh drops
                // privileges at launch (NOTE: no .user("jetty") here, so the script still runs as root)
                .run("chmod a+x ${JETTY_HOME}/bin/jetty.sh ; " +
                    "mkdir -p ${JETTY_BASE} ; " +
                    "useradd --home-dir=${JETTY_BASE} --shell=/bin/bash jetty ; " +
                    "chown jetty:jetty ${JETTY_BASE} ; " +
                    "echo \"JETTY_USER=jetty\" >> /etc/default/jetty") // drop privileges to jetty
                // Configure Jetty Base
                .workDir("${JETTY_BASE}")
                .build());
        withFileFromFile("/opt/jetty/", osImage.getJettyHomeDir());
    }
}
