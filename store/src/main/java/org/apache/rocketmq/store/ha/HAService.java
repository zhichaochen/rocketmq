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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.rocketmq.common.ServiceThread;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.common.RemotingUtil;
import org.apache.rocketmq.store.CommitLog;
import org.apache.rocketmq.store.DefaultMessageStore;

/**
 * 主从同步的核心实现类
 */
public class HAService {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);

    //记录当前连接到master的slave的数量。
    private final AtomicInteger connectionCount = new AtomicInteger(0);

    //缓存所有的slave连接
    private final List<HAConnection> connectionList = new LinkedList<>();

    private final AcceptSocketService acceptSocketService;

    private final DefaultMessageStore defaultMessageStore;

    private final WaitNotifyObject waitNotifyObject = new WaitNotifyObject();
    /**
     * master端记录的 【已经发送到slave】的max offset
     *
     * 可不代表slave就已经同步完成，或者同步成功了。
     */
    private final AtomicLong push2SlaveMaxOffset = new AtomicLong(0);

    private final GroupTransferService groupTransferService;

    private final HAClient haClient;

    public HAService(final DefaultMessageStore defaultMessageStore) throws IOException {
        this.defaultMessageStore = defaultMessageStore;
        this.acceptSocketService =
            new AcceptSocketService(defaultMessageStore.getMessageStoreConfig().getHaListenPort());
        this.groupTransferService = new GroupTransferService();
        this.haClient = new HAClient();
    }

    public void updateMasterAddress(final String newAddr) {
        if (this.haClient != null) {
            this.haClient.updateMasterAddress(newAddr);
        }
    }

    public void putRequest(final CommitLog.GroupCommitRequest request) {
        this.groupTransferService.putRequest(request);
    }

    /**
     * 判断是否可以同步
     *
     * @param masterPutWhere
     * @return
     */
    public boolean isSlaveOK(final long masterPutWhere) {
        //连接的slave大于0
        boolean result = this.connectionCount.get() > 0;
        result =
            result
                && ((masterPutWhere - this.push2SlaveMaxOffset.get()) < this.defaultMessageStore
                .getMessageStoreConfig().getHaSlaveFallbehindMax());
        return result;
    }

    /**
     * Master收到slave报告的offset
     *
     * 这里offset表示：slave已经同步完成的offset。
     *
     */
    public void notifyTransferSome(final long offset) {
        for (long value = this.push2SlaveMaxOffset.get(); offset > value; ) {
            boolean ok = this.push2SlaveMaxOffset.compareAndSet(value, offset);
            if (ok) {
                /**
                 * 唤醒GroupTransferService线程
                 */
                this.groupTransferService.notifyTransferSome();
                break;
            } else {
                /**
                 *
                 */
                value = this.push2SlaveMaxOffset.get();
            }
        }
    }

    public AtomicInteger getConnectionCount() {
        return connectionCount;
    }

    // public void notifyTransferSome() {
    // this.groupTransferService.notifyTransferSome();
    // }

    /**
     * 启动高可用服务
     *
     * @throws Exception
     */
    public void start() throws Exception {
        //建立HA服务端监听服务，处理客户Slave客户端监听请求。
        this.acceptSocketService.beginAccept();
        //启动监听线程，处理监听逻辑。
        this.acceptSocketService.start();
        //启动GroupTransferService线程。
        this.groupTransferService.start();
        //启动HA客户端线程。
        this.haClient.start();
    }

    /**
     * 缓存所有的slave连接
     * @param conn
     */
    public void addConnection(final HAConnection conn) {
        synchronized (this.connectionList) {
            this.connectionList.add(conn);
        }
    }

    /**
     * 移除 slave 连接
     * @param conn
     */
    public void removeConnection(final HAConnection conn) {
        synchronized (this.connectionList) {
            this.connectionList.remove(conn);
        }
    }

    public void shutdown() {
        this.haClient.shutdown();
        this.acceptSocketService.shutdown(true);
        this.destroyConnections();
        this.groupTransferService.shutdown();
    }

    public void destroyConnections() {
        synchronized (this.connectionList) {
            for (HAConnection c : this.connectionList) {
                c.shutdown();
            }

            this.connectionList.clear();
        }
    }

    public DefaultMessageStore getDefaultMessageStore() {
        return defaultMessageStore;
    }

    public WaitNotifyObject getWaitNotifyObject() {
        return waitNotifyObject;
    }

    public AtomicLong getPush2SlaveMaxOffset() {
        return push2SlaveMaxOffset;
    }

    /**
     * Listens to slave connections to create {@link HAConnection}.
     *
     * Master监听slave连接，并创建HAConnection
     */
    class AcceptSocketService extends ServiceThread {
        private final SocketAddress socketAddressListen;
        private ServerSocketChannel serverSocketChannel;
        private Selector selector;

        public AcceptSocketService(final int port) {
            this.socketAddressListen = new InetSocketAddress(port);
        }

        /**
         * Starts listening to slave connections.
         *
         * @throws Exception If fails.
         *
         * 开启监听slave连接
         */
        public void beginAccept() throws Exception {
            this.serverSocketChannel = ServerSocketChannel.open();
            //Selector.open()
            this.selector = RemotingUtil.openSelector();
            this.serverSocketChannel.socket().setReuseAddress(true);
            this.serverSocketChannel.socket().bind(this.socketAddressListen);
            this.serverSocketChannel.configureBlocking(false);
            /**
             * 将通道注册到selector，设置监听accept事件。
             */
            this.serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void shutdown(final boolean interrupt) {
            super.shutdown(interrupt);
            try {
                this.serverSocketChannel.close();
                this.selector.close();
            } catch (IOException e) {
                log.error("AcceptSocketService shutdown exception", e);
            }
        }

        /**
         * 监听slave的连接，
         * 连接之后创建HAConnection对象，并缓存
         */
        @Override
        public void run() {
            log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    /**
                     * 阻塞，等待有就绪通道。
                     * 如果超时，该方法将返回0，返回值，表示通道的就绪个数
                     */
                    this.selector.select(1000);
                    //获取就绪的SelectionKey，如果没有就绪，就会继续轮询
                    Set<SelectionKey> selected = this.selector.selectedKeys();

                    if (selected != null) {
                        //轮询就绪事件
                        for (SelectionKey k : selected) {
                            /**
                             * 检测就绪的 accept 事件
                             * 也可以使用k.isAcceptable() 判断
                             */
                            if ((k.readyOps() & SelectionKey.OP_ACCEPT) != 0) {
                                //监听进来的连接，一直阻塞，直到有新连接到达。
                                SocketChannel sc = ((ServerSocketChannel) k.channel()).accept();

                                if (sc != null) {
                                    HAService.log.info("HAService receive new connection, "
                                        + sc.socket().getRemoteSocketAddress());

                                    try {
                                        /**
                                         * 有slave要连接的时候，创建一个连接，并缓存
                                         */
                                        HAConnection conn = new HAConnection(HAService.this, sc);
                                        conn.start();
                                        HAService.this.addConnection(conn);
                                    } catch (Exception e) {
                                        log.error("new HAConnection exception", e);
                                        sc.close();
                                    }
                                }
                            } else {
                                log.warn("Unexpected ops in select " + k.readyOps());
                            }
                        }
                        /**
                         * 处理完毕本次连接，需要清空Selector key，
                         * 不然下次有连接的时候，还会往该集合中放，会有错误的。
                         */
                        selected.clear();
                    }
                } catch (Exception e) {
                    log.error(this.getServiceName() + " service has exception.", e);
                }
            }

            log.info(this.getServiceName() + " service end");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getServiceName() {
            return AcceptSocketService.class.getSimpleName();
        }
    }

    /**
     * 监控 【同步请求】（生产的同步消息） 的处理情况。
     *
     * 作用：判断主从同步【复制是否完成】。
     */
    class GroupTransferService extends ServiceThread {
        private final WaitNotifyObject notifyTransferObject = new WaitNotifyObject();
        /**
         * requestsWrite：缓存同步请求
         * requestsRead ：表示正在监控处理的同步请求。
         *
         * 当处理完毕后，会清空requestsRead列表，
         * 当等待结束，开始处理下一轮同步请求的时候，会将requestsWrite 和 requestsRead进行交换
         * 这样：requestsWrite 继续接收请求，当前线程，继续处理requestsRead线程。
         */
        private volatile List<CommitLog.GroupCommitRequest> requestsWrite = new ArrayList<>();
        private volatile List<CommitLog.GroupCommitRequest> requestsRead = new ArrayList<>();

        /**
         * 生产的同步消息，将同步请求缓存在 requestsWrite
         *
         * 使用双检锁
         *
         * @param request
         */
        public synchronized void putRequest(final CommitLog.GroupCommitRequest request) {
            synchronized (this.requestsWrite) {
                //将同步请求加入缓存
                this.requestsWrite.add(request);
            }
            if (hasNotified.compareAndSet(false, true)) {
                //
                waitPoint.countDown(); // notify
            }
        }

        /**
         * 通知，开始向slave传输数据。
         * 通知的到底是谁？？？？
         *      调用该线程的wakeup，应该是通知当前线程开始运行
         */
        public void notifyTransferSome() {
            this.notifyTransferObject.wakeup();
        }

        /**
         * 交换请求
         */
        private void swapRequests() {
            List<CommitLog.GroupCommitRequest> tmp = this.requestsWrite;
            this.requestsWrite = this.requestsRead;
            this.requestsRead = tmp;
        }

        /**
         * 该类核心处理
         *  GroupCommitRequest : 请求同步（生产者发送的每条同步消息，对应一个GroupCommitRequest）
         *
         *  getNextOffset：表示等待同步的offset。
         *
         *  发送同步消息的时候，会将需要同步的消息，发送到requestsWrite缓存中
         */
        private void doWaitTransfer() {
            synchronized (this.requestsRead) {
                if (!this.requestsRead.isEmpty()) {
                    for (CommitLog.GroupCommitRequest req : this.requestsRead) {
                        /**
                         * 判断是否已经完成
                         *
                         * 是这样的：slave是一直在持续不断的传输，有可能刚写入的消息就已经自动同步完成了。
                         * 但是生产者发送的同步消息，自动来请求同步，有可能请求同步的消息，已经同步完毕了。
                         *
                         * 条件：已经同步的offset  >= 请求同步的offset，表示请求的offset，已经同步完成。
                         */
                        boolean transferOK = HAService.this.push2SlaveMaxOffset.get() >= req.getNextOffset();

                        //计算超时时长 ：当前时间 + 同步超时时间(默认5s超时)
                        long waitUntilWhen = HAService.this.defaultMessageStore.getSystemClock().now()
                            + HAService.this.defaultMessageStore.getMessageStoreConfig().getSyncFlushTimeout();

                        /**
                         * 如果没有同步完成，且没有超时，则等待1s。
                         *
                         * 判断是否同步完成。
                         */
                        while (!transferOK && HAService.this.defaultMessageStore.getSystemClock().now() < waitUntilWhen) {
                            this.notifyTransferObject.waitForRunning(1000);
                            transferOK = HAService.this.push2SlaveMaxOffset.get() >= req.getNextOffset();
                        }

                        /**
                         * 超时了，还没有同步完成，则输出一条日志。
                         */
                        if (!transferOK) {
                            log.warn("transfer messsage to slave timeout, " + req.getNextOffset());
                        }

                        /**
                         * 唤醒生产者的同步消息线程，继续执行
                         * 参见：CommitLog#handleHA
                         */
                        req.wakeupCustomer(transferOK);
                    }

                    //处理完毕，清空列表。
                    this.requestsRead.clear();
                }
            }
        }

        public void run() {
            log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    //等待10ms
                    this.waitForRunning(10);
                    this.doWaitTransfer();
                } catch (Exception e) {
                    log.warn(this.getServiceName() + " service has exception. ", e);
                }
            }

            log.info(this.getServiceName() + " service end");
        }

        /**
         * 等待结束之后，交换请求
         */
        @Override
        protected void onWaitEnd() {
            this.swapRequests();
        }

        @Override
        public String getServiceName() {
            return GroupTransferService.class.getSimpleName();
        }
    }

    /**
     * 从服务器连接主服务实现类
     */
    class HAClient extends ServiceThread {
        //Socket读缓存区大小，默认4M
        private static final int READ_MAX_BUFFER_SIZE = 1024 * 1024 * 4;
        //master地址。
        private final AtomicReference<String> masterAddress = new AtomicReference<>();
        /**
         *  Slave向Master报告，slave当前的最大偏移量
         */
        private final ByteBuffer reportOffset = ByteBuffer.allocate(8);
        //网络传输通道。
        private SocketChannel socketChannel;
        //NIO事件选择器。
        private Selector selector;
        //最近一次写入时间戳。
        private long lastWriteTimestamp = System.currentTimeMillis();

        //commit log文件当前最大偏移量。
        private long currentReportedOffset = 0;

        /**
         * master发来的消息是一段一段的。
         * 这里记录一次请求中，已经处理了多少数据。
         * 处理完请求之后，会关闭socket，这时候会重置为0
         */
        private int dispatchPosition = 0;
        //读缓存区，大小为4M。
        private ByteBuffer byteBufferRead = ByteBuffer.allocate(READ_MAX_BUFFER_SIZE);
        //读缓存区备份，与BufferRead进行交换。
        private ByteBuffer byteBufferBackup = ByteBuffer.allocate(READ_MAX_BUFFER_SIZE);

        public HAClient() throws IOException {
            this.selector = RemotingUtil.openSelector();
        }

        public void updateMasterAddress(final String newAddr) {
            String currentAddr = this.masterAddress.get();
            if (currentAddr == null || !currentAddr.equals(newAddr)) {
                this.masterAddress.set(newAddr);
                log.info("update master address, OLD: " + currentAddr + " NEW: " + newAddr);
            }
        }

        /**
         * 判断是否需要向Master汇报已拉取消息偏移量。
         * 其依据为每次拉取间隔必须大于haSendHeartbeatInterval，默认5s。
         *
         * @return
         */
        private boolean isTimeToReportOffset() {
            long interval =
                HAService.this.defaultMessageStore.getSystemClock().now() - this.lastWriteTimestamp;
            boolean needHeart = interval > HAService.this.defaultMessageStore.getMessageStoreConfig()
                .getHaSendHeartbeatInterval();

            return needHeart;
        }

        /**
         * 向master报告，当前slave的最大offset
         *
         * 其实是去master拉取offset。
         *
         * @param maxOffset
         * @return
         */
        private boolean reportSlaveMaxOffset(final long maxOffset) {
            /**
             * 写入八个字节的Offset
             */
            //定为到0
            this.reportOffset.position(0);
            //最大为8
            this.reportOffset.limit(8);
            //最大偏移量
            this.reportOffset.putLong(maxOffset);

            /**
             * 手动设置成读模式
             */
            this.reportOffset.position(0);
            this.reportOffset.limit(8);

            for (int i = 0; i < 3 && this.reportOffset.hasRemaining(); i++) {
                try {
                    //发送数据
                    this.socketChannel.write(this.reportOffset);
                } catch (IOException e) {
                    log.error(this.getServiceName()
                        + "reportSlaveMaxOffset this.socketChannel.write exception", e);
                    return false;
                }
            }

            lastWriteTimestamp = HAService.this.defaultMessageStore.getSystemClock().now();
            return !this.reportOffset.hasRemaining();
        }

        /**
         * 处理不完整的信息
         */
        private void reallocateByteBuffer() {
            //没有处理的数据
            int remain = READ_MAX_BUFFER_SIZE - this.dispatchPosition;
            if (remain > 0) {
                /**
                 * 存在没有处理的数据，则备份一下
                 * 将数据放入byteBufferBackup
                 */
                this.byteBufferRead.position(this.dispatchPosition);

                this.byteBufferBackup.position(0);
                this.byteBufferBackup.limit(READ_MAX_BUFFER_SIZE);
                this.byteBufferBackup.put(this.byteBufferRead);
            }
            //交换两个ByteBuffer
            this.swapByteBuffer();

            /**
             * 这里的byteBufferRead，其实是byteBufferBackup，交换了。
             * 将position定位到数据的末尾，继续接收后续过来的数据。
             * 这样，消息就连接起来了。
             */
            this.byteBufferRead.position(remain);
            this.byteBufferRead.limit(READ_MAX_BUFFER_SIZE);
            this.dispatchPosition = 0;
        }

        /**
         * 交换ByteBuffer
         */
        private void swapByteBuffer() {
            ByteBuffer tmp = this.byteBufferRead;
            this.byteBufferRead = this.byteBufferBackup;
            this.byteBufferBackup = tmp;
        }

        /**
         * 处理网络读请求，也就是处理从Master传回的消息数据
         * 上一步发送了个报告offset的请求，在这里接受响应。
         *
         * @return
         */
        private boolean processReadEvent() {
            /**
             * 记录读取次数，socketChannel.read一次，+1
             * 当超过3次没有收到数据，则退出
             */
            int readSizeZeroTimes = 0;
            while (this.byteBufferRead.hasRemaining()) {
                try {
                    //将消息读入缓冲区
                    int readSize = this.socketChannel.read(this.byteBufferRead);
                    if (readSize > 0) {
                        readSizeZeroTimes = 0;
                        //转发读请求，处理请求数据
                        boolean result = this.dispatchReadRequest();
                        if (!result) {
                            log.error("HAClient, dispatchReadRequest error");
                            return false;
                        }
                    } else if (readSize == 0) {
                        /**
                         * 判断读取次数，超过3次则退出
                         */
                        if (++readSizeZeroTimes >= 3) {
                            break;
                        }
                    } else {
                        log.info("HAClient, processReadEvent read socket < 0");
                        return false;
                    }
                } catch (IOException e) {
                    log.info("HAClient, processReadEvent read socket exception", e);
                    return false;
                }
            }

            return true;
        }

        /**
         * client（slave）处理读请求
         *
         * 解析一条一条的消息，然后存储到commitlog文件并转发到消息消费队列与索引文件中。
         *
         * @return
         */
        private boolean dispatchReadRequest() {
            //消息头12个字节，包括消息的物理偏移量与消息的长度
            final int msgHeaderSize = 8 + 4; // phyoffset + size
            //记录byteBufferRead的当前指针
            int readSocketPos = this.byteBufferRead.position();

            while (true) {
                /**
                 * 探测byteBufferRead缓冲区中是否包含一条消息的头部
                 *      如果包含头部，则读取物理偏移量与消息长度
                 */
                int diff = this.byteBufferRead.position() - this.dispatchPosition;
                if (diff >= msgHeaderSize) {
                    /**
                     * 包含消息头部，则读取物理偏移量与消息长度
                     */
                    long masterPhyOffset = this.byteBufferRead.getLong(this.dispatchPosition);
                    int bodySize = this.byteBufferRead.getInt(this.dispatchPosition + 8);

                    //slavePhyOffset
                    long slavePhyOffset = HAService.this.defaultMessageStore.getMaxPhyOffset();

                    if (slavePhyOffset != 0) {
                        /**
                         * 判断slave的偏移量，和传过来的masterPhyOffset是否相同
                         */
                        if (slavePhyOffset != masterPhyOffset) {
                            log.error("master pushed offset not equal the max phy offset in slave, SLAVE: "
                                + slavePhyOffset + " MASTER: " + masterPhyOffset);
                            return false;
                        }
                    }
                    /**
                     * 探测是否包含一条完整的消息
                     * 一条完整的消息包含 ：消息头(消息的偏移量 + 消息长度) + 消息体。
                     */
                    if (diff >= (msgHeaderSize + bodySize)) {
                        byte[] bodyData = new byte[bodySize];
                        //dispatchPosition + 消息头  = 消息体所在的位置
                        this.byteBufferRead.position(this.dispatchPosition + msgHeaderSize);
                        //取出消息
                        this.byteBufferRead.get(bodyData);

                        //slave将同步过来的消息写入磁盘
                        HAService.this.defaultMessageStore.appendToCommitLog(masterPhyOffset, bodyData);

                        this.byteBufferRead.position(readSocketPos);
                        //记录数据处理指针
                        this.dispatchPosition += msgHeaderSize + bodySize;

                        /**
                         * 每同步一条消息，就向master及时报告当前同步进度,offset
                         */
                        if (!reportSlaveMaxOffsetPlus()) {
                            return false;
                        }

                        continue;
                    }
                }
                /**
                 * 已经读完缓冲区的所有数据
                 */
                if (!this.byteBufferRead.hasRemaining()) {
                    /**
                     *  判断有没有 没处理的数据。
                     *  可能会有不完整的消息，上面都是处理完整的消息，可能会剩下消息的一部分。
                     */
                    this.reallocateByteBuffer();
                }

                break;
            }

            return true;
        }

        /**
         * 每同步一条消息，就向master及时报告当前同步进度.
         * 判断条件：只要有没有报告的消息就去报告。
         *
         * @return
         */
        private boolean reportSlaveMaxOffsetPlus() {
            boolean result = true;
            long currentPhyOffset = HAService.this.defaultMessageStore.getMaxPhyOffset();
            if (currentPhyOffset > this.currentReportedOffset) {
                this.currentReportedOffset = currentPhyOffset;
                result = this.reportSlaveMaxOffset(this.currentReportedOffset);
                if (!result) {
                    this.closeMaster();
                    log.error("HAClient, reportSlaveMaxOffset error, " + this.currentReportedOffset);
                }
            }

            return result;
        }

        /**
         * 连接master
         * @return
         * @throws ClosedChannelException
         */
        private boolean connectMaster() throws ClosedChannelException {
            if (null == socketChannel) {
                String addr = this.masterAddress.get();
                if (addr != null) {

                    SocketAddress socketAddress = RemotingUtil.string2SocketAddress(addr);
                    if (socketAddress != null) {
                        //连接远程服务
                        this.socketChannel = RemotingUtil.connect(socketAddress);
                        if (this.socketChannel != null) {
                            //将该通道注册到selector
                            this.socketChannel.register(this.selector, SelectionKey.OP_READ);
                        }
                    }
                }
                //CommitLog文件，当前的最大offset
                this.currentReportedOffset = HAService.this.defaultMessageStore.getMaxPhyOffset();
                //最近写入时间戳
                this.lastWriteTimestamp = System.currentTimeMillis();
            }

            return this.socketChannel != null;
        }

        /**
         * 处理完这次的同步消息后，关闭这次请求
         */
        private void closeMaster() {
            if (null != this.socketChannel) {
                try {

                    SelectionKey sk = this.socketChannel.keyFor(this.selector);
                    if (sk != null) {
                        sk.cancel();
                    }

                    this.socketChannel.close();

                    this.socketChannel = null;
                } catch (IOException e) {
                    log.warn("closeMaster exception. ", e);
                }

                this.lastWriteTimestamp = 0;
                this.dispatchPosition = 0;

                this.byteBufferBackup.position(0);
                this.byteBufferBackup.limit(READ_MAX_BUFFER_SIZE);

                this.byteBufferRead.position(0);
                this.byteBufferRead.limit(READ_MAX_BUFFER_SIZE);
            }
        }

        /**
         * 每5s执行一次
         */
        @Override
        public void run() {
            log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    /**
                     * 判断是否连接到Master，如果没有则建立连接
                     */
                    if (this.connectMaster()) {
                        /**
                         * 判断是否需要向master报告已经拉取的offset
                         * 每5s报告一次
                         */
                        if (this.isTimeToReportOffset()) {
                            /**
                             * 报告 max offset
                             */
                            boolean result = this.reportSlaveMaxOffset(this.currentReportedOffset);
                            if (!result) {
                                this.closeMaster();
                            }
                        }

                        //阻塞1s
                        this.selector.select(1000);

                        /**
                         * 处理网络读请求
                         */
                        boolean ok = this.processReadEvent();

                        if (!ok) {
                            /**
                             * 处理完毕，关闭socket
                             */
                            this.closeMaster();
                        }

                        if (!reportSlaveMaxOffsetPlus()) {
                            continue;
                        }

                        long interval =
                            HAService.this.getDefaultMessageStore().getSystemClock().now()
                                - this.lastWriteTimestamp;
                        if (interval > HAService.this.getDefaultMessageStore().getMessageStoreConfig()
                            .getHaHousekeepingInterval()) {
                            log.warn("HAClient, housekeeping, found this connection[" + this.masterAddress
                                + "] expired, " + interval);
                            this.closeMaster();
                            log.warn("HAClient, master not response some time, so close connection");
                        }
                    } else {
                        this.waitForRunning(1000 * 5);
                    }
                } catch (Exception e) {
                    log.warn(this.getServiceName() + " service has exception. ", e);
                    this.waitForRunning(1000 * 5);
                }
            }

            log.info(this.getServiceName() + " service end");
        }
        // private void disableWriteFlag() {
        // if (this.socketChannel != null) {
        // SelectionKey sk = this.socketChannel.keyFor(this.selector);
        // if (sk != null) {
        // int ops = sk.interestOps();
        // ops &= ~SelectionKey.OP_WRITE;
        // sk.interestOps(ops);
        // }
        // }
        // }
        // private void enableWriteFlag() {
        // if (this.socketChannel != null) {
        // SelectionKey sk = this.socketChannel.keyFor(this.selector);
        // if (sk != null) {
        // int ops = sk.interestOps();
        // ops |= SelectionKey.OP_WRITE;
        // sk.interestOps(ops);
        // }
        // }
        // }

        @Override
        public String getServiceName() {
            return HAClient.class.getSimpleName();
        }
    }
}
