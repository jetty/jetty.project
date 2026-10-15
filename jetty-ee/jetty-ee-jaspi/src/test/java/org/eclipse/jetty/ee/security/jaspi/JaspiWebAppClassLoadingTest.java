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

package org.eclipse.jetty.ee.security.jaspi;

import jakarta.security.auth.message.config.AuthConfigFactory;
import org.eclipse.jetty.ee.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A web application registers its own {@code ServerAuthModule} through the Jakarta Authentication
 * API, so it must be able to load that API from the server. These tests only mean anything with
 * {@code jetty-ee-jaspi} on the classpath, which is what makes {@code JaspiConfiguration} known.
 */
@ExtendWith(WorkDirExtension.class)
public class JaspiWebAppClassLoadingTest
{
    private Server _server;

    @AfterEach
    public void after() throws Exception
    {
        if (_server != null)
            _server.stop();
    }

    private ClassLoader startWebApp(WorkDir workDir, boolean parentLoaderPriority) throws Exception
    {
        _server = new Server();
        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        context.setBaseResourceAsPath(workDir.getEmptyPathDir());
        context.setParentLoaderPriority(parentLoaderPriority);
        _server.setHandler(context);
        _server.start();
        return context.getClassLoader();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testWebAppSeesTheJakartaAuthenticationApi(boolean parentLoaderPriority, WorkDir workDir) throws Exception
    {
        ClassLoader loader = startWebApp(workDir, parentLoaderPriority);
        assertThat(loader.loadClass("jakarta.security.auth.message.config.AuthConfigFactory"), sameInstance(AuthConfigFactory.class));
    }

    @Test
    public void testWebAppDoesNotSeeTheJaspiImplementation(WorkDir workDir) throws Exception
    {
        ClassLoader loader = startWebApp(workDir, false);
        assertThrows(ClassNotFoundException.class, () ->
            loader.loadClass("org.eclipse.jetty.ee.security.jaspi.JaspiAuthenticator"));
    }
}
