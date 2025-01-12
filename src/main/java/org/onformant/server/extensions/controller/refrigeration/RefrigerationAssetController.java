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
package org.onformant.server.extensions.controller.refrigeration;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.multipart.MultipartFile;
import org.thingsboard.server.controller.BaseController;
import org.onformant.server.extensions.common.data.refrigeration.CreateAssetRequest;
import org.onformant.server.extensions.service.refrigeration.RefrigerationAssetService;
import org.onformant.server.extensions.service.refrigeration.RefrigerationFileService;
import org.thingsboard.server.service.executors.DbCallbackExecutorService;
import jakarta.validation.constraints.NotNull;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/refrigeration")
public class RefrigerationAssetController extends BaseController {

    private final DbCallbackExecutorService executor;
    private final RefrigerationAssetService refrigerationAssetService;
    private final RefrigerationFileService refrigerationFileService;

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @PostMapping(value = "/asset")
    public DeferredResult<ResponseEntity<?>> createAsset(@RequestBody CreateAssetRequest createAssetRequest) {
        DeferredResult<ResponseEntity<?>> result = new DeferredResult<>();
        try {
            ListenableFuture<String> listListenableFuture = refrigerationAssetService.createAsset(getTenantId(), getCurrentUser(), createAssetRequest);
            addCallback(listListenableFuture, result);
        } catch (Exception e) {
            log.error("Cannot create asset", e);
            result.setResult(new ResponseEntity<>(e, HttpStatus.INTERNAL_SERVER_ERROR));
        }
        return result;
    }

    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @PostMapping(value = "/asset/upload/{assetId}")
    public ResponseEntity<?> uploadFile(@PathVariable("assetId") String assetId,
                                        @RequestPart("file") MultipartFile file) {
        try {
            refrigerationFileService.uploadFile(getTenantId(), file, assetId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Cannot upload file", e);
            return ResponseEntity.badRequest().build();
        }
    }

    private void addCallback(ListenableFuture<?> response, DeferredResult<ResponseEntity<?>> result) {
        Futures.addCallback(response, new FutureCallback<Object>() {
            @Override
            public void onSuccess(Object o) {
                result.setResult(ResponseEntity.status(HttpStatus.OK).body(o));
            }

            @Override
            public void onFailure(@NotNull Throwable throwable) {
                result.setResult(new ResponseEntity<>(throwable, HttpStatus.INTERNAL_SERVER_ERROR));
            }
        }, executor);
    }
}

