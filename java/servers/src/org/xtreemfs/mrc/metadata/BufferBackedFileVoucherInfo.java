/*
 * Copyright (c) 2015 by Robert Bärhold, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.mrc.metadata;

import java.nio.ByteBuffer;

import org.xtreemfs.mrc.database.babudb.BabuDBStorageHelper;

/**
 * Contains all general information regarding the vouchers of a file.
 */
public class BufferBackedFileVoucherInfo extends BufferBackedIndexMetadata implements FileVoucherInfo {

    private final static int valLength   = Integer.SIZE / Byte.SIZE + 2 * Long.SIZE / Byte.SIZE;
    private final static int fsOffset    = Integer.SIZE / Byte.SIZE;
    private final static int bsOffset    = fsOffset + Long.SIZE / Byte.SIZE;

    private int              clientCount = 0;
    private long             filesize    = 0;
    private long             blockedSpace;

    /**
     * @param key
     * @param val
     *            contains 3 basic information: current client count, filesize at start, current blocked space
     */
    public BufferBackedFileVoucherInfo(byte[] key, byte[] val) {

        super(key, 0, key.length, val, 0, val.length);

        ByteBuffer tmp = ByteBuffer.wrap(val);
        clientCount = tmp.getInt(0);
        filesize = tmp.getLong(fsOffset);
        blockedSpace = tmp.getLong(bsOffset);
    }

    public BufferBackedFileVoucherInfo(long fileId, long filesize, long blockedSpace) {

        super(null, 0, 0, null, 0, 0);

        this.clientCount = 1;
        this.filesize = filesize;
        this.blockedSpace = blockedSpace;

        keyBuf = BabuDBStorageHelper.createFileVoucherInfoKey(fileId);
        keyLen = keyBuf.length;

        updateValueBuffer();
    }

    @Override
    public void increaseClientCount() {
        clientCount++;
    }

    @Override
    public void decreaseClientCount() {
        clientCount--;
    }

    @Override
    public void increaseBlockedSpaceByValue(long additionalBlockedSpace) {
        blockedSpace += additionalBlockedSpace;
    }

    /**
     * Updates the value buffer with the values of the current object.
     */
    private void updateValueBuffer() {

        if (clientCount == 0) {
            valLen = 0;
            valBuf = null;
        } else {
            valLen = valLength;
            valBuf = new byte[valLength];

            ByteBuffer tmp = ByteBuffer.wrap(valBuf);
            tmp.putInt(clientCount);
            tmp.putLong(fsOffset, filesize);
            tmp.putLong(bsOffset, blockedSpace);
        }
    }

    // Getter

    /**
     * Returns the value buffer after refreshing it with the current values.
     */
    @Override
    public byte[] getValBuf() {
        updateValueBuffer();

        return super.getValBuf();
    }

    @Override
    public int getClientCount() {
        return clientCount;
    }

    @Override
    public long getFilesize() {
        return filesize;
    }

    @Override
    public long getBlockedSpace() {
        return blockedSpace;
    }
}
