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
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
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

    private Path homeDir;
    private Path baseDir;

    public BaseHome()
    {
        try
        {
            // find ${jetty.base}

            // default is ${user.dir}
            this.baseDir = new File(System.getProperty("user.dir",".")).toPath();

            // if ${jetty.base} declared, use it
            String jettyBase = System.getProperty("jetty.base");
            if (jettyBase != null)
            {
                this.baseDir = new File(jettyBase).toPath();
            }

            // find ${jetty.home}

            // default location is based on lookup for BaseHome (from jetty's start.jar)
            URL jarfile = this.getClass().getClassLoader().getResource("org/eclipse/jetty/start/BaseHome.class");
            if (jarfile != null)
            {
                Matcher m = Pattern.compile("jar:(file:.*)!/org/eclipse/jetty/start/BaseHome.class").matcher(jarfile.toString());
                if (m.matches())
                {
                    // ${jetty.home} is relative to found BaseHome class
                    this.homeDir = new File(new URI(m.group(1))).getParentFile().toPath();
                }
            }

            // if we can't locate BaseHome, then assume home == base
            this.homeDir = baseDir;

            // if ${jetty.home} declared, use it
            String jettyHome = System.getProperty("jetty.home");
            if (jettyHome != null)
            {
                this.homeDir = new File(jettyHome).toPath();
            }

            // resolve base and home to absolute paths
            baseDir = baseDir.toAbsolutePath();
            homeDir = homeDir.toAbsolutePath();
        }
        catch (URISyntaxException e)
        {
            throw new RuntimeException(e);
        }
    }

    public BaseHome(File homeDir, File baseDir)
    {
        this.homeDir = homeDir.toPath().toAbsolutePath();
        this.baseDir = baseDir == null?this.homeDir:baseDir.toPath().toAbsolutePath();
    }

    public String getBase()
    {
        if (baseDir == null)
        {
            return null;
        }
        return baseDir.toString();
    }

    // TODO: change return type to Path
    public File getBaseDir()
    {
        return baseDir.toFile();
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
        return baseDir.resolve(FS.separators(path)).toFile();
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
        return getPath(path).toAbsolutePath().toFile();
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
    public Path getPath(String path)
    {
        String rpath = FS.separators(path);

        // Relative to Base Directory First
        if (isBaseDifferent())
        {
            Path file = baseDir.resolve(rpath);
            if (FS.exists(file))
            {
                return file;
            }
        }

        // Then relative to Home Directory
        Path file = homeDir.resolve(rpath);
        if (FS.exists(file))
        {
            return file;
        }

        // Finally, as an absolute path
        return FileSystems.getDefault().getPath(rpath);
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
                finder.setIncludeDirsInResults(true);
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
            finder.setIncludeDirsInResults(true);
            finder.setFileMatcher(matcher);

            Path homePath = homeDir.resolve(relativePath);

            if (FS.isValidDirectory(homePath))
            {
                finder.setBase(homePath);
                Files.walkFileTree(homePath,SEARCH_VISIT_OPTIONS,MAX_SEARCH_DEPTH,finder);
            }

            if (isBaseDifferent())
            {
                Path basePath = baseDir.resolve(relativePath);
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
        return homeDir.toString();
    }

    // TODO: change return type to Path
    public File getHomeDir()
    {
        return homeDir.toFile();
    }

    public void initialize(StartArgs args)
    {
        Pattern jetty_home = Pattern.compile("(-D)?jetty.home=(.*)");
        Pattern jetty_base = Pattern.compile("(-D)?jetty.base=(.*)");

        Path homePath = null;
        Path basePath = null;

        FileSystem fs = FileSystems.getDefault();

        for (String arg : args.getCommandLine())
        {
            Matcher home_match = jetty_home.matcher(arg);
            if (home_match.matches())
            {
                homePath = fs.getPath(home_match.group(2));
            }
            Matcher base_match = jetty_base.matcher(arg);
            if (base_match.matches())
            {
                basePath = fs.getPath(base_match.group(2));
            }
        }

        if (homePath != null)
        {
            // logic if home is specified
            this.homeDir = homePath;
            if (basePath == null)
            {
                this.baseDir = homePath;
                args.getProperties().setProperty("jetty.base",this.baseDir.toString(),"<internal-fallback>");
            }
            else
            {
                this.baseDir = basePath;
            }
        }
        else if (basePath != null)
        {
            // logic if home is undeclared
            this.baseDir = basePath;
        }

        // resolve base and home to absolute paths
        baseDir = baseDir.toAbsolutePath();
        homeDir = homeDir.toAbsolutePath();

        // Update System Properties
        args.addSystemProperty("jetty.home",this.homeDir.toString());
        args.addSystemProperty("jetty.base",this.baseDir.toString());
    }

    public boolean isBaseDifferent()
    {
        return homeDir.compareTo(baseDir) != 0;
    }

    // TODO: deprecate (in favor of Path version)
    public void setBaseDir(File dir)
    {
        setBaseDir(dir.toPath());
    }

    public void setBaseDir(Path dir)
    {
        this.baseDir = dir.toAbsolutePath();
        System.setProperty("jetty.base",dir.toString());
    }

    // TODO: deprecate (in favor of Path version)
    public void setHomeDir(File dir)
    {
        setHomeDir(dir.toPath());
    }

    public void setHomeDir(Path dir)
    {
        this.homeDir = dir.toAbsolutePath();
        System.setProperty("jetty.home",dir.toString());
    }

    /**
     * Replace/Shorten arbitrary path with property strings <code>"${jetty.home}"</code> or <code>"${jetty.base}"</code> where appropriate.
     * 
     * @param path
     *            the path to shorten
     * @return the potentially shortened path
     */
    public String toShortForm(final Path path)
    {
        Path apath = path.toAbsolutePath();

        if (isBaseDifferent())
        {
            // is path part of ${jetty.base} ?
            if (apath.startsWith(baseDir))
            {
                return "${jetty.base}" + File.separatorChar + baseDir.relativize(apath);
            }
        }

        // is path part of ${jetty.home} ?
        if (apath.startsWith(homeDir))
        {
            return "${jetty.home}" + File.separatorChar + homeDir.relativize(apath);
        }

        return apath.toString();
    }

    /**
     * Convenience method for <code>toShortForm(file.toPath())</code>
     */
    public String toShortForm(final File path)
    {
        return toShortForm(path.toPath());
    }

    /**
     * Replace/Shorten arbitrary path with property strings <code>"${jetty.home}"</code> or <code>"${jetty.base}"</code> where appropriate.
     * 
     * @param path
     *            the path to shorten
     * @return the potentially shortened path
     */
    public String toShortForm(final String path)
    {
        if (path == null)
        {
            return path;
        }

        return toShortForm(FS.toPath(path));
    }
}
