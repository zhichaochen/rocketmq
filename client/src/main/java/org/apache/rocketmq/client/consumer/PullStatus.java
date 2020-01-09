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
package org.apache.rocketmq.client.consumer;

/**
 * 拉取状态
 */
public enum PullStatus {
    /**
     * Founded：拉取到了消息
     */
    FOUND,
    /**
     * No new message can be pull
     * 还没有生产新的消息
     */
    NO_NEW_MSG,
    /**
     * Filtering results can not match
     * 没有匹配到
     */
    NO_MATCHED_MSG,
    /**
     * Illegal offset,may be too big or too small
     * 非法的offset
     */
    OFFSET_ILLEGAL
}
