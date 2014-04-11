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


package org.eclipse.jetty.util;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/**
 * Scanner
 * 
 * Utility for scanning a directory for added, removed and changed
 * files and reporting these events via registered Listeners.
 *
 */
public class Scanner extends AbstractLifeCycle
{
    private static final Logger LOG = Log.getLogger(Scanner.class);
    private static int __scannerId=0;
    private int _scanInterval;
    private int _scanCount = 0;
    private final List<Listener> _listeners = new ArrayList<Listener>();
    private final Map<String,TimeNSize> _prevScan = new HashMap<String,TimeNSize> ();
    private final Map<String,TimeNSize> _currentScan = new HashMap<String,TimeNSize> ();
    private FilenameFilter _filter;
    private final List<File> _scanDirs = new ArrayList<File>();
    private volatile boolean _running = false;
    private boolean _reportExisting = true;
    private boolean _reportDirs = true;
    private Timer _timer;
    private TimerTask _task;
    private int _scanDepth=0;
    
    public enum Notification { ADDED, CHANGED, REMOVED };
    private final Map<String,Notification> _notifications = new HashMap<String,Notification>();

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
            return (int)_lastModified^(int)_size;
        }
        
        @Override
        public boolean equals(Object o)
        {
            if (o instanceof TimeNSize)
            {
                TimeNSize tns = (TimeNSize)o;
                return tns._lastModified==_lastModified && tns._size==_size;
            }
            return false;
        }
        
        @Override
        public String toString()
        {
            return "[lm="+_lastModified+",s="+_size+"]";
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
        public void scan();
    }
    
    public interface DiscreteListener extends Listener
    {
        public void fileChanged (String filename) throws Exception;
        public void fileAdded (String filename) throws Exception;
        public void fileRemoved (String filename) throws Exception;
    }
    
    
    public interface BulkListener extends Listener
    {
        public void filesChanged (List<String> filenames) throws Exception;
    }

    /**
     * Listener that notifies when a scan has started and when it has ended.
     */
    public interface ScanCycleListener extends Listener
    {
        public void scanStarted(int cycle) throws Exception;
        public void scanEnded(int cycle) throws Exception;
    }

    /**
     * 
     */
    public Scanner ()
    {       
    }

    /**
     * Get the scan interval
     * @return interval between scans in seconds
     */
    public int getScanInterval()
    {
        return _scanInterval;
    }

    /**
     * Set the scan interval
     * @param scanInterval pause between scans in seconds, or 0 for no scan after the initial scan.
     */
    public synchronized void setScanInterval(int scanInterval)
    {
        _scanInterval = scanInterval;
        schedule();
    }

    /**
     * Set the location of the directory to scan.
     * @param dir
     * @deprecated use setScanDirs(List dirs) instead
     */
    @Deprecated
    public void setScanDir (File dir)
    {
        _scanDirs.clear(); 
        _scanDirs.add(dir);
    }

    /**
     * Get the location of the directory to scan
     * @return the first directory (of {@link #getScanDirs()} being scanned)
     * @deprecated use getScanDirs() instead
     */
    @Deprecated
    public File getScanDir ()
    {
        return (_scanDirs==null?null:(File)_scanDirs.get(0));
    }

    public void setScanDirs (List<File> dirs)
    {
        _scanDirs.clear(); 
        _scanDirs.addAll(dirs);
    }
    
    public synchronized void addScanDir( File dir )
    {
        _scanDirs.add( dir );
    }
    
    public List<File> getScanDirs ()
    {
        return Collections.unmodifiableList(_scanDirs);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param recursive True if scanning is recursive
     * @see  #setScanDepth(int)
     */
    public void setRecursive (boolean recursive)
    {
        _scanDepth=recursive?-1:0;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return True if scanning is fully recursive (scandepth==-1)
     * @see #getScanDepth()
     */
    public boolean getRecursive ()
    {
        return _scanDepth==-1;
    }
    
    /* ------------------------------------------------------------ */
    /** Get the scanDepth.
     * @return the scanDepth
     */
    public int getScanDepth()
    {
        return _scanDepth;
    }

    /* ------------------------------------------------------------ */
    /** Set the scanDepth.
     * @param scanDepth the scanDepth to set
     */
    public void setScanDepth(int scanDepth)
    {
        _scanDepth = scanDepth;
    }

    /**
     * Apply a filter to files found in the scan directory.
     * Only files matching the filter will be reported as added/changed/removed.
     * @param filter
     */
    public void setFilenameFilter (FilenameFilter filter)
    {
        _filter = filter;
    }

    /**
     * Get any filter applied to files in the scan dir.
     * @return the filename filter
     */
    public FilenameFilter getFilenameFilter ()
    {
        return _filter;
    }

    /* ------------------------------------------------------------ */
    /**
     * Whether or not an initial scan will report all files as being
     * added.
     * @param reportExisting if true, all files found on initial scan will be 
     * reported as being added, otherwise not
     */
    public void setReportExistingFilesOnStartup (boolean reportExisting)
    {
        _reportExisting = reportExisting;
    }

    /* ------------------------------------------------------------ */
    public boolean getReportExistingFilesOnStartup()
    {
        return _reportExisting;
    }
    
    /* ------------------------------------------------------------ */
    /** Set if found directories should be reported.
     * @param dirs
     */
    public void setReportDirs(boolean dirs)
    {
        _reportDirs=dirs;
    }
    
    /* ------------------------------------------------------------ */
    public boolean getReportDirs()
    {
        return _reportDirs;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Add an added/removed/changed listener
     * @param listener
     */
    public synchronized void addListener (Listener listener)
    {
        if (listener == null)
            return;
        _listeners.add(listener);   
    }



    /**
     * Remove a registered listener
     * @param listener the Listener to be removed
     */
    public synchronized void removeListener (Listener listener)
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

    public TimerTask newTimerTask ()
    {
        return new TimerTask()
        {
            @Override
            public void run() { scan(); }
        };
    }

    public Timer newTimer ()
    {
        return new Timer("Scanner-"+__scannerId++, true);
    }
    
    public void schedule ()
    {  
        if (_running)
        {
            if (_timer!=null)
                _timer.cancel();
            if (_task!=null)
                _task.cancel();
            if (getScanInterval() > 0)
            {
                _timer = newTimer();
                _task = newTimerTask();
                _timer.schedule(_task, 1010L*getScanInterval(),1010L*getScanInterval());
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
            if (_timer!=null)
                _timer.cancel();
            if (_task!=null)
                _task.cancel();
            _task=null;
            _timer=null;
        }
    }

    /**
     * Perform a pass of the scanner and report changes
     */
    public synchronized void scan ()
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
            catch (Exception e)
            {
                LOG.warn(e);
            }
            catch (Error e)
            {
                LOG.warn(e);
            }
        }
    }

    /**
     * Recursively scan all files in the designated directories.
     */
    public synchronized void scanFiles ()
    {
        if (_scanDirs==null)
            return;
        
        _currentScan.clear();
        Iterator<File> itor = _scanDirs.iterator();
        while (itor.hasNext())
        {
            File dir = itor.next();
            
            if ((dir != null) && (dir.exists()))
                try
                {
                    scanFile(dir.getCanonicalFile(), _currentScan,0);
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
    public synchronized void reportDifferences (Map<String,TimeNSize> currentScan, Map<String,TimeNSize> oldScan) 
    {
        // scan the differences and add what was found to the map of notifications:

        Set<String> oldScanKeys = new HashSet<String>(oldScan.keySet());
        
        // Look for new and changed files
        for (Map.Entry<String, TimeNSize> entry: currentScan.entrySet())
        {
            String file = entry.getKey(); 
            if (!oldScanKeys.contains(file))
            {
                Notification old=_notifications.put(file,Notification.ADDED);
                if (old!=null)
                { 
                    switch(old)
                    {
                        case REMOVED: 
                        case CHANGED:
                            _notifications.put(file,Notification.CHANGED);
                    }
                }
            }
            else if (!oldScan.get(file).equals(currentScan.get(file)))
            {
                Notification old=_notifications.put(file,Notification.CHANGED);
                if (old!=null)
                {
                    switch(old)
                    {
                        case ADDED:
                            _notifications.put(file,Notification.ADDED);
                    }
                }
            }
        }
        
        // Look for deleted files
        for (String file : oldScan.keySet())
        {
            if (!currentScan.containsKey(file))
            {
                Notification old=_notifications.put(file,Notification.REMOVED);
                if (old!=null)
                {
                    switch(old)
                    {
                        case ADDED:
                            _notifications.remove(file);
                    }
                }
            }
        }
        
        if (LOG.isDebugEnabled())
            LOG.debug("scanned "+_scanDirs+": "+_notifications);
                
        // Process notifications
        // Only process notifications that are for stable files (ie same in old and current scan).
        List<String> bulkChanges = new ArrayList<String>();
        for (Iterator<Entry<String,Notification>> iter = _notifications.entrySet().iterator();iter.hasNext();)
        {
            Entry<String,Notification> entry=iter.next();
            String file=entry.getKey();
            
            // Is the file stable?
            if (oldScan.containsKey(file))
            {
                if (!oldScan.get(file).equals(currentScan.get(file)))
                    continue;
            }
            else if (currentScan.containsKey(file))
                continue;
                            
            // File is stable so notify
            Notification notification=entry.getValue();
            iter.remove();
            bulkChanges.add(file);
            switch(notification)
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


    /**
     * Get last modified time on a single file or recurse if
     * the file is a directory. 
     * @param f file or directory
     * @param scanInfoMap map of filenames to last modified times
     */
    private void scanFile (File f, Map<String,TimeNSize> scanInfoMap, int depth)
    {
        try
        {
            if (!f.exists())
                return;

            if (f.isFile() || depth>0&& _reportDirs && f.isDirectory())
            {
                if ((_filter == null) || ((_filter != null) && _filter.accept(f.getParentFile(), f.getName())))
                {
                    String name = f.getCanonicalPath();
                    scanInfoMap.put(name, new TimeNSize(f.lastModified(),f.length()));
                }
            }
            
            // If it is a directory, scan if it is a known directory or the depth is OK.
            if (f.isDirectory() && (depth<_scanDepth || _scanDepth==-1 || _scanDirs.contains(f)))
            {
                File[] files = f.listFiles();
                if (files != null)
                {
                    for (int i=0;i<files.length;i++)
                        scanFile(files[i], scanInfoMap,depth+1);
                }
                else
                    LOG.warn("Error listing files in directory {}", f);
                    
            }
        }
        catch (IOException e)
        {
            LOG.warn("Error scanning watched files", e);
        }
    }

    private void warn(Object listener,String filename,Throwable th)
    {
        LOG.warn(listener+" failed on '"+filename, th);
    }

    /**
     * Report a file addition to the registered FileAddedListeners
     * @param filename
     */
    private void reportAddition (String filename)
    {
        Iterator<Listener> itor = _listeners.iterator();
        while (itor.hasNext())
        {
            Listener l = itor.next();
            try
            {
                if (l instanceof DiscreteListener)
                    ((DiscreteListener)l).fileAdded(filename);
            }
            catch (Exception e)
            {
                warn(l,filename,e);
            }
            catch (Error e)
            {
                warn(l,filename,e);
            }
        }
    }


    /**
     * Report a file removal to the FileRemovedListeners
     * @param filename
     */
    private void reportRemoval (String filename)
    {
        Iterator<Listener> itor = _listeners.iterator();
        while (itor.hasNext())
        {
            Object l = itor.next();
            try
            {
                if (l instanceof DiscreteListener)
                    ((DiscreteListener)l).fileRemoved(filename);
            }
            catch (Exception e)
            {
                warn(l,filename,e);
            }
            catch (Error e)
            {
                warn(l,filename,e);
            }
        }
    }


    /**
     * Report a file change to the FileChangedListeners
     * @param filename
     */
    private void reportChange (String filename)
    {
        Iterator<Listener> itor = _listeners.iterator();
        while (itor.hasNext())
        {
            Listener l = itor.next();
            try
            {
                if (l instanceof DiscreteListener)
                    ((DiscreteListener)l).fileChanged(filename);
            }
            catch (Exception e)
            {
                warn(l,filename,e);
            }
            catch (Error e)
            {
                warn(l,filename,e);
            }
        }
    }
    
    private void reportBulkChanges (List<String> filenames)
    {
        Iterator<Listener> itor = _listeners.iterator();
        while (itor.hasNext())
        {
            Listener l = itor.next();
            try
            {
                if (l instanceof BulkListener)
                    ((BulkListener)l).filesChanged(filenames);
            }
            catch (Exception e)
            {
                warn(l,filenames.toString(),e);
            }
            catch (Error e)
            {
                warn(l,filenames.toString(),e);
            }
        }
    }
    
    /**
     * signal any scan cycle listeners that a scan has started
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
     * sign
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
