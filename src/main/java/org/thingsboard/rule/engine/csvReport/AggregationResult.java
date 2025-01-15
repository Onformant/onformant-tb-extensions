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

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.data.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Data
@Slf4j
@NoArgsConstructor
public class AggregationResult {
    private String temperatureMin = StringUtils.EMPTY;
    private String temperatureMax = StringUtils.EMPTY;
    private String temperatureAvg = StringUtils.EMPTY;

    public List<String> buildCsvResult() {
        List<String> csvResult = new ArrayList<>();
        csvResult.add(temperatureMin);
        csvResult.add(temperatureMax);
        csvResult.add(temperatureAvg);
        return csvResult;
    }

    public AggregationResult(List<Double> aggregationValues) {
        if (aggregationValues.size() != 3) {
            log.error("aggregationValues must contain 3 elements. Default aggregation result is taken instead");
            return;
        }
        Double min = Collections.min(aggregationValues);
        Double max = Collections.max(aggregationValues);
        Double avg;
        if (Objects.equals(min, max)) {
            avg = min;
        } else {
            avg = aggregationValues.stream().filter(value -> value < max && value > min).findFirst().orElse(min);
        }
        temperatureMin = min.toString();
        temperatureMax = max.toString();
        temperatureAvg = avg.toString();
    }

}

