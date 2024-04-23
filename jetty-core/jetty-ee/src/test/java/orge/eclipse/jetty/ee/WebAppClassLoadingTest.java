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

package orge.eclipse.jetty.ee;

import java.util.Arrays;

import org.eclipse.jetty.ee.WebAppClassLoading;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ClassMatcher;
import org.eclipse.jetty.util.component.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItemInArray;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

public class WebAppClassLoadingTest
{
    @BeforeEach
    public void beforeEach()
    {
        Environment.ensure("Test");
    }

    @AfterEach
    public void afterEach()
    {
        Environment.ensure("Test").clearAttributes();
    }
    
    @Test
    public void testServerDefaults()
    {
        Server server = new Server();
        ClassMatcher protect = WebAppClassLoading.getProtectedClasses(server);
        assertThat(protect.size(), is(0));
        assertThat(server.getAttribute(WebAppClassLoading.PROTECTED_CLASSES_ATTRIBUTE), sameInstance(protect));
        ClassMatcher hide = WebAppClassLoading.getHiddenClasses(server);
        assertThat(hide.size(), is(0));
        assertThat(server.getAttribute(WebAppClassLoading.HIDDEN_CLASSES_ATTRIBUTE), sameInstance(hide));
    }

    @Test
    public void testServerAttributeDefaults()
    {
        Server server = new Server();
        ClassMatcher protect = new ClassMatcher("org.protect.");
        server.setAttribute(WebAppClassLoading.PROTECTED_CLASSES_ATTRIBUTE, protect);
        ClassMatcher hide = new ClassMatcher("org.hide.");
        server.setAttribute(WebAppClassLoading.HIDDEN_CLASSES_ATTRIBUTE, hide);

        assertThat(WebAppClassLoading.getProtectedClasses(server), sameInstance(protect));
        assertThat(WebAppClassLoading.getHiddenClasses(server), sameInstance(hide));
    }

    @Test
    public void testServerStringAttributeDefaults()
    {
        Server server = new Server();
        server.setAttribute(WebAppClassLoading.PROTECTED_CLASSES_ATTRIBUTE, new String[] {"org.protect."});
        server.setAttribute(WebAppClassLoading.HIDDEN_CLASSES_ATTRIBUTE, new String[] {"org.hide."});

        ClassMatcher protect = WebAppClassLoading.getProtectedClasses(server);
        assertThat(protect.size(), is(1));
        assertThat(Arrays.asList(protect.getPatterns()), contains("org.protect."));
        assertThat(server.getAttribute(WebAppClassLoading.PROTECTED_CLASSES_ATTRIBUTE), sameInstance(protect));
        ClassMatcher hide = WebAppClassLoading.getHiddenClasses(server);
        assertThat(hide.size(), is(1));
        assertThat(Arrays.asList(hide.getPatterns()), contains("org.hide."));
        assertThat(server.getAttribute(WebAppClassLoading.HIDDEN_CLASSES_ATTRIBUTE), sameInstance(hide));
    }

    @Test
    public void testServerProgrammaticDefaults()
    {
        Server server = new Server();
        WebAppClassLoading.addProtectedClasses(server, "org.protect.");
        WebAppClassLoading.addHiddenClasses(server, "org.hide.");

        ClassMatcher protect = WebAppClassLoading.getProtectedClasses(server);
        assertThat(protect.size(), is(1));
        assertThat(Arrays.asList(protect.getPatterns()), contains("org.protect."));
        assertThat(server.getAttribute(WebAppClassLoading.PROTECTED_CLASSES_ATTRIBUTE), sameInstance(protect));
        ClassMatcher hide = WebAppClassLoading.getHiddenClasses(server);
        assertThat(hide.size(), is(1));
        assertThat(Arrays.asList(hide.getPatterns()), contains("org.hide."));
        assertThat(server.getAttribute(WebAppClassLoading.HIDDEN_CLASSES_ATTRIBUTE), sameInstance(hide));
    }

    @Test
    public void testServerAddPatterns()
    {
        Server server = new Server();
        ClassMatcher protect = WebAppClassLoading.getProtectedClasses(server);
        ClassMatcher hide = WebAppClassLoading.getHiddenClasses(server);

        assertThat(protect.size(), is(0));
        assertThat(hide.size(), is(0));

        WebAppClassLoading.addProtectedClasses(server, "org.protect.", "com.protect.");
        WebAppClassLoading.addHiddenClasses(server, "org.hide.", "com.hide.");

        assertThat(protect.size(), is(2));
        assertThat(Arrays.asList(protect.getPatterns()), containsInAnyOrder("org.protect.", "com.protect."));
        assertThat(server.getAttribute(WebAppClassLoading.PROTECTED_CLASSES_ATTRIBUTE), sameInstance(protect));

        assertThat(hide.size(), is(2));
        assertThat(Arrays.asList(hide.getPatterns()), containsInAnyOrder("org.hide.", "com.hide."));
        assertThat(server.getAttribute(WebAppClassLoading.HIDDEN_CLASSES_ATTRIBUTE), sameInstance(hide));
    }

    @Test
    public void testEnvironmentDefaults()
    {
        Environment environment = Environment.get("Test");
        ClassMatcher protect = WebAppClassLoading.getProtectedClasses(environment);
        assertThat(protect, equalTo(WebAppClassLoading.DEFAULT_PROTECTED_CLASSES));
        assertThat(environment.getAttribute(WebAppClassLoading.PROTECTED_CLASSES_ATTRIBUTE), sameInstance(protect));
        ClassMatcher hide = WebAppClassLoading.getHiddenClasses(environment);
        assertThat(hide, equalTo(WebAppClassLoading.DEFAULT_HIDDEN_CLASSES));
        assertThat(environment.getAttribute(WebAppClassLoading.HIDDEN_CLASSES_ATTRIBUTE), sameInstance(hide));
    }

