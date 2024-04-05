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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.maven.AbstractForker;
import org.eclipse.jetty.maven.AbstractHomeForker;
import org.eclipse.jetty.maven.PluginLog;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;

/**
 * JettyHomeForker
 *
 * Unpacks a jetty-home and configures it with a base that allows it
 * to run an unassembled webapp.
 */
public class JettyHomeForker extends AbstractHomeForker
{
    protected MavenWebAppContext webApp;

    public JettyHomeForker()
    {
        environment = "ee9";
    }

    public void setWebApp(MavenWebAppContext webApp)
    {
        this.webApp = webApp;
    }

    protected void redeployWebApp()
        throws Exception
    {
        generateWebAppPropertiesFile();
        webappPath.resolve("maven.xml").toFile().setLastModified(System.currentTimeMillis());
    }

    protected void generateWebAppPropertiesFile()
        throws Exception
    {
        WebAppPropertyConverter.toProperties(webApp, etcPath.resolve("maven.props").toFile(), contextXml);
    }
}
