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
package org.apache.rocketmq.store.ha;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import org.apache.rocketmq.common.ServiceThread;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.common.RemotingUtil;
import org.apache.rocketmq.store.SelectMappedBufferResult;

/**
 * 表示一个Slave对Master的连接
 *
 * 处理Master与Slave的读写相关的操作
 */
public class HAConnection {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    //HAService对象。
    private final HAService haService;
    //读写socket通道
    private final SocketChannel socketChannel;
    //slave地址
    private final String clientAddr;
    //
    private WriteSocketService writeSocketService;
    //
    private ReadSocketService readSocketService;

    /**
     * slave请求拉取数据的开始偏移量
     */
    private volatile long slaveRequestOffset = -1;
    /**
     * slave报告的已同步完成的数据偏移量，表示slave的 max offset
     */
    private volatile long slaveAckOffset = -1;

    public HAConnection(final HAService haService, final SocketChannel socketChannel) throws IOException {
        this.haService = haService;
        this.socketChannel = socketChannel;
        this.clientAddr = this.socketChannel.socket().getRemoteSocketAddress().toString();
        this.socketChannel.configureBlocking(false);
        this.socketChannel.socket().setSoLinger(false, -1);
        this.socketChannel.socket().setTcpNoDelay(true);
        this.socketChannel.socket().setReceiveBufferSize(1024 * 64);
        this.socketChannel.socket().setSendBufferSize(1024 * 64);
        /**
         * 新建write socket线程
         */
        this.writeSocketService = new WriteSocketService(this.socketChannel);
        /**
         * 新建read socket线程
         */
        this.readSocketService = new ReadSocketService(this.socketChannel);
        this.haService.getConnectionCount().incrementAndGet();
    }

    /**
     * slave 建立连接之后启动。
     */
    public void start() {
        //启动read socket线程
        this.readSocketService.start();
        //启动write socket线程
        this.writeSocketService.start();
    }

    public void shutdown() {
        this.writeSocketService.shutdown(true);
        this.readSocketService.shutdown(true);
        this.close();
    }

    public void close() {
        if (this.socketChannel != null) {
            try {
                this.socketChannel.close();
            } catch (IOException e) {
                HAConnection.log.error("", e);
            }
        }
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    /**
     * 处理socket的【读】
     *
     * 解析slave服务器的拉取请求
     */
    class ReadSocketService extends ServiceThread {
        //网络读缓存区大小，默认1M。
        private static final int READ_MAX_BUFFER_SIZE = 1024 * 1024;
        //NIO网络事件选择器。
        private final Selector selector;
        /**
         * 网络通道，用于读写的socket通道。
         * 这里使用了SocketChannel，是要发送消息么？
         */
        private final SocketChannel socketChannel;
        //网络读写缓存区，默认为1M。
        private final ByteBuffer byteBufferRead = ByteBuffer.allocate(READ_MAX_BUFFER_SIZE);
        //记录一次拉取数据的，已处理的offset
        private int processPosition = 0;
        //上次读取数据的时间戳。
        private volatile long lastReadTimestamp = System.currentTimeMillis();

        /**
         * 创建一个事件选择器，并将该网络通道注册在事件选择器上，并注册网络读事件。
         *
         * @param socketChannel
         * @throws IOException
         */
        public ReadSocketService(final SocketChannel socketChannel) throws IOException {
            this.selector = RemotingUtil.openSelector();
            this.socketChannel = socketChannel;
            this.socketChannel.register(this.selector, SelectionKey.OP_READ);
            this.setDaemon(true);
        }

        /**
         * 核心实现就是每1s执行一次事件就绪选择，
         * 然后调用processReadEvent方法处理读请求，读取从服务器的拉取请求。
         */
        @Override
        public void run() {
            HAConnection.log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    //阻塞1s，判断是否有请求
                    this.selector.select(1000);

                    /**
                     * 处理读事件
                     */
                    boolean ok = this.processReadEvent();
                    if (!ok) {
                        HAConnection.log.error("processReadEvent error");
                        break;
                    }

                    /**
                     * 判断心跳连接是否异常。
                     */
                    long interval = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now() - this.lastReadTimestamp;
                    if (interval > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaHousekeepingInterval()) {
                        log.warn("ha housekeeping, found this connection[" + HAConnection.this.clientAddr + "] expired, " + interval);
                        break;
                    }
                } catch (Exception e) {
                    HAConnection.log.error(this.getServiceName() + " service has exception.", e);
                    break;
                }
            }
            /**
             * ============================================
             * ReadSocketService线程停止，或者发生异常，执行关闭操作
             */
            this.makeStop();

            writeSocketService.makeStop();

            haService.removeConnection(HAConnection.this);

            HAConnection.this.haService.getConnectionCount().decrementAndGet();

            SelectionKey sk = this.socketChannel.keyFor(this.selector);
            if (sk != null) {
                sk.cancel();
            }

            try {
                this.selector.close();
                this.socketChannel.close();
            } catch (IOException e) {
                HAConnection.log.error("", e);
            }

            HAConnection.log.info(this.getServiceName() + " service end");
        }

