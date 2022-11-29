/*
 *  Copyright (c) 2021 Microsoft Corporation
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Microsoft Corporation - initial API and implementation
 *
 */

package org.eclipse.edc.connector.dataplane.http.pipeline;

import io.netty.handler.codec.http.HttpMethod;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.eclipse.edc.connector.dataplane.http.testfixtures.HttpTestFixtures;
import org.eclipse.edc.connector.dataplane.spi.pipeline.InputStreamDataSource;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.spi.types.domain.HttpDataAddress;
import org.eclipse.edc.spi.types.domain.transfer.DataFlowRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.connector.dataplane.http.testfixtures.HttpTestFixtures.createHttpResponse;
import static org.eclipse.edc.spi.types.domain.HttpDataAddress.HTTP_DATA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpDataSinkFactoryTest {

    private static final OkHttpClient HTTP_CLIENT = mock(OkHttpClient.class);
    private static final Monitor MONITOR = mock(Monitor.class);
    private static final ExecutorService EXECUTOR_SERVICE = mock(ExecutorService.class);

    private final HttpRequestParamsSupplier supplierMock = mock(HttpRequestParamsSupplier.class);

    private HttpDataSinkFactory factory;

    private static DataFlowRequest createRequest(DataAddress destination) {
        return DataFlowRequest.Builder.newInstance()
                .id(UUID.randomUUID().toString())
                .processId(UUID.randomUUID().toString())
                .sourceDataAddress(DataAddress.Builder.newInstance().type("test-type").build())
                .destinationDataAddress(destination)
                .build();
    }

    @BeforeEach
    void setUp() {
        factory = new HttpDataSinkFactory(HTTP_CLIENT, Executors.newFixedThreadPool(1), 5, mock(Monitor.class), supplierMock);
    }

    @Test
    void verifyCanHandle() {
        assertThat(factory.canHandle(HttpTestFixtures.createRequest(HTTP_DATA).build())).isTrue();
    }

    @Test
    void verifyCannotHandle() {
        assertThat(factory.canHandle(HttpTestFixtures.createRequest("dummy").build())).isFalse();
    }

    @Test
    void verifyValidationFailsIfSupplierThrows() {
        var errorMsg = "Test error message";
        var address = HttpDataAddress.Builder.newInstance().build();
        var request = createRequest(address);

        when(supplierMock.apply(request)).thenThrow(new EdcException(errorMsg));

        var result = factory.validate(request);
        assertThat(result.failed()).isTrue();
        assertThat(result.getFailureMessages()).hasSize(1);
        assertThat(result.getFailureMessages().get(0)).contains(errorMsg);
    }

    @Test
    void verifySuccessSinkCreation() {
        var address = HttpDataAddress.Builder.newInstance().build();
        var request = createRequest(address);
        var params = HttpRequestParams.Builder.newInstance()
                .baseUrl("http://some.base.url")
                .method(HttpMethod.POST.name())
                .contentType("application/json")
                .build();

        when(supplierMock.apply(request)).thenReturn(params);

        assertThat(factory.validate(request).succeeded()).isTrue();
        var sink = factory.createSink(request);
        assertThat(sink).isNotNull();

        var expected = HttpDataSink.Builder.newInstance()
                .params(params)
                .httpClient(HTTP_CLIENT)
                .monitor(MONITOR)
                .requestId(request.getId())
                .executorService(EXECUTOR_SERVICE)
                .build();

        // validate the generated data sink field by field using reflection
        Arrays.stream(HttpDataSink.class.getDeclaredFields()).forEach(f -> {
            f.setAccessible(true);
            try {
                assertThat(f.get(sink)).isEqualTo(f.get(expected));
            } catch (IllegalAccessException e) {
                throw new AssertionError("Comparison failed for field: " + f.getName());
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = { "POST", "POST", "PUT" })
    void verifyCreateMethodDestination(String method) throws InterruptedException, ExecutionException, IOException {
        var address = HttpDataAddress.Builder.newInstance().build();
        var request = createRequest(address);
        var params = HttpRequestParams.Builder.newInstance()
                .baseUrl("http://some.base.url")
                .method(method)
                .contentType("application/json")
                .build();

        when(supplierMock.apply(request)).thenReturn(params);

        var call = mock(Call.class);
        when(call.execute()).thenReturn(createHttpResponse().build());

        when(HTTP_CLIENT.newCall(ArgumentMatchers.isA(Request.class))).thenAnswer(r -> {
            assertThat(((Request) r.getArgument(0)).method()).isEqualTo(method);
            return call;
        });

        var sink = factory.createSink(request);

        var result = sink.transfer(new InputStreamDataSource("test", new ByteArrayInputStream("test".getBytes()))).get();

        assertThat(result.succeeded()).isTrue();

        verify(call).execute();
    }
}
