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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;

/**
 * The Webapp Verifier is a component that can be configured to run and arbitrary number of {@link Rule}s that analyze
 * the contents of a war file and report rule violations.
 */
public class WebappVerifier implements ViolationListener
{
    /**
     * <p>
     * Represents the source webappURI.
     * </p>
     * <p>
     * Can be http, file, jar, etc ...
     * </p>
     * <p>
     * Verification does not occur directly against this URI.
     * </p>
     */
    private URI _webappURI;

    /**
     * Represents the local webapp file directory, often times points to the working (unpacked) webapp directory.
     * 
     * NOTE: if _webappURI is a "file://" access URI, and points to a directory, then the _webappDir will point to the
     * the same place, otherwise it will point to the webapp directory.
     */
    private File _webappDir;
    private File _workdir;
    private List<Rule> _rules;
    private Map<String, List<Violation>> _violations;

    /**
     * Instantiate a WebappVerifier, against the specific webappURI, using the default workdir.
     * 
     * @param webappURI
     *            the webappURI to verify
     */
    public WebappVerifier(URI webappURI)
    {
        this._webappURI = webappURI;
        this._workdir = new File(System.getProperty("java.io.tmpdir"),"jetty-waver");
        this._rules = new ArrayList<Rule>();
        this._violations = new HashMap<String, List<Violation>>();
    }

    public void addRule(Rule rule)
    {
        _rules.add(rule);
        try
        {
            rule.setViolationListener(this);
            rule.initialize();
        }
        catch (Throwable t)
        {
            // Capture any errors out of initialize or setViolationListener() that might occur.
            String msg = String.format("Unable to add rule [%s]: %s",rule.getName(),t.getMessage());
            reportException(Rule.ROOT_PATH,msg,rule,t);
        }
    }

    private String cleanFilename(URI uri)
    {
        // Need a filename to store this download to.
        String destname = uri.getPath();
        int idx = destname.lastIndexOf('/');
        if (idx > 0)
        {
            destname = destname.substring(idx);
        }
        destname = destname.trim().toLowerCase();

        if (destname.length() <= 0)
        {
            // whoops, there's nothing left.
            // could be caused by urls that point to paths.
            destname = new SimpleDateFormat("yyyy-MM-dd_hh-mm-ss").format(new Date());
        }

        // Neuter illegal characters
        char cleaned[] = new char[destname.length()];
        for (int i = 0; i < destname.length(); i++)
        {
            char c = destname.charAt(i);
            if (c == '|' || c == '/' || c == '\\' || c == '*' || c == '?' || c == ':' || c == '#')
            {
                cleaned[i] = '_';
            }
            else
            {
                cleaned[i] = c;
            }
        }

        destname = new String(cleaned);

        if (!destname.endsWith(".war"))
        {
            destname += ".war";
        }

        return destname;
    }

    private File download(URI uri) throws MalformedURLException, IOException
    {
        // Establish destfile
        File destfile = new File(_workdir,cleanFilename(uri));

        InputStream in = null;
        FileOutputStream out = null;
        try
        {
            in = uri.toURL().openStream();
            out = new FileOutputStream(destfile);
            IO.copy(in,out);
        }
        finally
        {
            IO.close(in);
        }
        return destfile;
    }

    private File establishLocalWebappDir() throws URISyntaxException, IOException
    {
        if ("file".equals(_webappURI.getScheme()))
        {
            File path = new File(_webappURI);
            if (path.isDirectory())
            {
                return path;
            }
            return unpack(path);
        }

        File downloadedFile = download(_webappURI);
        return unpack(downloadedFile);
    }

    public List<Rule> getRules()
    {
        return _rules;
    }

    public Collection<Violation> getViolations()
    {
        // TODO: Icky!  I need a better way of getting the list of raw
        // violations, complete, without path mapping in place.
        // Yet, I need path mapping as well, right?
        List<Violation> violations = new ArrayList<Violation>();
        for (List<Violation> pathviol : _violations.values())
        {
            violations.addAll(pathviol);
        }
        return violations;
    }

    public File getWebappDir()
    {
        return _webappDir;
    }

    private String getWebappRelativePath(File dir)
    {
        return _webappDir.toURI().relativize(dir.toURI()).toASCIIString();
    }

    public File getWorkDir()
    {
        return _workdir;
    }

