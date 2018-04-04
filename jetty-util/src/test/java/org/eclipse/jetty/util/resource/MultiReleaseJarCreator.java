//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.resource;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;

public class MultiReleaseJarCreator
{
    public static final String CODE_HELLO = "" +
            "package hello;\n" +
            "public class Hello {\n" +
            "  public static void main(String args[]) {\n" +
            "    Greetings greetings = new Greetings();\n" +
            "    System.out.println(greetings.get());\n" +
            "  }\n" +
            "}\n";

    public static final String CODE_GREETINGS_BASE = "" +
            "package hello;\n" +
            "public class Greetings {\n" +
            "  public String get() {\n" +
            "    return \"Hello from zipfs base.\";\n" +
            "  }\n" +
            "}\n";

    public static final String CODE_GREETINGS_VER9 = "" +
            "package hello;\n" +
            "public class Greetings {\n" +
            "  public String get() {\n" +
            "    return \"Hello from versions/9.\";\n" +
            "  }\n" +
            "}\n";

    public static final String CODE_GREETINGS_VER10 = "" +
            "package hello;\n" +
            "public class Greetings {\n" +
            "  public String get() {\n" +
            "    DetailedVer ver = new DetailedVer();\n" +
            "    return \"Hello from versions/\" + ver.get();\n" +
            "  }\n" +
            "}\n";

    public static final String CODE_DETAILED_VER10 = "" +
            "package hello;\n" +
            "public class DetailedVer {\n" +
            "  public int get() {\n" +
            "    return 10;\n" +
            "  }\n" +
            "}\n";

    public static final String CODE_DETAILED_VER11 = "" +
            "package hello;\n" +
            "public class DetailedVer {\n" +
            "  public int get() {\n" +
            "    return Integer.parseInt(\"1\" + \"1\");\n" +
            "  }\n" +
            "}\n";

    public static final String README_ROOT = "Hello README (from root)";
    public static final String README_VER9 = "README Hello (from versions/9)";
    public static final String README_VER11 = "README Hello (from versions/11)";

    private final Path outputDir;

    public MultiReleaseJarCreator(TestingDir testingdir)
    {
        this(testingdir.getPath());
    }

    public MultiReleaseJarCreator(Path outputDir)
    {
        this.outputDir = outputDir;
    }

    public Path createBasicJar() throws IOException
    {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_TITLE, "Basic Jar Example");

