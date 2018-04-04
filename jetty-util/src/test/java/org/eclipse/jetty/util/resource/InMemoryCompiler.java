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

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

public class InMemoryCompiler
{
    public static class Unit
    {
        public String classname;
        public String source;
        public byte[] bytecode;

        public Unit()
        {
        }

        public Unit(String name, String source)
        {
            this.classname = name;
            this.source = source;
        }

        public String getSourceFilename()
        {
            return classname.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension;
        }

        public String getClassFilename()
        {
            return classname.replace('.', '/') + JavaFileObject.Kind.CLASS.extension;
        }
    }

    private List<String> options;

    public InMemoryCompiler setSourceTarget(String source, String target)
    {
        options = Arrays.asList("-source", source, "-target", target, "-classpath", "");
        return this;
    }

    public Collection<Unit> compile(Collection<Unit> units)
    {
        Map<String, OutputFileObject> outputs = new HashMap<>();
        List<UnitFileObject> sources = new ArrayList<>();

        units.forEach((unit) -> {
            outputs.put(unit.classname, new OutputFileObject(unit.classname));
            sources.add(new UnitFileObject(unit));
        });

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        JavaFileManager javaFileManager = new CustomFileManager(compiler.getStandardFileManager(null, null, null), outputs);
        if (!compiler.getTask(null, javaFileManager, null, options, null, sources).call())
        {
            throw new RuntimeException("Compilation failed");
        }

        List<Unit> ret = new ArrayList<>();
        units.forEach((unit) -> {
            unit.bytecode = outputs.get(unit.classname).getBytes();
            ret.add(unit);
        });
        return ret;
    }

    private static class UnitFileObject extends SimpleJavaFileObject
    {
        private final Unit unit;

        public UnitFileObject(Unit unit)
        {
            super(URI.create("string:///" + unit.getSourceFilename()), JavaFileObject.Kind.SOURCE);
            this.unit = unit;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors)
        {
            return unit.source;
        }
    }

    private static class OutputFileObject extends SimpleJavaFileObject
    {
        private final ByteArrayOutputStream baos = new ByteArrayOutputStream();

        public OutputFileObject(String className)
        {
            super(URI.create(className), Kind.CLASS);
        }

        @Override
        public OutputStream openOutputStream()
        {
            return baos;
        }

        public byte[] getBytes()
        {
            return baos.toByteArray();
        }
    }

    private static class CustomFileManager extends ForwardingJavaFileManager<JavaFileManager>
    {
        private final Map<String, OutputFileObject> outputs;

        CustomFileManager(JavaFileManager jfm, Map<String, OutputFileObject> outputs)
        {
            super(jfm);
            this.outputs = outputs;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(JavaFileManager.Location loc, String name,
                                                   JavaFileObject.Kind kind, FileObject sibling)
        {
            OutputFileObject output = outputs.get(name);
            return output;
        }
    }
}
