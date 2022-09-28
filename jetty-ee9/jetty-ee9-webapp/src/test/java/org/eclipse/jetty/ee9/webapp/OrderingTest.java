//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.webapp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * OrderingTest
 */
public class OrderingTest
{
    WorkDir workDir;

    private Resource newTestableDirResource(String name) throws IOException
    {
        Path dir = workDir.getPath().resolve(name);
        if (!Files.exists(dir))
            Files.createDirectories(dir);
        return ResourceFactory.root().newResource(dir);
    }

    private Resource newTestableFileResource(String name) throws IOException
    {
        Path file = workDir.getPath().resolve(name);
        if (!Files.exists(file))
            Files.createFile(file);
        return ResourceFactory.root().newResource(file);
    }

    @BeforeEach
    public void beforeEach()
    {
        // ensure work dir exists, and is empty
        workDir.getEmptyPathDir();
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void afterEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @Test
    public void testRelativeOrdering0()
        throws Exception
    {
        //Example from ServletSpec p.70
        MetaData metaData = new MetaData();
        List<Resource> resources = new ArrayList<>();
        metaData._ordering = new RelativeOrdering(metaData);

        //A: after others, after C
        Resource jar1 = newTestableDirResource("A");
        resources.add(jar1);
        Resource r1 = newTestableFileResource("A/web-fragment.xml");
        FragmentDescriptor f1 = new FragmentDescriptor(r1);
        f1._name = "A";
        metaData._webFragmentNameMap.put(f1._name, f1);
        metaData._webFragmentResourceMap.put(jar1, f1);
        f1._otherType = FragmentDescriptor.OtherType.After;
        //((RelativeOrdering)metaData._ordering).addAfterOthers(r1);
        f1._afters.add("C");

        //B: before others
        Resource jar2 = newTestableDirResource("B");
        resources.add(jar2);
        Resource r2 = newTestableFileResource("B/web-fragment.xml");
        FragmentDescriptor f2 = new FragmentDescriptor(r2);
        f2._name = "B";
        metaData._webFragmentNameMap.put(f2._name, f2);
        metaData._webFragmentResourceMap.put(jar2, f2);
        f2._otherType = FragmentDescriptor.OtherType.Before;
        //((RelativeOrdering)metaData._ordering).addBeforeOthers(r2);

        //C: after others
        Resource jar3 = newTestableDirResource("C");
        resources.add(jar3);
        Resource r3 = newTestableFileResource("C/web-fragment.xml");
        FragmentDescriptor f3 = new FragmentDescriptor(r3);
        f3._name = "C";
        metaData._webFragmentNameMap.put(f3._name, f3);
        metaData._webFragmentResourceMap.put(jar3, f3);
        f3._otherType = FragmentDescriptor.OtherType.After;
        //((RelativeOrdering)metaData._ordering).addAfterOthers(r3);

        //D: no ordering
        Resource jar4 = newTestableDirResource("D");
        resources.add(jar4);
        Resource r4 = newTestableFileResource("D/web-fragment.xml");
        FragmentDescriptor f4 = new FragmentDescriptor(r4);
        f4._name = "D";
        metaData._webFragmentNameMap.put(f4._name, f4);
        metaData._webFragmentResourceMap.put(jar4, f4);
        f4._otherType = FragmentDescriptor.OtherType.None;
        //((RelativeOrdering)metaData._ordering).addNoOthers(r4);

        //E: no ordering
        Resource jar5 = newTestableDirResource("E");
        resources.add(jar5);
        Resource r5 = newTestableFileResource("E/web-fragment.xml");
        FragmentDescriptor f5 = new FragmentDescriptor(r5);
        f5._name = "E";
        metaData._webFragmentNameMap.put(f5._name, f5);
        metaData._webFragmentResourceMap.put(jar5, f5);
        f5._otherType = FragmentDescriptor.OtherType.None;
        //((RelativeOrdering)metaData._ordering).addNoOthers(r5);

        //F: before others, before B
        Resource jar6 = newTestableDirResource("F");
        resources.add(jar6);
        Resource r6 = newTestableFileResource("F/web-fragment.xml");
        FragmentDescriptor f6 = new FragmentDescriptor(r6);
        f6._name = "F";
        metaData._webFragmentNameMap.put(f6._name, f6);
        metaData._webFragmentResourceMap.put(jar6, f6);
        f6._otherType = FragmentDescriptor.OtherType.Before;
        //((RelativeOrdering)metaData._ordering).addBeforeOthers(r6);
        f6._befores.add("B");

        //
        // p.70 outcome: F, B, D, E, C, A
        //
        String[] outcomes = {"FBDECA"};
        List<Resource> orderedList = metaData._ordering.order(resources);

        StringBuilder result = new StringBuilder();
        for (Resource r : orderedList)
        {
            result.append(r.getFileName());
        }

        if (!checkResult(result.toString(), outcomes))
            fail("No outcome matched " + result);
    }

    @Test
    public void testRelativeOrdering1()
        throws Exception
    {
        List<Resource> resources = new ArrayList<>();
        MetaData metaData = new MetaData();
        metaData._ordering = new RelativeOrdering(metaData);

        //Example from ServletSpec p.70-71
        //No name: after others, before C
        Resource jar1 = newTestableDirResource("plain");
        resources.add(jar1);
        Resource r1 = newTestableFileResource("plain/web-fragment.xml");
        FragmentDescriptor f1 = new FragmentDescriptor(r1);
        f1._name = FragmentDescriptor.NAMELESS + "1";
        metaData._webFragmentNameMap.put(f1._name, f1);
        metaData._webFragmentResourceMap.put(jar1, f1);
        f1._otherType = FragmentDescriptor.OtherType.After;
        //((RelativeOrdering)metaData._ordering).addAfterOthers(f1);
        f1._befores.add("C");

        //B: before others
        Resource jar2 = newTestableDirResource("B");
        resources.add(jar2);
        Resource r2 = newTestableFileResource("B/web-fragment.xml");
        FragmentDescriptor f2 = new FragmentDescriptor(r2);
        f2._name = "B";
        metaData._webFragmentNameMap.put(f2._name, f2);
        metaData._webFragmentResourceMap.put(jar2, f2);
        f2._otherType = FragmentDescriptor.OtherType.Before;
        //((RelativeOrdering)metaData._ordering).addBeforeOthers(f2);

        //C: no ordering
        Resource jar3 = newTestableDirResource("C");
        resources.add(jar3);
        Resource r3 = newTestableFileResource("C/web-fragment.xml");
        FragmentDescriptor f3 = new FragmentDescriptor(r3);
        f3._name = "C";
        metaData._webFragmentNameMap.put(f3._name, f3);
        metaData._webFragmentResourceMap.put(jar3, f3);
        //((RelativeOrdering)metaData._ordering).addNoOthers(f3);
        f3._otherType = FragmentDescriptor.OtherType.None;

        //D: after others
        Resource jar4 = newTestableDirResource("D");
        resources.add(jar4);
        Resource r4 = newTestableFileResource("D/web-fragment.xml");
        FragmentDescriptor f4 = new FragmentDescriptor(r4);
        f4._name = "D";
        metaData._webFragmentNameMap.put(f4._name, f4);
        metaData._webFragmentResourceMap.put(jar4, f4);
        //((RelativeOrdering)metaData._ordering).addAfterOthers(f4);
        f4._otherType = FragmentDescriptor.OtherType.After;

        //E: before others
        Resource jar5 = newTestableDirResource("E");
        resources.add(jar5);
        Resource r5 = newTestableFileResource("E/web-fragment.xml");
        FragmentDescriptor f5 = new FragmentDescriptor(r5);
        f5._name = "E";
        metaData._webFragmentNameMap.put(f5._name, f5);
        metaData._webFragmentResourceMap.put(jar5, f5);
        //((RelativeOrdering)metaData._ordering).addBeforeOthers(f5);
        f5._otherType = FragmentDescriptor.OtherType.Before;

        //F: no ordering
        Resource jar6 = newTestableDirResource("F");
        resources.add(jar6);
        Resource r6 = newTestableFileResource("F/web-fragment.xml");
        FragmentDescriptor f6 = new FragmentDescriptor(r6);
        f6._name = "F";
        metaData._webFragmentNameMap.put(f6._name, f6);
        metaData._webFragmentResourceMap.put(jar6, f6);
        //((RelativeOrdering)metaData._ordering).addNoOthers(f6);
        f6._otherType = FragmentDescriptor.OtherType.None;

        List<Resource> orderedList = metaData._ordering.order(resources);

        // p.70-71 Possible outcomes are:
        // B, E, F, noname, C, D
        // B, E, F, noname, D, C
        // E, B, F, noname, C, D
        // E, B, F, noname, D, C
        // E, B, F, D, noname, C
        //
        String[] outcomes = {
            "BEFplainCD",
            "BEFplainDC",
            "EBFplainCD",
            "EBFplainDC",
            "EBFDplainC"
        };

        StringBuilder orderedNames = new StringBuilder();
        for (Resource r : orderedList)
        {
            orderedNames.append(r.getFileName());
        }

        if (!checkResult(orderedNames.toString(), outcomes))
            fail("No outcome matched " + orderedNames);
    }

    @Test
    public void testRelativeOrdering2()
        throws Exception
    {
        List<Resource> resources = new ArrayList<>();
        MetaData metaData = new MetaData();
        metaData._ordering = new RelativeOrdering(metaData);

        //Example from Spec p. 71-72

        //A: after B
        Resource jar1 = newTestableDirResource("A");
        resources.add(jar1);
        Resource r1 = newTestableFileResource("A/web-fragment.xml");
        FragmentDescriptor f1 = new FragmentDescriptor(r1);
        f1._name = "A";
        metaData._webFragmentNameMap.put(f1._name, f1);
        metaData._webFragmentResourceMap.put(jar1, f1);
        //((RelativeOrdering)metaData._ordering).addNoOthers(f1);
        f1._otherType = FragmentDescriptor.OtherType.None;
        f1._afters.add("B");

        //B: no order
        Resource jar2 = newTestableDirResource("B");
        resources.add(jar2);
        Resource r2 = newTestableFileResource("B/web-fragment.xml");
        FragmentDescriptor f2 = new FragmentDescriptor(r2);
        f2._name = "B";
        metaData._webFragmentNameMap.put(f2._name, f2);
        metaData._webFragmentResourceMap.put(jar2, f2);
        //((RelativeOrdering)metaData._ordering).addNoOthers(f2);
        f2._otherType = FragmentDescriptor.OtherType.None;

        //C: before others
        Resource jar3 = newTestableDirResource("C");
        resources.add(jar3);
        Resource r3 = newTestableFileResource("C/web-fragment.xml");
        FragmentDescriptor f3 = new FragmentDescriptor(r3);
        f3._name = "C";
        metaData._webFragmentNameMap.put(f3._name, f3);
        metaData._webFragmentResourceMap.put(jar3, f3);
        //((RelativeOrdering)metaData._ordering).addBeforeOthers(f3);
        f3._otherType = FragmentDescriptor.OtherType.Before;

        //D: no order
        Resource jar4 = newTestableDirResource("D");
        resources.add(jar4);
        Resource r4 = newTestableFileResource("D/web-fragment.xml");
        FragmentDescriptor f4 = new FragmentDescriptor(r4);
        f4._name = "D";
        metaData._webFragmentNameMap.put(f4._name, f4);
        metaData._webFragmentResourceMap.put(jar4, f4);
        //((RelativeOrdering)metaData._ordering).addNoOthers(f4);
        f4._otherType = FragmentDescriptor.OtherType.None;
        //
        // p.71-72 possible outcomes are:
        // C,B,D,A
        // C,D,B,A
        // C,B,A,D
        //
        String[] outcomes = {
            "CBDA",
            "CDBA",
            "CBAD"
        };

        List<Resource> orderedList = metaData._ordering.order(resources);
        StringBuilder result = new StringBuilder();
        for (Resource r : orderedList)
        {
            result.append(r.getFileName());
        }

        if (!checkResult(result.toString(), outcomes))
            fail("No outcome matched " + result);
    }

    @Test
    public void testRelativeOrdering3()
        throws Exception
    {
        List<Resource> resources = new ArrayList<>();
        MetaData metaData = new MetaData();
        metaData._ordering = new RelativeOrdering(metaData);

        //A: after others, before C
        Resource jar1 = newTestableDirResource("A");
        resources.add(jar1);
        Resource r1 = newTestableFileResource("A/web-fragment.xml");
        FragmentDescriptor f1 = new FragmentDescriptor(r1);
        f1._name = "A";
        metaData._webFragmentNameMap.put(f1._name, f1);
        metaData._webFragmentResourceMap.put(jar1, f1);
        //((RelativeOrdering)metaData._ordering).addAfterOthers(f1);
        f1._otherType = FragmentDescriptor.OtherType.After;
        f1._befores.add("C");

        //B: before others, before C
        Resource jar2 = newTestableDirResource("B");
        resources.add(jar2);
        Resource r2 = newTestableFileResource("B/web-fragment.xml");
        FragmentDescriptor f2 = new FragmentDescriptor(r2);
        f2._name = "B";
        metaData._webFragmentNameMap.put(f2._name, f2);
        metaData._webFragmentResourceMap.put(jar2, f2);
        //((RelativeOrdering)metaData._ordering).addBeforeOthers(f2);
        f2._otherType = FragmentDescriptor.OtherType.Before;
        f2._befores.add("C");

        //C: no ordering
        Resource jar3 = newTestableDirResource("C");
        resources.add(jar3);
        Resource r3 = newTestableFileResource("C/web-fragment.xml");
        FragmentDescriptor f3 = new FragmentDescriptor(r3);
        f3._name = "C";
        metaData._webFragmentNameMap.put(f3._name, f3);
        metaData._webFragmentResourceMap.put(jar3, f3);
        //((RelativeOrdering)metaData._ordering).addNoOthers(f3);
        f3._otherType = FragmentDescriptor.OtherType.None;

        //result: BAC
        String[] outcomes = {"BAC"};

        List<Resource> orderedList = metaData._ordering.order(resources);
        StringBuilder result = new StringBuilder();
        for (Resource r : orderedList)
        {
            result.append(r.getFileName());
        }

        if (!checkResult(result.toString(), outcomes))
            fail("No outcome matched " + result);
    }

    @Test
    public void testOrderFragments() throws Exception
    {
        final MetaData metadata = new MetaData();
        final Resource jarResource = newTestableDirResource("A");

        metadata.setOrdering(new RelativeOrdering(metadata));
        metadata.addWebInfResource(jarResource);
        metadata.orderFragments();
        assertEquals(1, metadata.getWebInfResources(true).size());
        metadata.orderFragments();
        assertEquals(1, metadata.getWebInfResources(true).size());
    }

    @Test
    public void testCircular1()
        throws Exception
    {

        //A: after B
        //B: after A
        List<Resource> resources = new ArrayList<>();
        MetaData metaData = new MetaData();
        metaData._ordering = new RelativeOrdering(metaData);

        //A: after B
        Resource jar1 = newTestableDirResource("A");
        resources.add(jar1);
        Resource r1 = newTestableFileResource("A/web-fragment.xml");
        FragmentDescriptor f1 = new FragmentDescriptor(r1);
        f1._name = "A";
        metaData._webFragmentNameMap.put(f1._name, f1);
        metaData._webFragmentResourceMap.put(jar1, f1);
        //((RelativeOrdering)metaData._ordering).addNoOthers(f1);
        f1._otherType = FragmentDescriptor.OtherType.None;
        f1._afters.add("B");

        //B: after A
        Resource jar2 = newTestableDirResource("B");
        resources.add(jar2);
        Resource r2 = newTestableFileResource("B/web-fragment.xml");
        FragmentDescriptor f2 = new FragmentDescriptor(r2);
        f2._name = "B";
        metaData._webFragmentNameMap.put(f2._name, f2);
        metaData._webFragmentResourceMap.put(jar2, f2);
        //((RelativeOrdering)metaData._ordering).addNoOthers(f2);
        f2._otherType = FragmentDescriptor.OtherType.None;
        f2._afters.add("A");

        assertThrows(IllegalStateException.class, () ->
        {
            metaData._ordering.order(resources);
            fail("No circularity detected");
        });
    }

    @Test
    public void testInvalid1()
        throws Exception
    {
        List<Resource> resources = new ArrayList<>();
        MetaData metaData = new MetaData();
        metaData._ordering = new RelativeOrdering(metaData);

        //A: after others, before C
        Resource jar1 = newTestableDirResource("A");
        resources.add(jar1);
        Resource r1 = newTestableFileResource("A/web-fragment.xml");
        FragmentDescriptor f1 = new FragmentDescriptor(r1);
        f1._name = "A";
        metaData._webFragmentNameMap.put(f1._name, f1);
        metaData._webFragmentResourceMap.put(jar1, f1);
        //((RelativeOrdering)metaData._ordering).addAfterOthers(r1);
        f1._otherType = FragmentDescriptor.OtherType.After;
        f1._befores.add("C");

        //B: before others, after C
        Resource jar2 = newTestableDirResource("B");
        resources.add(jar2);
        Resource r2 = newTestableFileResource("B/web-fragment.xml");
        FragmentDescriptor f2 = new FragmentDescriptor(r2);
        f2._name = "B";
        metaData._webFragmentNameMap.put(f2._name, f2);
        metaData._webFragmentResourceMap.put(jar2, f2);
        //((RelativeOrdering)metaData._ordering).addBeforeOthers(r2);
        f2._otherType = FragmentDescriptor.OtherType.Before;
        f2._afters.add("C");

        //C: no ordering
        Resource jar3 = newTestableDirResource("C");
        resources.add(jar3);
        Resource r3 = newTestableFileResource("C/web-fragment.xml");
        FragmentDescriptor f3 = new FragmentDescriptor(r3);
        f3._name = "C";
        metaData._webFragmentNameMap.put(f3._name, f3);
        metaData._webFragmentResourceMap.put(jar3, f3);
        //((RelativeOrdering)metaData._ordering).addNoOthers(r3);
        f3._otherType = FragmentDescriptor.OtherType.None;

        assertThrows(IllegalStateException.class, () ->
        {
            List<Resource> orderedList = metaData._ordering.order(resources);
            StringBuilder result = new StringBuilder();
            for (Resource r : orderedList)
            {
                result.append(r.getFileName());
            }
            System.err.println("Invalid Result = " + result);
            fail("A and B have an impossible relationship to C");
        });
    }

    @Test
    public void testAbsoluteOrdering1()
        throws Exception
    {
        //
        // A,B,C,others
        //
        List<Resource> resources = new ArrayList<>();
        MetaData metaData = new MetaData();
        metaData._ordering = new AbsoluteOrdering(metaData);
        ((AbsoluteOrdering)metaData._ordering).add("A");
        ((AbsoluteOrdering)metaData._ordering).add("B");
        ((AbsoluteOrdering)metaData._ordering).add("C");
        ((AbsoluteOrdering)metaData._ordering).addOthers();

        Resource jar1 = newTestableDirResource("A");
        resources.add(jar1);
        Resource r1 = newTestableFileResource("A/web-fragment.xml");
        FragmentDescriptor f1 = new FragmentDescriptor(r1);
        f1._name = "A";
        metaData._webFragmentNameMap.put(f1._name, f1);
        metaData._webFragmentResourceMap.put(jar1, f1);

        Resource jar2 = newTestableDirResource("B");
        resources.add(jar2);
        Resource r2 = newTestableFileResource("B/web-fragment.xml");
        FragmentDescriptor f2 = new FragmentDescriptor(r2);
        f2._name = "B";
        metaData._webFragmentNameMap.put(f2._name, f2);
        metaData._webFragmentResourceMap.put(jar2, f2);

        Resource jar3 = newTestableDirResource("C");
        resources.add(jar3);
        Resource r3 = newTestableFileResource("C/web-fragment.xml");
        FragmentDescriptor f3 = new FragmentDescriptor(r3);
        f3._name = "C";
        metaData._webFragmentNameMap.put(f3._name, f3);
        metaData._webFragmentResourceMap.put(jar3, f3);

        Resource jar4 = newTestableDirResource("D");
        resources.add(jar4);
        Resource r4 = newTestableFileResource("D/web-fragment.xml");
        FragmentDescriptor f4 = new FragmentDescriptor(r4);
        f4._name = "D";
        metaData._webFragmentNameMap.put(f4._name, f4);
        metaData._webFragmentResourceMap.put(jar4, f4);

        Resource jar5 = newTestableDirResource("E");
        resources.add(jar5);
        Resource r5 = newTestableFileResource("E/web-fragment.xml");
        FragmentDescriptor f5 = new FragmentDescriptor(r5);
        f5._name = "E";
        metaData._webFragmentNameMap.put(f5._name, f5);
        metaData._webFragmentResourceMap.put(jar5, f5);

        Resource jar6 = newTestableDirResource("plain");
        resources.add(jar6);
        Resource r6 = newTestableFileResource("plain/web-fragment.xml");
        FragmentDescriptor f6 = new FragmentDescriptor(r6);
        f6._name = FragmentDescriptor.NAMELESS + "1";
        metaData._webFragmentNameMap.put(f6._name, f6);
        metaData._webFragmentResourceMap.put(jar6, f6);

        List<Resource> list = metaData._ordering.order(resources);

        String[] outcomes = {"ABCDEplain"};
        StringBuilder result = new StringBuilder();
        for (Resource r : list)
        {
            result.append(r.getFileName());
        }

        if (!checkResult(result.toString(), outcomes))
            fail("No outcome matched " + result);
    }

    @Test
    public void testAbsoluteOrdering2()
        throws Exception
    {
        // C,B,A
        List<Resource> resources = new ArrayList<>();

        MetaData metaData = new MetaData();
        metaData._ordering = new AbsoluteOrdering(metaData);
        ((AbsoluteOrdering)metaData._ordering).add("C");
        ((AbsoluteOrdering)metaData._ordering).add("B");
        ((AbsoluteOrdering)metaData._ordering).add("A");

        Resource jar1 = newTestableDirResource("A");
        resources.add(jar1);
        Resource r1 = newTestableFileResource("A/web-fragment.xml");
        FragmentDescriptor f1 = new FragmentDescriptor(r1);
        f1._name = "A";
        metaData._webFragmentNameMap.put(f1._name, f1);
        metaData._webFragmentResourceMap.put(jar1, f1);

        Resource jar2 = newTestableDirResource("B");
        resources.add(jar2);
        Resource r2 = newTestableFileResource("B/web-fragment.xml");
        FragmentDescriptor f2 = new FragmentDescriptor(r2);
        f2._name = "B";
        metaData._webFragmentNameMap.put(f2._name, f2);
        metaData._webFragmentResourceMap.put(jar2, f2);

        Resource jar3 = newTestableDirResource("C");
        resources.add(jar3);
        Resource r3 = newTestableFileResource("C/web-fragment.xml");
        FragmentDescriptor f3 = new FragmentDescriptor(r3);
        f3._name = "C";
        metaData._webFragmentNameMap.put(f3._name, f3);
        metaData._webFragmentResourceMap.put(jar3, f3);

        Resource jar4 = newTestableDirResource("D");
        resources.add(jar4);
        Resource r4 = newTestableFileResource("D/web-fragment.xml");
        FragmentDescriptor f4 = new FragmentDescriptor(r4);
        f4._name = "D";
        metaData._webFragmentNameMap.put(f4._name, f4);
        metaData._webFragmentResourceMap.put(jar4, f4);

        Resource jar5 = newTestableDirResource("E");
        resources.add(jar5);
        Resource r5 = newTestableFileResource("E/web-fragment.xml");
        FragmentDescriptor f5 = new FragmentDescriptor(r5);
        f5._name = "E";
        metaData._webFragmentNameMap.put(f5._name, f5);
        metaData._webFragmentResourceMap.put(jar5, f5);

        Resource jar6 = newTestableDirResource("plain");
        resources.add(jar6);
        Resource r6 = newTestableFileResource("plain/web-fragment.xml");
        FragmentDescriptor f6 = new FragmentDescriptor(r6);
        f6._name = FragmentDescriptor.NAMELESS + "1";
        metaData._webFragmentNameMap.put(f6._name, f6);
        metaData._webFragmentResourceMap.put(jar6, f6);

        List<Resource> list = metaData._ordering.order(resources);
        String[] outcomes = {"CBA"};
        StringBuilder result = new StringBuilder();
        for (Resource r : list)
        {
            result.append(r.getFileName());
        }

        if (!checkResult(result.toString(), outcomes))
            fail("No outcome matched " + result);
    }

    @Test
    public void testAbsoluteOrdering3()
        throws Exception
    {
        //empty <absolute-ordering>

        MetaData metaData = new MetaData();
        metaData._ordering = new AbsoluteOrdering(metaData);
        List<Resource> resources = new ArrayList<>();

        resources.add(newTestableDirResource("A"));
        resources.add(newTestableDirResource("B"));

        List<Resource> list = metaData._ordering.order(resources);
        assertThat(list, is(empty()));
    }

    @Test
    public void testRelativeOrderingWithPlainJars()
        throws Exception
    {
        //B,A,C other jars with no fragments
        List<Resource> resources = new ArrayList<>();
        MetaData metaData = new MetaData();
        metaData._ordering = new RelativeOrdering(metaData);

        //A: after others, before C
        Resource jar1 = newTestableDirResource("A");
        resources.add(jar1);
        Resource r1 = newTestableFileResource("A/web-fragment.xml");
        FragmentDescriptor f1 = new FragmentDescriptor(r1);
        f1._name = "A";
        metaData._webFragmentNameMap.put(f1._name, f1);
        metaData._webFragmentResourceMap.put(jar1, f1);
        //((RelativeOrdering)metaData._ordering).addAfterOthers(f1);
        f1._otherType = FragmentDescriptor.OtherType.After;
        f1._befores.add("C");

        //B: before others, before C
        Resource jar2 = newTestableDirResource("B");
        resources.add(jar2);
        Resource r2 = newTestableFileResource("B/web-fragment.xml");
        FragmentDescriptor f2 = new FragmentDescriptor(r2);
        f2._name = "B";
        metaData._webFragmentNameMap.put(f2._name, f2);
        metaData._webFragmentResourceMap.put(jar2, f2);
        //((RelativeOrdering)metaData._ordering).addBeforeOthers(f2);
        f2._otherType = FragmentDescriptor.OtherType.Before;
        f2._befores.add("C");

        //C: after A
        Resource jar3 = newTestableDirResource("C");
        resources.add(jar3);
        Resource r3 = newTestableFileResource("C/web-fragment.xml");
        FragmentDescriptor f3 = new FragmentDescriptor(r3);
        f3._name = "C";
        metaData._webFragmentNameMap.put(f3._name, f3);
        metaData._webFragmentResourceMap.put(jar3, f3);
        //((RelativeOrdering)metaData._ordering).addNoOthers(f3);
        f3._otherType = FragmentDescriptor.OtherType.None;
        f3._afters.add("A");

        //No fragment jar 1
        Resource r4 = newTestableFileResource("plain1");
        resources.add(r4);

        //No fragment jar 2
        Resource r5 = newTestableFileResource("plain2");
        resources.add(r5);

        //result: BAC
        String[] outcomes = {"Bplain1plain2AC"};

        List<Resource> orderedList = metaData._ordering.order(resources);
        StringBuilder result = new StringBuilder();
        for (Resource r : orderedList)
        {
            result.append(r.getFileName());
        }

        if (!checkResult(result.toString(), outcomes))
            fail("No outcome matched " + result);
    }

    @Test
    public void testRelativeOrderingWithPlainJars2()
        throws Exception
    {
        //web.xml has no ordering, jar A has fragment after others, jar B is plain, jar C is plain
        List<Resource> resources = new ArrayList<>();
        MetaData metaData = new MetaData();
        metaData._ordering = new RelativeOrdering(metaData);

        //A has after others
        Resource jar1 = newTestableDirResource("A");
        resources.add(jar1);
        Resource r1 = newTestableFileResource("A/web-fragment.xml");
        FragmentDescriptor f1 = new FragmentDescriptor(r1);
        f1._name = "A";
        metaData._webFragmentNameMap.put(f1._name, f1);
        metaData._webFragmentResourceMap.put(jar1, f1);
        f1._otherType = FragmentDescriptor.OtherType.After;

        //No fragment jar B
        Resource r4 = newTestableFileResource("plainB");
        resources.add(r4);

        //No fragment jar C
        Resource r5 = newTestableFileResource("plainC");
        resources.add(r5);

        List<Resource> orderedList = metaData._ordering.order(resources);
        String[] outcomes = {"plainBplainCA"};
        StringBuilder result = new StringBuilder();
        for (Resource r : orderedList)
        {
            result.append(r.getFileName());
        }

        if (!checkResult(result.toString(), outcomes))
            fail("No outcome matched " + result);
    }

    @Test
    public void testAbsoluteOrderingWithPlainJars()
        throws Exception
    {
        //
        // A,B,C,others
        //
        List<Resource> resources = new ArrayList<>();
        MetaData metaData = new MetaData();
        metaData._ordering = new AbsoluteOrdering(metaData);
        ((AbsoluteOrdering)metaData._ordering).add("A");
        ((AbsoluteOrdering)metaData._ordering).add("B");
        ((AbsoluteOrdering)metaData._ordering).add("C");
        ((AbsoluteOrdering)metaData._ordering).addOthers();

        Resource jar1 = newTestableDirResource("A");
        resources.add(jar1);
        Resource r1 = newTestableFileResource("A/web-fragment.xml");
        FragmentDescriptor f1 = new FragmentDescriptor(r1);
        f1._name = "A";
        metaData._webFragmentNameMap.put(f1._name, f1);
        metaData._webFragmentResourceMap.put(jar1, f1);

        Resource jar2 = newTestableDirResource("B");
        resources.add(jar2);
        Resource r2 = newTestableFileResource("B/web-fragment.xml");
        FragmentDescriptor f2 = new FragmentDescriptor(r2);
        f2._name = "B";
        metaData._webFragmentNameMap.put(f2._name, f2);
        metaData._webFragmentResourceMap.put(jar2, f2);

        Resource jar3 = newTestableDirResource("C");
        resources.add(jar3);
        Resource r3 = newTestableFileResource("C/web-fragment.xml");
        FragmentDescriptor f3 = new FragmentDescriptor(r3);
        f3._name = "C";
        metaData._webFragmentNameMap.put(f3._name, f3);
        metaData._webFragmentResourceMap.put(jar3, f3);

        Resource jar4 = newTestableDirResource("D");
        resources.add(jar4);
        Resource r4 = newTestableFileResource("D/web-fragment.xml");
        FragmentDescriptor f4 = new FragmentDescriptor(r4);
        f4._name = "D";
        metaData._webFragmentNameMap.put(f4._name, f4);
        metaData._webFragmentResourceMap.put(jar4, f4);

        Resource jar5 = newTestableDirResource("E");
        resources.add(jar5);
        Resource r5 = newTestableFileResource("E/web-fragment.xml");
        FragmentDescriptor f5 = new FragmentDescriptor(r5);
        f5._name = "E";
        metaData._webFragmentNameMap.put(f5._name, f5);
        metaData._webFragmentResourceMap.put(jar5, f5);

        Resource jar6 = newTestableDirResource("plain");
        resources.add(jar6);
        Resource r6 = newTestableFileResource("plain/web-fragment.xml");
        FragmentDescriptor f6 = new FragmentDescriptor(r6);
        f6._name = FragmentDescriptor.NAMELESS + "1";
        metaData._webFragmentNameMap.put(f6._name, f6);
        metaData._webFragmentResourceMap.put(jar6, f6);

        //plain jar
        Resource r7 = newTestableFileResource("plain1");
        resources.add(r7);

        Resource r8 = newTestableFileResource("plain2");
        resources.add(r8);

        List<Resource> list = metaData._ordering.order(resources);

        String[] outcomes = {"ABCDEplainplain1plain2"};
        StringBuilder result = new StringBuilder();
        for (Resource r : list)
        {
            result.append(r.getFileName());
        }

        if (!checkResult(result.toString(), outcomes))
            fail("No outcome matched " + result);
    }

    public boolean checkResult(String result, String[] outcomes)
    {
        for (String s : outcomes)
        {
            if (s.equals(result))
                return true;
        }
        return false;
    }
}
