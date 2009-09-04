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

public abstract class AbstractRule implements Rule
{
    private ViolationListener violationListener;
    private File rootDir;

    protected void error(String path, String detail)
    {
        Violation violation = new Violation(Severity.ERROR,path,detail);
        violation.setVerifierInfo(this);
        violationListener.reportViolation(violation);
    }

    protected void exception(String path, String detail, Throwable t)
    {
        Violation violation = new Violation(Severity.ERROR,path,detail,t);
        violation.setVerifierInfo(this);
        violationListener.reportViolation(violation);
    }

    protected String getWebappRelativePath(File dir)
    {
        if (rootDir == null)
        {
            throw new RuntimeException("rootDir is not initialized, can't get relative path.  "
                    + "Did you overide .visitWebappStart() and not call super.visitWebappStart()?");
        }
        return rootDir.toURI().relativize(dir.toURI()).toASCIIString();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#setViolationListener(ViolationListener)
     */
    public void setViolationListener(ViolationListener listener)
    {
        this.violationListener = listener;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#initialize()
     */
    public void initialize() throws Throwable
    {
        /* override to implement */
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#visitDirectoryEnd(java.lang.String, java.io.File)
     */
    public void visitDirectoryEnd(String path, File dir)
    {
        /* override to implement */
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#visitDirectoryStart(java.lang.String, java.io.File)
     */
    public void visitDirectoryStart(String path, File dir)
    {
        /* override to implement */
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#visitFile(java.lang.String, java.io.File, java.io.File)
     */
    public void visitFile(String path, File dir, File file)
    {
        /* override to implement */
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#visitWebappEnd(java.lang.String, java.io.File)
     */
    public void visitWebappEnd(String path, File dir)
    {
        /* override to implement */
    }

    /**
     * {@inheritDoc}
     * 
     * NOTE: be sure to call super.{@link #visitWebappStart(String, File)} in your overriden method.
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#visitWebappStart(java.lang.String, java.io.File)
     */
    public void visitWebappStart(String path, File dir)
    {
        if (path.equals(ROOT_PATH))
        {
            rootDir = new File(path);
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#visitWebInfClass(java.lang.String, java.lang.String,
     *      java.io.File)
     */
    public void visitWebInfClass(String path, String className, File classFile)
    {
        /* override to implement */
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#visitWebInfClassesEnd(java.lang.String, java.io.File)
     */
    public void visitWebInfClassesEnd(String path, File dir)
    {
        // TODO Auto-generated method stub

    }

    /**
     * {@inheritDoc}
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#visitWebInfClassesStart(java.lang.String, java.io.File)
     */
    public void visitWebInfClassesStart(String path, File dir)
    {
        /* override to implement */
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#visitWebInfClassResource(java.lang.String, java.lang.String,
     *      java.io.File)
     */
    public void visitWebInfClassResource(String path, String resourcePath, File resourceFile)
    {
        /* override to implement */
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#visitWebInfLibEnd(java.lang.String, java.io.File)
     */
    public void visitWebInfLibEnd(String path, File dir)
    {
        /* override to implement */
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#visitWebInfLibJar(String, File, JarFile)
     */
    public void visitWebInfLibJar(String path, File archive, JarFile jar)
    {
        /* override to implement */
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#visitWebInfLibStart(java.lang.String, java.io.File)
     */
    public void visitWebInfLibStart(String path, File dir)
    {
        /* override to implement */
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.eclipse.jetty.webapp.verifier.Rule#visitWebInfLibZip(String, File, ZipFile)
     */
    public void visitWebInfLibZip(String path, File archive, ZipFile zip)
    {
        /* override to implement */
    }

    protected void warning(String path, String detail)
    {
        Violation violation = new Violation(Severity.WARNING,path,detail);
        violation.setVerifierInfo(this);
        violationListener.reportViolation(violation);
    }
}
