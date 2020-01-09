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
package org.apache.rocketmq.common;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;

/**
 * 线程的抽象类，提供了控制线程的公用方法。
 */
public abstract class ServiceThread implements Runnable {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.COMMON_LOGGER_NAME);

    //join(time) : join线程阻塞多长时间？
    private static final long JOIN_TIME = 90 * 1000;

    private Thread thread;
    /**
     * 等待的
     */
    protected final CountDownLatch2 waitPoint = new CountDownLatch2(1);
    /**
     * 该变量判断同步线程是否【正在工作】：
     * hasNotified：true ：通知线程开始工作。
     * hasNotified：false ：通知线程休息10毫秒
     */
    protected volatile AtomicBoolean hasNotified = new AtomicBoolean(false);

    //线程是否停止
    protected volatile boolean stopped = false;

    //是否守护线程
    protected boolean isDaemon = false;

    /**
     * 我的疑问？什么时候使用volatile，什么时候使用AtomicBoolean
     *
     * 还有AtomicBooleanFieldUpdater
     */

    //Make it able to restart the thread
    /**
     * 线程是否已启动
     * true：表示线程已经启动，调用了start方法
     * false：表示线程已经关闭，调用了shutdown方法
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    public ServiceThread() {

    }

    public abstract String getServiceName();

    /**
     * 启动线程
     */
    public void start() {
        log.info("Try to start service thread:{} started:{} lastThread:{}", getServiceName(), started.get(), thread);
        if (!started.compareAndSet(false, true)) {
            return;
        }
        stopped = false;
        //创建线程，其中param1：线程，线程名
        this.thread = new Thread(this, getServiceName());
        this.thread.setDaemon(isDaemon);
        this.thread.start();
    }

    /**
     * 关闭
     */
    public void shutdown() {
        this.shutdown(false);
    }

    /**
     * 关闭线程
     *
     * interrupt ：是否打断正在运行的线程
     * true：设置打断标志位，同时打断正在运行的线程。
     * false：仅仅设置stop标志，等线程自然结束。
     *
     * @param interrupt ：
     */
    public void shutdown(final boolean interrupt) {
        log.info("Try to shutdown service thread:{} started:{} lastThread:{}", getServiceName(), started.get(), thread);
        if (!started.compareAndSet(true, false)) {
            return;
        }
        //设置停止标志
        this.stopped = true;
        log.info("shutdown thread " + this.getServiceName() + " interrupt " + interrupt);

        if (hasNotified.compareAndSet(false, true)) {
            waitPoint.countDown(); // notify
        }

        try {
            if (interrupt) {
                //打断当前线程
                this.thread.interrupt();
            }
            long beginTime = System.currentTimeMillis();
            /**
             * 如果不是守护线程，则使用join打断当前线程。
             */
            if (!this.thread.isDaemon()) {
                this.thread.join(this.getJointime());
            }
            //打断所用时长
            long elapsedTime = System.currentTimeMillis() - beginTime;
            log.info("join thread " + this.getServiceName() + " elapsed time(ms) " + elapsedTime + " "
                + this.getJointime());
        } catch (InterruptedException e) {
            log.error("Interrupted", e);
        }
    }

    public long getJointime() {
        return JOIN_TIME;
    }

    @Deprecated
    public void stop() {
        this.stop(false);
    }

    @Deprecated
    public void stop(final boolean interrupt) {
        if (!started.get()) {
            return;
        }
        this.stopped = true;
        log.info("stop thread " + this.getServiceName() + " interrupt " + interrupt);

        if (hasNotified.compareAndSet(false, true)) {
            waitPoint.countDown(); // notify
        }

        if (interrupt) {
            this.thread.interrupt();
        }
    }

    public void makeStop() {
        if (!started.get()) {
            return;
        }
        this.stopped = true;
        log.info("makestop thread " + this.getServiceName());
    }

    public void wakeup() {
        if (hasNotified.compareAndSet(false, true)) {
            waitPoint.countDown(); // notify
        }
    }

    /**
     * 让正在运行的线程休息一会儿。
     *
     * @param interval
     */
    protected void waitForRunning(long interval) {
        /**
         * 设置通知标志 hasNotified = false。
         */
        if (hasNotified.compareAndSet(true, false)) {
            this.onWaitEnd();
            return;
        }

        //entry to wait
        waitPoint.reset();

        try {
            //休息
            waitPoint.await(interval, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            log.error("Interrupted", e);
        } finally {
            hasNotified.set(false);
            this.onWaitEnd();
        }
    }

    /**
     * 当等待结束之后，由线程自己实现
     */
    protected void onWaitEnd() {
    }

    /**
     * 判断实现是否停止
     * @return
     */
    public boolean isStopped() {
        return stopped;
    }

    public boolean isDaemon() {
        return isDaemon;
    }

    public void setDaemon(boolean daemon) {
        isDaemon = daemon;
    }
}
