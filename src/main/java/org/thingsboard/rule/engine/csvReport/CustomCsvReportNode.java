/**
 * Copyright © 2018 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.rule.engine.csvReport;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.common.util.ListeningExecutor;
import org.thingsboard.rule.engine.api.EmptyNodeConfiguration;
import org.thingsboard.rule.engine.api.RuleNode;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNode;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.csvReport.dto.AssetAggregations;
import org.thingsboard.rule.engine.csvReport.dto.AssetInfo;
import org.thingsboard.rule.engine.csvReport.dto.CsvReportSettings;
import org.thingsboard.rule.engine.csvReport.dto.TimePeriod;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.blob.BlobEntity;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.BlobEntityId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.msg.TbMsgType;
import org.thingsboard.server.common.data.plugin.ComponentType;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.thingsboard.server.dao.asset.AssetService;
import org.thingsboard.server.dao.blob.BlobEntityService;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.dao.user.UserService;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


@Slf4j
@RuleNode(
        type = ComponentType.ACTION,
        name = "generate CSV report",
        configClazz = EmptyNodeConfiguration.class,
        nodeDescription = "Generates CSV report based on incoming data. Grouping interval is 3 hours. It generates csv" +
                "report and saves it as a blob entity. Returns user email and blobEntityId to be used for email build",
        nodeDetails = "Expects msg with the structure: " +
                "assetIds: [UUID1, UUID2..],\n" +
                "userId: UUID,\n" +
                "    zoneId : ZONE_ID,\n" +
                "    groupingInterval: DAYLY/WEEKLY/MONTHLY",
        icon = "file_upload"
)
public class CustomCsvReportNode implements TbNode {
    private static final String AGGREGATION_KEY = "TempF_DS";
    public static final long THREE_HOURS_MS = TimeUnit.HOURS.toMillis(3);
    private TenantId tenantId;
    private TimeseriesService timeseriesService;
    private ListeningExecutor dbCallbackExecutor;
    private AssetService assetService;
    private UserService userService;
    private BlobEntityService blobEntityService;
    private TbContext context;

    @Override
    public void init(TbContext ctx, TbNodeConfiguration configuration) throws TbNodeException {
        tenantId = ctx.getTenantId();
        assetService = ctx.getAssetService();
        timeseriesService = ctx.getTimeseriesService();
        dbCallbackExecutor = ctx.getDbCallbackExecutor();
        blobEntityService = ctx.getPeContext().getBlobEntityService();
        userService = ctx.getUserService();
        context = ctx;
    }

    @Override
    public void onMsg(TbContext ctx, TbMsg msg) throws ExecutionException, InterruptedException, TbNodeException {
        ctx.ack(msg);
        var settings = JacksonUtil.fromString(msg.getData(), CsvReportSettings.class);
        var timePeriod = getTimePeriod(msg.getMetaDataTs(), settings.getGroupingInterval());
        var assets = getExistingAssetsByIds(settings.getAssetIds());
        var aggregationsFuture = aggregateDataForAssets(assets, timePeriod);

        Futures.addCallback(aggregationsFuture, new FutureCallback<>() {
            @Override
            public void onSuccess(List<AssetAggregations> aggregations) {
                try {
                    Map<AssetInfo, List<TsKvEntry>> transformedAggregation = aggregations.stream()
                            .collect(Collectors.toMap(AssetAggregations::getAsset, AssetAggregations::getTsKvEntries));

                    List<Long> timeframes = buildTimeframes(timePeriod);
                    String csvReport = CsvReportBuilder.buildCsvReport(assets, transformedAggregation, timeframes,
                            ZoneId.of(settings.getZoneId()));
                    BlobEntity blobEntity = createBlobEntityFromReport(csvReport);
                    User user = userService.findUserById(tenantId, new UserId(settings.getUserId()));
                    sendMsgWithReport(msg.getOriginator(), blobEntity.getId(), user.getEmail());
                } catch (Exception e) {
                    log.error("Failed to generate report from TenantId {} using msg {}", tenantId.getId(), msg, e);
                    ctx.tellFailure(msg, e);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Failed to generate report while retriving aggregated data from TenantId {} using msg {}"
                        , tenantId.getId(), msg, t);
                ctx.tellFailure(msg, t);
            }
        }, dbCallbackExecutor);

    }

    private List<Long> buildTimeframes(TimePeriod timePeriod) {
        List<Long> timeframes = new ArrayList<>();
        timeframes.add(timePeriod.getStartTime());
        while (timeframes.get(timeframes.size() - 1) < timePeriod.getEndTime() - THREE_HOURS_MS) {
            timeframes.add(timeframes.get(timeframes.size() - 1) + THREE_HOURS_MS);
        }
        return timeframes;
    }

    private void sendMsgWithReport(EntityId originatorId, BlobEntityId blobEntityId, String userEmail) {
        TbMsgMetaData tbMsgMetaData = new TbMsgMetaData();
        tbMsgMetaData.putValue("userEmail", userEmail);
        tbMsgMetaData.putValue("attachments", blobEntityId.getId().toString());
        TbMsg tbMsg = TbMsg.newMsg(TbMsgType.NA, originatorId, tbMsgMetaData, TbMsg.EMPTY_JSON_OBJECT);
        context.tellSuccess(tbMsg);
    }

    private BlobEntity createBlobEntityFromReport(String csvReport) {
        BlobEntity blobEntity = buildBlobEntity(csvReport);
        return blobEntityService.saveBlobEntity(blobEntity);
    }

    private BlobEntity buildBlobEntity(String csvReport) {
        Charset charset = StandardCharsets.UTF_8;
        ByteBuffer byteBuffer = charset.encode(csvReport);

        BlobEntity blobEntity = new BlobEntity();
        blobEntity.setName("csv report");
        blobEntity.setTenantId(tenantId);
        blobEntity.setType("csv report");
        blobEntity.setContentType("text/csv");
        blobEntity.setData(byteBuffer);
        return blobEntity;
    }

    private ListenableFuture<List<AssetAggregations>> aggregateDataForAssets(List<AssetInfo> assets,
                                                                             TimePeriod timePeriod) {
        var queries = buildListOfQueries(timePeriod);
        return getAggregationForAssetsRecursively(assets, 0, queries, new ArrayList<>());
    }

    private ListenableFuture<List<AssetAggregations>> getAggregationForAssetsRecursively(List<AssetInfo> assets, int index,
                                                                                         List<ReadTsKvQuery> queries,
                                                                                         List<AssetAggregations> assetAggregations) {
        if (index >= assets.size()) {
            return Futures.immediateFuture(assetAggregations);
        }

        AssetInfo asset = assets.get(index);
        ListenableFuture<List<TsKvEntry>> aggregationFuture = timeseriesService.findAll(tenantId, asset.getId(), queries);
        return Futures.transformAsync(aggregationFuture,
                tsKvEntries -> {
                    assetAggregations.add(new AssetAggregations(asset, tsKvEntries));
                    return getAggregationForAssetsRecursively(assets, index + 1, queries, assetAggregations);
                }, dbCallbackExecutor);
    }

    private List<ReadTsKvQuery> buildListOfQueries(TimePeriod timePeriod) {
        long startTs = timePeriod.getStartTime();
        long endTs = timePeriod.getEndTime();
        return List.of(
                buildTsKvQuery(startTs, endTs, Aggregation.MAX),
                buildTsKvQuery(startTs, endTs, Aggregation.MIN),
                buildTsKvQuery(startTs, endTs, Aggregation.AVG));
    }

    private List<AssetInfo> getExistingAssetsByIds(List<UUID> assetIds) {
        List<AssetInfo> foundAssets = new ArrayList<>();
        for (UUID assetId : assetIds) {
            Asset asset = assetService.findAssetById(tenantId, new AssetId(assetId));
            if (asset != null) {
                foundAssets.add(new AssetInfo(asset.getId(), asset.getName()));
            }
        }
        return foundAssets;
    }

    private BaseReadTsKvQuery buildTsKvQuery(long startTs, long endTs, Aggregation max) {
        return new BaseReadTsKvQuery(AGGREGATION_KEY, startTs, endTs, THREE_HOURS_MS, Integer.MAX_VALUE, max, "ASC");
    }

    private TimePeriod getTimePeriod(long ts, GroupingInterval groupingInterval) {
        long endTs = roundToHour(ts);
        long startTs = endTs - groupingInterval.getInterval();
        return new TimePeriod(startTs, endTs);
    }


    private long roundToHour(long timestamp) {
        Instant instant = Instant.ofEpochMilli(timestamp);
        Instant roundedToHour = instant.truncatedTo(ChronoUnit.HOURS);
        return roundedToHour.toEpochMilli();
    }

}
