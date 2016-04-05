/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.carbondata.core.locks;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Collections;
import java.util.List;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.carbondata.common.logging.LogService;
import org.carbondata.common.logging.LogServiceFactory;
import org.carbondata.core.constants.CarbonCommonConstants;
import org.carbondata.core.datastorage.store.impl.FileFactory;
import org.carbondata.core.datastorage.store.impl.FileFactory.FileType;
import org.carbondata.core.util.CarbonCoreLogEvent;
import org.carbondata.core.util.CarbonProperties;

/**
 * Class Provides generic implementation of the lock in Carbon.
 */
public abstract class CarbonLock {
    private static final LogService LOGGER =
            LogServiceFactory.getLogService(CarbonLock.class.getName());
    protected String location;
    private boolean isLocked;
    private DataOutputStream dataOutputStream;

    private boolean isLocal;

    private FileChannel channel;

    private FileOutputStream fileOutputStream;

    private FileLock fileLock;

    private int retryCount;

    private int retryTimeout;
    
    /**
     * isZookeeperEnabled to check if zookeeper feature is enabled or not for carbon.
     */
    private boolean isZookeeperEnabled;
    
    /**
     * zk is the zookeeper client instance
     */
    private static ZooKeeper zk;
    
    /**
     * zooKeeperLocation is the location in the zoo keeper file system where the locks will be maintained.
     */
    private static final String zooKeeperLocation = CarbonProperties.getInstance()
        .getProperty(CarbonCommonConstants.ZOOKEEPER_LOCATION) ;
    
    /**
     * lockName is the name of the lock to use. This name should be same for every process that want
     * to share the same lock
     */
    protected String lockName;
    
    /**
     * lockPath is the unique path created for the each instance of the carbon lock.
     */
    private String lockPath;
    
    // static block to create the zookeeper client.
    // here the zookeeper client will be created and also the znode will be created.
    