    /**
     * Tests a filesystem path for webapp expected files, like "WEB-INF/web.xml"
     */
    private boolean isValidWebapp(File path)
    {
        File webXml = new File(path,"WEB-INF" + File.separator + "web.xml");
        if (!webXml.exists())
        {
            reportViolation(Severity.ERROR,Rule.ROOT_PATH,"Missing WEB-INF/web.xml");
            return false;
        }

        if (!webXml.isFile())
        {
            reportViolation(Severity.ERROR,Rule.ROOT_PATH,"The WEB-INF/web.xml is not a File");
            return false;
        }

        return true;
    }

    private void reportException(String path, String detail, Rule rule, Throwable t)
    {
        Violation viol = new Violation(Severity.ERROR,path,detail,t);
        viol.setVerifierInfo(rule);
        reportViolation(viol);
    }

    public void reportViolation(Severity error, String path, String detail)
    {
        reportViolation(new Violation(error,path,detail));
    }

    public void reportViolation(Violation violation)
    {
        List<Violation> pathviol = _violations.get(violation.getPath());
        if (pathviol == null)
        {
            pathviol = new ArrayList<Violation>();
        }
        pathviol.add(violation);
        _violations.put(violation.getPath(),pathviol);
    }

    public void setRules(Collection<Rule> rules)
    {
        this._rules.clear();
        // Add each rule separately, to establish initialization & listeners
        for (Rule verifier : rules)
        {
            addRule(verifier);
        }
    }

    public void setWorkDir(File workDir)
    {
        this._workdir = workDir;
    }

    private File unpack(File path) throws URISyntaxException, IOException
    {
        String destname = path.getName().substring(0,path.getName().length() - 4);
        File destDir = new File(_workdir,destname);
        URI warURI = new URI("jar",path.toURI() + "!/",null);
        JarResource warResource = (JarResource)Resource.newResource(warURI);
        warResource.extract(destDir,false);
        return destDir;
    }

    public void visitAll()
    {
        try
        {
            _webappDir = establishLocalWebappDir();

            // Issue start.
            for (Rule rule : _rules)
            {
                rule.visitWebappStart(Rule.ROOT_PATH,_webappDir);
            }

            if (isValidWebapp(_webappDir))
            {
                // Iterate through content
                visitContents();
                // Iterate through WEB-INF/classes
                visitWebInfClasses();
                // Iterate through WEB-INF/lib
                visitWebInfLib();
            }

            // Issue end.
            for (Rule rule : _rules)
            {
                rule.visitWebappEnd(Rule.ROOT_PATH,_webappDir);
            }
        }
        catch (IOException e)
        {
            reportViolation(new Violation(Severity.ERROR,Rule.ROOT_PATH,e.getMessage(),e));
        }
        catch (URISyntaxException e)
        {
            reportViolation(new Violation(Severity.ERROR,Rule.ROOT_PATH,e.getMessage(),e));
        }
    }

    private void visitWebInfClasses()
    {
        File classesDir = new File(_webappDir,"WEB-INF" + File.separator + "classes");
        if (!classesDir.exists())
        {
            // skip this path.
            return;
        }

        String classesPath = getWebappRelativePath(classesDir);

        if (!classesDir.isDirectory())
        {
            reportViolation(Severity.ERROR,classesPath,"WEB-INF/classes is not a Directory?");
            return;
        }

        // Issue start.
        for (Rule rule : _rules)
        {
            rule.visitWebInfClassesStart(classesPath,classesDir);
        }

        visitClassesDir(classesDir,classesDir);

        // Issue end.
        for (Rule rule : _rules)
        {
            rule.visitWebInfClassesEnd(classesPath,classesDir);
        }
    }

    private void visitClassesDir(File classesRoot, File classesDir)
    {
        File files[] = classesDir.listFiles();
        for (File file : files)
        {
            if (file.isFile())
            {
                if (file.getName().endsWith(".class"))
                {
                    visitClass(classesRoot,file);
                }
                else
                {
                    visitClassResource(classesRoot,file);
                }
            }
            else if (file.isDirectory())
            {
                // recurse
                visitClassesDir(classesRoot,file);
            }
        }
    }

    private void visitClassResource(File classesRoot, File file)
    {
        String path = getWebappRelativePath(file);
        String resourcePath = classesRoot.toURI().relativize(file.toURI()).toASCIIString();
        for (Rule rule : _rules)
        {
            rule.visitWebInfClassResource(path,resourcePath,file);
        }
    }

