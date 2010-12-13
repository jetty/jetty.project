// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================


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
import java.util.Queue;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.eclipse.jetty.util.log.Log;


/**
 * Scanner
 * 
 * Utility for scanning a directory for added, removed and changed
 * files and reporting these events via registered Listeners.
 *
 * TODO AbstractLifeCycle
 */
public class Scanner
{
    private static int __scannerId=0;
    private int _scanInterval;
    private final List<Listener> _listeners = new ArrayList<Listener>();
    private final Map<String,Long> _prevScan = new HashMap<String,Long> ();
    private final Map<String,Long> _currentScan = new HashMap<String,Long> ();
    private FilenameFilter _filter;
    private final List<File> _scanDirs = new ArrayList<File>();
    private volatile boolean _running = false;
    private boolean _reportExisting = true;
    private boolean _reportDirs = true;
    private Timer _timer;
    private TimerTask _task;
    private int _scanDepth=0;


    /**
     * Listener
     * 
     * Marker for notifications re file changes.
     */
    public interface Listener
    {
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
    
    public List<File> getScanDirs ()
    {
        return Collections.unmodifiableList(_scanDirs);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param recursive True if scanning is recursive
     * @see  #setScanDepth()
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
    public synchronized void start ()
    {
        if (_running)
            return;

        _running = true;

        if (_reportExisting)
        {
            // if files exist at startup, report them
            scan();
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
                _timer.schedule(_task, 1000L*getScanInterval(),1000L*getScanInterval());
            }
        }
    }
    /**
     * Stop the scanning.
     */
    public synchronized void stop ()
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
        scanFiles();
        reportDifferences(_currentScan, _prevScan);
        _prevScan.clear();
        _prevScan.putAll(_currentScan);
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
                    Log.warn("Error scanning files.", e);
                }
        }
    }


    /**
     * Report the adds/changes/removes to the registered listeners
     * 
     * @param currentScan the info from the most recent pass
     * @param oldScan info from the previous pass
     */
    public void reportDifferences (Map<String,Long> currentScan, Map<String,Long> oldScan) 
    {
        List<String> bulkChanges = new ArrayList<String>();
        
        Set<String> oldScanKeys = new HashSet<String>(oldScan.keySet());
        Iterator<Entry<String, Long>> itor = currentScan.entrySet().iterator();
        while (itor.hasNext())
        {
            Map.Entry<String, Long> entry = itor.next();
            if (!oldScanKeys.contains(entry.getKey()))
            {
                Log.debug("File added: "+entry.getKey());
                reportAddition ((String)entry.getKey());
                bulkChanges.add(entry.getKey());
            }
            else if (!oldScan.get(entry.getKey()).equals(entry.getValue()))
            {
                Log.debug("File changed: "+entry.getKey());
                reportChange((String)entry.getKey());
                oldScanKeys.remove(entry.getKey());
                bulkChanges.add(entry.getKey());
            }
            else
                oldScanKeys.remove(entry.getKey());
        }

        if (!oldScanKeys.isEmpty())
        {

            Iterator<String> keyItor = oldScanKeys.iterator();
            while (keyItor.hasNext())
            {
                String filename = (String)keyItor.next();
                Log.debug("File removed: "+filename);
                reportRemoval(filename);
                bulkChanges.add(filename);
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
    private void scanFile (File f, Map<String,Long> scanInfoMap, int depth)
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
                    long lastModified = f.lastModified();
                    scanInfoMap.put(name, new Long(lastModified));
                }
            }
            
            // If it is a directory, scan if it is a known directory or the depth is OK.
            if (f.isDirectory() && (depth<_scanDepth || _scanDepth==-1 || _scanDirs.contains(f)))
            {
                File[] files = f.listFiles();
                for (int i=0;i<files.length;i++)
                    scanFile(files[i], scanInfoMap,depth+1);
            }
        }
        catch (IOException e)
        {
            Log.warn("Error scanning watched files", e);
        }
    }

    private void warn(Object listener,String filename,Throwable th)
    {
        Log.warn(th);
        Log.warn(listener+" failed on '"+filename);
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

}
