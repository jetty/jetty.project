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
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final static EnumSet<FileVisitOption> SEARCH_VISIT_OPTIONS = EnumSet.of(FileVisitOption.FOLLOW_LINKS);;
    private final static int MAX_SEARCH_DEPTH = 30;

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
     * <li>Otherwise return absolute path reference (standard java logic)</li>
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

    /**
     * Get a List of {@link Path}s from a provided pattern.
     * <p>
     * Resolution Steps:
     * <ol>
     * <li>If the pattern starts with "regex:" or "glob:" then a standard {@link PathMatcher} is built using
     * {@link java.nio.file.FileSystem#getPathMatcher(String)} as a file search.</li>
     * <li>If pattern starts with a known filesystem root (using information from {@link java.nio.file.FileSystem#getRootDirectories()}) then this is assumed to
     * be a absolute file system pattern.</li>
     * <li>All other patterns are treated as relative to BaseHome information:
     * <ol>
     * <li>Search ${jetty.home} first</li>
     * <li>Search ${jetty.base} for overrides</li>
     * </ol>
     * </li>
     * </ol>
     * <p>
     * Pattern examples:
     * <dl>
     * <dt><code>lib/logging/*.jar</code></dt>
     * <dd>Relative pattern, not recursive, search <code>${jetty.home}</code> then <code>${jetty.base}</code> for lib/logging/*.jar content</dd>
     * 
     * <dt><code>lib/**&#47;*-dev.jar</code></dt>
     * <dd>Relative pattern, recursive search <code>${jetty.home}</code> then <code>${jetty.base}</code> for files under <code>lib</code> ending in
     * <code>-dev.jar</code></dd>
     * </dl>
     * 
     * <dt><code>etc/jetty.xml</code></dt>
     * <dd>Relative pattern, no glob, search for <code>${jetty.home}/etc/jetty.xml</code> then <code>${jetty.base}/etc/jetty.xml</code></dd>
     * 
     * <dt><code>glob:/opt/app/common/*-corp.jar</code></dt>
     * <dd>PathMapper pattern, glob, search <code>/opt/app/common/</code> for <code>*-corp.jar</code></code></dd>
     * 
     * </dl>
     * 
     * <p>
     * Notes:
     * <ul>
     * <li>FileSystem case sensitivity is implementation specific (eg: linux is case-sensitive, windows is case-insensitive).<br/>
     * See {@link java.nio.file.FileSystem#getPathMatcher(String)} for more details</li>
     * <li>Pattern slashes are implementation neutral (use '/' always and you'll be fine)</li>
     * <li>Recursive searching is limited to 30 levels deep (not configurable)</li>
     * <li>File System loops are detected and skipped</li>
     * </ul>
     * 
     * @param pattern
     *            the pattern to search.
     * @return the collection of paths found
     * @throws IOException
     *             if error during search operation
     */
    public List<Path> getPaths(String pattern) throws IOException
    {
        List<Path> hits = new ArrayList<>();

        if (PathMatchers.isAbsolute(pattern))
        {
            Path root = PathMatchers.getSearchRoot(pattern);
            PathMatcher matcher = PathMatchers.getMatcher(pattern);

            if (FS.isValidDirectory(root))
            {
                PathFinder finder = new PathFinder();
                finder.setFileMatcher(matcher);
                finder.setBase(root);
                Files.walkFileTree(root,SEARCH_VISIT_OPTIONS,MAX_SEARCH_DEPTH,finder);
                hits.addAll(finder.getHits());
            }
        }
        else
        {
            Path relativePath = PathMatchers.getSearchRoot(pattern);
            PathMatcher matcher = PathMatchers.getMatcher(pattern);
            PathFinder finder = new PathFinder();
            finder.setFileMatcher(matcher);

            Path homePath = homeDir.toPath().resolve(relativePath);

            if (FS.isValidDirectory(homePath))
            {
                finder.setBase(homePath);
                Files.walkFileTree(homePath,SEARCH_VISIT_OPTIONS,MAX_SEARCH_DEPTH,finder);
            }

            if (isBaseDifferent())
            {
                Path basePath = baseDir.toPath().resolve(relativePath);
                if (FS.isValidDirectory(basePath))
                {
                    finder.setBase(basePath);
                    Files.walkFileTree(basePath,SEARCH_VISIT_OPTIONS,MAX_SEARCH_DEPTH,finder);
                }
            }
            hits.addAll(finder.getHits());
        }

        Collections.sort(hits,new NaturalSort.Paths());
        return hits;
    }

    /**
     * Search specified Path with pattern and return hits
     * 
     * @param dir
     *            the path to a directory to start search from
     * @param searchDepth
     *            the number of directories deep to perform the search
     * @param pattern
     *            the raw pattern to use for the search (must be relative)
     * @return the list of Paths found
     * @throws IOException
     *             if unable to search the path
     */
    public List<Path> getPaths(Path dir, int searchDepth, String pattern) throws IOException
    {
        if (PathMatchers.isAbsolute(pattern))
        {
            throw new RuntimeException("Pattern cannot be absolute: " + pattern);
        }

        List<Path> hits = new ArrayList<>();
        if (FS.isValidDirectory(dir))
        {
            PathMatcher matcher = PathMatchers.getMatcher(pattern);
            PathFinder finder = new PathFinder();
            finder.setFileMatcher(matcher);
            finder.setBase(dir);
            Files.walkFileTree(dir,SEARCH_VISIT_OPTIONS,searchDepth,finder);
            hits.addAll(finder.getHits());
            Collections.sort(hits,new NaturalSort.Paths());
        }
        return hits;
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
