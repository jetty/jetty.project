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
import java.lang.reflect.Field;
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
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
    public static class Config
    {
        public static final int UNLIMITED_DEPTH = -9999;
        
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
         *
         * <pre>
         *    On Linux:
         *    Config config = new Config(Path("/home/user/example"));
         *    config.addExcludeGlobRelative("*.war") =&gt; "glob:/home/user/example/*.war"
         * 
         *    On Windows
         *    Config config = new Config(Path("D:/code/examples"));
         *    config.addExcludeGlobRelative("*.war") =&gt; "glob:D:\\code\\examples\\*.war"
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
         *
         * <pre>
         *    On Linux:
         *    Config config = new Config(Path("/home/user/example"));
         *    config.addIncludeGlobRelative("*.war") =&gt; "glob:/home/user/example/*.war"
         * 
         *    On Windows
         *    Config config = new Config(Path("D:/code/examples"));
         *    config.addIncludeGlobRelative("*.war") =&gt; "glob:D:\\code\\examples\\*.war"
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
            if (dir == this.dir)
                subconfig.recurseDepth = this.recurseDepth; // TODO shouldn't really do a subconfig for this
            else
            {
                if (this.recurseDepth == UNLIMITED_DEPTH)
                    subconfig.recurseDepth = UNLIMITED_DEPTH;
                else
                    subconfig.recurseDepth = this.recurseDepth - (dir.getNameCount() - this.dir.getNameCount());                
            }
            return subconfig;
        }

        public int getRecurseDepth()
        {
            return recurseDepth;
        }
        
        public boolean isRecurseDepthUnlimited ()
        {
            return this.recurseDepth == UNLIMITED_DEPTH;
        }
        
        public Path getPath ()
        {
            return this.dir;
        }

        private boolean hasMatch(Path path, List<PathMatcher> matchers)
        {
            for (PathMatcher matcher : matchers)
            {
                if (matcher.matches(path))
                {
                    return true;
                }
            }
            return false;
        }

        public boolean isExcluded(Path dir) throws IOException
        {
            if (excludeHidden)
            {
                if (Files.isHidden(dir))
                {
                    if (NOISY_LOG.isDebugEnabled())
                    {
                        NOISY_LOG.debug("isExcluded [Hidden] on {}",dir);
                    }
                    return true;
                }
            }

            if (excludes.isEmpty())
            {
                // no excludes == everything allowed
                return false;
            }

            boolean matched = hasMatch(dir,excludes);
            if (NOISY_LOG.isDebugEnabled())
            {
                NOISY_LOG.debug("isExcluded [{}] on {}",matched,dir);
            }
            return matched;
        }

        public boolean isIncluded(Path dir)
        {
            if (includes.isEmpty())
            {
                // no includes == everything allowed
                if (NOISY_LOG.isDebugEnabled())
                {
                    NOISY_LOG.debug("isIncluded [All] on {}",dir);
                }
                return true;
            }

            boolean matched = hasMatch(dir,includes);
            if (NOISY_LOG.isDebugEnabled())
            {
                NOISY_LOG.debug("isIncluded [{}] on {}",matched,dir);
            }
            return matched;
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
            if (!child.startsWith(dir))
            {
                // not part of parent? don't recurse
                return false;
            }

            //If not limiting depth, should recurse all
            if (isRecurseDepthUnlimited())
                return true;
            
            //Depth limited, check it
            int childDepth = dir.relativize(child).getNameCount();
            return (childDepth <= recurseDepth);
        }

        private String toGlobPattern(Path path, String subPattern)
        {
            StringBuilder s = new StringBuilder();
            s.append("glob:");

            boolean needDelim = false;

            // Add root (aka "C:\" for Windows)
            Path root = path.getRoot();
            if (root != null)
            {
                if (NOISY_LOG.isDebugEnabled())
                {
                    NOISY_LOG.debug("Path: {} -> Root: {}",path,root);
                }
                for (char c : root.toString().toCharArray())
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
            else
            {
                needDelim = true;
            }

            // Add the individual path segments
            for (Path segment : path)
            {
                if (needDelim)
                {
                    s.append(PATTERN_SEP);
                }
                s.append(segment);
                needDelim = true;
            }

            // Add the sub pattern (if specified)
            if ((subPattern != null) && (subPattern.length() > 0))
            {
                if (needDelim)
                {
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
    
    public static class DepthLimitedFileVisitor extends SimpleFileVisitor<Path>
    {
        private Config base;
        private PathWatcher watcher;
        
        public DepthLimitedFileVisitor (PathWatcher watcher, Config base)
        {
            this.base = base;
            this.watcher = watcher;
        }

        /*
         * 2 situations:
         * 
         * 1. a subtree exists at the time a dir to watch is added (eg watching /tmp/xxx and it contains aaa/)
         *  - will start with /tmp/xxx for which we want to register with the poller
         *  - want to visit each child
         *     - if child is file, gen add event
         *     - if child is dir, gen add event but ONLY register it if inside depth limit and ONLY continue visit of child if inside depth limit
         * 2. a subtree is added inside a watched dir (eg watching /tmp/xxx, add aaa/ to xxx/)
         *  - will start with /tmp/xxx/aaa 
         *    - gen add event but ONLY register it if inside depth limit and ONLY continue visit of children if inside depth limit
         *    
         */
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
        {
            //In a directory:
            // 1. the dir is the base directory
            //   - register it with the poll mechanism
            //   - generate pending add event (iff notifiable and matches patterns)
            //   - continue the visit (sibling dirs, sibling files)
            // 2. the dir is a subdir at some depth in the basedir's tree
            //   - if the level of the subdir less or equal to base's limit
            //     - register it wih the poll mechanism
            //     - generate pending add event (iff notifiable and matches patterns)
            //   - else stop visiting this dir

            if (!base.isExcluded(dir))
            {
                if (base.isIncluded(dir))
                {
                    if (watcher.isNotifiable())
                    {
                        // Directory is specifically included in PathMatcher, then
                        // it should be notified as such to interested listeners
                        PathWatchEvent event = new PathWatchEvent(dir,PathWatchEventType.ADDED);
                        if (LOG.isDebugEnabled())
                        {
                            LOG.debug("Pending {}",event);
                        }
                        watcher.addToPendingList(dir, event);
                    }
                }

                //Register the dir with the watcher if it is:
                // - the base dir and recursion is unlimited
                // - the base dir and its depth is 0 (meaning we want to capture events from it, but not necessarily its children)
                // - the base dir and we are recursing it and the depth is within the limit
                // - a child dir and its depth is within the limits
                if ((base.getPath().equals(dir) && (base.isRecurseDepthUnlimited() || base.getRecurseDepth() >= 0)) || base.shouldRecurseDirectory(dir))
                    watcher.register(dir,base);
            }

            //Continue walking the tree of this dir if it is:
            // - the base dir and recursion is unlimited
            // - the base dir and we're not recursing in it
            // - the base dir and we are recursing it and the depth is within the limit
            // - a child dir and its depth is within the limits
            if ((base.getPath().equals(dir)&& (base.isRecurseDepthUnlimited() || base.getRecurseDepth() >= 0)) || base.shouldRecurseDirectory(dir))
                return FileVisitResult.CONTINUE;
            else
                return FileVisitResult.SKIP_SUBTREE;               
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
        {
            // In a file:
            //    - register with poll mechanism
            //    - generate pending add event (iff notifiable and matches patterns)
            
            if (base.matches(file) && watcher.isNotifiable())
            {
                PathWatchEvent event = new PathWatchEvent(file,PathWatchEventType.ADDED);
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("Pending {}",event);
                }
                watcher.addToPendingList(file, event);
            }

            return FileVisitResult.CONTINUE;
        }
        
    }

    /**
     * Listener for path change events
     */
    public static interface Listener extends EventListener
    {
        void onPathWatchEvent(PathWatchEvent event);
    }
    
    public static interface EventListListener extends EventListener
    {
        void onPathWatchEvents(List<PathWatchEvent> events);
    }

    public static class PathWatchEvent
    {
        private final Path path;
        private final PathWatchEventType type;
        private int count = 0;
     


        public PathWatchEvent(Path path, PathWatchEventType type)
        {
            this.path = path;
            this.count = 1;
            this.type = type;

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

       

        public Path getPath()
        {
            return path;
        }


        public PathWatchEventType getType()
        {
            return type;
        }
        
        
        public void incrementCount(int num)
        {
            count += num;
        }
        public int getCount()
        {
            return count;
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

        @Override
        public String toString()
        {
            return String.format("PathWatchEvent[%s|%s]",type,path);
        }
    }
    
    public static class PathPendingEvents
    {
        private Path _path;
        private List<PathWatchEvent> _events;
        private long _timestamp;
        private long _lastFileSize = -1;

        public PathPendingEvents (Path path)
        {
            _path = path;
        }
        
        public PathPendingEvents (Path path, PathWatchEvent event)
        {
            this (path);
            addEvent(event);
        }
        
        public void addEvent (PathWatchEvent event)
        {
            long now = System.currentTimeMillis();
            _timestamp = now;

            if (_events == null)
            {
                _events = new ArrayList<PathWatchEvent>();
                _events.add(event);
            }
            else
            {
                //Check if the same type of event is already present, in which case we
                //can increment its counter. Otherwise, add it
                PathWatchEvent existingType = null;
                for (PathWatchEvent e:_events)
                {
                    if (e.getType() == event.getType())
                    {
                        existingType = e;
                        break;
                    }
                }

                if (existingType == null)
                {
                    _events.add(event);
                }
                else
                {
                    existingType.incrementCount(event.getCount());
                }
            }

        }
        
        public List<PathWatchEvent> getEvents()
        {
            return _events;
        }

        public long getTimestamp()
        {
            return _timestamp;
        }
   
        
        /**
         * Check to see if the file referenced by this Event is quiet.
         * <p>
         * Will validate the timestamp to see if it is expired, as well as if the file size hasn't changed within the quiet period.
         * <p>
         * Always updates timestamp to 'now' on use of this method.
         *
         * @param expiredDuration
         *            the expired duration past the timestamp to be considered expired
         * @param expiredUnit
         *            the unit of time for the expired check
         * @return true if expired, false if not
         */
        public boolean isQuiet(long now, long expiredDuration, TimeUnit expiredUnit)
        {

            long pastdue = _timestamp + expiredUnit.toMillis(expiredDuration);
            _timestamp = now;

            long fileSize = _path.toFile().length(); //File.length() returns 0 for non existant files
            boolean fileSizeChanged = (_lastFileSize != fileSize);
            _lastFileSize = fileSize;

            if ((now > pastdue) && (!fileSizeChanged /*|| fileSize == 0*/))
            {
                // Quiet period timestamp has expired, and file size hasn't changed, or the file
                // has been deleted.
                // Consider this a quiet event now.
                return true;
            }

            return false;
        }

    }

    public static enum PathWatchEventType
    {
        ADDED, DELETED, MODIFIED, UNKNOWN;
    }

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
            String osl = os.toLowerCase(Locale.ENGLISH);
            IS_WINDOWS = osl.contains("windows");
        }
    }

    private static final Logger LOG = Log.getLogger(PathWatcher.class);
    /**
     * super noisy debug logging
     */
    private static final Logger NOISY_LOG = Log.getLogger(PathWatcher.class.getName() + ".Noisy");

    @SuppressWarnings("unchecked")
    protected static <T> WatchEvent<T> cast(WatchEvent<?> event)
    {
        return (WatchEvent<T>)event;
    }

    private static final WatchEvent.Kind<?> WATCH_EVENT_KINDS[] = { ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY };
    private final WatchService watcher;
    private final WatchEvent.Modifier watchModifiers[];
    private final boolean nativeWatchService;
    
    private Map<WatchKey, Config> keys = new HashMap<>();
    private List<EventListener> listeners = new ArrayList<>();

    /**
     * Update Quiet Time - set to 1000 ms as default (a lower value in Windows is not supported)
     */
    private long updateQuietTimeDuration = 1000;
    private TimeUnit updateQuietTimeUnit = TimeUnit.MILLISECONDS;
    private Thread thread;
    private boolean _notifyExistingOnStart = true;
    private Map<Path, PathPendingEvents> pendingEvents = new LinkedHashMap<>();
    
    
    
    /**
     * Construct new PathWatcher
     * @throws IOException
     */
    public PathWatcher() throws IOException
    {
        this.watcher = FileSystems.getDefault().newWatchService();
        
        WatchEvent.Modifier modifiers[] = null;
        boolean nativeService = true;
        // Try to determine native behavior
        // See http://stackoverflow.com/questions/9588737/is-java-7-watchservice-slow-for-anyone-else
        try
        {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Class<?> pollingWatchServiceClass = Class.forName("sun.nio.fs.PollingWatchService",false,cl);
            if (pollingWatchServiceClass.isAssignableFrom(this.watcher.getClass()))
            {
                nativeService = false;
                LOG.info("Using Non-Native Java {}",pollingWatchServiceClass.getName());
                Class<?> c = Class.forName("com.sun.nio.file.SensitivityWatchEventModifier");
                Field f = c.getField("HIGH");
                modifiers = new WatchEvent.Modifier[]
                {
                    (WatchEvent.Modifier)f.get(c)
                };
            }
        }
        catch (Throwable t)
        {
            // Unknown JVM environment, assuming native.
            LOG.ignore(t);
        }
        
        this.watchModifiers = modifiers;
        this.nativeWatchService = nativeService;
    }

    /**
     * Add a directory to watch with customized watch parameters.
     *
     * @param baseDir
     *            the dir to watch with its customized config
     * @throws IOException
     *             if unable to setup the directory watch
     */
    public void addDirectoryWatch(final Config baseDir) throws IOException
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Watching directory {}",baseDir);
        }
        Files.walkFileTree(baseDir.getPath(), new DepthLimitedFileVisitor(this, baseDir));
    }
    
    

    /**
     * Add a file or directory to watch for changes.
     * 
     * @param file
     * @throws IOException
     */
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

    /**
     * Add a listener for changes the watcher notices.
     * 
     * @param listener change listener
     */
    public void addListener(EventListener listener)
    {
        listeners.add(listener);
    }

    /**
     * Append some info on the paths that we are watching.
     * 
     * @param s
     */
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

    /** 
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
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

    /** 
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        watcher.close();
        super.doStop();
    }
    
    /**
     * Check to see if the watcher is in a state where it should generate
     * watch events to the listeners. Used to determine if watcher should generate
     * events for existing files and dirs on startup.
     * 
     * @return true if the watcher should generate events to the listeners.
     */
    protected boolean isNotifiable ()
    {
        return (isStarted() || (!isStarted() && isNotifyExistingOnStart()));
    }

    /**
     * Get an iterator over the listeners.
     * 
     * @return iterator over the listeners.
     */
    public Iterator<EventListener> getListeners()
    {
        return listeners.iterator();
    }

    /**
     * Change the quiet time.
     * 
     * @return the quiet time in millis
     */
    public long getUpdateQuietTimeMillis()
    {
        return TimeUnit.MILLISECONDS.convert(updateQuietTimeDuration,updateQuietTimeUnit);
    }

  

    /**
     * Generate events to the listeners.
     * 
     * @param events
     */
    protected void notifyOnPathWatchEvents (List<PathWatchEvent> events)
    {
        if (events == null || events.isEmpty())
            return;

        for (EventListener listener : listeners)
        {
            if (listener instanceof EventListListener)
            {
                try
                {
                    ((EventListListener)listener).onPathWatchEvents(events);
                }
                catch (Throwable t)
                {
                    LOG.warn(t);
                }
            }
            else
            {
                Listener l = (Listener)listener;
                for (PathWatchEvent event:events)
                {
                    try
                    {
                        l.onPathWatchEvent(event);
                    }
                    catch (Throwable t)
                    {
                        LOG.warn(t);
                    }
                }
            }
        }

    }

    /**
     * Register a dir or a file with the WatchService.
     * 
     * @param dir
     * @param root
     * @throws IOException
     */
    protected void register(Path dir, Config root) throws IOException
    {
        LOG.debug("Registering watch on {}",dir);
        if(watchModifiers != null) {
            // Java Watcher
            WatchKey key = dir.register(watcher,WATCH_EVENT_KINDS,watchModifiers);
            keys.put(key,root.asSubConfig(dir));
        } else {
            // Native Watcher
            WatchKey key = dir.register(watcher,WATCH_EVENT_KINDS);
            keys.put(key,root.asSubConfig(dir));
        }
    }

    
    /**
     * Delete a listener
     * @param listener
     * @return
     */
    public boolean removeListener(Listener listener)
    {
        return listeners.remove(listener);
    }

    
    /** 
     * Forever loop.
     * 
     * Wait for the WatchService to report some filesystem events for the
     * watched paths.
     * 
     * When an event for a path first occurs, it is subjected to a quiet time.
     * Subsequent events that arrive for the same path during this quiet time are
     * accumulated and the timer reset. Only when the quiet time has expired are
     * the accumulated events sent. MODIFY events are handled slightly differently -
     * multiple MODIFY events arriving within a quiet time are coalesced into a
     * single MODIFY event. Both the accumulation of events and coalescing of MODIFY
     * events reduce the number and frequency of event reporting for "noisy" files (ie
     * those that are undergoing rapid change).
     * 
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run()
    {

        List<PathWatchEvent> notifiableEvents = new ArrayList<PathWatchEvent>();
        
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
                //If no pending events, wait forever for new events
                if (pendingEvents.isEmpty())
                {
                    if (NOISY_LOG.isDebugEnabled())
                        NOISY_LOG.debug("Waiting for take()");

                    key = watcher.take();
                }
                else
                {
                    //There are existing events that might be ready to go,
                    //only wait as long as the quiet time for any new events
                    if (NOISY_LOG.isDebugEnabled())
                        NOISY_LOG.debug("Waiting for poll({}, {})",updateQuietTimeDuration,updateQuietTimeUnit);

                    key = watcher.poll(updateQuietTimeDuration,updateQuietTimeUnit);
                   
                    //If no new events its safe to process the pendings
                    if (key == null)
                    {
                        long now = System.currentTimeMillis();
                        // no new event encountered.
                        for (Path path : new HashSet<Path>(pendingEvents.keySet()))
                        {
                            PathPendingEvents pending = pendingEvents.get(path);
                            if (pending.isQuiet(now, updateQuietTimeDuration,updateQuietTimeUnit))
                            {
                                //No fresh events received during quiet time for this path, 
                                //so generate the events that were pent up
                                for (PathWatchEvent p:pending.getEvents())
                                {
                                    notifiableEvents.add(p);
                                }
                                // remove from pending list
                                pendingEvents.remove(path);
                            }
                        }
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

            //If there was some new events to process
            if (key != null)
            {

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
                            addToPendingList(child, new PathWatchEvent(child,ev));
                        }
                    }
                    else if (config.matches(child))
                    {
                        addToPendingList(child, new PathWatchEvent(child,ev));      
                    }
                }
            }

            //Send any notifications generated this pass
            notifyOnPathWatchEvents(notifiableEvents);
            notifiableEvents.clear();
            
            if (key != null && !key.reset())
            {
                keys.remove(key);
                if (keys.isEmpty())
                {
                    return; // all done, no longer monitoring anything
                }
            }
        }
    }
    
    
    /**
     * Add an event reported by the WatchService to list of pending events
     * that will be sent after their quiet time has expired.
     * 
     * @param path
     * @param event
     */
    public void addToPendingList (Path path, PathWatchEvent event)
    {
        PathPendingEvents pending = pendingEvents.get(path);
        
        //Are there already pending events for this path?
        if (pending == null)
        {
            //No existing pending events, create pending list
            pendingEvents.put(path,new PathPendingEvents(path, event));
        }
        else
        {
            //There are already some events pending for this path
            pending.addEvent(event);
        }
    }
    
    
    /**
     * Whether or not to issue notifications for directories and files that
     * already exist when the watcher starts.
     * 
     * @param notify
     */
    public void setNotifyExistingOnStart (boolean notify)
    {
        _notifyExistingOnStart = notify;
    }
    
    public boolean isNotifyExistingOnStart ()
    {
        return _notifyExistingOnStart;
    }

    /**
     * Set the quiet time.
     * 
     * @param duration
     * @param unit
     */
    public void setUpdateQuietTime(long duration, TimeUnit unit)
    {
        long desiredMillis = unit.toMillis(duration);
        
        if (!this.nativeWatchService && (desiredMillis < 5000))
        {
            LOG.warn("Quiet Time is too low for non-native WatchService [{}]: {} < 5000 ms (defaulting to 5000 ms)",watcher.getClass().getName(),desiredMillis);
            this.updateQuietTimeDuration = 5000;
            this.updateQuietTimeUnit = TimeUnit.MILLISECONDS;
            return;
        }

        if (IS_WINDOWS && (desiredMillis < 1000))
        {
            LOG.warn("Quiet Time is too low for Microsoft Windows: {} < 1000 ms (defaulting to 1000 ms)",desiredMillis);
            this.updateQuietTimeDuration = 1000;
            this.updateQuietTimeUnit = TimeUnit.MILLISECONDS;
            return;
        }
        
        // All other OS and watch service combinations can use desired setting
        this.updateQuietTimeDuration = duration;
        this.updateQuietTimeUnit = unit;
    }

    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder(this.getClass().getName());
        appendConfigId(s);
        return s.toString();
    }
}
