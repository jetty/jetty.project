//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Predicate;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Scanner
 *
 * Utility for scanning a directory for added, removed and changed
 * files and reporting these events via registered Listeners.
 */
public class Scanner extends AbstractLifeCycle
{
    /**
     * When walking a directory, a depth of 1 ensures that
     * the directory's descendants are visited, not just the
     * directory itself (as a file).
     * 
     * @see Visitor#preVisitDirectory
     */
    public static final int DEFAULT_SCAN_DEPTH = 1;
    public static final int MAX_SCAN_DEPTH = Integer.MAX_VALUE;
    
    private static final Logger LOG = Log.getLogger(Scanner.class);
    private static int __scannerId = 0;
    private int _scanInterval;
    private int _scanCount = 0;
    private final List<Listener> _listeners = new ArrayList<>();
    private final Map<String, TimeNSize> _prevScan = new HashMap<>();
    private final Map<String, TimeNSize> _currentScan = new HashMap<>();
    private FilenameFilter _filter;
    private final Map<Path, IncludeExcludeSet<PathMatcher, Path>> _scannables = new HashMap<>();
    private volatile boolean _running = false;
    private boolean _reportExisting = true;
    private boolean _reportDirs = true;
    private Timer _timer;
    private TimerTask _task;
    private int _scanDepth = DEFAULT_SCAN_DEPTH;

    public enum Notification
    {
        ADDED, CHANGED, REMOVED
    }

    private final Map<String, Notification> _notifications = new HashMap<>();
    
    /**
     * PathMatcherSet
     *
     * A set of PathMatchers for testing Paths against path matching patterns via
     * @see IncludeExcludeSet
     */
    static class PathMatcherSet extends HashSet<PathMatcher> implements Predicate<Path>
    {
        @Override
        public boolean test(Path p)
        {
            for (PathMatcher pm : this)
            {
                if (pm.matches(p))
                    return true;
            }
            return false;
        }
    }

    /**
     * TimeNSize
     * 
     * Metadata about a file: Last modified time and file size.
     */
    static class TimeNSize
    {
        final long _lastModified;
        final long _size;

        public TimeNSize(long lastModified, long size)
        {
            _lastModified = lastModified;
            _size = size;
        }

        @Override
        public int hashCode()
        {
            return (int)_lastModified ^ (int)_size;
        }

        @Override
        public boolean equals(Object o)
        {
            if (o instanceof TimeNSize)
            {
                TimeNSize tns = (TimeNSize)o;
                return tns._lastModified == _lastModified && tns._size == _size;
            }
            return false;
        }

        @Override
        public String toString()
        {
            return "[lm=" + _lastModified + ",s=" + _size + "]";
        }
    }

    /**
     * Visitor
     *
     * A FileVisitor for walking a subtree of paths. The Scanner uses
     * this to examine the dirs and files it has been asked to scan.
     */
    class Visitor implements FileVisitor<Path>
    {
        Map<String, TimeNSize> scanInfoMap;
        IncludeExcludeSet<PathMatcher, Path> rootIncludesExcludes;
        Path root;

