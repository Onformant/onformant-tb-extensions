package org.thingsboard.server.extensions.service.refrigeration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.blob.BlobEntity;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.relation.EntityRelation;
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
import org.thingsboard.server.dao.blob.BlobEntityService;
import org.thingsboard.server.dao.relation.RelationService;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.thingsboard.server.extensions.common.data.refrigeration.Constants.ASSET_ID;
import static org.thingsboard.server.extensions.common.data.refrigeration.Constants.CERTIFICATE;
import static org.thingsboard.server.extensions.common.data.refrigeration.Constants.FROM_ASSET_TO_FILE;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefrigerationFileService {

    private final BlobEntityService blobEntityService;
    private final RelationService relationService;

    public void uploadFile(TenantId tenantId, MultipartFile file, String assetId) throws IOException {
        AssetId ownerId = AssetId.fromString(assetId);
        BlobEntity blobEntity = createBlob(tenantId, file, ownerId);
        blobEntity = blobEntityService.saveBlobEntity(blobEntity);
        relationService.saveRelation(tenantId, new EntityRelation(ownerId, blobEntity.getId(), FROM_ASSET_TO_FILE));
    }

    private BlobEntity createBlob(TenantId tenantId, MultipartFile file, EntityId ownerId) throws IOException {
        BlobEntity blobEntity = new BlobEntity();
        blobEntity.setTenantId(tenantId);
        blobEntity.setName(file.getOriginalFilename());
        blobEntity.setOwnerId(ownerId);
        blobEntity.setContentType(file.getContentType());
        blobEntity.setType(CERTIFICATE);
        blobEntity.setData(ByteBuffer.wrap(file.getBytes()));
        blobEntity.setAdditionalInfo(JacksonUtil.newObjectNode().put(ASSET_ID, ownerId.toString()));
        return blobEntity;
    }
}
