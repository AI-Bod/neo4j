/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.bolt.protocol.v40.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.neo4j.bolt.dbapi.impl.BoltKernelDatabaseManagementServiceProvider;
import org.neo4j.bolt.dbapi.impl.BoltKernelGraphDatabaseServiceProvider;
import org.neo4j.bolt.messaging.BoltIOException;
import org.neo4j.bolt.protocol.common.connector.connection.Connection;
import org.neo4j.bolt.protocol.common.transaction.TransactionStateMachineSPI;
import org.neo4j.bolt.protocol.common.transaction.TransactionStateMachineSPIProvider;
import org.neo4j.bolt.protocol.common.transaction.statement.StatementProcessorReleaseManager;
import org.neo4j.bolt.runtime.BoltProtocolBreachFatality;
import org.neo4j.bolt.testing.mock.ConnectionMockFactory;
import org.neo4j.common.DependencyResolver;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.api.DatabaseNotFoundException;
import org.neo4j.kernel.GraphDatabaseQueryService;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.database.Database;
import org.neo4j.kernel.database.DatabaseIdFactory;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.monitoring.Monitors;
import org.neo4j.time.SystemNanoClock;

class TransactionStateMachineSPIProviderV4Test {

    @Test
    void shouldReturnTransactionStateMachineSPIIfDatabaseExists() throws Throwable {
        String databaseName = "database";
        String txId = "123";

        var connection = ConnectionMockFactory.newInstance();

        DatabaseManagementService managementService = managementService(databaseName);
        TransactionStateMachineSPIProvider spiProvider = newSpiProvider(managementService, connection);

        TransactionStateMachineSPI spi = spiProvider.getTransactionStateMachineSPI(
                databaseName, mock(StatementProcessorReleaseManager.class), txId);
        assertThat(spi).isInstanceOf(TransactionStateMachineV4SPI.class);
    }

    @Test
    void shouldReturnDefaultTransactionStateMachineSPIWithEmptyDatabasename() throws Throwable {
        String databaseName = "neo4j";
        String txId = "123";

        var connection = ConnectionMockFactory.newFactory()
                .withSelectedDefaultDatabase("neo4j")
                .build();

        DatabaseManagementService managementService = managementService(databaseName);
        TransactionStateMachineSPIProvider spiProvider = newSpiProvider(managementService, connection);

        TransactionStateMachineSPI spi = spiProvider.getTransactionStateMachineSPI(
                "", mock(StatementProcessorReleaseManager.class, RETURNS_MOCKS), txId);

        assertThat(spi).isInstanceOf(TransactionStateMachineV4SPI.class);
    }

    @Test
    void shouldErrorIfDatabaseNotFound() {
        DatabaseManagementService managementService = mock(DatabaseManagementService.class);
        var databaseName = "database";
        String txId = "123";

        var connection = ConnectionMockFactory.newInstance();

        when(managementService.database(databaseName)).thenThrow(new DatabaseNotFoundException(databaseName));
        TransactionStateMachineSPIProvider spiProvider = newSpiProvider(managementService, connection);

        BoltIOException error = assertThrows(
                BoltIOException.class,
                () -> spiProvider.getTransactionStateMachineSPI(
                        databaseName, mock(StatementProcessorReleaseManager.class), txId));
        assertThat(error.status()).isEqualTo(Status.Database.DatabaseNotFound);
        assertThat(error.getMessage()).contains("Database does not exist. Database name: 'database'.");
    }

    @Test
    void shouldAllocateMemoryForTransactionStateMachineSPI() throws BoltProtocolBreachFatality, BoltIOException {
        String databaseName = "neo4j";
        String txId = "123";

        var clock = mock(SystemNanoClock.class);
        var scopedMemoryTracker = mock(MemoryTracker.class, RETURNS_MOCKS);
        var memoryTracker = mock(MemoryTracker.class);
        when(memoryTracker.getScopedMemoryTracker()).thenReturn(scopedMemoryTracker);

        var connection = ConnectionMockFactory.newFactory()
                .withSelectedDefaultDatabase("neo4j")
                .withMemoryTracker(memoryTracker)
                .build();

        DatabaseManagementService managementService = managementService(databaseName);

        var dbProvider = new BoltKernelDatabaseManagementServiceProvider(
                managementService, new Monitors(), clock, Duration.ZERO);
        var spiProvider = new TransactionStateMachineSPIProviderV4(dbProvider, connection, clock);

        spiProvider.getTransactionStateMachineSPI("", mock(StatementProcessorReleaseManager.class), txId);

        ArgumentCaptor<Long> allocations = ArgumentCaptor.forClass(Long.class);

        verify(memoryTracker).getScopedMemoryTracker();
        verify(scopedMemoryTracker, times(2)).allocateHeap(allocations.capture());
        assertEquals(
                List.of(TransactionStateMachineV4SPI.SHALLOW_SIZE, BoltKernelGraphDatabaseServiceProvider.SHALLOW_SIZE),
                allocations.getAllValues());
        verify(scopedMemoryTracker).getScopedMemoryTracker();
        verifyNoMoreInteractions(memoryTracker);
        verifyNoMoreInteractions(scopedMemoryTracker);
    }

    private static DatabaseManagementService managementService(String databaseName) {
        DatabaseManagementService managementService = mock(DatabaseManagementService.class);
        GraphDatabaseFacade databaseFacade = mock(GraphDatabaseFacade.class);
        final DependencyResolver dependencyResolver = mock(DependencyResolver.class);
        GraphDatabaseQueryService queryService = mock(GraphDatabaseQueryService.class);
        var databaseId = DatabaseIdFactory.from(databaseName, UUID.randomUUID());

        when(databaseFacade.databaseId()).thenReturn(databaseId);
        when(databaseFacade.isAvailable()).thenReturn(true);
        when(managementService.database(databaseName)).thenReturn(databaseFacade);
        when(databaseFacade.getDependencyResolver()).thenReturn(dependencyResolver);
        when(dependencyResolver.resolveDependency(GraphDatabaseQueryService.class))
                .thenReturn(queryService);
        when(dependencyResolver.resolveDependency(Database.class)).thenReturn(mock(Database.class));
        when(queryService.getDependencyResolver()).thenReturn(dependencyResolver);

        return managementService;
    }

    private TransactionStateMachineSPIProvider newSpiProvider(
            DatabaseManagementService managementService, Connection connection) {
        var clock = mock(SystemNanoClock.class);
        var dbProvider = new BoltKernelDatabaseManagementServiceProvider(
                managementService, new Monitors(), clock, Duration.ZERO);
        return new TransactionStateMachineSPIProviderV4(dbProvider, connection, clock);
    }
}
