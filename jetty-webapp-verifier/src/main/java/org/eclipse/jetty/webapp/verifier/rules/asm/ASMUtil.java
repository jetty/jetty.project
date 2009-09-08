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
package org.eclipse.jetty.webapp.verifier.rules.asm;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.eclipse.jetty.util.IO;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;

/**
 * Some simple utility methods for working with ASM in a common way.
 */
public class ASMUtil
{
    public static void visitClassFile(File classFile, ClassVisitor visitor, int flags) throws IOException
    {
        FileInputStream fin = null;
        ClassReader creader = null;

        try
        {
            fin = new FileInputStream(classFile);
            creader = new ClassReader(fin);
            creader.accept(visitor,flags);
        }
        finally
        {
            IO.close(fin);
        }
    }

    public static void visitClass(InputStream stream, ClassVisitor visitor, int flags) throws IOException
    {
        ClassReader creader = new ClassReader(stream);
        creader.accept(visitor,flags);
    }
}