        public Visitor(Path root, IncludeExcludeSet<PathMatcher, Path> rootIncludesExcludes, Map<String, TimeNSize> scanInfoMap)
        {
            this.root = root;
            this.rootIncludesExcludes = rootIncludesExcludes;
            this.scanInfoMap = scanInfoMap;
        }
        
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
        {
            if (!Files.exists(dir))
                return FileVisitResult.SKIP_SUBTREE;
            
            File f = dir.toFile();
            
            //if we want to report directories and we haven't already seen it
            if (_reportDirs && !scanInfoMap.containsKey(f.getCanonicalPath()))
            {
                boolean accepted = false;
                if (rootIncludesExcludes != null && !rootIncludesExcludes.isEmpty())
                { 
                    //accepted if not explicitly excluded and either is explicitly included or there are no explicit inclusions
                    boolean result = rootIncludesExcludes.test(dir);
                    if (result)
                        accepted = true;
                }
                else
                {
                    if (_filter == null || _filter.accept(f.getParentFile(), f.getName()))
                        accepted = true;
                }

                if (accepted)
                {
                    scanInfoMap.put(f.getCanonicalPath(), new TimeNSize(f.lastModified(), f.isDirectory() ? 0 : f.length()));
                    if (LOG.isDebugEnabled()) LOG.debug("scan accepted dir {} mod={}", f, f.lastModified());
                }
            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
        {
            if (!Files.exists(file))
                return FileVisitResult.CONTINUE;

            File f = file.toFile();
            boolean accepted = false;

            if (f.isFile() || (f.isDirectory() && _reportDirs && !scanInfoMap.containsKey(f.getCanonicalPath())))
            {
                if (rootIncludesExcludes != null && !rootIncludesExcludes.isEmpty())
                {
                    //accepted if not explicitly excluded and either is explicitly included or there are no explicit inclusions
                    boolean result = rootIncludesExcludes.test(file);
                    if (result)
                        accepted = true;
                }
                else if (_filter == null || _filter.accept(f.getParentFile(), f.getName()))
                    accepted = true;
            }

            if (accepted)
            {
                scanInfoMap.put(f.getCanonicalPath(), new TimeNSize(f.lastModified(), f.isDirectory() ? 0 : f.length()));
                if (LOG.isDebugEnabled()) LOG.debug("scan accepted {} mod={}", f, f.lastModified());
            }
            
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException
        {
            LOG.warn(exc);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
        {
            return FileVisitResult.CONTINUE;
        }
    }
    
    /**
     * Listener
     *
     * Marker for notifications re file changes.
     */
    public interface Listener
    {
    }

    public interface ScanListener extends Listener
    {
        void scan();
    }

    public interface DiscreteListener extends Listener
    {
        void fileChanged(String filename) throws Exception;

        void fileAdded(String filename) throws Exception;

        void fileRemoved(String filename) throws Exception;
    }

    public interface BulkListener extends Listener
    {
        void filesChanged(List<String> filenames) throws Exception;
    }

    /**
     * Listener that notifies when a scan has started and when it has ended.
     */
    public interface ScanCycleListener extends Listener
    {
        void scanStarted(int cycle) throws Exception;

        void scanEnded(int cycle) throws Exception;
    }

    /**
     *
     */
    public Scanner()
    {
    }

    /**
     * Get the scan interval
     *
     * @return interval between scans in seconds
     */
    public synchronized int getScanInterval()
    {
        return _scanInterval;
    }

    /**
     * Set the scan interval
     *
     * @param scanInterval pause between scans in seconds, or 0 for no scan after the initial scan.
     */
    public synchronized void setScanInterval(int scanInterval)
    {
        _scanInterval = scanInterval;
        schedule();
    }

    public void setScanDirs(List<File> dirs)
    {
        _scannables.clear();
        if (dirs == null)
            return;

        for (File f:dirs)
        {
            addScanDir(f);
        }
    }

    @Deprecated
    public synchronized void addScanDir(File dir)
    {
        if (dir == null)
            return;
        try
        {
            if (dir.isDirectory())
                addDirectory(dir.toPath());
            else
                addFile(dir.toPath());
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }
    
    /**
     * Add a file to be scanned. The file must not be null, and must exist.
     * 
     * @param p the Path of the file to scan.
     * @throws IOException 
     */
    public synchronized void addFile(Path p) throws IOException
    {
        if (p == null)
            throw new IllegalStateException("Null path");
        
        File f = p.toFile();
        if (!f.exists() || f.isDirectory())
            throw new IllegalStateException("Not file or doesn't exist: " + f.getCanonicalPath());
        _scannables.put(p, null);
    }

    /**
     * Add a directory to be scanned. The directory must not be null and must exist.
     * 
     * @param p the directory to scan.
     * @return an IncludeExcludeSet to which the caller can add PathMatcher patterns to match
     * @throws IOException
     */
    public synchronized IncludeExcludeSet<PathMatcher, Path> addDirectory(Path p)
        throws IOException
    {
        if (p == null)
            throw new IllegalStateException("Null path");
        
        File f = p.toFile();
        if (!f.exists() || !f.isDirectory())
            throw new IllegalStateException("Not directory or doesn't exist: " + f.getCanonicalPath());
        
        IncludeExcludeSet<PathMatcher, Path> includesExcludes = _scannables.get(p);
        if (includesExcludes == null)
        {
            includesExcludes = new IncludeExcludeSet<>(PathMatcherSet.class);
            _scannables.put(p.toRealPath(), includesExcludes);
        }
        
        return includesExcludes;
    }

    @Deprecated
    public List<File> getScanDirs()
    {
        ArrayList<File> files = new ArrayList<>();
        for (Path p : _scannables.keySet())
            files.add(p.toFile());
        return Collections.unmodifiableList(files);
    }
    
    public Set<Path> getScannables()
    {
        return _scannables.keySet();
    }

    /**
     * @param recursive True if scanning is recursive
     * @see #setScanDepth(int)
     */
    @Deprecated
    public void setRecursive(boolean recursive)
    {
        _scanDepth = recursive ? Integer.MAX_VALUE : 1;
    }

    /**
     * @return True if scanning is recursive
     * @see #getScanDepth()
     */
    @Deprecated
    public boolean getRecursive()
    {
        return _scanDepth > 1;
    }

    /**
     * Get the scanDepth.
     *
     * @return the scanDepth
     */
    public int getScanDepth()
    {
        return _scanDepth;
    }

    /**
     * Set the scanDepth.
     *
     * @param scanDepth the scanDepth to set
     */
    public void setScanDepth(int scanDepth)
    {
        _scanDepth = scanDepth;
    }

    /**
     * Apply a filter to files found in the scan directory.
     * Only files matching the filter will be reported as added/changed/removed.
     *
     * @param filter the filename filter to use
     */
    @Deprecated
    public void setFilenameFilter(FilenameFilter filter)
    {
        _filter = filter;
    }

    /**
     * Get any filter applied to files in the scan dir.
     *
     * @return the filename filter
     */
    @Deprecated
    public FilenameFilter getFilenameFilter()
    {
        return _filter;
    }

    /**
     * Whether or not an initial scan will report all files as being
     * added.
     *
     * @param reportExisting if true, all files found on initial scan will be
     * reported as being added, otherwise not
     */
    public void setReportExistingFilesOnStartup(boolean reportExisting)
    {
        _reportExisting = reportExisting;
    }

    public boolean getReportExistingFilesOnStartup()
    {
        return _reportExisting;
    }

    /**
     * Set if found directories should be reported.
     *
     * @param dirs true to report directory changes as well
     */
    public void setReportDirs(boolean dirs)
    {
        _reportDirs = dirs;
    }

    public boolean getReportDirs()
    {
        return _reportDirs;
    }

    /**
     * Add an added/removed/changed listener
     *
     * @param listener the listener to add
     */
    public synchronized void addListener(Listener listener)
    {
        if (listener == null)
            return;
        _listeners.add(listener);
    }

    /**
     * Remove a registered listener
     *
     * @param listener the Listener to be removed
     */
    public synchronized void removeListener(Listener listener)
    {
        if (listener == null)
            return;
        _listeners.remove(listener);
    }

    /**
     * Start the scanning action.
     */
    @Override
    public synchronized void doStart()
    {
        if (_running)
            return;

        _running = true;
        if (LOG.isDebugEnabled())
            LOG.debug("Scanner start: rprtExists={}, depth={}, rprtDirs={}, interval={}, filter={}, scannables={}", 
                _reportExisting, _scanDepth, _reportDirs, _scanInterval, _filter, _scannables);

        if (_reportExisting)
        {
            // if files exist at startup, report them
            scan();
            scan(); // scan twice so files reported as stable
        }
        else
        {
            //just register the list of existing files and only report changes
            scanFiles();
            _prevScan.putAll(_currentScan);
        }
        schedule();
    }

    public TimerTask newTimerTask()
    {
        return new TimerTask()
        {
            @Override
            public void run()
            {
                scan();
            }
        };
    }

    public Timer newTimer()
    {
        return new Timer("Scanner-" + __scannerId++, true);
    }

    public void schedule()
    {
        if (_running)
        {
            if (_timer != null)
                _timer.cancel();
            if (_task != null)
                _task.cancel();
            if (getScanInterval() > 0)
            {
                _timer = newTimer();
                _task = newTimerTask();
                _timer.schedule(_task, 1010L * getScanInterval(), 1010L * getScanInterval());
            }
        }
    }

    /**
     * Stop the scanning.
     */
    @Override
    public synchronized void doStop()
    {
        if (_running)
        {
            _running = false;
            if (_timer != null)
                _timer.cancel();
            if (_task != null)
                _task.cancel();
            _task = null;
            _timer = null;
        }
    }
    
    /**
     * Clear the list of scannables. The scanner must first
     * be in the stopped state.
     */
    public void reset()
    {
        if (!isStopped())
            throw new IllegalStateException("Not stopped");
        
        //clear the scannables
        _scannables.clear();
        
        //clear the previous scans
        _currentScan.clear();
        _prevScan.clear();
    }

    /**
     * @param path tests if the path exists
     * @return true if the path exists in one of the scandirs
     */
    public boolean exists(String path)
    {
        for (Path p : _scannables.keySet())
        {
            if (p.resolve(path).toFile().exists())
                return true;
        }
        return false;
    }

    /**
     * Perform a pass of the scanner and report changes
     */
    public synchronized void scan()
    {
        reportScanStart(++_scanCount);
        scanFiles();
        reportDifferences(_currentScan, _prevScan);
        _prevScan.clear();
        _prevScan.putAll(_currentScan);
        reportScanEnd(_scanCount);

        for (Listener l : _listeners)
        {
            try
            {
                if (l instanceof ScanListener)
                    ((ScanListener)l).scan();
            }
            catch (Throwable e)
            {
                LOG.warn(e);
            }
        }
    }

    /**
     * Scan all of the given paths.
     */
    public synchronized void scanFiles()
    {
        _currentScan.clear();
        for (Entry<Path, IncludeExcludeSet<PathMatcher, Path>> entry : _scannables.entrySet())
        {
            Path p = entry.getKey();
            try
            {
                Files.walkFileTree(p, EnumSet.allOf(FileVisitOption.class), _scanDepth, new Visitor(p, entry.getValue(), _currentScan));
            }
            catch (IOException e)
            {
                LOG.warn("Error scanning files.", e);
            }
        }
    }

    /**
     * Report the adds/changes/removes to the registered listeners
     *
     * @param currentScan the info from the most recent pass
     * @param oldScan info from the previous pass
     */
    private synchronized void reportDifferences(Map<String, TimeNSize> currentScan, Map<String, TimeNSize> oldScan)
    {
        // scan the differences and add what was found to the map of notifications:
        Set<String> oldScanKeys = new HashSet<>(oldScan.keySet());

        // Look for new and changed files
        for (Map.Entry<String, TimeNSize> entry : currentScan.entrySet())
        {
            String file = entry.getKey();
            if (!oldScanKeys.contains(file))
            {
                Notification old = _notifications.put(file, Notification.ADDED);
                if (old != null)
                {
                    switch (old)
                    {
                        case REMOVED:
                        case CHANGED:
                            _notifications.put(file, Notification.CHANGED);
                    }
                }
            }
            else if (!oldScan.get(file).equals(currentScan.get(file)))
            {
                Notification old = _notifications.put(file, Notification.CHANGED);
                if (old == Notification.ADDED)
                    _notifications.put(file, Notification.ADDED);
            }
        }

        // Look for deleted files
        for (String file : oldScan.keySet())
        {
            if (!currentScan.containsKey(file))
            {
                Notification old = _notifications.put(file, Notification.REMOVED);
                if (old == Notification.ADDED)
                    _notifications.remove(file);
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("scanned " + _scannables.keySet() + ": " + _notifications);

        // Process notifications
        // Only process notifications that are for stable files (ie same in old and current scan).
        List<String> bulkChanges = new ArrayList<>();
        for (Iterator<Entry<String, Notification>> iter = _notifications.entrySet().iterator(); iter.hasNext(); )
        {

            Entry<String, Notification> entry = iter.next();
            String file = entry.getKey();
            // Is the file stable?
            if (oldScan.containsKey(file))
            {
                if (!oldScan.get(file).equals(currentScan.get(file)))
                    continue;
            }
            else if (currentScan.containsKey(file))
                continue;

            // File is stable so notify
            Notification notification = entry.getValue();
            iter.remove();
            bulkChanges.add(file);
            switch (notification)
            {
                case ADDED:
                    reportAddition(file);
                    break;
                case CHANGED:
                    reportChange(file);
                    break;
                case REMOVED:
                    reportRemoval(file);
                    break;
            }
        }
        if (!bulkChanges.isEmpty())
            reportBulkChanges(bulkChanges);
    }

    private void warn(Object listener, String filename, Throwable th)
    {
        LOG.warn(listener + " failed on '" + filename, th);
    }

    /**
     * Report a file addition to the registered FileAddedListeners
     *
     * @param filename the filename
     */
    private void reportAddition(String filename)
    {
        for (Listener l : _listeners)
        {
            try
            {
                if (l instanceof DiscreteListener)
                    ((DiscreteListener)l).fileAdded(filename);
            }
            catch (Throwable e)
            {
                warn(l, filename, e);
            }
        }
    }

    /**
     * Report a file removal to the FileRemovedListeners
     *
     * @param filename the filename
     */
    private void reportRemoval(String filename)
    {
        for (Object l : _listeners)
        {
            try
            {
                if (l instanceof DiscreteListener)
                    ((DiscreteListener)l).fileRemoved(filename);
            }
            catch (Throwable e)
            {
                warn(l, filename, e);
            }
        }
    }

    /**
     * Report a file change to the FileChangedListeners
     *
     * @param filename the filename
     */
    private void reportChange(String filename)
    {
        for (Listener l : _listeners)
        {
            try
            {
                if (l instanceof DiscreteListener)
                    ((DiscreteListener)l).fileChanged(filename);
            }
            catch (Throwable e)
            {
                warn(l, filename, e);
            }
        }
    }

    /**
     * Report the list of filenames for which changes were detected.
     * 
     * @param filenames names of all files added/changed/removed
     */
    private void reportBulkChanges(List<String> filenames)
    {
        for (Listener l : _listeners)
        {
            try
            {
                if (l instanceof BulkListener)
                    ((BulkListener)l).filesChanged(filenames);
            }
            catch (Throwable e)
            {
                warn(l, filenames.toString(), e);
            }
        }
    }

    /**
     * Call ScanCycleListeners with start of scan
     */
    private void reportScanStart(int cycle)
    {
        for (Listener listener : _listeners)
        {
            try
            {
                if (listener instanceof ScanCycleListener)
                {
                    ((ScanCycleListener)listener).scanStarted(cycle);
                }
            }
            catch (Exception e)
            {
                LOG.warn(listener + " failed on scan start for cycle " + cycle, e);
            }
        }
    }

    /**
     * Call ScanCycleListeners with end of scan.
     */
    private void reportScanEnd(int cycle)
    {
        for (Listener listener : _listeners)
        {
            try
            {
                if (listener instanceof ScanCycleListener)
                {
                    ((ScanCycleListener)listener).scanEnded(cycle);
                }
            }
            catch (Exception e)
            {
                LOG.warn(listener + " failed on scan end for cycle " + cycle, e);
            }
        }
    }
}
