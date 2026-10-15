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

package org.eclipse.jetty.ee9.security.jaspi;

import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.spi.ToolProvider;

import jakarta.security.auth.message.AuthStatus;
import jakarta.security.auth.message.MessageInfo;
import jakarta.security.auth.message.MessagePolicy;
import jakarta.security.auth.message.config.AuthConfigFactory;
import jakarta.security.auth.message.config.AuthConfigProvider;
import jakarta.security.auth.message.config.ClientAuthConfig;
import jakarta.security.auth.message.config.ServerAuthConfig;
import jakarta.security.auth.message.module.ServerAuthModule;
import org.eclipse.jetty.ee9.security.jaspi.modules.BasicAuthenticationAuthModule;
import org.eclipse.jetty.ee9.security.jaspi.provider.JaspiAuthConfigProvider;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A provider or auth module named by class name is loaded from the thread context classloader,
 * which is how a web application names one of its own, and from the classloader of the jaspi
 * classes when the context classloader cannot see it.
 * <p>The types of the compiled sources are named by {@link Class#getName()} so that they follow
 * whichever version of the API this module is built against.</p>
 */
@ExtendWith(WorkDirExtension.class)
public class JaspiProviderClassLoadingTest
{
    private static final String MESSAGE_LAYER = "HttpServlet";
    private static final String APP_CONTEXT = "server /test";
    private static final String PROVIDER_CLASS = "hidden.HiddenAuthConfigProvider";
    private static final String AUTH_MODULE_CLASS = "hidden.HiddenServerAuthModule";

    private static final String PROVIDER_SOURCE = """
        package hidden;

        public class HiddenAuthConfigProvider implements %1$s
        {
            public HiddenAuthConfigProvider(java.util.Map<String, String> properties, %2$s factory)
            {
            }

            public %3$s getClientAuthConfig(String layer, String appContext, javax.security.auth.callback.CallbackHandler handler)
            {
                return null;
            }

            public %4$s getServerAuthConfig(String layer, String appContext, javax.security.auth.callback.CallbackHandler handler)
            {
                return null;
            }

            public void refresh()
            {
            }
        }
        """.formatted(AuthConfigProvider.class.getName(), AuthConfigFactory.class.getName(),
            ClientAuthConfig.class.getName(), ServerAuthConfig.class.getName());

    private static final String AUTH_MODULE_SOURCE = """
        package hidden;

        public class HiddenServerAuthModule implements %1$s
        {
            public void initialize(%2$s requestPolicy, %2$s responsePolicy, javax.security.auth.callback.CallbackHandler handler, java.util.Map options)
            {
            }

            public Class[] getSupportedMessageTypes()
            {
                return new Class[0];
            }

            public %3$s validateRequest(%4$s messageInfo, javax.security.auth.Subject clientSubject, javax.security.auth.Subject serviceSubject)
            {
                return %3$s.SUCCESS;
            }

            public %3$s secureResponse(%4$s messageInfo, javax.security.auth.Subject serviceSubject)
            {
                return %3$s.SEND_SUCCESS;
            }

            public void cleanSubject(%4$s messageInfo, javax.security.auth.Subject subject)
            {
            }
        }
        """.formatted(ServerAuthModule.class.getName(), MessagePolicy.class.getName(),
            AuthStatus.class.getName(), MessageInfo.class.getName());

    private ClassLoader _contextClassLoader;

    @AfterEach
    public void after()
    {
        if (_contextClassLoader != null)
            Thread.currentThread().setContextClassLoader(_contextClassLoader);
    }

    private void setContextClassLoader(ClassLoader loader)
    {
        _contextClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
    }

    /**
     * Compile the provider and the auth module into a classloader of their own, so that they are
     * not on the classpath of this test and so are invisible to the classloader of the jaspi
     * classes under test.
     */
    private ClassLoader hiddenClassLoader(Path directory) throws Exception
    {
        Optional<ToolProvider> javac = ToolProvider.findFirst("javac");
        assumeTrue(javac.isPresent(), "a JDK compiler is needed to build classes this test cannot see");

        Path provider = Files.writeString(directory.resolve("HiddenAuthConfigProvider.java"), PROVIDER_SOURCE);
        Path authModule = Files.writeString(directory.resolve("HiddenServerAuthModule.java"), AUTH_MODULE_SOURCE);
        String classpath = Path.of(AuthConfigProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        try (PrintWriter out = new PrintWriter(System.out, true); PrintWriter err = new PrintWriter(System.err, true))
        {
            assertThat(javac.get().run(out, err, "-classpath", classpath, "-d", directory.toString(),
                provider.toString(), authModule.toString()), is(0));
        }

        assertThrows(ClassNotFoundException.class, () -> Class.forName(PROVIDER_CLASS));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(AUTH_MODULE_CLASS));
        return new URLClassLoader(new URL[]{directory.toUri().toURL()}, getClass().getClassLoader());
    }

    @Test
    public void testProviderClassNameComesFromTheContextClassLoader(WorkDir workDir) throws Exception
    {
        ClassLoader loader = hiddenClassLoader(workDir.getEmptyPathDir());
        setContextClassLoader(loader);

        AuthConfigFactory factory = new DefaultAuthConfigFactory();
        factory.registerConfigProvider(PROVIDER_CLASS, Map.of(), MESSAGE_LAYER, APP_CONTEXT, "a provider of the web application");

        AuthConfigProvider provider = factory.getConfigProvider(MESSAGE_LAYER, APP_CONTEXT, null);
        assertThat(provider.getClass().getName(), is(PROVIDER_CLASS));
        assertThat(provider.getClass().getClassLoader(), sameInstance(loader));
    }

    @Test
    public void testAuthModuleClassNameComesFromTheContextClassLoader(WorkDir workDir) throws Exception
    {
        ClassLoader loader = hiddenClassLoader(workDir.getEmptyPathDir());
        setContextClassLoader(loader);

        AuthConfigProvider provider = new JaspiAuthConfigProvider(AUTH_MODULE_CLASS, Map.of());
        assertThat(provider.getServerAuthConfig(MESSAGE_LAYER, APP_CONTEXT, null), notNullValue());
    }

    @Test
    public void testProviderClassNameFallsBackToTheJaspiClassLoader() throws Exception
    {
        // The etc/jaspi XML files name classes a web application classloader cannot see, so the context classloader must not be the only one tried.
        setContextClassLoader(new URLClassLoader(new URL[0], null));

        AuthConfigFactory factory = new DefaultAuthConfigFactory();
        factory.registerConfigProvider(JaspiAuthConfigProvider.class.getName(),
            Map.of("ServerAuthModule", BasicAuthenticationAuthModule.class.getName()),
            MESSAGE_LAYER, APP_CONTEXT, "a provider deployed with the server");

        AuthConfigProvider provider = factory.getConfigProvider(MESSAGE_LAYER, APP_CONTEXT, null);
        assertThat(provider, instanceOf(JaspiAuthConfigProvider.class));
        assertThat(provider.getServerAuthConfig(MESSAGE_LAYER, APP_CONTEXT, null), notNullValue());
    }
}
