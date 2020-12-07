//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     */
    public static final int DEFAULT_SCAN_DEPTH = 1;
    public static final int MAX_SCAN_DEPTH = Integer.MAX_VALUE;
    private static final Logger LOG = LoggerFactory.getLogger(Scanner.class);
    private static final AtomicInteger SCANNER_IDS = new AtomicInteger();

    private int _scanInterval;
    private final AtomicInteger _scanCount = new AtomicInteger(0);
    private final List<Listener> _listeners = new CopyOnWriteArrayList<>();
    private Map<String, MetaData> _prevScan;
    private FilenameFilter _filter;
    private final Map<Path, IncludeExcludeSet<PathMatcher, Path>> _scannables = new ConcurrentHashMap<>();
    private boolean _reportExisting = true;
    private boolean _reportDirs = true;
    private Scheduler.Task _task;
    private Scheduler _scheduler;
    private int _scanDepth = DEFAULT_SCAN_DEPTH;

    private enum Status
    {
        ADDED, CHANGED, REMOVED, STABLE
    }
    
    enum Notification
    {
        ADDED, CHANGED, REMOVED
    }
    
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
     * MetaData
     * 
     * Metadata about a file: Last modified time, file size and
     * last file status (ADDED, CHANGED, DELETED, STABLE)
     */
    private static class MetaData
    {
        final long _lastModified;
        final long _size;
        Status _status;

        public MetaData(long lastModified, long size)
        {
            _lastModified = lastModified;
            _size = size;
        }

        public boolean isModified(MetaData m)
        {
            return m._lastModified != _lastModified || m._size != _size;
        }

        @Override
        public String toString()
        {
            return "[lm=" + _lastModified + ",sz=" + _size + ",s=" + _status + "]";
        }
    }
    
    private class ScanTask implements Runnable
    {
        @Override
        public void run()
        {
            scan();
            schedule();
        }
    }

    /**
     * Visitor
     *
     * A FileVisitor for walking a subtree of paths. The Scanner uses
     * this to examine the dirs and files it has been asked to scan.
     */
    private class Visitor implements FileVisitor<Path>
    {
        Map<String, MetaData> scanInfoMap;
        IncludeExcludeSet<PathMatcher,Path> rootIncludesExcludes;
        Path root;
        
        public Visitor(Path root, IncludeExcludeSet<PathMatcher,Path> rootIncludesExcludes, Map<String, MetaData> scanInfoMap)
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
                    accepted = rootIncludesExcludes.test(dir);
                }
                else
                {
                    if (_filter == null || _filter.accept(f.getParentFile(), f.getName()))
                        accepted = true;
                }

                if (accepted)
                {
                    scanInfoMap.put(f.getCanonicalPath(), new MetaData(f.lastModified(), f.isDirectory() ? 0 : f.length()));
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
                    accepted = rootIncludesExcludes.test(file);
                }
                else if (_filter == null || _filter.accept(f.getParentFile(), f.getName()))
                    accepted = true;
            }

            if (accepted)
            {
                scanInfoMap.put(f.getCanonicalPath(), new MetaData(f.lastModified(), f.isDirectory() ? 0 : f.length()));
                if (LOG.isDebugEnabled()) LOG.debug("scan accepted {} mod={}", f, f.lastModified());
            }
            
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException
        {
            LOG.warn("FileVisit failed: {}", file, exc);
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

    /**
     * Notification of exact file changes in the last scan.
     */
    public interface DiscreteListener extends Listener
    {
        public void fileChanged(String filename) throws Exception;

        public void fileAdded(String filename) throws Exception;

        public void fileRemoved(String filename) throws Exception;
    }

    /**
     * Notification of files that changed in the last scan.
     */
    public interface BulkListener extends Listener
    {
        public void filesChanged(Set<String> filenames) throws Exception;
    }

    /**
     * Listener that notifies when a scan has started and when it has ended.
     */
    public interface ScanCycleListener extends Listener
    {
        public default void scanStarted(int cycle) throws Exception
        {
        }

        public default void scanEnded(int cycle) throws Exception
        {
        }
    }

    public Scanner()
    {
    }

    /**
     * Get the scan interval
     *
     * @return interval between scans in seconds
     */
    public int getScanInterval()
    {
        return _scanInterval;
    }

    /**
     * Set the scan interval
     *
     * @param scanInterval pause between scans in seconds, or 0 for no scan after the initial scan.
     */
    public void setScanInterval(int scanInterval)
    {
        if (isRunning())
            throw new IllegalStateException("Scanner started");
        
        _scanInterval = scanInterval;
    }

    public void setScanDirs(List<File> dirs)
    {
        if (isRunning())
            throw new IllegalStateException("Scanner started");

        _scannables.clear();
        if (dirs == null)
            return;
        for (File f :dirs)
        {
            if (f.isDirectory())
                addDirectory(f.toPath());
            else
                addFile(f.toPath());
        }
    }

    /**
     * Add a file to be scanned. The file must not be null, and must exist.
     * 
     * @param p the Path of the file to scan.
     */
    public void addFile(Path p)
    {
        if (isRunning())
            throw new IllegalStateException("Scanner started");
        
        if (p == null)
            throw new IllegalStateException("Null path");

        if (!Files.exists(p) || Files.isDirectory(p))
            throw new IllegalStateException("Not file or doesn't exist: " + p);
        try
        {
            _scannables.putIfAbsent(p.toRealPath(), new IncludeExcludeSet<>(PathMatcherSet.class));
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Add a directory to be scanned. The directory must not be null and must exist.
     * 
     * @param p the directory to scan.
     * @return an IncludeExcludeSet to which the caller can add PathMatcher patterns to match
     */
    public IncludeExcludeSet<PathMatcher, Path> addDirectory(Path p)
    {
        if (isRunning())
            throw new IllegalStateException("Scanner started");
        
        if (p == null)
            throw new IllegalStateException("Null path");

        if (!Files.exists(p) || !Files.isDirectory(p))
            throw new IllegalStateException("Not directory or doesn't exist: " + p);

        try
        {
            IncludeExcludeSet<PathMatcher, Path> includesExcludes = new IncludeExcludeSet<>(PathMatcherSet.class);
            IncludeExcludeSet<PathMatcher, Path> prev = _scannables.putIfAbsent(p.toRealPath(), includesExcludes);
            if (prev != null)
                includesExcludes = prev;
            return includesExcludes;
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
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

    public Set<Path> getScannables()
    {
        return Collections.unmodifiableSet(_scannables.keySet());
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
        if (isRunning())
            throw new IllegalStateException("Scanner started");
        
        _scanDepth = scanDepth;
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
        if (isRunning())
            throw new IllegalStateException("Scanner started");
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
        if (isRunning())
            throw new IllegalStateException("Scanner started");
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
    public void addListener(Listener listener)
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
    public void removeListener(Listener listener)
    {
        if (listener == null)
            return;
        _listeners.remove(listener);
    }

    /**
     * Start the scanning action.
     */
    @Override
    public void doStart() throws Exception
    {
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
            _prevScan = scanFiles();
        }
        
        
        //Create the scheduler and start it
        _scheduler = new ScheduledExecutorScheduler("Scanner-" + SCANNER_IDS.getAndIncrement(), true, 1);
        _scheduler.start();
        
        //schedule the scan
        schedule();
    }

    private void schedule()
    {
        if (isRunning() && getScanInterval() > 0)
            _task = _scheduler.schedule(new ScanTask(), 1010L * getScanInterval(), TimeUnit.MILLISECONDS);
    }

    /**
     * Stop the scanning.
     */
    @Override
    public void doStop() throws Exception
    {
        Scheduler.Task task = _task;
        _task = null;
        if (task != null)
            task.cancel();
        Scheduler scheduler = _scheduler;
        _scheduler = null;
        if (scheduler != null)
            scheduler.stop();
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
        _prevScan = null;
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
     * Hint to the scanner to perform a scan cycle as soon as possible. 
     * NOTE that the scan is not guaranteed to have happened by the
     * time this method returns.
     */
    public void nudge()
    {
        if (!isRunning())
            throw new IllegalStateException("Scanner not running");
        scan(Callback.NOOP);
    }
    
    /**
     * Get the scanner to perform a scan cycle as soon as possible
     * and call the Callback when the scan is finished or failed.
     * 
     * @param complete called when the scan cycle finishes or fails.
     */
    public void scan(Callback complete)
    {
        Scheduler scheduler = _scheduler;
        
        if (!isRunning() || scheduler == null)
        {
            complete.failed(new IllegalStateException("Scanner not running"));
            return;
        }

        scheduler.schedule(() ->
        {
            try
            {
                scan();
                complete.succeeded();
            }
            catch (Throwable t)
            {
                complete.failed(t);
            }
        }, 0, TimeUnit.MILLISECONDS);
    }

    /**
     * Perform a pass of the scanner and report changes
     */
    void scan()
    {
        int cycle = _scanCount.incrementAndGet();
        reportScanStart(cycle);
        Map<String, MetaData> currentScan = scanFiles();
        reportDifferences(currentScan, _prevScan == null ? Collections.emptyMap() : Collections.unmodifiableMap(_prevScan));
        _prevScan = currentScan;
        reportScanEnd(cycle);
    }

    /**
     * Scan all of the given paths.
     */
    private Map<String, MetaData> scanFiles()
    {
        Map<String, MetaData> currentScan = new HashMap<>();
        for (Path p : _scannables.keySet())
        {
            try
            {
                Files.walkFileTree(p, EnumSet.allOf(FileVisitOption.class),_scanDepth, new Visitor(p, _scannables.get(p), currentScan));
            }
            catch (IOException e)
            {
                LOG.warn("Error scanning files.", e);
            }
        }
        return currentScan;
    }

    /**
     * Report the adds/changes/removes to the registered listeners
     * 
     * Only report an add or change once a file has stablilized in size.
     *
     * @param currentScan the info from the most recent pass
     * @param oldScan info from the previous pass
     */
    private void reportDifferences(Map<String, MetaData> currentScan, Map<String, MetaData> oldScan)
    {
        Map<String, Notification> changes = new HashMap<>();

        //Handle deleted files
        Set<String> oldScanKeys = new HashSet<>(oldScan.keySet());        
        oldScanKeys.removeAll(currentScan.keySet());
        for (String file : oldScanKeys)
        {
            changes.put(file, Notification.REMOVED);
        }

        // Handle new and changed files
        for (String file : currentScan.keySet())
        {
            MetaData current = currentScan.get(file);
            MetaData previous = oldScan.get(file);

            if (previous == null)
            {
                //New file - don't immediately
                //notify this, wait until the size has
                //settled down then notify the add.
                current._status = Status.ADDED;
            }
            else if (current.isModified(previous))
            {
                //Changed file - handle case where file
                //that was added on previous scan has since
                //been modified. We need to retain status
                //as added, so we send the ADDED event once
                //the file has settled down.
                if (previous._status == Status.ADDED)
                    current._status = Status.ADDED;
                else
                    current._status = Status.CHANGED;
            }
            else
            {
                //Unchanged file: if it was previously
                //ADDED, we can now send the ADDED event.
                if (previous._status == Status.ADDED)
                    changes.put(file,  Notification.ADDED);
                else if (previous._status == Status.CHANGED)
                    changes.put(file, Notification.CHANGED);

                current._status = Status.STABLE;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("scanned {}", _scannables.keySet());

        //Call the DiscreteListeners 
        for (Map.Entry<String, Notification> entry : changes.entrySet())
        {
            switch (entry.getValue())
            {
                case ADDED:
                    reportAddition(entry.getKey());
                    break;
                case CHANGED:
                    reportChange(entry.getKey());
                    break;
                case REMOVED:
                    reportRemoval(entry.getKey());
                    break;
                default:
                    LOG.warn("Unknown file change: {}", entry.getValue());
                    break;
            }
        }
        //Call the BulkListeners
        reportBulkChanges(changes.keySet());
    }

    private void warn(Object listener, String filename, Throwable th)
    {
        LOG.warn("{} failed on '{}'", listener, filename, th);
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
        if (filename == null)
            return;
        
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
    private void reportBulkChanges(Set<String> filenames)
    {
        if (filenames == null || filenames.isEmpty())
            return;
        
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
     * 
     * @param cycle scan count
     */
    private void reportScanStart(int cycle)
    {
        for (Listener listener : _listeners)
        {
            try
            {
                if (listener instanceof ScanCycleListener)
                    ((ScanCycleListener)listener).scanStarted(cycle);
            }
            catch (Exception e)
            {
                LOG.warn("{} failed on scan start for cycle {}", listener, cycle, e);
            }
        }
    }

    /**
     * Call ScanCycleListeners with end of scan.
     * 
     * @param cycle scan count
     */
    private void reportScanEnd(int cycle)
    {
        for (Listener listener : _listeners)
        {
            try
            {
                if (listener instanceof ScanCycleListener)
                    ((ScanCycleListener)listener).scanEnded(cycle);

            }
            catch (Exception e)
            {
                LOG.warn("{} failed on scan end for cycle {}", listener, cycle, e);
            }
        }
    }
}