    @Test
    public void testEnvironmentAttributeDefaults()
    {
        Environment environment = Environment.get("Test");
        ClassMatcher protect = new ClassMatcher("org.protect.");
        environment.setAttribute(WebAppClassLoading.PROTECTED_CLASSES_ATTRIBUTE, protect);
        ClassMatcher hide = new ClassMatcher("org.hide.");
        environment.setAttribute(WebAppClassLoading.HIDDEN_CLASSES_ATTRIBUTE, hide);

        assertThat(WebAppClassLoading.getProtectedClasses(environment), sameInstance(protect));
        assertThat(WebAppClassLoading.getHiddenClasses(environment), sameInstance(hide));
    }

    @Test
    public void testEnvironmentStringAttributeDefaults()
    {
        Environment environment = Environment.get("Test");
        environment.setAttribute(WebAppClassLoading.PROTECTED_CLASSES_ATTRIBUTE, new String[] {"org.protect."});
        environment.setAttribute(WebAppClassLoading.HIDDEN_CLASSES_ATTRIBUTE, new String[] {"org.hide."});

        ClassMatcher protect = WebAppClassLoading.getProtectedClasses(environment);
        assertThat(protect.size(), is(1));
        assertThat(Arrays.asList(protect.getPatterns()), contains("org.protect."));
        assertThat(environment.getAttribute(WebAppClassLoading.PROTECTED_CLASSES_ATTRIBUTE), sameInstance(protect));
        ClassMatcher hide = WebAppClassLoading.getHiddenClasses(environment);
        assertThat(hide.size(), is(1));
        assertThat(Arrays.asList(hide.getPatterns()), contains("org.hide."));
        assertThat(environment.getAttribute(WebAppClassLoading.HIDDEN_CLASSES_ATTRIBUTE), sameInstance(hide));
    }

    @Test
    public void testEnvironmentProgrammaticDefaults()
    {
        Environment environment = Environment.get("Test");
        WebAppClassLoading.addProtectedClasses(environment, "org.protect.");
        WebAppClassLoading.addHiddenClasses(environment, "org.hide.");

        ClassMatcher protect = WebAppClassLoading.getProtectedClasses(environment);
        ClassMatcher hide = WebAppClassLoading.getHiddenClasses(environment);

        assertThat(protect.size(), is(WebAppClassLoading.DEFAULT_PROTECTED_CLASSES.size() + 1));
        assertThat(protect.getPatterns(), hasItemInArray("org.protect."));
        for (String pattern : WebAppClassLoading.DEFAULT_PROTECTED_CLASSES)
            assertThat(protect.getPatterns(), hasItemInArray(pattern));
        assertThat(environment.getAttribute(WebAppClassLoading.PROTECTED_CLASSES_ATTRIBUTE), sameInstance(protect));

        assertThat(hide.size(), is(WebAppClassLoading.DEFAULT_HIDDEN_CLASSES.size() + 1));
        assertThat(hide.getPatterns(), hasItemInArray("org.hide."));
        for (String pattern : WebAppClassLoading.DEFAULT_HIDDEN_CLASSES)
            assertThat(hide.getPatterns(), hasItemInArray(pattern));
        assertThat(environment.getAttribute(WebAppClassLoading.HIDDEN_CLASSES_ATTRIBUTE), sameInstance(hide));
    }

    @Test
    public void testEnvironmentAddPatterns()
    {
        Environment environment = Environment.get("Test");
        ClassMatcher protect = WebAppClassLoading.getProtectedClasses(environment);
        ClassMatcher hide = WebAppClassLoading.getHiddenClasses(environment);

        assertThat(protect, equalTo(WebAppClassLoading.DEFAULT_PROTECTED_CLASSES));
        assertThat(hide, equalTo(WebAppClassLoading.DEFAULT_HIDDEN_CLASSES));

        WebAppClassLoading.addProtectedClasses(environment, "org.protect.", "com.protect.");
        WebAppClassLoading.addHiddenClasses(environment, "org.hide.", "com.hide.");

        assertThat(protect.size(), is(WebAppClassLoading.DEFAULT_PROTECTED_CLASSES.size() + 2));
        assertThat(protect.getPatterns(), hasItemInArray("org.protect."));
        assertThat(protect.getPatterns(), hasItemInArray("com.protect."));
        for (String pattern : WebAppClassLoading.DEFAULT_PROTECTED_CLASSES)
            assertThat(protect.getPatterns(), hasItemInArray(pattern));
        assertThat(environment.getAttribute(WebAppClassLoading.PROTECTED_CLASSES_ATTRIBUTE), sameInstance(protect));

        assertThat(hide.size(), is(WebAppClassLoading.DEFAULT_HIDDEN_CLASSES.size() + 2));
        assertThat(hide.getPatterns(), hasItemInArray("org.hide."));
        assertThat(hide.getPatterns(), hasItemInArray("com.hide."));
        for (String pattern : WebAppClassLoading.DEFAULT_HIDDEN_CLASSES)
            assertThat(hide.getPatterns(), hasItemInArray(pattern));
        assertThat(environment.getAttribute(WebAppClassLoading.HIDDEN_CLASSES_ATTRIBUTE), sameInstance(hide));
    }

}
