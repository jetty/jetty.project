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

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

/**
 * Abstact implementation of {@link ClassVisitor} to make asm use within Rules easier.
 */
public abstract class AbstractClassVisitor implements ClassVisitor
{

    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces)
    {
        /* Override Ready */
    }

    public AnnotationVisitor visitAnnotation(String desc, boolean visible)
    {
        /* Override Ready */
        return null;
    }

    public void visitAttribute(Attribute attr)
    {
        /* Override Ready */
    }

    public void visitEnd()
    {
        /* Override Ready */
    }

    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value)
    {
        /* Override Ready */
        return null;
    }

    public void visitInnerClass(String name, String outerName, String innerName, int access)
    {
        /* Override Ready */
    }

    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions)
    {
        /* Override Ready */
        return null;
    }

    public void visitOuterClass(String owner, String name, String desc)
    {
        /* Override Ready */
    }

    public void visitSource(String source, String debug)
    {
        /* Override Ready */
    }
}
