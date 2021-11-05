/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.replication.logical.seqno;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.index.seqno.RetentionLeaseActions;
import org.elasticsearch.index.seqno.RetentionLeaseAlreadyExistsException;
import org.elasticsearch.index.shard.ShardId;

/*
 * Derived from org.opensearch.replication.seqno.RemoteClusterRetentionLeaseHelper
 */
public class RetentionLeaseHelper {

    private static final Logger LOGGER = Loggers.getLogger(RetentionLeaseHelper.class);

    private static String retentionLeaseSource(String subscriberClusterName) {
        return "logical_replication:" + subscriberClusterName;
    }

    private static String retentionLeaseIdForShard(String subscriberClusterName, ShardId subscriberShardId) {
        var retentionLeaseSource = retentionLeaseSource(subscriberClusterName);
        return retentionLeaseSource + ":" + subscriberShardId;
    }


    public static void addRetentionLease(ShardId publisherShardId,
                                  ShardId subscriberShardId,
                                  long seqNo,
                                  String subscriberClusterName,
                                  Client client,
                                  ActionListener<RetentionLeaseActions.Response> listener) {
        var retentionLeaseId = retentionLeaseIdForShard(subscriberClusterName, subscriberShardId);
        var request = new RetentionLeaseActions.AddOrRenewRequest(
            publisherShardId,
            retentionLeaseId,
            seqNo,
            retentionLeaseSource(subscriberClusterName)
        );
        client.execute(
            RetentionLeaseActions.Add.INSTANCE,
            request,
            ActionListener.wrap(
                listener::onResponse,
                e -> {
                    if (e instanceof RetentionLeaseAlreadyExistsException) {
                        LOGGER.error(e.getMessage());
                        LOGGER.info("Renew retention lease as it already exists {} with {}", retentionLeaseId, seqNo);
                        // Only one retention lease should exists for the follower shard
                        // Ideally, this should have got cleaned-up
                        renewRetentionLease(publisherShardId, subscriberShardId, seqNo, subscriberClusterName, client, listener);
                    } else {
                        listener.onFailure(e);
                    }
                }
            )
        );
    }

    public static void renewRetentionLease(ShardId publisherShardId,
                                           ShardId subscriberShardId,
                                           long seqNo,
                                           String subscriberClusterName,
                                           Client client,
                                           ActionListener<RetentionLeaseActions.Response> listener) {
        var retentionLeaseId = retentionLeaseIdForShard(subscriberClusterName, subscriberShardId);
        var request = new RetentionLeaseActions.AddOrRenewRequest(
            publisherShardId, retentionLeaseId, seqNo, retentionLeaseSource(subscriberClusterName));
        client.execute(RetentionLeaseActions.Renew.INSTANCE, request, listener);
    }

    public static void attemptRetentionLeaseRemoval(ShardId publisherShardId,
                                                    ShardId subscriberShardId,
                                                    String subscriberClusterName,
                                                    Client client,
                                                    ActionListener<RetentionLeaseActions.Response> listener) {
        var retentionLeaseId = retentionLeaseIdForShard(subscriberClusterName, subscriberShardId);
        var request = new RetentionLeaseActions.RemoveRequest(publisherShardId, retentionLeaseId);
        client.execute(
            RetentionLeaseActions.Remove.INSTANCE,
            request,
            ActionListener.wrap(
                response -> {
                    LOGGER.info("Removed retention lease with id - {}", retentionLeaseId);
                    listener.onResponse(response);
                },
                e -> {
                    LOGGER.error("Exception in removing retention lease", e);
                    listener.onFailure(e);
                }
            )
        );
    }
}