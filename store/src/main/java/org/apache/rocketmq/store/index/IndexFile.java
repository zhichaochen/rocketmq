/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.List;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.store.MappedFile;

/**
 * 表示一个索引文件
 * 其中：
 *      1、索引文件使用当前时间戳作为文件名称。
 *      2、什么是hash槽？？？？
 *          本质来说是，一个key进行hash之后，hashkey % hashSlotNum.
 *          算出来，该索引存放的那一块位置。
 */
public class IndexFile {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    // 每个 hash  槽所占的字节数
    private static int hashSlotSize = 4;
    // 一条index的大小（默认一条索引占用20个字节）
    private static int indexSize = 20;
    // 用来验证是否是一个有效的索引
    private static int invalidIndex = 0;
    /**
     * index 文件中 hash 槽的总个数,默认500万
     * 本质来说：key进行hash 之后， hashkey % hashSlotNum.
     */
    private final int hashSlotNum;
    // indexFile中包含的条目数，默认2000万
    private final int indexNum;
    // 对应的映射文件
    private final MappedFile mappedFile;
    // 对应的文件通道
    private final FileChannel fileChannel;
    // 对应 PageCache
    private final MappedByteBuffer mappedByteBuffer;
    /**
     * IndexHeader, 默认占用40个字节
     * 每一个index file在开头的位置都记录了一些头信息
     */
    private final IndexHeader indexHeader;

    public IndexFile(final String fileName, final int hashSlotNum, final int indexNum,
        final long endPhyOffset, final long endTimestamp) throws IOException {
        int fileTotalSize =
            IndexHeader.INDEX_HEADER_SIZE + (hashSlotNum * hashSlotSize) + (indexNum * indexSize);
        this.mappedFile = new MappedFile(fileName, fileTotalSize);
        this.fileChannel = this.mappedFile.getFileChannel();
        this.mappedByteBuffer = this.mappedFile.getMappedByteBuffer();
        this.hashSlotNum = hashSlotNum;
        this.indexNum = indexNum;

        ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
        this.indexHeader = new IndexHeader(byteBuffer);

        if (endPhyOffset > 0) {
            this.indexHeader.setBeginPhyOffset(endPhyOffset);
            this.indexHeader.setEndPhyOffset(endPhyOffset);
        }

        if (endTimestamp > 0) {
            this.indexHeader.setBeginTimestamp(endTimestamp);
            this.indexHeader.setEndTimestamp(endTimestamp);
        }
    }

    /**
     * 文件名
     * @return
     */
    public String getFileName() {
        return this.mappedFile.getFileName();
    }

    public void load() {
        this.indexHeader.load();
    }

    /**
     * 刷盘
     */
    public void flush() {
        long beginTime = System.currentTimeMillis();

        //增加文件引用
        if (this.mappedFile.hold()) {
            //更新头信息
            this.indexHeader.updateByteBuffer();
            //强制刷盘
            this.mappedByteBuffer.force();
            //释放文件引用
            this.mappedFile.release();
            log.info("flush index file elapsed time(ms) " + (System.currentTimeMillis() - beginTime));
        }
    }

    /**
     * 判断文件是否写满，IndexFile：没有限定文件的大小，而是限制Index的数量。
     *
     * @return
     */
    public boolean isWriteFull() {
        return this.indexHeader.getIndexCount() >= this.indexNum;
    }

    public boolean destroy(final long intervalForcibly) {
        return this.mappedFile.destroy(intervalForcibly);
    }