    private void visitClass(File classesRoot, File file)
    {
        String path = getWebappRelativePath(file);
        String className = classesRoot.toURI().relativize(file.toURI()).toASCIIString();
        className = className.replace("/",".");
        if (className.endsWith(".class"))
        {
            className = className.substring(0,className.length() - 6);
        }
        for (Rule rule : _rules)
        {
            rule.visitWebInfClass(path,className,file);
        }
    }

    private void visitWebInfLib()
    {
        File libDir = new File(_webappDir,"WEB-INF" + File.separator + "lib");
        if (!libDir.exists())
        {
            // skip this path.
            return;
        }

        String libPath = getWebappRelativePath(libDir);

        if (!libDir.isDirectory())
        {
            reportViolation(Severity.ERROR,libPath,"WEB-INF/lib is not a Directory?");
            return;
        }

        // Issue start.
        for (Rule rule : _rules)
        {
            rule.visitWebInfLibStart(libPath,libDir);
        }

        // Iterator through contents of WEB-INF/lib
        File archives[] = libDir.listFiles();
        for (File archive : archives)
        {
            if (!archive.isFile())
            {
                reportViolation(Severity.WARNING,Rule.ROOT_PATH,"Found non-file in WEB-INF/lib.  Remove it, "
                        + "as it cannot be accessed by the Servlet container or the Webapp: " + archive);
                continue;
            }

            if (archive.getName().toLowerCase().endsWith(".jar"))
            {
                visitWebInfLibJar(libPath,archive);
                continue;
            }

            if (archive.getName().toLowerCase().endsWith(".zip"))
            {
                visitWebInfLibZip(libPath,archive);
                continue;
            }

            reportViolation(Severity.WARNING,Rule.ROOT_PATH,"Found non-archive in WEB-INF/lib.  Remove it, "
                    + "as it cannot be accessed by the Servlet container or the Webapp: " + archive);
        }

        // Issue end.
        for (Rule rule : _rules)
        {
            rule.visitWebInfLibEnd(libPath,libDir);
        }
    }

    private void visitWebInfLibJar(String libPath, File archive)
    {
        String jarPath = libPath + archive.getName();

        // Issue visit on Jar
        for (Rule rule : _rules)
        {
            JarFile jar = null;
            try
            {
                jar = new JarFile(archive);
                rule.visitWebInfLibJar(jarPath,archive,jar);
            }
            catch (Throwable t)
            {
                reportViolation(new Violation(Severity.ERROR,jarPath,t.getMessage(),t));
            }
            finally
            {
                if (jar != null)
                {
                    try
                    {
                        jar.close();
                    }
                    catch (IOException ignore)
                    {
                        /* ignore */
                    }
                }
            }
        }
    }

    private void visitWebInfLibZip(String libPath, File archive)
    {
        String zipPath = libPath + archive.getName();

        // Issue visit on Zip
        for (Rule rule : _rules)
        {
            ZipFile zip = null;
            try
            {
                zip = new ZipFile(archive);
                rule.visitWebInfLibZip(zipPath,archive,zip);
            }
            catch (Throwable t)
            {
                reportViolation(new Violation(Severity.ERROR,zipPath,t.getMessage(),t));
            }
            finally
            {
                if (zip != null)
                {
                    try
                    {
                        zip.close();
                    }
                    catch (IOException ignore)
                    {
                        /* ignore */
                    }
                }
            }
        }
    }

    private void visitContents()
    {
        visitDirectoryRecursively(_webappDir);
    }

    private void visitDirectoryRecursively(File dir)
    {
        String path = getWebappRelativePath(dir);

        // Start Dir
        for (Rule rule : this._rules)
        {
            rule.visitDirectoryStart(path,dir);
        }

        File entries[] = dir.listFiles();

        // Individual Files
        for (File file : entries)
        {
            if (file.isFile())
            {
                String filepath = path + file.getName();
                for (Rule rule : this._rules)
                {
                    rule.visitFile(filepath,dir,file);
                }
            }
        }

        // Sub dirs
        for (File file : entries)
        {
            if (file.isDirectory())
            {
                visitDirectoryRecursively(file);
            }
        }

        // End Dir
        for (Rule rule : this._rules)
        {
            rule.visitDirectoryEnd(path,dir);
        }
    }

}
