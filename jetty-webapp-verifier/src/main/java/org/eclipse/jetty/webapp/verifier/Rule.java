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
import java.net.URLClassLoader;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/**
 * <p>
 * Rule is the interface that the {@link WebappVerifier} uses to notify Rule implementations of events in the Iteration
 * of a Webapp contents.
 * </p>
 * 
 * <p>
 * The visitor pattern used here has the following order ..
 * </p>
 * 
 * <pre>
 *  1) [1] {@link #visitWebappStart(String, File)}
 *  Iterate Contents:
 *     2) [1..n] {@link #visitDirectoryStart(String, File)}
 *     3) [0..n] {@link #visitFile(String, File, File)}
 *     4) [1..n] {@link #visitDirectoryEnd(String, File)}
 *  Iterate WEB-INF/classes:
 *     5) [0..1] {@link #visitWebInfClassesStart(String, File)}
 *     6) [0..n] {@link #visitWebInfClass(String, String, File)}
 *     7) [0..n] {@link #visitWebInfClassResource(String, String, File)}
 *     8) [0..1] {@link #visitWebInfClassesEnd(String, File)}
 *  Iterate WEB-INF/lib:
 *     9) [0..1] {@link #visitWebInfLibStart(String, File)}
 *    10) [0..n] {@link #visitWebInfLibJar(String, File, JarFile)}
 *    11) [0..n] {@link #visitWebInfLibZip(String, File, ZipFile)}
 *    12) [0..1] {@link #visitWebInfLibEnd(String, File)}
 *  13) [1] {@link #visitWebappEnd(String, File)}
 * </pre>
 */
public interface Rule
{
    public static final String ROOT_PATH = "";

    /**
     * A short name for the rule.
     */
    public String getName();

    /**
     * A Description of the purpose of the rule. What does it check for? Why?
     */
    public String getDescription();

    /**
     * Initialization logic for the rule, exceptions from initialization will be logged as a {@link Severity#ERROR}
     * level {@link Violation} with the {@link Violation#getThrowable()} set.
     * 
     * If you want more meaningful violation messages than default, be sure to capture your own initialization related
     * failures and report them to the {@link ViolationListener} at a {@link Severity#ERROR} level.
     */
    public void initialize() throws Throwable;

    /**
     * Set the listener to report violations back to.
     */
    public void setViolationListener(ViolationListener listener);

    /**
     * The iteration of the webapp has begun.
     * 
     * @param path
     *            the war relative path to this directory.
     * @param dir
     *            the real File System directory to the webapp work directory
     */
    public void visitWebappStart(String path, File dir);

    /**
     * A visit of a directory has begun.
     * 
     * @param path
     *            the war relative path to this directory.
     * @param dir
     *            the real File System directory object for this directory.
     */
    public void visitDirectoryStart(String path, File dir);

    /**
     * A visit of a file.
     * 
     * @param path
     *            the war relative path to this file.
     * @param dir
     *            the real File System directory object for this file. This is the same directory as seen in
     *            {@link #visitDirectoryStart(String, File)} and {@link #visitDirectoryEnd(String, File)}
     * @param file
     *            the real File System {@link File} object to this file
     */
    public void visitFile(String path, File dir, File file);

    /**
     * A visit of a directory has ended.
     * 
     * @param path
     *            the war relative path to this directory.
     * @param dir
     *            the real File System directory.
     */
    public void visitDirectoryEnd(String path, File dir);

    /**
     * The visit to WEB-INF/classes is starting
     * 
     * @param path
     *            the war relative path to the WEB-INF/classes dir. (Note: Will always be "WEB-INF/classes")
     * @param dir
     *            the real File System directory.
     */
    public void visitWebInfClassesStart(String path, File dir);

    /**
     * A visit of a Class found in WEB-INF/classes.
     * 
     * @param path
     *            the war relative path to this directory.
     * @param className
     *            the full classname of the class found. TODO: Base this off of class bytecode?
     * @param classFile
     *            the real File System directory object for this directory.
     */
    public void visitWebInfClass(String path, String className, File classFile);

    /**
     * A visit of a Resource available in "WEB-INF/classes" which is not a Class (such as an XML file or a properties
     * file)
     * 
     * @param path
     *            the war relative path to this directory.
     * @param resourcePath
     *            the full resourcePath to the file found. Returned in a format that is compatible to
     *            {@link URLClassLoader#findResource(String)} call.
     * @param resourceFile
     *            the real File System directory object for this directory.
     */
    public void visitWebInfClassResource(String path, String resourcePath, File resourceFile);

    /**
     * The visit to WEB-INF/classes has ended
     * 
     * @param path
     *            the war relative path to the WEB-INF/classes dir. (Note: Will always be "WEB-INF/classes")
     * @param dir
     *            the real File System directory.
     */
    public void visitWebInfClassesEnd(String path, File dir);

    /**
     * The visit to WEB-INF/lib is starting.
     * 
     * @param path
     *            the war relative path to the WEB-INF/lib dir. (Note: Will always be "WEB-INF/lib")
     * @param dir
     *            the real File System directory.
     */
    public void visitWebInfLibStart(String path, File dir);

    /**
     * A visit to a JAR archive in the WEB-INF/lib directory.
     * 
     * @param path
     *            the war relative path to the JAR archive in the WEB-INF/lib dir.
     * @param archive
     *            the real File System file.
     * @param jar
     *            the {@link JarFile}
     */
    public void visitWebInfLibJar(String path, File archive, JarFile jar);

    /**
     * A visit to an archive in the WEB-INF/lib directory has begun.
     * 
     * @param path
     *            the war relative path to the archive in the WEB-INF/lib dir.
     * @param archive
     *            the real File System file.
     * @param zip
     *            the {@link ZipFile}
     */
    public void visitWebInfLibZip(String path, File archive, ZipFile zip);

    /**
     * The visit to WEB-INF/lib has ended.
     * 
     * @param path
     *            the war relative path to the WEB-INF/lib dir. (Note: Will always be "WEB-INF/lib")
     * @param dir
     *            the real File System directory.
     */
    public void visitWebInfLibEnd(String path, File dir);

    /**
     * The iteration of the webapp has ended.
     * 
     * @param path
     *            the war relative path to this directory.
     * @param dir
     *            the real File System directory to the webapp work directory
     */
    public void visitWebappEnd(String path, File dir);
}
