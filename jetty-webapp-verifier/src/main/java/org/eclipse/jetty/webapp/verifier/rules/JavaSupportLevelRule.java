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
package org.eclipse.jetty.webapp.verifier.rules;

import java.io.File;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.jetty.webapp.verifier.AbstractArchiveScanningRule;
import org.eclipse.jetty.webapp.verifier.rules.asm.ASMUtil;
import org.eclipse.jetty.webapp.verifier.rules.asm.AbstractClassVisitor;

/**
 * Ensure all compiled classes within webapp are within the supported JVM range.
 */
public class JavaSupportLevelRule extends AbstractArchiveScanningRule
{
    class ClassVersionVisitor extends AbstractClassVisitor
    {
        private double classVersion = -1;

        public double getClassVersion()
        {
            return classVersion;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces)
        {
            this.classVersion = getJavaClassVersion(version);
        }

        private double getJavaClassVersion(int version)
        {
            int major = version & 0xFFFF;
            int minor = version >>> 16;

            // Java Versions to Class Version (major.minor) 
            // Java 1.6 = 50.0
            // Java 1.5 = 49.0
            // Java 1.4 = 48.0
            // Java 1.3 = 47.0
            // Java 1.2 = 46.0
            // Java 1.1 = 45.3

            // TODO: check these since they are > instead of >=
            if (major >= 50)
            {
                return 1.6;
            }
            else if (major >= 49)
            {
                return 1.5;
            }
            else if (major >= 48)
            {
                return 1.4;
            }
            else if (major >= 47)
            {
                return 1.3;
            }
            else if (major >= 46)
            {
                return 1.2;
            }
            else if (major >= 45)
            {
                if (minor >= 3)
                {
                    return 1.1;
                }
                return 1.0;
            }

            return 0.0;
        }

        public void reset()
        {
            this.classVersion = (-1);
        }
    }

    private double supportedVersion = 1.5;
    private ClassVersionVisitor visitor;

    @Override
    public String getDescription()
    {
        return "Ensure all compiled classes within webapp are within the supported JVM";
    }

    @Override
    public String getName()
    {
        return "java-support-level";
    }

    public double getSupportedVersion()
    {
        return supportedVersion;
    }

    public void setSupportedVersion(double supportedVersion)
    {
        this.supportedVersion = supportedVersion;
    }

    @Override
    public void visitWebappStart(String path, File dir)
    {
        visitor = new ClassVersionVisitor();
    }


    @Override
    public void visitWebInfClass(String path, String className, File classFile)
    {
        try
        {
            visitor.reset();
            ASMUtil.visitClassFile(classFile,visitor,0);
            if (visitor.classVersion > supportedVersion)
            {
                error(path,"Class is compiled for java version [" + visitor.classVersion + "] which is over supported java version [" + supportedVersion + "]");
            }
        }
        catch (IOException e)
        {
            exception(path,"Unable to read class",e);
        }
    }

    @Override
    public void visitArchiveClass(String path, String className, ZipFile archive, ZipEntry archiveEntry)
    {
        try
        {
            visitor.reset();
            ASMUtil.visitClass(archive.getInputStream(archiveEntry),visitor,0);
            if (visitor.classVersion > supportedVersion)
            {
                error(path,"Class is compiled for java version [" + visitor.classVersion + "] which is over supported java version [" + supportedVersion + "]");
            }
        }
        catch (IOException e)
        {
            exception(path,"Unable to read class",e);
        }
    }
}
