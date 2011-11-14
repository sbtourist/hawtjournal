/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.hawtjournal.api;

import org.fusesource.hawtjournal.api.Journal.WriteCommand;
import java.util.concurrent.locks.ReentrantLock;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.fusesource.hawtbuf.Buffer;
import org.fusesource.hawtjournal.util.IOHelper;
import static org.fusesource.hawtjournal.util.LogHelper.*;

/**
 * File reader/updater to randomly access data files, supporting concurrent thread-isolated reads and writes.
 * 
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 * @author Sergio Bossa
 */
class DataFileAccessor {

    private final ScheduledExecutorService disposer = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentMap<Thread, ConcurrentMap<Integer, RandomAccessFile>> perThreadDataFileRafs = new ConcurrentHashMap<Thread, ConcurrentMap<Integer, RandomAccessFile>>();
    private final ConcurrentMap<Thread, ConcurrentMap<Integer, Lock>> perThreadDataFileLocks = new ConcurrentHashMap<Thread, ConcurrentMap<Integer, Lock>>();
    private final ReadWriteLock compactionLock = new ReentrantReadWriteLock();
    private final Lock accessorLock = compactionLock.readLock();
    private final Lock compactorMutex = compactionLock.writeLock();
    //
    private final Journal journal;

    public DataFileAccessor(Journal journal) {
        this.journal = journal;
    }

    void updateLocation(Location location, byte type, boolean sync) throws IOException {
        Lock threadLock = getOrCreateLock(Thread.currentThread(), location.getDataFileId());
        accessorLock.lock();
        threadLock.lock();
        try {
            journal.sync();
            //
            RandomAccessFile raf = getOrCreateRaf(Thread.currentThread(), location.getDataFileId());
            if (seekToLocation(raf, location)) {
                raf.skipBytes(Journal.RECORD_POINTER_SIZE + Journal.RECORD_LENGTH_SIZE);
                raf.write(type);
                location.setType(type);
                if (sync) {
                    IOHelper.sync(raf.getFD());
                }
            } else {
                throw new IOException("Cannot find location: " + location);
            }
        } finally {
            threadLock.unlock();
            accessorLock.unlock();
        }
    }

    Buffer readLocation(Location location) throws IOException {
        WriteCommand asyncWrite = journal.getInflightWrites().get(location);
        Buffer result = null;
        if (asyncWrite != null) {
            result = asyncWrite.getData();
        } else {
            Lock threadLock = getOrCreateLock(Thread.currentThread(), location.getDataFileId());
            accessorLock.lock();
            threadLock.lock();
            try {
                RandomAccessFile raf = getOrCreateRaf(Thread.currentThread(), location.getDataFileId());
                if (seekToLocation(raf, location)) {
                    location.setPointer(raf.readInt());
                    location.setSize(raf.readInt());
                    location.setType(raf.readByte());
                    if (location.isBatchControlRecord()) {
                        int batchSize = raf.readInt();
                        byte[] data = new byte[Journal.CHECKSUM_SIZE + batchSize];
                        raf.readFully(data);
                        result = new Buffer(data, 0, data.length);
                    } else {
                        byte[] data = new byte[location.getSize() - Journal.HEADER_SIZE];
                        raf.readFully(data);
                        result = new Buffer(data, 0, data.length);
                    }
                } else {
                    throw new IOException("Cannot find location: " + location);
                }
            } catch (RuntimeException e) {
                throw new IOException("Invalid location: " + location + ", : " + e, e);
            } finally {
                threadLock.unlock();
                accessorLock.unlock();
            }
        }
        if (!location.isDeletedRecord()) {
            return result;
        } else {
            throw new IOException("Deleted location: " + location);
        }
    }