        @Override
        public String getServiceName() {
            return ReadSocketService.class.getSimpleName();
        }

        /**
         * 处理读请求，读取slave服务器的拉取请求。
         * 只会发来一个8个字节的offset，表示slave的最大offset。
         *
         * @return
         */
        private boolean processReadEvent() {
            int readSizeZeroTimes = 0;
            /**
             * byteBufferRead没有剩余空间，表示已经满了
             * 则调用flip()，切换成读模式
             */
            if (!this.byteBufferRead.hasRemaining()) {
                //切换成读模式
                this.byteBufferRead.flip();
                //表示还没有处理
                this.processPosition = 0;
            }

            /**
             * NIO网络读的常规方法，由于NIO是非阻塞的，
             * 【一次网络读写的字节大小不确定，一般都会尝试多次读取】。
             */
            while (this.byteBufferRead.hasRemaining()) {
                try {
                    //从socket读取数据
                    int readSize = this.socketChannel.read(this.byteBufferRead);
                    if (readSize > 0) {
                        readSizeZeroTimes = 0;

                        this.lastReadTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
                        /**
                         * slave发来的report offset（其实也就是拉取消息的请求）
                         * slave拉取一条消息：其中只有一个8位的long，表示slave的offset
                         */
                        //当新发过来的数据超过8个字节
                        if ((this.byteBufferRead.position() - this.processPosition) >= 8) {
                            //计算下标
                            int pos = this.byteBufferRead.position() - (this.byteBufferRead.position() % 8);

                            //slave 的最大的offset
                            long readOffset = this.byteBufferRead.getLong(pos - 8);
                            this.processPosition = pos;

                            //记录slave的offset。
                            HAConnection.this.slaveAckOffset = readOffset;
                            /**
                             * 设置slave请求的offset = readOffset
                             */
                            if (HAConnection.this.slaveRequestOffset < 0) {
                                HAConnection.this.slaveRequestOffset = readOffset;
                                log.info("slave[" + HAConnection.this.clientAddr + "] request offset " + readOffset);
                            }
                            /**
                             * 通知WriteSocketService线程,开始同步数据。
                             */
                            HAConnection.this.haService.notifyTransferSome(HAConnection.this.slaveAckOffset);
                        }
                    } else if (readSize == 0) {
                        /**
                         * 如果没有收到消息，最多重试3次。
                         */
                        if (++readSizeZeroTimes >= 3) {
                            break;
                        }
                    } else {
                        log.error("read socket[" + HAConnection.this.clientAddr + "] < 0");
                        return false;
                    }
                } catch (IOException e) {
                    log.error("processReadEvent exception", e);
                    return false;
                }
            }

            return true;
        }
    }

    /**
     * 开启一个线程，处理【写】
     * 主要负责将消息内容传输给slave服务器，器类图如图所示：
     */
    class WriteSocketService extends ServiceThread {
        //NIO网络事件选择器。
        private final Selector selector;
        //网络socket通道。
        private final SocketChannel socketChannel;

        //消息头长度，消息de  [物理偏移量+消息长度]。
        private final int headerSize = 8 + 4;

        //表示一个请求头的内容
        private final ByteBuffer byteBufferHeader = ByteBuffer.allocate(headerSize);
        //下一次传输的物理偏移量。
        private long nextTransferFromWhere = -1;
        //根据偏移量查找到的【消息的结果】。
        private SelectMappedBufferResult selectMappedBufferResult;
        //上一次数据是否传输完毕。
        private boolean lastWriteOver = true;
        //上次写入的时间戳。
        private long lastWriteTimestamp = System.currentTimeMillis();

        public WriteSocketService(final SocketChannel socketChannel) throws IOException {
            this.selector = RemotingUtil.openSelector();
            this.socketChannel = socketChannel;
            this.socketChannel.register(this.selector, SelectionKey.OP_WRITE);
            this.setDaemon(true);
        }

        @Override
        public void run() {
            HAConnection.log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    //阻塞1s
                    this.selector.select(1000);

                    /**
                     * 如果slaveRequestOffset等于-1，说明Master还未收到从服务器的拉取请求，放弃本次事件处理
                     * slaveRequestOffset在收到从服务器拉取请求时更新（HAConnection$ReadSocketService）。
                     */
                    if (-1 == HAConnection.this.slaveRequestOffset) {
                        Thread.sleep(10);
                        continue;
                    }

                    /**
                     * 计算从哪儿开始拉取（一般来说从 slave 报告的 offset开始拉取，也就是slave的max offset）
                     *
                     * 如果nextTransferFromWhere为-1表示初次进行数据传输，需要计算需要传输的物理偏移量，
                     * 如果slaveRequestOffset为0，
                     *      则从当前commitlog文件最大偏移量开始传输，
                     *      否则根据从服务器的拉取请求偏移量开始传输。
                     */
                    if (-1 == this.nextTransferFromWhere) {
                        //slave请求的offset 为 0
                        if (0 == HAConnection.this.slaveRequestOffset) {

                            //masterOffset
                            long masterOffset = HAConnection.this.haService.getDefaultMessageStore().getCommitLog().getMaxOffset();
                            masterOffset =
                                masterOffset
                                    - (masterOffset % HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig()
                                    .getMappedFileSizeCommitLog());

                            if (masterOffset < 0) {
                                masterOffset = 0;
                            }

                            this.nextTransferFromWhere = masterOffset;
                        } else {
                            //salve 请求的offset 不为0
                            this.nextTransferFromWhere = HAConnection.this.slaveRequestOffset;
                        }

                        log.info("master transfer data from " + this.nextTransferFromWhere + " to slave[" + HAConnection.this.clientAddr
                            + "], and slave request " + HAConnection.this.slaveRequestOffset);
                    }

                    //判断上次写事件是否将信息已全部写入到客户端。
                    if (this.lastWriteOver) {
                        /**
                         * master salve的默认心跳间隔是5s，如果超过5s，则首先需要发送心跳包
                         */
                        long interval =
                            HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now() - this.lastWriteTimestamp;

                        if (interval > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig()
                            .getHaSendHeartbeatInterval()) {
                            /**
                             * 心跳包的长度为12个字节
                             */
                            // Build Header
                            this.byteBufferHeader.position(0);
                            this.byteBufferHeader.limit(headerSize);
                            //待拉取的偏移量
                            this.byteBufferHeader.putLong(this.nextTransferFromWhere);
                            //消息长度，默认为0
                            this.byteBufferHeader.putInt(0);
                            this.byteBufferHeader.flip();

                            //发送心跳数据
                            this.lastWriteOver = this.transferData();
                            if (!this.lastWriteOver)
                                continue;
                        }
                    } else {
                        /**
                         * 如果上次数据未写完，则继续传输上一次的数据，然后再次判断是否传输完成，
                         * 如果消息还是未全部传输，则结束此次事件处理，待下次写事件到底后，
                         * 继续将未传输完的数据先写入消息从服务器。
                         */
                        this.lastWriteOver = this.transferData();
                        if (!this.lastWriteOver)
                            continue;
                    }

                    /**
                     * ==================================================================
                     * 开始向slave同步数据
                     *
                     */

                    //查询master 比 salve 多出来的 offset。
                    SelectMappedBufferResult selectResult =
                        HAConnection.this.haService.getDefaultMessageStore().getCommitLogData(this.nextTransferFromWhere);
                    if (selectResult != null) {
                        //数据长度
                        int size = selectResult.getSize();
                        /**
                         * 一次同步，最多传输32kb，也有可能是32 条.
                         *
                         * 数据长度不能超过 1024 * 32。超过的话，按1024 * 32
                         */
                        if (size > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaTransferBatchSize()) {
                            size = HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaTransferBatchSize();
                        }

                        long thisOffset = this.nextTransferFromWhere;
                        this.nextTransferFromWhere += size;

                        //限制传输数据不能超过 1024 * 32
                        selectResult.getByteBuffer().limit(size);
                        this.selectMappedBufferResult = selectResult;

                        /**
                         * 同步数据内容，共12个字节
                         */
                        // Build Header
                        this.byteBufferHeader.position(0);
                        this.byteBufferHeader.limit(headerSize);
                        this.byteBufferHeader.putLong(thisOffset);//offset
                        this.byteBufferHeader.putInt(size);//数据size
                        //切换成读模式
                        this.byteBufferHeader.flip();

                        //RPC同步数据
                        this.lastWriteOver = this.transferData();
                    } else {
                        /**
                         * 没有可同步的数据，那么休息 100ms
                         */
                        HAConnection.this.haService.getWaitNotifyObject().allWaitForRunning(100);
                    }
                    /**
                     * 同步slave结束==============================
                     */
                } catch (Exception e) {

                    HAConnection.log.error(this.getServiceName() + " service has exception.", e);
                    break;
                }
            }

            /**
             * =====================================================
             * 处理停止WriteSocketService线程的情况。发送的情况有：
             * 1、stop 设置成 true。2、或者处理过程中发生了异常。
             */

            //移除当前线程，从WaitNotifyObject中
            HAConnection.this.haService.getWaitNotifyObject().removeFromWaitingThreadTable();

            //释放引用，表示对当前文件的操作已经结束
            if (this.selectMappedBufferResult != null) {
                this.selectMappedBufferResult.release();
            }

            //停止 WriteSocketService 线程
            this.makeStop();
            //停止 readSocketService 线程
            readSocketService.makeStop();
            //移除slave的连接，都发生错误了，还连接着干嘛？
            haService.removeConnection(HAConnection.this);

            /**
             * 从Selector中移除socketChannel
             */
            SelectionKey sk = this.socketChannel.keyFor(this.selector);
            if (sk != null) {
                sk.cancel();
            }

            /**
             * 关闭 selector，关闭socketChannel
             */
            try {
                this.selector.close();
                this.socketChannel.close();
            } catch (IOException e) {
                HAConnection.log.error("", e);
            }

            HAConnection.log.info(this.getServiceName() + " service end");
        }

        /**
         * 向slave发送请求
         * @return
         * @throws Exception
         */
        private boolean transferData() throws Exception {
            int writeSizeZeroTimes = 0;
            // Write Header
            while (this.byteBufferHeader.hasRemaining()) {
                //写入数据
                int writeSize = this.socketChannel.write(this.byteBufferHeader);
                if (writeSize > 0) {
                    writeSizeZeroTimes = 0;
                    this.lastWriteTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
                } else if (writeSize == 0) {
                    if (++writeSizeZeroTimes >= 3) {
                        break;
                    }
                } else {
                    throw new Exception("ha master write header error < 0");
                }
            }

            if (null == this.selectMappedBufferResult) {
                return !this.byteBufferHeader.hasRemaining();
            }

            writeSizeZeroTimes = 0;

            // Write Body
            if (!this.byteBufferHeader.hasRemaining()) {
                while (this.selectMappedBufferResult.getByteBuffer().hasRemaining()) {
                    int writeSize = this.socketChannel.write(this.selectMappedBufferResult.getByteBuffer());
                    if (writeSize > 0) {
                        writeSizeZeroTimes = 0;
                        this.lastWriteTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
                    } else if (writeSize == 0) {
                        if (++writeSizeZeroTimes >= 3) {
                            break;
                        }
                    } else {
                        throw new Exception("ha master write body error < 0");
                    }
                }
            }

            boolean result = !this.byteBufferHeader.hasRemaining() && !this.selectMappedBufferResult.getByteBuffer().hasRemaining();

            if (!this.selectMappedBufferResult.getByteBuffer().hasRemaining()) {
                this.selectMappedBufferResult.release();
                this.selectMappedBufferResult = null;
            }

            return result;
        }

        @Override
        public String getServiceName() {
            return WriteSocketService.class.getSimpleName();
        }

        @Override
        public void shutdown() {
            super.shutdown();
        }
    }
}