    /**
     * 计算索引位置，并保存索引。
     *
     * @param key : topic + 消息key
     * @param phyOffset ： 物理偏移量
     * @param storeTimestamp ：存储的时间戳
     * @return
     */
    public boolean putKey(final String key, final long phyOffset, final long storeTimestamp) {
        //每个文件不能超过30万个
        if (this.indexHeader.getIndexCount() < this.indexNum) {
            // hash key
            int keyHash = indexKeyHashMethod(key);
            // hash slot
            int slotPos = keyHash % this.hashSlotNum;
            //计算hash槽的位置，记录了index数目
            int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * hashSlotSize;

            FileLock fileLock = null;

            try {

                /**
                 * 获取哈氏槽的值。
                 * 记录上个索引的下标，也就是第多少个索引
                 * 用上个索引的下标，计算出来的位置，就是下个索引准备写入的位置。
                 */
                int slotValue = this.mappedByteBuffer.getInt(absSlotPos);
                if (slotValue <= invalidIndex || slotValue > this.indexHeader.getIndexCount()) {
                    slotValue = invalidIndex;
                }

                long timeDiff = storeTimestamp - this.indexHeader.getBeginTimestamp();

                timeDiff = timeDiff / 1000;

                if (this.indexHeader.getBeginTimestamp() <= 0) {
                    timeDiff = 0;
                } else if (timeDiff > Integer.MAX_VALUE) {
                    timeDiff = Integer.MAX_VALUE;
                } else if (timeDiff < 0) {
                    timeDiff = 0;
                }
                /**
                 * 计算索引位置，并将索引写入
                 * 1、key的hash值，占用四个字节
                 * 2、物理位置：占用八个字节
                 * 3、时间差：4个字节
                 * 4、上一个索引的下标
                 */
                int absIndexPos =
                    IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * hashSlotSize
                        + this.indexHeader.getIndexCount() * indexSize;

                this.mappedByteBuffer.putInt(absIndexPos, keyHash);
                this.mappedByteBuffer.putLong(absIndexPos + 4, phyOffset);
                this.mappedByteBuffer.putInt(absIndexPos + 4 + 8, (int) timeDiff);
                /**
                 * 当前索引，记录【该hash槽中，上一个索引的下标】
                 *
                 * 这恰巧是解决hash 冲突的方式。组成像链表一样的结构
                 *
                 * 切记：它记录的是上一个该hash槽的下标，故而，能通过该值，查找到hash冲突的上一个
                 * 索引，再通过记录的消息的时间，就能得到是那条消息。
                 */
                this.mappedByteBuffer.putInt(absIndexPos + 4 + 8 + 4, slotValue);
                /**
                 * 当前的hash槽，保存当前索引的下标
                 */
                this.mappedByteBuffer.putInt(absSlotPos, this.indexHeader.getIndexCount());

                if (this.indexHeader.getIndexCount() <= 1) {
                    this.indexHeader.setBeginPhyOffset(phyOffset);
                    this.indexHeader.setBeginTimestamp(storeTimestamp);
                }

                this.indexHeader.incHashSlotCount();
                this.indexHeader.incIndexCount();
                this.indexHeader.setEndPhyOffset(phyOffset);
                this.indexHeader.setEndTimestamp(storeTimestamp);

                return true;
            } catch (Exception e) {
                log.error("putKey exception, Key: " + key + " KeyHashCode: " + key.hashCode(), e);
            } finally {
                if (fileLock != null) {
                    try {
                        fileLock.release();
                    } catch (IOException e) {
                        log.error("Failed to release the lock", e);
                    }
                }
            }
        } else {
            log.warn("Over index file capacity: index count = " + this.indexHeader.getIndexCount()
                + "; index max num = " + this.indexNum);
        }

        return false;
    }

    /**
     * 将索引key 进行hash
     *
     * @param key
     * @return
     */
    public int indexKeyHashMethod(final String key) {
        //hash
        int keyHash = key.hashCode();
        //求绝对值，如果是负数，转化成正数
        int keyHashPositive = Math.abs(keyHash);
        if (keyHashPositive < 0)
            keyHashPositive = 0;
        return keyHashPositive;
    }

    public long getBeginTimestamp() {
        return this.indexHeader.getBeginTimestamp();
    }

