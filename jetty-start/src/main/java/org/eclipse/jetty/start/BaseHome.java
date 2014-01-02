//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.start;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.start.FS.RelativeRegexFilter;

/**
 * File access for <code>${jetty.home}</code>, <code>${jetty.base}</code>, directories.
 * <p>
 * By default, both <code>${jetty.home}</code> and <code>${jetty.base}</code> are the same directory, but they can point at different directories.
 * <p>
 * The <code>${jetty.home}</code> directory is where the main Jetty binaries and default configuration is housed.
 * <p>
 * The <code>${jetty.base}</code> directory is where the execution specific configuration and webapps are obtained from.
 */
public class BaseHome
{
    private File homeDir;
    private File baseDir;

    public BaseHome()
    {
        try
        {
            this.baseDir = new File(System.getProperty("jetty.base",System.getProperty("user.dir",".")));
            URL jarfile = this.getClass().getClassLoader().getResource("org/eclipse/jetty/start/BaseHome.class");
            if (jarfile != null)
            {
                Matcher m = Pattern.compile("jar:(file:.*)!/org/eclipse/jetty/start/BaseHome.class").matcher(jarfile.toString());
                if (m.matches())
                {
                    homeDir = new File(new URI(m.group(1))).getParentFile();
                }
            }
            homeDir = new File(System.getProperty("jetty.home",(homeDir == null?baseDir:homeDir).getAbsolutePath()));

            baseDir = baseDir.getAbsoluteFile().getCanonicalFile();
            homeDir = homeDir.getAbsoluteFile().getCanonicalFile();
        }
        catch (IOException | URISyntaxException e)
        {
            throw new RuntimeException(e);
        }
    }

