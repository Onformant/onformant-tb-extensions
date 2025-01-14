/**
 * Copyright © 2025 The Onformant Authors
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
package org.thingsboard.server.extensions.service.refrigeration;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.AttributeScope;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.asset.AssetProfile;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.DoubleDataEntry;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.msg.TbMsgType;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.thingsboard.server.dao.asset.AssetProfileService;
import org.thingsboard.server.dao.asset.AssetService;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.relation.RelationService;

import org.thingsboard.server.service.executors.DbCallbackExecutorService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.thingsboard.server.extensions.common.data.refrigeration.Constants.*;
import org.thingsboard.server.extensions.common.data.refrigeration.CreateAssetRequest;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefrigerationAssetService {
    private final AssetService assetService;
    private final DeviceService deviceService;
    private final AttributesService attributesService;
    private final AssetProfileService assetProfileService;
    private final RelationService relationService;
    private final TbClusterService tbClusterService;
    private final DbCallbackExecutorService executor;

    public ListenableFuture<String> createAsset(TenantId tenantId, User user, CreateAssetRequest createAssetRequest) {
        Device device = createDeviceEntity(tenantId, createAssetRequest.getDevUiKey());
        AssetProfile assetProfile = assetProfileService.findOrCreateAssetProfile(tenantId, createAssetRequest.getAssetProfileName());
        Asset asset = createAssetEntity(tenantId, user.getCustomerId(), createAssetRequest, assetProfile);
        handleRelations(tenantId, createAssetRequest, asset, device);
        sendEntityCreatedMsg(tenantId, asset.getId());
        return Futures.transformAsync(saveAttributes(tenantId, createAssetRequest, asset.getId()), attr -> {
            ListenableFuture<List<Long>> attrFuture = saveAttributes(tenantId, createAssetRequest, device.getId());
            return Futures.transform(attrFuture, res -> asset.getId().toString(), executor);
        }, executor);
    }

    private void handleRelations(TenantId tenantId, CreateAssetRequest createAssetRequest, Asset asset, Device device) {
        if (createAssetRequest.getId() != null) {
            relationService.findByFromAndType(tenantId, asset.getId(), FROM_ASSET_TO_DEVICE, RelationTypeGroup.COMMON).forEach(r -> relationService.deleteRelation(tenantId, r));
        }
        relationService.saveRelations(tenantId, List.of(new EntityRelation(asset.getId(), device.getId(), FROM_ASSET_TO_DEVICE),
                new EntityRelation(AssetId.fromString(createAssetRequest.getLocationId()), asset.getId(), FROM_LOCATION_TO_ASSET)));
    }

    private ListenableFuture<List<Long>> saveAttributes(TenantId tenantId, CreateAssetRequest createAssetRequest, EntityId entityId) {
        long lastUpdateTs = System.currentTimeMillis();
        ListenableFuture<List<Long>> saveDevUIFuture = attributesService.save(tenantId, entityId, AttributeScope.SERVER_SCOPE, List.of(
                new BaseAttributeKvEntry(new StringDataEntry(DEV_UI, createAssetRequest.getDevUiKey()), lastUpdateTs),
                new BaseAttributeKvEntry(new StringDataEntry(ASSET_PROFILE, createAssetRequest.getAssetProfileName()), lastUpdateTs)
        ));
        if (!createAssetRequest.getAssetProfileName().equalsIgnoreCase(POWER_METER)) {
            return Futures.transformAsync(saveDevUIFuture, result -> {
                        List<AttributeKvEntry> attributes = List.of(
                                new BaseAttributeKvEntry(new DoubleDataEntry(MIN_PRODUCT_TEMPERATURE, getValue(createAssetRequest.getMinProductTemperature(), -1)), lastUpdateTs),
                                new BaseAttributeKvEntry(new DoubleDataEntry(MAX_PRODUCT_TEMPERATURE, getValue(createAssetRequest.getMaxProductTemperature(), 1)), lastUpdateTs),
                                new BaseAttributeKvEntry(new DoubleDataEntry(MIN_AIR_TEMPERATURE, getValue(createAssetRequest.getMinAirTemperature(), 1)), lastUpdateTs),
                                new BaseAttributeKvEntry(new DoubleDataEntry(MAX_AIR_TEMPERATURE, getValue(createAssetRequest.getMaxAirTemperature(), 1)), lastUpdateTs),
                                new BaseAttributeKvEntry(new DoubleDataEntry(MAX_HUMIDITY, getValue(createAssetRequest.getMaxHumidity(), 1)), lastUpdateTs),
                                new BaseAttributeKvEntry(new DoubleDataEntry(MIN_HUMIDITY, getValue(createAssetRequest.getMinHumidity(), 1)), lastUpdateTs));
                        ListenableFuture<List<Long>> saveFuture = attributesService.save(tenantId, entityId, AttributeScope.SERVER_SCOPE, attributes);
                        sendAttributesUpdateMsg(tenantId, entityId, attributes);
                        return saveFuture;
                    }
            ,executor);
        }
        return saveDevUIFuture;
    }

    private Device createDeviceEntity(TenantId tenantId, String name) {
        Device device = deviceService.findDeviceByTenantIdAndName(tenantId, name);
        if (device == null) {
            throw new RuntimeException("Device with name " + name + " not found");
        }
        return device;
    }

    private Asset createAssetEntity(TenantId tenantId, CustomerId customerId, CreateAssetRequest createAssetRequest, AssetProfile assetProfile) {
        Asset asset;
        String id = createAssetRequest.getId();
        if (id == null) {
            asset = new Asset();
        } else {
            asset = assetService.findAssetById(tenantId, AssetId.fromString(id));
        }
        asset.setName(createAssetRequest.getName());
        asset.setTenantId(tenantId);
        asset.setAssetProfileId(assetProfile.getId());
        asset.setOwnerId(customerId);
        return assetService.saveAsset(asset);
    }

    public void sendEntityCreatedMsg(TenantId tenantId, EntityId entityId) {
        Map<String, String> metaData = new HashMap<>();
        TbMsg msg = TbMsg.newMsg(TbMsgType.ENTITY_CREATED, entityId, new TbMsgMetaData(metaData), "{}");
        tbClusterService.pushMsgToRuleEngine(tenantId, tenantId, msg, null);
    }

    public void sendAttributesUpdateMsg(TenantId tenantId, EntityId entityId, List<AttributeKvEntry> attributes) {
        Map<String, String> collect = attributes.stream().collect(Collectors.toMap(KvEntry::getKey, KvEntry::getValueAsString));
        Map<String, String> metaData = new HashMap<>();
        TbMsg msg = TbMsg.newMsg(TbMsgType.ATTRIBUTES_UPDATED, entityId, new TbMsgMetaData(metaData), JacksonUtil.toString(collect));
        tbClusterService.pushMsgToRuleEngine(tenantId, tenantId, msg, null);
    }

    private Double getValue(String value, int sign) {
        return value == null ? sign * 1000.0 : Double.parseDouble(value);
    }
}
