//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import static java.nio.file.StandardWatchEventKinds.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Watch a Path (and sub directories) for Path changes.
 * <p>
 * Suitable replacement for the old {@link Scanner} implementation.
 * <p>
 * Allows for configured Excludes and Includes using {@link FileSystem#getPathMatcher(String)} syntax.
 * <p>
 * Reports activity via registered {@link Listener}s
 */
public class PathWatcher extends AbstractLifeCycle implements Runnable
{
    /**
     * Set to true to enable super noisy debug logging
     */
    private static final boolean NOISY = false;
    
    private static final boolean IS_WINDOWS;
    
    static
    {
        String os = System.getProperty("os.name");
        if (os == null)
        {
            IS_WINDOWS = false;
        }
        else
        {
            IS_WINDOWS = os.toLowerCase(Locale.ENGLISH).contains("windows");
        }
    }

    public static class Config
    {
        private static final String PATTERN_SEP;

        static
        {
            String sep = File.separator;
            if (File.separatorChar == '\\')
            {
                sep = "\\\\";
            }
            PATTERN_SEP = sep;
        }
        
        protected final Path dir;
        protected int recurseDepth = 0; // 0 means no sub-directories are scanned
        protected List<PathMatcher> includes;
        protected List<PathMatcher> excludes;
        protected boolean excludeHidden = false;

        public Config(Path path)
        {
            this.dir = path;
            includes = new ArrayList<>();
            excludes = new ArrayList<>();
        }

        /**
         * Add an exclude PathMatcher
         *
         * @param matcher
         *            the path matcher for this exclude
         */
        public void addExclude(PathMatcher matcher)
        {
            this.excludes.add(matcher);
        }

        /**
         * Add an exclude PathMatcher.
         * <p>
         * Note: this pattern is FileSystem specific (so use "/" for Linux and OSX, and "\\" for Windows)
         *
         * @param syntaxAndPattern
         *            the PathMatcher syntax and pattern to use
         * @see FileSystem#getPathMatcher(String) for detail on syntax and pattern
         */
        public void addExclude(final String syntaxAndPattern)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Adding exclude: [{}]",syntaxAndPattern);
            }
            addExclude(dir.getFileSystem().getPathMatcher(syntaxAndPattern));
        }

        /**
         * Add a <code>glob:</code> syntax pattern exclude reference in a directory relative, os neutral, pattern.
         * <p>
         * 
         * <pre>
         *    On Linux:
         *    Config config = new Config(Path("/home/user/example"));
         *    config.addExcludeGlobRelative("*.war") => "glob:/home/user/example/*.war"
         *    
         *    On Windows
         *    Config config = new Config(Path("D:/code/examples"));
         *    config.addExcludeGlobRelative("*.war") => "glob:D:\\code\\examples\\*.war"
         * 
         * </pre>
         * 
         * @param pattern
         *            the pattern, in unixy format, relative to config.dir
         */
        public void addExcludeGlobRelative(String pattern)
        {
            addExclude(toGlobPattern(dir,pattern));
        }

        /**
         * Exclude hidden files and hidden directories
         */
        public void addExcludeHidden()
        {
            if (!excludeHidden)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("Adding hidden files and directories to exclusions");
                }
                excludeHidden = true;

                addExclude("regex:^.*" + PATTERN_SEP + "\\..*$"); // ignore hidden files
                addExclude("regex:^.*" + PATTERN_SEP + "\\..*" + PATTERN_SEP + ".*$"); // ignore files in hidden directories
            }
        }

        /**
         * Add multiple exclude PathMatchers
         *
         * @param syntaxAndPatterns
         *            the list of PathMatcher syntax and patterns to use
         * @see FileSystem#getPathMatcher(String) for detail on syntax and pattern
         */
        public void addExcludes(List<String> syntaxAndPatterns)
        {
            for (String syntaxAndPattern : syntaxAndPatterns)
            {
                addExclude(syntaxAndPattern);
            }
        }

        /**
         * Add an include PathMatcher
         *
         * @param matcher
         *            the path matcher for this include
         */
        public void addInclude(PathMatcher matcher)
        {
            this.includes.add(matcher);
        }

        /**
         * Add an include PathMatcher
         *
         * @param syntaxAndPattern
         *            the PathMatcher syntax and pattern to use
         * @see FileSystem#getPathMatcher(String) for detail on syntax and pattern
         */
        public void addInclude(String syntaxAndPattern)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Adding include: [{}]",syntaxAndPattern);
            }
            addInclude(dir.getFileSystem().getPathMatcher(syntaxAndPattern));
        }

        /**
         * Add a <code>glob:</code> syntax pattern reference in a directory relative, os neutral, pattern.
         * <p>
         * 
         * <pre>
         *    On Linux:
         *    Config config = new Config(Path("/home/user/example"));
         *    config.addIncludeGlobRelative("*.war") => "glob:/home/user/example/*.war"
         *    
         *    On Windows
         *    Config config = new Config(Path("D:/code/examples"));
         *    config.addIncludeGlobRelative("*.war") => "glob:D:\\code\\examples\\*.war"
         * 
         * </pre>
         * 
         * @param pattern
         *            the pattern, in unixy format, relative to config.dir
         */
        public void addIncludeGlobRelative(String pattern)
        {
            addInclude(toGlobPattern(dir,pattern));
        }

        /**
         * Add multiple include PathMatchers
         *
         * @param syntaxAndPatterns
         *            the list of PathMatcher syntax and patterns to use
         * @see FileSystem#getPathMatcher(String) for detail on syntax and pattern
         */
        public void addIncludes(List<String> syntaxAndPatterns)
        {
            for (String syntaxAndPattern : syntaxAndPatterns)
            {
                addInclude(syntaxAndPattern);
            }
        }

        /**
         * Build a new config from a this configuration.
         * <p>
         * Useful for working with sub-directories that also need to be watched.
         *
         * @param dir
         *            the directory to build new Config from (using this config as source of includes/excludes)
         * @return the new Config
         */
        public Config asSubConfig(Path dir)
        {
            Config subconfig = new Config(dir);
            subconfig.includes = this.includes;
            subconfig.excludes = this.excludes;
            subconfig.recurseDepth = this.recurseDepth - 1;
            return subconfig;
        }

        public int getRecurseDepth()
        {
            return recurseDepth;
        }

        private boolean hasMatch(Path path, List<PathMatcher> matchers)
        {
            for (PathMatcher matcher : matchers)
            {
                if (matcher.matches(path))
                {
                    if(NOISY) LOG.debug("Matched TRUE on {}",path);
                    return true;
                }
            }

            if(NOISY) LOG.debug("Matched FALSE on {}",path);
            return false;
        }

        public boolean isExcluded(Path dir) throws IOException
        {
            if (excludeHidden)
            {
                if (Files.isHidden(dir))
                {
                    return true;
                }
            }

            if (excludes.isEmpty())
            {
                // no excludes == everything allowed
                return false;
            }

            return hasMatch(dir,excludes);
        }

        public boolean isIncluded(Path dir)
        {
            if (includes.isEmpty())
            {
                // no includes == everything allowed
                return true;
            }
            return hasMatch(dir,includes);
        }

        public boolean matches(Path path)
        {
            try
            {
                return !isExcluded(path) && isIncluded(path);
            }
            catch (IOException e)
            {
                LOG.warn("Unable to match path: " + path,e);
                return false;
            }
        }

        /**
         * Set the recurse depth for the directory scanning.
         * <p>
         * 0 indicates no recursion, 1 is only one directory deep, and so on.
         *
         * @param depth
         *            the number of directories deep to recurse
         */
        public void setRecurseDepth(int depth)
        {
            this.recurseDepth = depth;
        }

        /**
         * Determine if the provided child directory should be recursed into based on the configured {@link #setRecurseDepth(int)}
         *
         * @param child
         *            the child directory to test against
         * @return true if recurse should occur, false otherwise
         */
        public boolean shouldRecurseDirectory(Path child)
        {
            if (!child.startsWith(child))
            {
                // not part of parent? don't recurse
                return false;
            }

            int childDepth = dir.relativize(child).getNameCount();
            return (childDepth <= recurseDepth);
        }

        private String toGlobPattern(Path path, String subPattern)
        {
            StringBuilder s = new StringBuilder();
            s.append("glob:");

            if (path.getRoot() != null)
            {
                for (char c : path.getRoot().toString().toCharArray())
                {
                    if (c == '\\')
                    {
                        s.append(PATTERN_SEP);
                    }
                    else
                    {
                        s.append(c);
                    }
                }
            }

            for (Path segment : path)
            {
                s.append(segment);
                s.append(PATTERN_SEP);
            }

            for (char c : subPattern.toCharArray())
            {
                if (c == '/')
                {
                    s.append(PATTERN_SEP);
                }
                else
                {
                    s.append(c);
                }
            }

            return s.toString();
        }

        @Override
        public String toString()
        {
            StringBuilder s = new StringBuilder();
            s.append(dir);
            if (recurseDepth > 0)
            {
                s.append(" [depth=").append(recurseDepth).append("]");
            }
            return s.toString();
        }
    }

    /**
     * Listener for path change events
     */
    public static interface Listener
    {
        void onPathWatchEvent(PathWatchEvent event);
    }

    public static class PathWatchEvent
    {
        private final Path path;
        private final PathWatchEventType type;
        private int count;
        private long timestamp;

        public PathWatchEvent(Path path, PathWatchEventType type)
        {
            this.path = path;
            this.count = 0;
            this.type = type;
            this.timestamp = System.currentTimeMillis();
        }

        public PathWatchEvent(Path path, WatchEvent<Path> event)
        {
            this.path = path;
            this.count = event.count();
            if (event.kind() == ENTRY_CREATE)
            {
                this.type = PathWatchEventType.ADDED;
            }
            else if (event.kind() == ENTRY_DELETE)
            {
                this.type = PathWatchEventType.DELETED;
            }
            else if (event.kind() == ENTRY_MODIFY)
            {
                this.type = PathWatchEventType.MODIFIED;
            }
            else
            {
                this.type = PathWatchEventType.UNKNOWN;
            }
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
            {
                return true;
            }
            if (obj == null)
            {
                return false;
            }
            if (getClass() != obj.getClass())
            {
                return false;
            }
            PathWatchEvent other = (PathWatchEvent)obj;
            if (path == null)
            {
                if (other.path != null)
                {
                    return false;
                }
            }
            else if (!path.equals(other.path))
            {
                return false;
            }
            if (type != other.type)
            {
                return false;
            }
            return true;
        }

        /**
         * Check the timestamp to see if it is expired.
         * <p>
         * Updates timestamp to 'now' on use of this method.
         *
         * @param expiredDuration
         *            the expired duration past the timestamp to be considered expired
         * @param expiredUnit
         *            the unit of time for the expired check
         * @return true if expired, false if not
         */
        public boolean expiredCheck(long expiredDuration, TimeUnit expiredUnit)
        {
            long now = System.currentTimeMillis();
            long pastdue = this.timestamp + expiredUnit.toMillis(expiredDuration);
            if (now > pastdue)
            {
                return true;
            }
            this.timestamp = now;
            return false;
        }

        public int getCount()
        {
            return count;
        }

        public Path getPath()
        {
            return path;
        }

        public long getTimestamp()
        {
            return timestamp;
        }

        public PathWatchEventType getType()
        {
            return type;
        }

        @Override
        public int hashCode()
        {
            final int prime = 31;
            int result = 1;
            result = (prime * result) + ((path == null)?0:path.hashCode());
            result = (prime * result) + ((type == null)?0:type.hashCode());
            return result;
        }

        public void incrementCount(int num)
        {
            this.count += num;
        }

        @Override
        public String toString()
        {
            return String.format("PathWatchEvent[%s|%s,count=%d]",type,path,count);
        }
    }

    public static enum PathWatchEventType
    {
        ADDED,
        DELETED,
        MODIFIED,
        UNKNOWN;
    }

    private static final Logger LOG = Log.getLogger(PathWatcher.class);

    @SuppressWarnings("unchecked")
    protected static <T> WatchEvent<T> cast(WatchEvent<?> event)
    {
        return (WatchEvent<T>)event;
    }

    private WatchService watcher;
    private Map<WatchKey, Config> keys = new HashMap<>();
    private List<Listener> listeners = new ArrayList<>();
    private List<PathWatchEvent> pendingAddEvents = new ArrayList<>();
    /**
     * Update Quiet Time - set to 1000 ms as default (a lower value in Windows is not supported)
     */
    private long updateQuietTimeDuration = 1000;
    private TimeUnit updateQuietTimeUnit = TimeUnit.MILLISECONDS;
    private Thread thread;

    public PathWatcher() throws IOException
    {
        this.watcher = FileSystems.getDefault().newWatchService();
    }

    /**
     * Add a directory watch configuration to the the PathWatcher.
     *
     * @param baseDir
     *            the base directory configuration to watch
     * @throws IOException
     *             if unable to setup the directory watch
     */
    public void addDirectoryWatch(final Config baseDir) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Watching directory {}",baseDir);
        }
        Files.walkFileTree(baseDir.dir,new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
            {
                FileVisitResult result = FileVisitResult.SKIP_SUBTREE;

                if (LOG.isDebugEnabled())
                {
                    LOG.debug("preVisitDirectory: {}",dir);
                }

                // Is directory not specifically excluded?
                if (!baseDir.isExcluded(dir))
                {
                    if (baseDir.isIncluded(dir))
                    {
                        // Directory is specifically included in PathMatcher, then
                        // it should be notified as such to interested listeners
                        PathWatchEvent event = new PathWatchEvent(dir,PathWatchEventType.ADDED);
                        if (LOG.isDebugEnabled())
                        {
                            LOG.debug("Pending {}",event);
                        }
                        pendingAddEvents.add(event);
                    }

                    // Recurse Directory, based on configured depth
                    if (baseDir.shouldRecurseDirectory(dir))
                    {
                        register(dir,baseDir);
                        result = FileVisitResult.CONTINUE;
                    }
                }

                if (LOG.isDebugEnabled())
                {
                    LOG.debug("preVisitDirectory: result {}",result);
                }

                return result;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                if (baseDir.matches(file))
                {
                    PathWatchEvent event = new PathWatchEvent(file,PathWatchEventType.ADDED);
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Pending {}",event);
                    }
                    pendingAddEvents.add(event);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public void addFileWatch(final Path file) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Watching file {}",file);
        }
        Path abs = file;
        if (!abs.isAbsolute())
        {
            abs = file.toAbsolutePath();
        }
        Config config = new Config(abs.getParent());
        // the include for the directory itself
        config.addIncludeGlobRelative("");
        // the include for the file
        config.addIncludeGlobRelative(file.getFileName().toString());
        addDirectoryWatch(config);
    }

    public void addListener(Listener listener)
    {
        listeners.add(listener);
    }

    private void appendConfigId(StringBuilder s)
    {
        List<Path> dirs = new ArrayList<>();

        for (Config config : keys.values())
        {
            dirs.add(config.dir);
        }

        Collections.sort(dirs);

        s.append("[");
        if (dirs.size() > 0)
        {
            s.append(dirs.get(0));
            if (dirs.size() > 1)
            {
                s.append(" (+").append(dirs.size() - 1).append(")");
            }
        }
        else
        {
            s.append("<null>");
        }
        s.append("]");
    }

    @Override
    protected void doStart() throws Exception
    {
        // Start Thread for watcher take/pollKeys loop
        StringBuilder threadId = new StringBuilder();
        threadId.append("PathWatcher-Thread");
        appendConfigId(threadId);

        thread = new Thread(this,threadId.toString());
        thread.start();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        watcher.close();
        super.doStop();
    }

    public Iterator<Listener> getListeners()
    {
        return listeners.iterator();
    }

    public long getUpdateQuietTimeMillis()
    {
        return TimeUnit.MILLISECONDS.convert(updateQuietTimeDuration,updateQuietTimeUnit);
    }

    protected void notifyOnPathWatchEvent(PathWatchEvent event)
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onPathWatchEvent(event);
            }
            catch (Throwable t)
            {
                LOG.warn(t);
            }
        }
    }

    protected void register(Path dir, Config root) throws IOException
    {
        LOG.debug("Registering watch on {}",dir);
        WatchKey key = dir.register(watcher,ENTRY_CREATE,ENTRY_DELETE,ENTRY_MODIFY);
        keys.put(key,root.asSubConfig(dir));
    }

    public boolean removeListener(Listener listener)
    {
        return listeners.remove(listener);
    }

    @Override
    public void run()
    {
        Map<Path, PathWatchEvent> pendingUpdateEvents = new HashMap<>();

        // Start the java.nio watching
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Starting java.nio file watching with {}",watcher);
        }

        while (true)
        {
            WatchKey key = null;
            try
            {
                // Process old events (from addDirectoryWatch())
                if (!pendingAddEvents.isEmpty())
                {
                    for (PathWatchEvent event : pendingAddEvents)
                    {
                        notifyOnPathWatchEvent(event);
                    }
                    pendingAddEvents.clear();
                }

                // Process new events
                if (pendingUpdateEvents.isEmpty())
                {
                    if(NOISY) LOG.debug("Waiting for take()");
                    // wait for any event
                    key = watcher.take();
                }
                else
                {
                    if(NOISY) LOG.debug("Waiting for poll({}, {})",updateQuietTimeDuration,updateQuietTimeUnit);
                    key = watcher.poll(updateQuietTimeDuration,updateQuietTimeUnit);
                    if (key == null)
                    {
                        // no new event encountered.
                        for (Path path : new HashSet<Path>(pendingUpdateEvents.keySet()))
                        {
                            PathWatchEvent pending = pendingUpdateEvents.get(path);
                            if (pending.expiredCheck(updateQuietTimeDuration,updateQuietTimeUnit))
                            {
                                // it is expired
                                // notify that update is complete
                                notifyOnPathWatchEvent(pending);
                                // remove from pending list
                                pendingUpdateEvents.remove(path);
                            }
                        }
                        continue; // loop again
                    }
                }
            }
            catch (ClosedWatchServiceException e)
            {
                // Normal shutdown of watcher
                return;
            }
            catch (InterruptedException e)
            {
                if (isRunning())
                {
                    LOG.warn(e);
                }
                else
                {
                    LOG.ignore(e);
                }
                return;
            }

            Config config = keys.get(key);
            if (config == null)
            {
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("WatchKey not recognized: {}",key);
                }
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents())
            {
                @SuppressWarnings("unchecked")
                WatchEvent.Kind<Path> kind = (Kind<Path>)event.kind();
                WatchEvent<Path> ev = cast(event);
                Path name = ev.context();
                Path child = config.dir.resolve(name);

                if (kind == ENTRY_CREATE)
                {
                    // handle special case for registering new directories
                    // recursively
                    if (Files.isDirectory(child,LinkOption.NOFOLLOW_LINKS))
                    {
                        try
                        {
                            addDirectoryWatch(config.asSubConfig(child));
                        }
                        catch (IOException e)
                        {
                            LOG.warn(e);
                        }
                    }
                    else if (config.matches(child))
                    {
                        notifyOnPathWatchEvent(new PathWatchEvent(child,ev));
                    }
                }
                else if (config.matches(child))
                {
                    if (kind == ENTRY_MODIFY)
                    {
                        // handle modify events with a quiet time before they
                        // are notified to the listeners

                        PathWatchEvent pending = pendingUpdateEvents.get(child);
                        if (pending == null)
                        {
                            // new pending update
                            pendingUpdateEvents.put(child,new PathWatchEvent(child,ev));
                        }
                        else
                        {
                            // see if pending is expired
                            if (pending.expiredCheck(updateQuietTimeDuration,updateQuietTimeUnit))
                            {
                                // it is expired, notify that update is complete
                                notifyOnPathWatchEvent(pending);
                                // remove from pending list
                                pendingUpdateEvents.remove(child);
                            }
                            else
                            {
                                // update the count (useful for debugging)
                                pending.incrementCount(ev.count());
                            }
                        }
                    }
                    else
                    {
                        notifyOnPathWatchEvent(new PathWatchEvent(child,ev));
                    }
                }
            }

            if (!key.reset())
            {
                keys.remove(key);
                if (keys.isEmpty())
                {
                    return; // all done, no longer monitoring anything
                }
            }
        }
    }

    public void setUpdateQuietTime(long duration, TimeUnit unit)
    {
        long desiredMillis = unit.toMillis(duration);
        
        if (IS_WINDOWS && desiredMillis < 1000)
        {
            LOG.warn("Quiet Time is too low for Microsoft Windows: {} < 1000 ms (defaulting to 1000 ms)",desiredMillis);
            this.updateQuietTimeDuration = 1000;
            this.updateQuietTimeUnit = TimeUnit.MILLISECONDS;
        }
        else
        {
            // All other OS's can use desired setting
            this.updateQuietTimeDuration = duration;
            this.updateQuietTimeUnit = unit;
        }
    }

    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder(this.getClass().getName());
        appendConfigId(s);
        return s.toString();
    }
}
