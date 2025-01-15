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

import com.opencsv.CSVWriter;
import org.thingsboard.rule.engine.csvReport.dto.AssetInfo;
import org.thingsboard.server.common.data.kv.TsKvEntry;

import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.thingsboard.rule.engine.csvReport.CustomCsvReportNode.THREE_HOURS_MS;

public class CsvReportBuilder {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String buildCsvReport(List<AssetInfo> assets, Map<AssetInfo, List<TsKvEntry>> aggregations,
                                        List<Long> timeframes, ZoneId zoneId) {
        List<String> assetNames = assets.stream().map(AssetInfo::getName).collect(Collectors.toList());
        StringWriter stringWriter = new StringWriter();
        CSVWriter csvWriter = new CSVWriter(stringWriter);

        String[] headers = buildHeaders(assetNames);
        csvWriter.writeNext(headers);

        for (Long timeframe : timeframes) {
            List<String> row = new ArrayList<>();
            row.add(formatTimestampWithZone(timeframe, zoneId));
            for (AssetInfo asset : assets) {
                AggregationResult aggregationResult = buildAggregationResult(timeframe, aggregations.get(asset));
                row.addAll(aggregationResult.buildCsvResult());
            }
            csvWriter.writeNext(toArray(row));
        }

        return stringWriter.toString();
    }

    private static AggregationResult buildAggregationResult(Long timeframe, List<TsKvEntry> aggregations) {
        if (aggregations == null || aggregations.isEmpty()) {
            return new AggregationResult();
        }
        List<Double> allAggregationsForTimeframe = aggregations
                .stream()
                .filter(aggregation ->
                        timeframeEqualsToAggregationTs(timeframe, aggregation.getTs())
                                && aggregation.getValueAsString() != null)
                .map(aggregation -> {
                    try {
                        return Double.parseDouble(aggregation.getValueAsString());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (allAggregationsForTimeframe.isEmpty()) {
            return new AggregationResult();
        }
        return new AggregationResult(allAggregationsForTimeframe);
    }

    /*    shift is used as aggregation is done differently on FE(dashboard) and BE. If there is interval 15:00 - 18:00,
     then for some magical reason timeframe for it will be 16:30 (FE) and not 15:00(BE). */
    private static boolean timeframeEqualsToAggregationTs(Long timeframe, long ts) {
        long shift = THREE_HOURS_MS / 2;
        return ts == timeframe + shift;
    }

    private static String[] buildHeaders(List<String> assetNames) {
        String[] headers = new String[1 + assetNames.size() * 3];
        headers[0] = "Time";
        int index = 1;
        for (var assetName : assetNames) {
            headers[index++] = String.format("%s Min", assetName);
            headers[index++] = String.format("%s Max", assetName);
            headers[index++] = String.format("%s Avg", assetName);
        }
        return headers;
    }

    private static String formatTimestampWithZone(long timestamp, ZoneId zoneId) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), zoneId);
        return dateTime.format(formatter);
    }

    private static String[] toArray(List<String> row) {
        return row.toArray(new String[0]);
    }

}
