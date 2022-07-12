//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
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
import java.nio.file.LinkOption;
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
import java.util.stream.Collectors;

import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scanner
 *
 * Utility for scanning a directory for added, removed and changed
 * files and reporting these events via registered Listeners.
 * The scanner operates on the {@link Path#toRealPath(LinkOption...)} of the files scanned and
 * can be configured to follow symlinks.
 */
public class Scanner extends ContainerLifeCycle
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
    private Map<Path, MetaData> _prevScan;
    private FilenameFilter _filter;
    private final Map<Path, IncludeExcludeSet<PathMatcher, Path>> _scannables = new ConcurrentHashMap<>();
    private boolean _reportExisting = true;
    private boolean _reportDirs = true;
    private Scheduler.Task _task;
    private final Scheduler _scheduler;
    private int _scanDepth = DEFAULT_SCAN_DEPTH;
    private final LinkOption[] _linkOptions;

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
        Map<Path, MetaData> scanInfoMap;
        IncludeExcludeSet<PathMatcher, Path> rootIncludesExcludes;
        Path root;

        private Visitor(Path root, IncludeExcludeSet<PathMatcher, Path> rootIncludesExcludes, Map<Path, MetaData> scanInfoMap)
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

            dir = dir.toRealPath(_linkOptions);
            File f = dir.toFile();

            //if we want to report directories and we haven't already seen it
            if (_reportDirs && !scanInfoMap.containsKey(dir))
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
                    scanInfoMap.put(dir, new MetaData(f.lastModified(), f.isDirectory() ? 0 : f.length()));
                    if (LOG.isDebugEnabled()) LOG.debug("scan accepted dir {} mod={}", f, f.lastModified());
                }
            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException
        {
            path = path.toRealPath(_linkOptions);

            if (!Files.exists(path))
                return FileVisitResult.CONTINUE;

            File f = path.toFile();
            boolean accepted = false;

            if (f.isFile() || (f.isDirectory() && _reportDirs && !scanInfoMap.containsKey(path)))
            {
                if (rootIncludesExcludes != null && !rootIncludesExcludes.isEmpty())
                {
                    //accepted if not explicitly excluded and either is explicitly included or there are no explicit inclusions
                    accepted = rootIncludesExcludes.test(path);
                }
                else if (_filter == null || _filter.accept(f.getParentFile(), f.getName()))
                    accepted = true;
            }

            if (accepted)
            {
                scanInfoMap.put(path, new MetaData(f.lastModified(), f.isDirectory() ? 0 : f.length()));
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
        /**
         * Called when a file is changed.
         * Default implementation calls {@link #fileChanged(String)}.
         * @param path the {@link Path#toRealPath(LinkOption...)} of the changed file
         * @throws Exception May be thrown for handling errors
         */
        default void pathChanged(Path path) throws Exception
        {
            path.toString();
            fileChanged(path.toString());
        }

        /**
         * Called when a file is added.
         * Default implementation calls {@link #fileAdded(String)}.
         * @param path the {@link Path#toRealPath(LinkOption...)} of the added file
         * @throws Exception May be thrown for handling errors
         */
        default void pathAdded(Path path) throws Exception
        {
            fileAdded(path.toString());
        }

        /**
         * Called when a file is removed.
         * Default implementation calls {@link #fileRemoved(String)}.
         * @param path the {@link Path#toRealPath(LinkOption...)} of the removed file
         * @throws Exception May be thrown for handling errors
         */
        default void pathRemoved(Path path) throws Exception
        {
            fileRemoved(path.toString());
        }

        /**
         * Called when a file is changed.
         * May not be called if {@link #pathChanged(Path)} is overridden.
         * @param filename the {@link Path#toRealPath(LinkOption...)} as a string of the changed file
         * @throws Exception May be thrown for handling errors
         */
        default void fileChanged(String filename) throws Exception
        {
        }

        /**
         * Called when a file is added.
         * May not be called if {@link #pathAdded(Path)} is overridden.
         * @param filename the {@link Path#toRealPath(LinkOption...)} as a string of the added file
         * @throws Exception May be thrown for handling errors
         */
        default void fileAdded(String filename) throws Exception
        {
        }

        /**
         * Called when a file is removed.
         * May not be called if {@link #pathRemoved(Path)} is overridden.
         * @param filename the {@link Path#toRealPath(LinkOption...)} as a string of the removed file
         * @throws Exception May be thrown for handling errors
         */
        default void fileRemoved(String filename) throws Exception
        {
        }
    }

    /**
     * Notification of files that changed in the last scan.
     */
    public interface BulkListener extends Listener
    {
        default void pathsChanged(Set<Path> paths) throws Exception
        {
            filesChanged(paths.stream().map(Path::toString).collect(Collectors.toSet()));
        }

        void filesChanged(Set<String> filenames) throws Exception;
    }

    /**
     * Listener that notifies when a scan has started and when it has ended.
     */
    public interface ScanCycleListener extends Listener
    {
        default void scanStarted(int cycle) throws Exception
        {
        }

        default void scanEnded(int cycle) throws Exception
        {
        }
    }
    
    public Scanner()
    {
        this(null);
    }
    
    public Scanner(Scheduler scheduler)
    {
        this(scheduler, true);
    }

    /**
     * @param scheduler The scheduler to use for scanning.
     * @param reportRealPaths If true, the {@link Listener}s are called with the real path of scanned files.
     */
    public Scanner(Scheduler scheduler, boolean reportRealPaths)
    {
        //Create the scheduler and start it
        _scheduler = scheduler == null ? new ScheduledExecutorScheduler("Scanner-" + SCANNER_IDS.getAndIncrement(), true, 1) : scheduler;
        addBean(_scheduler);
        _linkOptions = reportRealPaths ? new LinkOption[0] : new LinkOption[] {LinkOption.NOFOLLOW_LINKS};
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

    public void setScanDirs(List<Path> dirs)
    {
        if (isRunning())
            throw new IllegalStateException("Scanner started");

        _scannables.clear();
        if (dirs == null)
            return;
        for (Path p :dirs)
        {
            if (Files.isDirectory(p))
                addDirectory(p);
            else
                addFile(p);
        }
    }

    /**
     * Add a file to be scanned. The file must not be null, and must exist.
     *
     * @param path the Path of the file to scan.
     */
    public void addFile(Path path)
    {
        if (isRunning())
            throw new IllegalStateException("Scanner started");

        if (path == null)
            throw new IllegalStateException("Null path");

        try
        {
            // Always follow links when check ultimate type of the path
            Path real = path.toRealPath();
            if (!Files.exists(real) || Files.isDirectory(real))
                throw new IllegalStateException("Not file or doesn't exist: " + path);

            _scannables.putIfAbsent(real, new IncludeExcludeSet<>(PathMatcherSet.class));
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

        try
        {
            // Check status of the real path
            Path real = p.toRealPath();
            if (!Files.exists(real) || !Files.isDirectory(real))
                throw new IllegalStateException("Not directory or doesn't exist: " + p);

            IncludeExcludeSet<PathMatcher, Path> includesExcludes = new IncludeExcludeSet<>(PathMatcherSet.class);
            IncludeExcludeSet<PathMatcher, Path> prev = _scannables.putIfAbsent(real, includesExcludes);
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

        super.doStart();

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
        Map<Path, MetaData> currentScan = scanFiles();
        reportDifferences(currentScan, _prevScan == null ? Collections.emptyMap() : Collections.unmodifiableMap(_prevScan));
        _prevScan = currentScan;
        reportScanEnd(cycle);
    }

    /**
     * Scan all of the given paths.
     */
    private Map<Path, MetaData> scanFiles()
    {
        Map<Path, MetaData> currentScan = new HashMap<>();
        for (Map.Entry<Path, IncludeExcludeSet<PathMatcher, Path>> entry : _scannables.entrySet())
        {
            try
            {
                Files.walkFileTree(entry.getKey(), EnumSet.allOf(FileVisitOption.class), _scanDepth,
                                   new Visitor(entry.getKey(), entry.getValue(), currentScan));
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
    private void reportDifferences(Map<Path, MetaData> currentScan, Map<Path, MetaData> oldScan)
    {
        Map<Path, Notification> changes = new HashMap<>();

        //Handle deleted files
        Set<Path> oldScanKeys = new HashSet<>(oldScan.keySet());
        oldScanKeys.removeAll(currentScan.keySet());
        for (Path path : oldScanKeys)
        {
            changes.put(path, Notification.REMOVED);
        }

        // Handle new and changed files
        for (Map.Entry<Path, MetaData> entry : currentScan.entrySet())
        {
            MetaData current = entry.getValue();
            MetaData previous = oldScan.get(entry.getKey());

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
                    changes.put(entry.getKey(),  Notification.ADDED);
                else if (previous._status == Status.CHANGED)
                    changes.put(entry.getKey(), Notification.CHANGED);

                current._status = Status.STABLE;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("scanned {}", _scannables.keySet());

        //Call the DiscreteListeners
        for (Map.Entry<Path, Notification> entry : changes.entrySet())
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

    private void warn(Object listener, Path path, Throwable th)
    {
        LOG.warn("{} failed on '{}'", listener, path, th);
    }

    /**
     * Report a file addition to the registered FileAddedListeners
     *
     * @param path the path
     */
    private void reportAddition(Path path)
    {
        for (Listener l : _listeners)
        {
            try
            {
                if (l instanceof DiscreteListener)
                    ((DiscreteListener)l).pathAdded(path);
            }
            catch (Throwable e)
            {
                warn(l, path, e);
            }
        }
    }

    /**
     * Report a file removal to the FileRemovedListeners
     *
     * @param path the path of the removed filename
     */
    private void reportRemoval(Path path)
    {
        for (Object l : _listeners)
        {
            try
            {
                if (l instanceof DiscreteListener)
                    ((DiscreteListener)l).pathRemoved(path);
            }
            catch (Throwable e)
            {
                warn(l, path, e);
            }
        }
    }

    /**
     * Report a file change to the FileChangedListeners
     *
     * @param path the path of the changed file
     */
    private void reportChange(Path path)
    {
        if (path == null)
            return;

        for (Listener l : _listeners)
        {
            try
            {
                if (l instanceof DiscreteListener)
                    ((DiscreteListener)l).pathChanged(path);
            }
            catch (Throwable e)
            {
                warn(l, path, e);
            }
        }
    }

    /**
     * Report the list of filenames for which changes were detected.
     *
     * @param paths The paths of all files added/changed/removed
     */
    private void reportBulkChanges(Set<Path> paths)
    {
        if (paths == null || paths.isEmpty())
            return;

        for (Listener l : _listeners)
        {
            try
            {
                if (l instanceof BulkListener)
                    ((BulkListener)l).pathsChanged(paths);
            }
            catch (Throwable e)
            {
                LOG.warn("{} failed on '{}'", l, paths, e);
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
