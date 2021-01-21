//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.osgi.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;

import aQute.bnd.osgi.Constants;
import org.eclipse.jetty.annotations.ClassInheritanceHandler;
import org.eclipse.jetty.osgi.annotations.AnnotationParser;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.tinybundles.core.TinyBundle;
import org.ops4j.pax.tinybundles.core.TinyBundles;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;


/**
 * TestJettyOSGiAnnotationParser
 *
 */

@RunWith(PaxExam.class)
public class TestJettyOSGiAnnotationParser
{
    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure()
    {
        ArrayList<Option> options = new ArrayList<>();
        options.add(TestOSGiUtil.optionalRemoteDebug());
        options.add(CoreOptions.junitBundles());
        options.addAll(TestOSGiUtil.coreJettyDependencies());
        options.add(mavenBundle().groupId("biz.aQute.bnd").artifactId("biz.aQute.bndlib").version("3.5.0").start());
        options.add(mavenBundle().groupId("org.ops4j.pax.tinybundles").artifactId("tinybundles").versionAsInProject().start());
        return options.toArray(new Option[options.size()]);
    }

    @Test
    public void testParse() throws Exception
    {
        //get a reference to a pre-prepared module-info
        Path path = Paths.get("src", "test", "resources", "module-info.clazz");
        File moduleInfo = path.toFile();
        assertTrue(moduleInfo.exists());
        
        TinyBundle bundle = TinyBundles.bundle();
        bundle.set(Constants.BUNDLE_SYMBOLICNAME, "bundle.with.module.info");
        bundle.add("module-info.class", new FileInputStream(moduleInfo)); //copy it into the fake bundle
        InputStream is = bundle.build();
        bundleContext.installBundle("dummyLocation", is);
        
        //test the osgi annotation parser ignore the module-info.class file in the fake bundle
        //Get a reference to the deployed fake bundle
        Bundle b = TestOSGiUtil.getBundle(bundleContext, "bundle.with.module.info");
        AnnotationParser parser = new AnnotationParser(0);
        parser.indexBundle(b);
        ClassInheritanceHandler handler = new ClassInheritanceHandler(new ConcurrentHashMap<>());
        parser.parse(Collections.singleton(handler), b);

    }
}