    static
    {
      // TO-DO Need to take this from the spark configuration.
      String zookeeperUrl = "127.0.0.1:2181" ;
      int sessionTimeOut = 100;
      try {
        zk = new ZooKeeper( zookeeperUrl , sessionTimeOut, new Watcher() {
          
          @Override
          public void process(WatchedEvent event) {
            if(event.getState().equals(KeeperState.SyncConnected))
            {
              LOGGER.info(CarbonCoreLogEvent.UNIBI_CARBONCORE_MSG, "zoo keeper client connected.");
            }
            
          }
        });
        
        // creating a znode in which all the znodes (lock files )are maintained.
        zk.create(zooKeeperLocation,new byte[1], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
      } 
      catch (IOException | KeeperException | InterruptedException e) 
      {
        LOGGER.error(CarbonCoreLogEvent.UNIBI_CARBONCORE_MSG, e.getMessage());
      }
    }
    

    protected void initRetry() {
        String retries = CarbonProperties.getInstance()
                .getProperty(CarbonCommonConstants.NUMBER_OF_TRIES_FOR_LOAD_METADATA_LOCK);
        try {
            retryCount = Integer.parseInt(retries);
        } catch (NumberFormatException e) {
            retryCount = CarbonCommonConstants.NUMBER_OF_TRIES_FOR_LOAD_METADATA_LOCK_DEFAULT;
        }

        String maxTimeout = CarbonProperties.getInstance()
                .getProperty(CarbonCommonConstants.MAX_TIMEOUT_FOR_LOAD_METADATA_LOCK);
        try {
            retryTimeout = Integer.parseInt(maxTimeout);
        } catch (NumberFormatException e) {
            retryTimeout = CarbonCommonConstants.MAX_TIMEOUT_FOR_LOAD_METADATA_LOCK_DEFAULT;
        }

    }
    
    /**
     * This method will set the zookeeper status whether zookeeper to be used for locking or not.
     */
    protected void updateZooKeeperLockingStatus()
    {
      isZookeeperEnabled = CarbonCommonConstants.ZOOKEEPER_ENABLE_DEFAULT;
      if( CarbonProperties.getInstance()
          .getProperty(CarbonCommonConstants.ZOOKEEPER_ENABLE_LOCK).equalsIgnoreCase("false"))
      {
        isZookeeperEnabled = false;
      }
      else if( CarbonProperties.getInstance()
          .getProperty(CarbonCommonConstants.ZOOKEEPER_ENABLE_LOCK).equalsIgnoreCase("true"))
      {
        isZookeeperEnabled = true;
      }
    
    }
    

    /**
     * This API will provide file based locking mechanism
     * In HDFS locking is handled using the hdfs append API which provide only one stream at a time.
     * In local file system locking is handled using the file channel.
     */
    private boolean lock() {

        if (FileFactory.getFileType(location) == FileType.LOCAL) {
            isLocal = true;
        }
        if(!isZookeeperEnabled)
        {
          try 
          {
            if (!FileFactory.isFileExist(location, FileFactory.getFileType(location))) 
            {
                FileFactory.createNewLockFile(location, FileFactory.getFileType(location));
            }
          }
          catch (IOException e) 
          {
            isLocked = false;
            return isLocked;
          }
        }
        if (isLocal) {
            localFileLocking();
        }
        else if(isZookeeperEnabled)
        {
           zookeeperLocking();
        }
        else
        {
            hdfsFileLocking();
        }
        return isLocked;
    }

    /**
     * Handling of the locking in HDFS file system.
     */
    private void hdfsFileLocking() {
        try {

            dataOutputStream = FileFactory
                    .getDataOutputStreamUsingAppend(location, FileFactory.getFileType(location));

            isLocked = true;

        } catch (IOException e) {
            isLocked = false;
        }
    }
    
    /**
     * Handling of the locking mechanism using zoo keeper.
     */
    private void zookeeperLocking()
    {
       try
       {
         // create the lock file with lockName.
           lockPath = zk.create(zooKeeperLocation + "/" + lockName, null, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL);
         
         // get the children present in zooKeeperLocation.
         List<String> nodes = zk.getChildren(zooKeeperLocation, null);
         
         // sort the children
         Collections.sort(nodes);
         
      // here the logic is , for each lock request zookeeper will create a file ending with
      // incremental digits.
      // so first request will be 00001 next is 00002 and so on.
      // if the current request is 00002 and already one previous request(00001) is present then get
      // children will give both nodes.
      // after the sort we are checking if the lock path is first or not .if it is first then lock has been acquired.
         
         if(lockPath.endsWith(nodes.get(0)))
         {
           isLocked = true;
           return;
         }
         else
         {
           // if locking failed then deleting the created lock as next time again new lock file will be
           // created.
           zk.delete(lockPath, -1);
           isLocked = false;
         }
       }
       catch(KeeperException e)
       {
         isLocked = false;
       }
       catch(InterruptedException e)
       {
         isLocked = false;
       }
    }

    /**
     * Handling of the locking in local file system using file channel.
     */
    private void localFileLocking() {
        try {

            fileOutputStream = new FileOutputStream(location);
            channel = fileOutputStream.getChannel();

            fileLock = channel.tryLock();
            if (null != fileLock) {
                isLocked = true;
            } else {
                isLocked = false;
            }

        } catch (IOException e) {
            isLocked = false;
        }
    }

    /**
     * This API will release the stream of locked file, Once operation in the metadata has been done
     *
     * @return
     */
    public boolean unlock() {
        boolean status = false;

        // if is locked then only we will unlock.
        if (isLock()) {

            if (isLocal) {
                status = localLockFileUnlock();
            } else if(isZookeeperEnabled)
            {
              status = zookeeperLockFileUnlock();
            }
            else
            {
                status = hdfslockFileUnlock();

            }
        }
        return status;
    }

    /**
     * 
     * @return status where lock file is unlocked or not.
     */
    private boolean zookeeperLockFileUnlock() 
    {
      try
      {
        // exists will return null if the path doesnt exists.
        if(null !=  zk.exists(lockPath, true))
          {
              zk.delete(lockPath, -1);
              lockPath = null;
          }
      }
      catch (KeeperException | InterruptedException e) 
      {
        LOGGER.error(CarbonCoreLogEvent.UNIBI_CARBONCORE_MSG, e.getMessage());
        return false;
      }
      return true;
    }

    /**
     * handling of the hdfs file unlocking.
     *
     * @param status
     * @return
     */
    private boolean hdfslockFileUnlock() {
        if (null != dataOutputStream) {
            try {
                dataOutputStream.close();
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * handling of the local file unlocking.
     *
     * @return
     */
    private boolean localLockFileUnlock() {
        boolean status;
        try {
            fileLock.release();
            status = true;
        } catch (IOException e) {
            status = false;
        } finally {
            if (null != fileOutputStream) {
                try {
                    fileOutputStream.close();
                } catch (IOException e) {
                    LOGGER.error(CarbonCoreLogEvent.UNIBI_CARBONCORE_MSG, e.getMessage());
                }
            }
        }
        return status;
    }

    /**
     * To check whether the file is locked or not.
     */
    public boolean isLock() {
        return isLocked;
    }

    /**
     * API for enabling the locking of file.
     */
    public boolean lockWithRetries() {
        try {
            for (int i = 0; i < retryCount; i++) {
                if (lock()) {
                    return true;
                } else {
                    Thread.sleep(retryTimeout * 1000L);
                }
            }
        } catch (InterruptedException e) {
            return false;
        }
        return false;
    }

}