        InMemoryCompiler compiler = new InMemoryCompiler().setSourceTarget("8", "8");

        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("README.txt", README_ROOT));
        AddUnitEntries unitEntriesConsumer = new AddUnitEntries(entries);

        List<InMemoryCompiler.Unit> units = new ArrayList<>();
        units.add(new InMemoryCompiler.Unit("hello.Hello", CODE_HELLO));
        units.add(new InMemoryCompiler.Unit("hello.Greetings", CODE_GREETINGS_BASE));

        compiler.compile(units).stream().forEach(unitEntriesConsumer);

        return createJar(outputDir.resolve("basic.jar"), manifest, entries);
    }

    public Path createMultiReleaseJar9() throws IOException
    {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_TITLE, "Multi-Release Jar Example");
        manifest.getMainAttributes().putValue("Multi-Release", "true");

        InMemoryCompiler compiler8 = new InMemoryCompiler().setSourceTarget("8", "8");

        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("README.txt", README_ROOT));
        entries.add(new Entry("META-INF/versions/9/README.txt", README_VER9));
        AddUnitEntries unitEntriesConsumer = new AddUnitEntries(entries);

        List<InMemoryCompiler.Unit> units8 = new ArrayList<>();
        units8.add(new InMemoryCompiler.Unit("hello.Hello", CODE_HELLO));
        units8.add(new InMemoryCompiler.Unit("hello.Greetings", CODE_GREETINGS_BASE));
        compiler8.compile(units8).stream().forEach(unitEntriesConsumer);

        List<InMemoryCompiler.Unit> units9 = new ArrayList<>();
        units9.add(new InMemoryCompiler.Unit("hello.Greetings", CODE_GREETINGS_VER9));
        unitEntriesConsumer.setPathPrefix("META-INF/versions/9/");
        compiler8.compile(units9).stream().forEach(unitEntriesConsumer);

        return createJar(outputDir.resolve("multirelease-9.jar"), manifest, entries);
    }

    public Path createMultiReleaseJar10() throws IOException
    {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_TITLE, "Multi-Release Jar Example");
        manifest.getMainAttributes().putValue("Multi-Release", "true");

        InMemoryCompiler compiler8 = new InMemoryCompiler().setSourceTarget("8", "8");

        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("README.txt", README_ROOT));
        entries.add(new Entry("META-INF/versions/9/README.txt", README_VER9));
        AddUnitEntries unitEntriesConsumer = new AddUnitEntries(entries);

        List<InMemoryCompiler.Unit> units8 = new ArrayList<>();
        units8.add(new InMemoryCompiler.Unit("hello.Hello", CODE_HELLO));
        units8.add(new InMemoryCompiler.Unit("hello.Greetings", CODE_GREETINGS_BASE));
        compiler8.compile(units8).stream().forEach(unitEntriesConsumer);

        List<InMemoryCompiler.Unit> units9 = new ArrayList<>();
        units9.add(new InMemoryCompiler.Unit("hello.Greetings", CODE_GREETINGS_VER9));
        unitEntriesConsumer.setPathPrefix("META-INF/versions/9/");
        compiler8.compile(units9).stream().forEach(unitEntriesConsumer);

        List<InMemoryCompiler.Unit> units10 = new ArrayList<>();
        units10.add(new InMemoryCompiler.Unit("hello.Greetings", CODE_GREETINGS_VER10));
        units10.add(new InMemoryCompiler.Unit("hello.DetailedVer", CODE_DETAILED_VER10));
        unitEntriesConsumer.setPathPrefix("META-INF/versions/10/");
        compiler8.compile(units10).stream().forEach(unitEntriesConsumer);

        return createJar(outputDir.resolve("multirelease-10.jar"), manifest, entries);
    }

    public Path createMultiReleaseJar11() throws IOException
    {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_TITLE, "Multi-Release Jar Example");
        manifest.getMainAttributes().putValue("Multi-Release", "true");

        InMemoryCompiler compiler8 = new InMemoryCompiler().setSourceTarget("8", "8");

        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("README.txt", README_ROOT));
        entries.add(new Entry("META-INF/versions/9/README.txt", README_VER9));
        entries.add(new Entry("META-INF/versions/11/README.txt", README_VER11));
        AddUnitEntries unitEntriesConsumer = new AddUnitEntries(entries);

        List<InMemoryCompiler.Unit> units8 = new ArrayList<>();
        units8.add(new InMemoryCompiler.Unit("hello.Hello", CODE_HELLO));
        units8.add(new InMemoryCompiler.Unit("hello.Greetings", CODE_GREETINGS_BASE));
        compiler8.compile(units8).stream().forEach(unitEntriesConsumer);

        List<InMemoryCompiler.Unit> units9 = new ArrayList<>();
        units9.add(new InMemoryCompiler.Unit("hello.Greetings", CODE_GREETINGS_VER9));
        unitEntriesConsumer.setPathPrefix("META-INF/versions/9/");
        compiler8.compile(units9).stream().forEach(unitEntriesConsumer);

        List<InMemoryCompiler.Unit> units10 = new ArrayList<>();
        units10.add(new InMemoryCompiler.Unit("hello.Greetings", CODE_GREETINGS_VER10));
        units10.add(new InMemoryCompiler.Unit("hello.DetailedVer", CODE_DETAILED_VER10));
        unitEntriesConsumer.setPathPrefix("META-INF/versions/10/");
        compiler8.compile(units10).stream().forEach(unitEntriesConsumer);

        List<InMemoryCompiler.Unit> units11 = new ArrayList<>();
        units11.add(new InMemoryCompiler.Unit("hello.DetailedVer", CODE_DETAILED_VER11));
        unitEntriesConsumer.setPathPrefix("META-INF/versions/11/");
        compiler8.compile(units11).stream().forEach(unitEntriesConsumer);

        return createJar(outputDir.resolve("multirelease-11.jar"), manifest, entries);
    }

    private Path createJar(Path jarpath, Manifest manifest, List<Entry> entries) throws IOException
    {
        try (OutputStream outputStream = Files.newOutputStream(jarpath);
             JarOutputStream jarOutputStream = new JarOutputStream(outputStream, manifest))
        {
            entries.forEach((entry) -> {
                JarEntry je = new JarEntry(entry.name);
                try
                {
                    jarOutputStream.putNextEntry(je);
                    jarOutputStream.write(entry.contents);
                    jarOutputStream.closeEntry();
                }
                catch (IOException iox)
                {
                    throw new RuntimeException(iox);
                }
            });
        }

        return jarpath;
    }

    public static class Entry
    {
        String name;
        byte[] contents;

        public Entry() {}
        public Entry(String name, String contents) {
            this.name = name;
            this.contents = contents.getBytes(UTF_8);
        }
    }

    private static class AddUnitEntries implements Consumer<InMemoryCompiler.Unit>
    {
        private List<Entry> entries;
        private String pathPrefix = "";

        public AddUnitEntries(List<Entry> entries)
        {
            this.entries = entries;
        }

        @Override
        public void accept(InMemoryCompiler.Unit unit)
        {
            // Class file entry
            Entry classEntry = new Entry();
            classEntry.name = pathPrefix + unit.getClassFilename();
            classEntry.contents = unit.bytecode;
            entries.add(classEntry);
            // Java source file entry
            Entry srcEntry = new Entry();
            srcEntry.name = pathPrefix + unit.getSourceFilename();
            srcEntry.contents = unit.source.getBytes(UTF_8);
            entries.add(srcEntry);
        }

        public void setPathPrefix(String pathPrefix)
        {
            this.pathPrefix = pathPrefix;
        }
    }

    public static void main(String[] args) throws IOException, URISyntaxException
    {
        Path outputDir = MavenTestingUtils.getTargetTestingPath();
        FS.ensureDirExists(outputDir);
        MultiReleaseJarCreator creator = new MultiReleaseJarCreator(outputDir);

        System.out.println("basic-jar = " + creator.createBasicJar());
        System.out.println("multirelease-jar/9 = " + creator.createMultiReleaseJar9());
        System.out.println("multirelease-jar/10 = " + creator.createMultiReleaseJar10());
        System.out.println("multirelease-jar/11 = " + creator.createMultiReleaseJar11());
    }
}
