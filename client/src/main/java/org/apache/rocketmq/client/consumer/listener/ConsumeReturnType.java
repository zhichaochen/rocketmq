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

package org.apache.rocketmq.client.consumer.listener;

/**
 * 消费消息，返回类型
 */
public enum ConsumeReturnType {
    /**
     * consume return success：成功
     */
    SUCCESS,
    /**
     * consume timeout ,even if success：超时
     */
    TIME_OUT,
    /**
     * consume throw exception：发生异常
     */
    EXCEPTION,
    /**
     * consume return null：业务代码消费消息后返回null
     */
    RETURNNULL,
    /**
     * consume return failed ：消费失败
     */
    FAILED
}