    public long getEndTimestamp() {
        return this.indexHeader.getEndTimestamp();
    }

    public long getEndPhyOffset() {
        return this.indexHeader.getEndPhyOffset();
    }

    /**
     * 判断时间是否匹配
     *
     * @param begin
     * @param end
     * @return
     */
    public boolean isTimeMatched(final long begin, final long end) {
        boolean result = begin < this.indexHeader.getBeginTimestamp() && end > this.indexHeader.getEndTimestamp();
        result = result || (begin >= this.indexHeader.getBeginTimestamp() && begin <= this.indexHeader.getEndTimestamp());
        result = result || (end >= this.indexHeader.getBeginTimestamp() && end <= this.indexHeader.getEndTimestamp());
        return result;
    }

    /**
     * 从索引文件 【查找】 消息位置。
     *
     * @param phyOffsets ：用来存放查找到的消息位置
     * @param key ：索引key
     * @param maxNum ：查找消息的最大数量
     * @param begin ：开始时间戳
     * @param end ：结束时间戳
     * @param lock
     */
    public void selectPhyOffset(final List<Long> phyOffsets, final String key, final int maxNum,
        final long begin, final long end, boolean lock) {
        if (this.mappedFile.hold()) {
            int keyHash = indexKeyHashMethod(key);
            //hash槽
            int slotPos = keyHash % this.hashSlotNum;
            int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * hashSlotSize;

            FileLock fileLock = null;
            try {
                if (lock) {
                    // fileLock = this.fileChannel.lock(absSlotPos,
                    // hashSlotSize, true);
                }

                int slotValue = this.mappedByteBuffer.getInt(absSlotPos);
                // if (fileLock != null) {
                // fileLock.release();
                // fileLock = null;
                // }

                if (slotValue <= invalidIndex || slotValue > this.indexHeader.getIndexCount()
                    || this.indexHeader.getIndexCount() <= 1) {
                } else {
                    /**
                     * 一个索引记录了上一条索引的下标，形成一个链式一样的关系。
                     * 故而在这里要循环查找。通过时间判断是否符合条件
                     */
                    for (int nextIndexToRead = slotValue; ; ) {
                        /**
                         * 消息达到最大数量的时候，退出循环
                         */
                        if (phyOffsets.size() >= maxNum) {
                            break;
                        }
                        /**
                         * 计算索引的位置：
                         */
                        int absIndexPos =
                            IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * hashSlotSize
                                + nextIndexToRead * indexSize;

                        int keyHashRead = this.mappedByteBuffer.getInt(absIndexPos);
                        long phyOffsetRead = this.mappedByteBuffer.getLong(absIndexPos + 4);

                        long timeDiff = (long) this.mappedByteBuffer.getInt(absIndexPos + 4 + 8);
                        /**
                         * 上一个索引的下标
                         */
                        int prevIndexRead = this.mappedByteBuffer.getInt(absIndexPos + 4 + 8 + 4);

                        if (timeDiff < 0) {
                            break;
                        }

                        timeDiff *= 1000L;

                        long timeRead = this.indexHeader.getBeginTimestamp() + timeDiff;
                        boolean timeMatched = (timeRead >= begin) && (timeRead <= end);

                        if (keyHash == keyHashRead && timeMatched) {
                            phyOffsets.add(phyOffsetRead);
                        }

                        if (prevIndexRead <= invalidIndex
                            || prevIndexRead > this.indexHeader.getIndexCount()
                            || prevIndexRead == nextIndexToRead || timeRead < begin) {
                            break;
                        }

                        nextIndexToRead = prevIndexRead;
                    }
                }
            } catch (Exception e) {
                log.error("selectPhyOffset exception ", e);
            } finally {
                if (fileLock != null) {
                    try {
                        fileLock.release();
                    } catch (IOException e) {
                        log.error("Failed to release the lock", e);
                    }
                }

                this.mappedFile.release();
            }
        }
    }
}
