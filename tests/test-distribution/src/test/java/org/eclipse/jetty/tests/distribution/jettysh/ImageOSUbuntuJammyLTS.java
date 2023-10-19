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

public class ImageOSUbuntuJammyLTS extends ImageOS
{
    public ImageOSUbuntuJammyLTS()
    {
        super("ubuntu-22.04",
            builder ->
                builder
                    .from("ubuntu:22.04")
                    .run("apt update ; " +
                        "apt -y upgrade ; " +
                        "apt install -y openjdk-17-jdk-headless ; " +
                        "apt install -y curl vim net-tools ")
                    .env("TEST_DIR", "/var/test")
                    .env("JETTY_HOME", "$TEST_DIR/jetty-home")
                    .env("JETTY_BASE", "$TEST_DIR/jetty-base")
                    .env("PATH", "$PATH:${JETTY_HOME}/bin/")
                    .user("root")
                    // Configure /etc/default/jetty
                    .run("echo \"JETTY_HOME=${JETTY_HOME}\" > /etc/default/jetty ; " +
                        "echo \"JETTY_BASE=${JETTY_BASE}\" >> /etc/default/jetty ; " +
                        "echo \"JETTY_RUN=${JETTY_BASE}\" >> /etc/default/jetty ")
                    // setup Jetty Home
                    .copy("/opt/jetty/", "${JETTY_HOME}/")
                    .env("PATH", "$PATH:${JETTY_HOME}/bin/")
                    .run("chmod ugo+x ${JETTY_HOME}/bin/jetty.sh")
                    .build()
        );
    }
}