    boolean fillLocationDetails(Location location) throws IOException {
        WriteCommand asyncWrite = journal.getInflightWrites().get(location);
        if (asyncWrite != null) {
            location.setPointer(asyncWrite.getLocation().getPointer());
            location.setSize(asyncWrite.getLocation().getSize());
            location.setType(asyncWrite.getLocation().getType());
            return true;
        } else {
            Lock threadLock = getOrCreateLock(Thread.currentThread(), location.getDataFileId());
            accessorLock.lock();
            threadLock.lock();
            try {
                RandomAccessFile raf = getOrCreateRaf(Thread.currentThread(), location.getDataFileId());
                if (seekToLocation(raf, location)) {
                    location.setPointer(raf.readInt());
                    location.setSize(raf.readInt());
                    location.setType(raf.readByte());
                    if (location.getSize() > 0 && location.getType() != Location.NO_RECORD_TYPE) {
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
            } finally {
                threadLock.unlock();
                accessorLock.unlock();
            }
        }
    }

    boolean fillNextLocationDetails(Location start) throws IOException {
        Location asyncLocation = new Location(start.getDataFileId(), start.getPointer() + 1);
        WriteCommand asyncWrite = journal.getInflightWrites().get(asyncLocation);
        if (asyncWrite != null) {
            start.setPointer(asyncWrite.getLocation().getPointer());
            start.setSize(asyncWrite.getLocation().getSize());
            start.setType(asyncWrite.getLocation().getType());
            return true;
        } else {
            Lock threadLock = getOrCreateLock(Thread.currentThread(), start.getDataFileId());
            accessorLock.lock();
            threadLock.lock();
            try {
                RandomAccessFile raf = getOrCreateRaf(Thread.currentThread(), start.getDataFileId());
                if (seekToNextLocation(raf, start)) {
                    start.setPointer(raf.readInt());
                    start.setSize(raf.readInt());
                    start.setType(raf.readByte());
                    if (start.getSize() > 0 && start.getType() != Location.NO_RECORD_TYPE) {
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    return false;
                }
            } finally {
                threadLock.unlock();
                accessorLock.unlock();
            }
        }
    }

    void dispose(DataFile dataFile) {
        for (Entry<Thread, ConcurrentMap<Integer, RandomAccessFile>> threadRafs : perThreadDataFileRafs.entrySet()) {
            for (Entry<Integer, RandomAccessFile> raf : threadRafs.getValue().entrySet()) {
                if (raf.getKey().equals(dataFile.getDataFileId())) {
                    Lock lock = getOrCreateLock(threadRafs.getKey(), raf.getKey());
                    lock.lock();
                    try {
                        removeRaf(threadRafs.getKey(), raf.getKey());
                        return;
                    } catch (IOException ex) {
                        warn(ex, ex.getMessage());
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }
    }

    void open() {
        disposer.scheduleAtFixedRate(new ResourceDisposer(), journal.getDisposeInterval(), journal.getDisposeInterval(), TimeUnit.MILLISECONDS);
    }

    void close() {
        disposer.shutdown();
    }

    void pause() {
        compactorMutex.lock();
    }

    void resume() {
        compactorMutex.unlock();
    }

    private boolean seekToLocation(RandomAccessFile raf, Location destination) throws IOException {
        raf.seek(0);
        long position = raf.getFilePointer();
        if (raf.length() - position > Journal.HEADER_SIZE) {
            int pointer = raf.readInt();
            while (pointer != destination.getPointer()) {
                int length = raf.readInt();
                raf.skipBytes(length - Journal.RECORD_POINTER_SIZE - Journal.RECORD_LENGTH_SIZE);
                position = raf.getFilePointer();
                if (raf.length() - position > Journal.HEADER_SIZE) {
                    pointer = raf.readInt();
                } else {
                    return false;
                }
            }
            raf.seek(position);
            return true;
        } else {
            return false;
        }
    }

    /*private boolean seekToLocation(RandomAccessFile raf, Location destination) throws IOException {
    // First try the next file position:
    long position = raf.getFilePointer();
    if (raf.length() - position >= Journal.HEADER_SIZE) {
    int pointer = raf.readInt();
    int length = raf.readInt();
    int type = raf.readInt();
    if (pointer != destination.getPointer() || type != destination.getType()) {
    // Else seek from beginning:
    raf.seek(0);
    position = raf.getFilePointer();
    pointer = raf.readInt();
    while (pointer != destination.getPointer()) {
    length = raf.readInt();
    raf.skipBytes(length - Journal.RECORD_POINTER_SIZE - Journal.RECORD_LENGTH_SIZE);
    position = raf.getFilePointer();
    if (raf.length() - position >= Journal.HEADER_SIZE) {
    pointer = raf.readInt();
    } else {
    return false;
    }
    }
    } else {
    System.out.println("Found!");
    }
    } else {
    return false;
    }
    raf.seek(position);
    return true;
    }*/
    private boolean seekToNextLocation(RandomAccessFile raf, Location start) throws IOException {
        if (seekToLocation(raf, start)) {
            raf.skipBytes(Journal.RECORD_POINTER_SIZE);
            raf.skipBytes(raf.readInt() - Journal.RECORD_POINTER_SIZE - Journal.RECORD_LENGTH_SIZE);
            if (raf.getFilePointer() < raf.length()) {
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    private RandomAccessFile getOrCreateRaf(Thread thread, Integer file) throws IOException {
        ConcurrentMap<Integer, RandomAccessFile> rafs = perThreadDataFileRafs.get(thread);
        if (rafs == null) {
            rafs = new ConcurrentHashMap<Integer, RandomAccessFile>();
            perThreadDataFileRafs.put(thread, rafs);
        }
        RandomAccessFile raf = rafs.get(file);
        if (raf == null) {
            raf = journal.getDataFiles().get(file).openRandomAccessFile();
            rafs.put(file, raf);
        }
        return raf;
    }

    private void removeRaf(Thread thread, Integer file) throws IOException {
        RandomAccessFile raf = perThreadDataFileRafs.get(thread).remove(file);
        raf.close();
    }

    private Lock getOrCreateLock(Thread thread, Integer file) {
        ConcurrentMap<Integer, Lock> locks = perThreadDataFileLocks.get(thread);
        if (locks == null) {
            locks = new ConcurrentHashMap<Integer, Lock>();
            perThreadDataFileLocks.put(thread, locks);
        }
        Lock lock = locks.get(file);
        if (lock == null) {
            lock = new ReentrantLock();
            locks.put(file, lock);
        }
        return lock;
    }

    private class ResourceDisposer implements Runnable {

        public void run() {
            Set<Thread> deadThreads = new HashSet<Thread>();
            for (Entry<Thread, ConcurrentMap<Integer, RandomAccessFile>> threadRafs : perThreadDataFileRafs.entrySet()) {
                for (Entry<Integer, RandomAccessFile> raf : threadRafs.getValue().entrySet()) {
                    Lock lock = getOrCreateLock(threadRafs.getKey(), raf.getKey());
                    if (lock.tryLock()) {
                        try {
                            removeRaf(threadRafs.getKey(), raf.getKey());
                            if (!threadRafs.getKey().isAlive()) {
                                deadThreads.add(threadRafs.getKey());
                            }
                        } catch (IOException ex) {
                            warn(ex, ex.getMessage());
                        } finally {
                            lock.unlock();
                        }
                    }
                }
            }
            for (Thread deadThread : deadThreads) {
                perThreadDataFileRafs.remove(deadThread);
                perThreadDataFileLocks.remove(deadThread);
            }
        }

    }
}