    public BaseHome(File homeDir, File baseDir)
    {
        try
        {
            this.homeDir = homeDir.getCanonicalFile();
            this.baseDir = baseDir == null?this.homeDir:baseDir.getCanonicalFile();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public String getBase()
    {
        if (baseDir == null)
        {
            return null;
        }
        return baseDir.getAbsolutePath();
    }

    public File getBaseDir()
    {
        return baseDir;
    }

    /**
     * Create a file reference to some content in <code>"${jetty.base}"</code>
     * 
     * @param path
     *            the path to reference
     * @return the file reference
     */
    public File getBaseFile(String path)
    {
        return new File(baseDir,FS.separators(path));
    }

    /**
     * Get a specific file reference.
     * <p>
     * File references go through 3 possibly scenarios.
     * <ol>
     * <li>If exists relative to <code>${jetty.base}</code>, return that reference</li>
     * <li>If exists relative to <code>${jetty.home}</code>, return that reference</li>
     * <li>Otherwise return absolute path reference</li>
     * </ol>
     * 
     * @param path
     *            the path to get.
     * @return the file reference.
     */
    public File getFile(String path)
    {
        String rpath = FS.separators(path);

        // Relative to Base Directory First
        if (isBaseDifferent())
        {
            File file = new File(baseDir,rpath);
            if (file.exists())
            {
                return file;
            }
        }

        // Then relative to Home Directory
        File file = new File(homeDir,rpath);
        if (file.exists())
        {
            return file;
        }

        // Finally, as an absolute path
        return new File(rpath);
    }

    public String getHome()
    {
        return homeDir.getAbsolutePath();
    }

    public File getHomeDir()
    {
        return homeDir;
    }

    public void initialize(StartArgs args)
    {
        Pattern jetty_home = Pattern.compile("(-D)?jetty.home=(.*)");
        Pattern jetty_base = Pattern.compile("(-D)?jetty.base=(.*)");

        File homePath = null;
        File basePath = null;

        for (String arg : args.getCommandLine())
        {
            Matcher home_match = jetty_home.matcher(arg);
            if (home_match.matches())
            {
                homePath = new File(home_match.group(2));
            }
            Matcher base_match = jetty_base.matcher(arg);
            if (base_match.matches())
            {
                basePath = new File(base_match.group(2));
            }
        }

        if (homePath != null)
        {
            // logic if home is specified
            this.homeDir = homePath.getAbsoluteFile();
            if (basePath == null)
            {
                this.baseDir = homePath.getAbsoluteFile();
                args.getProperties().setProperty("jetty.base",this.baseDir.toString(),"<internal-fallback>");
            }
            else
            {
                this.baseDir = basePath.getAbsoluteFile();
            }
        }
        else if (basePath != null)
        {
            // logic if home is undeclared
            this.baseDir = basePath.getAbsoluteFile();
        }

        // Update System Properties
        args.addSystemProperty("jetty.home",this.homeDir.getAbsolutePath());
        args.addSystemProperty("jetty.base",this.baseDir.getAbsolutePath());
    }

    public boolean isBaseDifferent()
    {
        return homeDir.compareTo(baseDir) != 0;
    }

    /**
     * Get all of the files that are in a specific relative directory.
     * <p>
     * If the same found path exists in both <code>${jetty.base}</code> and <code>${jetty.home}</code>, then the one in <code>${jetty.base}</code> is returned
     * (it overrides the one in ${jetty.home})
     * 
     * @param relPathToDirectory
     *            the relative path to the directory
     * @return the list of files found.
     */
    public List<File> listFiles(String relPathToDirectory)
    {
        return listFiles(relPathToDirectory,FS.AllFilter.INSTANCE);
    }

    /**
     * Get all of the files that are in a specific relative directory, with applied {@link FileFilter}
     * <p>
     * If the same found path exists in both <code>${jetty.base}</code> and <code>${jetty.home}</code>, then the one in <code>${jetty.base}</code> is returned
     * (it overrides the one in ${jetty.home})
     * 
     * @param relPathToDirectory
     *            the relative path to the directory
     * @param filter
     *            the filter to use
     * @return the list of files found.
     */
    public List<File> listFiles(String relPathToDirectory, FileFilter filter)
    {
        Objects.requireNonNull(filter,"FileFilter cannot be null");

        File homePath = new File(homeDir,FS.separators(relPathToDirectory));
        List<File> homeFiles = new ArrayList<>();
        if (FS.canReadDirectory(homePath))
        {
            homeFiles.addAll(Arrays.asList(homePath.listFiles(filter)));
        }

        if (isBaseDifferent())
        {
            // merge
            File basePath = new File(baseDir,FS.separators(relPathToDirectory));
            List<File> ret = new ArrayList<>();
            if (FS.canReadDirectory(basePath))
            {
                File baseFiles[] = basePath.listFiles(filter);

                if (baseFiles != null)
                {
                    for (File base : baseFiles)
                    {
                        String relpath = toRelativePath(baseDir,base);
                        File home = new File(homeDir,FS.separators(relpath));
                        if (home.exists())
                        {
                            homeFiles.remove(home);
                        }
                        ret.add(base);
                    }
                }
            }

            // add any remaining home files.
            ret.addAll(homeFiles);

            Collections.sort(ret,new NaturalSort.Files());
            return ret;
        }
        else
        {
            // simple return
            Collections.sort(homeFiles,new NaturalSort.Files());
            return homeFiles;
        }
    }
    
    /**
     * Get all of the files that are in a specific relative directory, with applied regex.
     * <p>
     * If the same found path exists in both <code>${jetty.base}</code> and <code>${jetty.home}</code>, then the one in <code>${jetty.base}</code> is returned
     * (it overrides the one in ${jetty.home})
     * <p>
     * All regex paths are assumed to be in unix notation (use of <code>"/"</code> to separate paths, as <code>"\"</code> is used to escape in regex)
     * 
     * @param regex
     *            the regex to use to match against the found files.
     * @return the list of files found.
     */
    public List<File> listFilesRegex(String regex)
    {
        Objects.requireNonNull(regex,"Glob cannot be null");

        Pattern pattern = Pattern.compile(regex);

        List<File> homeFiles = new ArrayList<>();
        if (FS.canReadDirectory(homeDir))
        {
            StartLog.debug("Finding files in ${jetty.home} that match: %s",regex);
            recurseDir(homeFiles,homeDir,new FS.RelativeRegexFilter(homeDir,pattern));
            StartLog.debug("Found %,d files",homeFiles.size());
        }

        if (isBaseDifferent())
        {
            // merge
            List<File> ret = new ArrayList<>();
            if (FS.canReadDirectory(baseDir))
            {
                List<File> baseFiles = new ArrayList<>();
                StartLog.debug("Finding files in ${jetty.base} that match: %s",regex);
                recurseDir(baseFiles,baseDir,new FS.RelativeRegexFilter(baseDir,pattern));
                StartLog.debug("Found %,d files",baseFiles.size());

                for (File base : baseFiles)
                {
                    String relpath = toRelativePath(baseDir,base);
                    File home = new File(homeDir,FS.separators(relpath));
                    if (home.exists())
                    {
                        homeFiles.remove(home);
                    }
                    ret.add(base);
                }
            }

            // add any remaining home files.
            ret.addAll(homeFiles);
            StartLog.debug("Merged Files: %,d files%n",ret.size());

            Collections.sort(ret,new NaturalSort.Files());
            return ret;
        }
        else
        {
            // simple return
            Collections.sort(homeFiles,new NaturalSort.Files());
            return homeFiles;
        }
    }

    private void recurseDir(List<File> files, File dir, RelativeRegexFilter filter)
    {
        // find matches first
        files.addAll(Arrays.asList(dir.listFiles(filter)));

        // now dive down into sub-directories
        for (File subdir : dir.listFiles(FS.DirFilter.INSTANCE))
        {
            recurseDir(files,subdir,filter);
        }
    }

    /**
     * Collect the list of files in both <code>${jetty.base}</code> and <code>${jetty.home}</code>, even if the same file shows up in both places.
     */
    public List<File> rawListFiles(String relPathToDirectory, FileFilter filter)
    {
        Objects.requireNonNull(filter,"FileFilter cannot be null");

        List<File> ret = new ArrayList<>();

        // Home Dir
        File homePath = new File(homeDir,FS.separators(relPathToDirectory));
        ret.addAll(Arrays.asList(homePath.listFiles(filter)));

        if (isBaseDifferent())
        {
            // Base Dir
            File basePath = new File(baseDir,FS.separators(relPathToDirectory));
            ret.addAll(Arrays.asList(basePath.listFiles(filter)));
        }

        // Sort
        Collections.sort(ret,new NaturalSort.Files());
        return ret;
    }

    public void setBaseDir(File dir)
    {
        try
        {
            this.baseDir = dir.getCanonicalFile();
            System.setProperty("jetty.base",dir.getCanonicalPath());
        }
        catch (IOException e)
        {
            e.printStackTrace(System.err);
        }
    }

    public void setHomeDir(File dir)
    {
        try
        {
            this.homeDir = dir.getCanonicalFile();
            System.setProperty("jetty.home",dir.getCanonicalPath());
        }
        catch (IOException e)
        {
            e.printStackTrace(System.err);
        }
    }

    // TODO - inline
    private String toRelativePath(File dir, File path)
    {
        return FS.toRelativePath(dir,path);
    }

    /**
     * Convenience method for <code>toShortForm(file.getCanonicalPath())</code>
     */
    public String toShortForm(File path)
    {
        try
        {
            return toShortForm(path.getCanonicalPath());
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
        return toShortForm(path.getAbsolutePath());
    }

    /**
     * Replace/Shorten arbitrary path with property strings <code>"${jetty.home}"</code> or <code>"${jetty.base}"</code> where appropriate.
     * 
     * @param path
     *            the path to shorten
     * @return the potentially shortened path
     */
    public String toShortForm(String path)
    {
        if (path == null)
        {
            return path;
        }

        String value;

        if (isBaseDifferent())
        {
            value = baseDir.getAbsolutePath();
            if (path.startsWith(value))
            {
                return "${jetty.base}" + path.substring(value.length());
            }
        }

        value = homeDir.getAbsolutePath();

        if (path.startsWith(value))
        {
            return "${jetty.home}" + path.substring(value.length());
        }

        return path;
    }

}
