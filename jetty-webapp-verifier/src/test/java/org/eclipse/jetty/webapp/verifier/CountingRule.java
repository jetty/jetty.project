// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.webapp.verifier;

import java.io.File;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

public class CountingRule extends AbstractRule
{
    public int countWebappStart = 0;
    public int countDirStart = 0;
    public int countFile = 0;
    public int countDirEnd = 0;
    public int countWebInfClassesStart = 0;
    public int countWebInfClass = 0;
    public int countWebInfClassResource = 0;
    public int countWebInfClassesEnd = 0;
    public int countWebInfLibStart = 0;
    public int countWebInfLibJar = 0;
    public int countWebInfLibZip = 0;
    public int countWebInfLibEnd = 0;
    public int countWebappEnd = 0;

    public String getDescription()
    {
        return "TestCase only rule, that counts hits to visitors";
    }

    public String getName()
    {
        return "test-counting";
    }

    @Override
    public void visitDirectoryEnd(String path, File dir)
    {
        countDirEnd++;
    }

    @Override
    public void visitDirectoryStart(String path, File dir)
    {
        countDirStart++;
    }

    @Override
    public void visitFile(String path, File dir, File file)
    {
        countFile++;
    }

    @Override
    public void visitWebappEnd(String path, File dir)
    {
        countWebappEnd++;
    }

    @Override
    public void visitWebappStart(String path, File dir)
    {
        super.visitWebappStart(path,dir);
        countWebappStart++;
    }

    @Override
    public void visitWebInfClass(String path, String className, File classFile)
    {
        countWebInfClass++;
    }

    @Override
    public void visitWebInfClassesEnd(String path, File dir)
    {
        countWebInfClassesEnd++;
    }

    @Override
    public void visitWebInfClassesStart(String path, File dir)
    {
        countWebInfClassesStart++;
    }

    @Override
    public void visitWebInfClassResource(String path, String resourcePath, File resourceFile)
    {
        countWebInfClassResource++;
    }

    @Override
    public void visitWebInfLibEnd(String path, File dir)
    {
        countWebInfLibEnd++;
    }

    @Override
    public void visitWebInfLibJar(String path, File archive, JarFile jar)
    {
        countWebInfLibJar++;
    }

    @Override
    public void visitWebInfLibStart(String path, File dir)
    {
        countWebInfLibStart++;
    }

    @Override
    public void visitWebInfLibZip(String path, File archive, ZipFile zip)
    {
        countWebInfLibZip++;
    }
}
