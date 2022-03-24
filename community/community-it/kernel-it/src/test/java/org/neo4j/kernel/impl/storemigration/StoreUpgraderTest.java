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
package org.neo4j.kernel.impl.storemigration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.neo4j.collection.Dependencies;
import org.neo4j.common.ProgressReporter;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.function.Suppliers;
import org.neo4j.internal.batchimport.BatchImporterFactory;
import org.neo4j.internal.batchimport.IndexImporterFactory;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.layout.Neo4jLayout;
import org.neo4j.io.layout.recordstorage.RecordDatabaseLayout;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.io.pagecache.tracing.DefaultPageCacheTracer;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.kernel.api.impl.index.SchemaIndexMigrator;
import org.neo4j.kernel.api.index.IndexProvider;
import org.neo4j.kernel.database.DatabaseTracers;
import org.neo4j.kernel.impl.store.format.RecordFormats;
import org.neo4j.kernel.impl.store.format.aligned.PageAligned;
import org.neo4j.kernel.impl.store.format.standard.Standard;
import org.neo4j.kernel.impl.store.format.standard.StandardV4_3;
import org.neo4j.kernel.impl.storemigration.StoreUpgrader.UnableToUpgradeException;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.LogTailMetadata;
import org.neo4j.kernel.impl.transaction.log.files.LogFiles;
import org.neo4j.kernel.impl.transaction.log.files.LogFilesBuilder;
import org.neo4j.kernel.impl.transaction.log.files.LogTailInformation;
import org.neo4j.kernel.recovery.LogTailExtractor;
import org.neo4j.logging.AssertableLogProvider;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.logging.internal.NullLogService;
import org.neo4j.monitoring.Monitors;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.storageengine.api.StorageEngineFactory;
import org.neo4j.storageengine.api.StoreVersion;
import org.neo4j.storageengine.api.StoreVersionCheck;
import org.neo4j.storageengine.migration.AbstractStoreMigrationParticipant;
import org.neo4j.storageengine.migration.MigrationProgressMonitor;
import org.neo4j.storageengine.migration.StoreMigrationParticipant;
import org.neo4j.storageengine.migration.UpgradeNotAllowedException;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.Neo4jLayoutExtension;
import org.neo4j.test.extension.pagecache.PageCacheExtension;
import org.neo4j.test.scheduler.ThreadPoolJobScheduler;
import org.neo4j.test.utils.TestDirectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.neo4j.configuration.GraphDatabaseSettings.default_database;
import static org.neo4j.configuration.GraphDatabaseSettings.neo4j_home;
import static org.neo4j.io.pagecache.context.EmptyVersionContextSupplier.EMPTY;
import static org.neo4j.io.pagecache.tracing.PageCacheTracer.NULL;
import static org.neo4j.kernel.impl.storemigration.MigrationTestUtils.verifyFilesHaveSameContent;
import static org.neo4j.logging.LogAssertions.assertThat;
import static org.neo4j.memory.EmptyMemoryTracker.INSTANCE;
import static org.neo4j.storageengine.migration.MigrationProgressMonitor.SILENT;
import static org.neo4j.storageengine.migration.StoreMigrationParticipant.NOT_PARTICIPATING;

@PageCacheExtension
@Neo4jLayoutExtension
@Disabled
public class StoreUpgraderTest
{
    private static final String INTERNAL_LOG_FILE = "debug.log";
    private static final CursorContextFactory CONTEXT_FACTORY = new CursorContextFactory( PageCacheTracer.NULL, EMPTY );

    @Inject
    private TestDirectory testDirectory;
    @Inject
    private Neo4jLayout neo4jLayout;
    @Inject
    private PageCache pageCache;
    @Inject
    private FileSystemAbstraction fileSystem;

    private RecordDatabaseLayout databaseLayout;
    private JobScheduler jobScheduler;

    private final Config allowMigrateConfig = Config.newBuilder().set( GraphDatabaseSettings.allow_upgrade, true )
                                                                 .set( GraphDatabaseSettings.record_format, PageAligned.LATEST_NAME ).build();
    private Path prepareDatabaseDirectory;

    private static Collection<Arguments> versions()
    {
        return Collections.singletonList( arguments( StandardV4_3.RECORD_FORMATS ) );
    }

    @BeforeEach
    void prepareDb()
    {
        jobScheduler = new ThreadPoolJobScheduler();
    }

    @AfterEach
    void tearDown() throws Exception
    {
        jobScheduler.close();
    }

    private void init( RecordFormats formats ) throws IOException
    {
        String version = formats.storeVersion();
        databaseLayout = RecordDatabaseLayout.of( neo4jLayout, "db-" + version );
        prepareDatabaseDirectory = testDirectory.directory( "prepare_" + version );
        prepareSampleDatabase( version, fileSystem, databaseLayout, prepareDatabaseDirectory );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void forbidRegistrationOfParticipantsWithSameName( RecordFormats formats ) throws IOException
    {
        init( formats );
        StoreVersionCheck check = getVersionCheck( pageCache );
        StoreUpgrader upgrader = newUpgrader( check, allowMigrateConfig, pageCache );
        upgrader.addParticipant( new EmptyNamedMigrationParticipant( "foo" ) );
        assertThrows( IllegalStateException.class, () -> upgrader.addParticipant( new EmptyNamedMigrationParticipant( "foo" ) ) );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void shouldHaltUpgradeIfUpgradeConfigurationVetoesTheProcess( RecordFormats formats ) throws IOException
    {
        init( formats );
        Config deniedMigrationConfig = Config.newBuilder()
                .set( GraphDatabaseSettings.allow_upgrade, false )
                .set( GraphDatabaseSettings.record_format, Standard.LATEST_NAME )
                .build();
        StoreVersionCheck check = getVersionCheck( pageCache );

        assertThrows( UpgradeNotAllowedException.class, () -> newUpgrader( check, deniedMigrationConfig, pageCache ).migrateIfNeeded( databaseLayout ) );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void shouldRefuseToUpgradeIfAnyOfTheStoresWereNotShutDownCleanly( RecordFormats formats ) throws IOException
    {
        init( formats );
        Path comparisonDirectory = testDirectory.directory(
            "shouldRefuseToUpgradeIfAnyOfTheStoresWereNotShutDownCleanly-comparison" );
        removeCheckPointFromTxLog( fileSystem, databaseLayout );
        fileSystem.deleteRecursively( comparisonDirectory );
        fileSystem.copyRecursively( databaseLayout.databaseDirectory(), comparisonDirectory );
        StoreVersionCheck check = getVersionCheck( pageCache );

        assertThrows( StoreUpgrader.UnableToUpgradeException.class, () -> newUpgrader( check, pageCache ).migrateIfNeeded( databaseLayout ) );
        verifyFilesHaveSameContent( fileSystem, comparisonDirectory, databaseLayout.databaseDirectory() );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void shouldRefuseToUpgradeIfAllOfTheStoresWereNotShutDownCleanly( RecordFormats formats ) throws IOException
    {
        init( formats );
        Path comparisonDirectory = testDirectory.directory(
            "shouldRefuseToUpgradeIfAllOfTheStoresWereNotShutDownCleanly-comparison" );
        removeCheckPointFromTxLog( fileSystem, databaseLayout );
        fileSystem.deleteRecursively( comparisonDirectory );
        fileSystem.copyRecursively( databaseLayout.databaseDirectory(), comparisonDirectory );
        StoreVersionCheck check = getVersionCheck( pageCache );

        assertThrows( StoreUpgrader.UnableToUpgradeException.class, () -> newUpgrader( check, pageCache ).migrateIfNeeded( databaseLayout ) );
        verifyFilesHaveSameContent( fileSystem, comparisonDirectory, databaseLayout.databaseDirectory() );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void shouldContinueMovingFilesIfUpgradeCancelledWhileMoving( RecordFormats formats ) throws Exception
    {
        init( formats );
        StoreVersionCheck check = getVersionCheck( pageCache );

        String versionToMigrateTo = check.configuredVersion();
        StoreVersionCheck.Result upgradeResult = check.checkUpgrade( check.configuredVersion(), CursorContext.NULL_CONTEXT );
        assertTrue( upgradeResult.outcome().isSuccessful() );
        String versionToMigrateFrom = upgradeResult.actualVersion();

        // GIVEN
        {
            StoreUpgrader upgrader = newUpgrader( check, allowMigrateConfig, pageCache );
            String failureMessage = "Just failing";
            upgrader.addParticipant( participantThatWillFailWhenMoving( failureMessage ) );

            // WHEN
            var e = assertThrows( UnableToUpgradeException.class, () -> upgrader.migrateIfNeeded( databaseLayout ) );
            assertTrue( e.getCause() instanceof IOException );
            assertEquals( failureMessage, e.getCause().getMessage() );
        }

        // AND WHEN
        {
            StoreUpgrader upgrader = newUpgrader( check, pageCache );
            StoreMigrationParticipant observingParticipant = Mockito.mock( StoreMigrationParticipant.class );
            upgrader.addParticipant( observingParticipant );
            upgrader.migrateIfNeeded( databaseLayout );

            // THEN
            verify( observingParticipant, Mockito.never() ).migrate( any( DatabaseLayout.class ), any( DatabaseLayout.class ), any( ProgressReporter.class ),
                    any( StoreVersion.class ), any( StoreVersion.class ), eq( IndexImporterFactory.EMPTY ), any( LogTailMetadata.class ) );
            verify( observingParticipant ).
                    moveMigratedFiles( any( DatabaseLayout.class ), any( DatabaseLayout.class ), eq( versionToMigrateFrom ), eq( versionToMigrateTo ) );

            verify( observingParticipant ).cleanup( any( DatabaseLayout.class ) );
        }
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void tracePageCacheAccessOnVersionCheck( RecordFormats formats ) throws IOException
    {
        init( formats );

        fileSystem.deleteFile( databaseLayout.file( INTERNAL_LOG_FILE ) );
        var pageCacheTracer = new DefaultPageCacheTracer();
        CursorContextFactory contextFactory = new CursorContextFactory( pageCacheTracer, EMPTY );
        new RecordStoreVersionCheck( fileSystem, pageCache, databaseLayout, NullLogProvider.getInstance(), Config.defaults(), contextFactory );

        assertThat( pageCacheTracer.hits() ).isEqualTo( 0 );
        assertThat( pageCacheTracer.pins() ).isEqualTo( 1 );
        assertThat( pageCacheTracer.unpins() ).isEqualTo( 1 );
        assertThat( pageCacheTracer.faults() ).isEqualTo( 1 );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void upgradeShouldNotLeaveLeftoverAndMigrationDirs( RecordFormats formats ) throws Exception
    {
        init( formats );

        // Given
        fileSystem.deleteFile( databaseLayout.file( INTERNAL_LOG_FILE ) );
        StoreVersionCheck check = getVersionCheck( pageCache );

        // When
        newUpgrader( check, allowMigrateConfig, pageCache ).migrateIfNeeded( databaseLayout );

        // Then
        assertThat( migrationHelperDirs() ).isEmpty();
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void upgradeShouldGiveProgressMonitorProgressMessages( RecordFormats formats ) throws Exception
    {
        init( formats );

        // Given
        StoreVersionCheck check = getVersionCheck( pageCache );

        // When
        AssertableLogProvider logProvider = new AssertableLogProvider();
        newUpgrader( check, pageCache, allowMigrateConfig,
            new VisibleMigrationProgressMonitor( logProvider.getLog( "test" ) ) ).migrateIfNeeded( databaseLayout );

        // Then
        assertThat( logProvider ).containsMessages( "Store files", "Indexes", "Successfully finished" );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void upgraderShouldCleanupLegacyLeftoverAndMigrationDirs( RecordFormats formats ) throws Exception
    {
        init( formats );

        // Given
        fileSystem.deleteFile( databaseLayout.file( INTERNAL_LOG_FILE ) );
        fileSystem.mkdir( databaseLayout.file( StoreUpgrader.MIGRATION_DIRECTORY ) );
        fileSystem.mkdir( databaseLayout.file( StoreUpgrader.MIGRATION_LEFT_OVERS_DIRECTORY ) );
        fileSystem.mkdir( databaseLayout.file( StoreUpgrader.MIGRATION_LEFT_OVERS_DIRECTORY + "_1" ) );
        fileSystem.mkdir( databaseLayout.file( StoreUpgrader.MIGRATION_LEFT_OVERS_DIRECTORY + "_2" ) );
        fileSystem.mkdir( databaseLayout.file( StoreUpgrader.MIGRATION_LEFT_OVERS_DIRECTORY + "_42" ) );

        // When
        StoreVersionCheck check = getVersionCheck( pageCache );
        StoreUpgrader storeUpgrader = newUpgrader( check, pageCache );
        storeUpgrader.migrateIfNeeded( databaseLayout );

        // Then
        assertThat( migrationHelperDirs() ).isEmpty();
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void upgradeFailsIfMigrationIsNotAllowed( RecordFormats formats ) throws IOException
    {
        init( formats );

        StoreVersionCheck check = getVersionCheck( pageCache );

        AssertableLogProvider logProvider = new AssertableLogProvider();
        assertThrows( UpgradeNotAllowedException.class, () -> newUpgrader( check, pageCache, Config.defaults(),
            new VisibleMigrationProgressMonitor( logProvider.getLog( "test" ) ) ).migrateIfNeeded( databaseLayout ) );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void upgradeMoveTransactionLogs( RecordFormats formats ) throws IOException
    {
        init( formats );

        Path txRoot = testDirectory.directory( "customTxRoot" );
        AssertableLogProvider logProvider = new AssertableLogProvider();
        StoreVersionCheck check = getVersionCheck( pageCache );

        Config config = Config.newBuilder().fromConfig( allowMigrateConfig )
                .set( neo4j_home, testDirectory.homePath() )
                .set( GraphDatabaseSettings.transaction_logs_root_path, txRoot.toAbsolutePath() )
                .set( default_database, databaseLayout.getDatabaseName() )
                .build();
        DatabaseLayout migrationLayout = DatabaseLayout.of( config );

        newUpgrader( check, pageCache, config, new VisibleMigrationProgressMonitor( logProvider.getLog( "test" ) ) )
            .migrateIfNeeded( migrationLayout );

        assertThat( logProvider ).containsMessages( "Starting transaction logs migration.", "Transaction logs migration completed." );
        assertThat( getLogFiles( migrationLayout.databaseDirectory() ) ).isEmpty();
        Path databaseTransactionLogsHome = txRoot.resolve( migrationLayout.getDatabaseName() );
        assertTrue( fileSystem.fileExists( databaseTransactionLogsHome ) );

        Set<String> logFileNames = getLogFileNames( databaseTransactionLogsHome );
        assertThat( logFileNames ).isNotEmpty();
        assertThat( logFileNames ).containsAll( getLogFileNames( prepareDatabaseDirectory ) );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void failToMoveTransactionLogsIfTheyAlreadyExist( RecordFormats formats ) throws IOException
    {
        init( formats );

        Path txRoot = testDirectory.directory( "customTxRoot" );
        AssertableLogProvider logProvider = new AssertableLogProvider();
        StoreVersionCheck check = getVersionCheck( pageCache );

        Config config = Config.newBuilder().fromConfig( allowMigrateConfig )
                .set( neo4j_home, testDirectory.homePath() )
                .set( GraphDatabaseSettings.transaction_logs_root_path, txRoot.toAbsolutePath() )
                .set( default_database, databaseLayout.getDatabaseName() )
                .build();
        DatabaseLayout migrationLayout = DatabaseLayout.of( config );

        Path databaseTransactionLogsHome = txRoot.resolve( migrationLayout.getDatabaseName() );
        fileSystem.mkdir( databaseTransactionLogsHome );
        createDummyTxLogFiles( databaseTransactionLogsHome );

        assertThrows( StoreUpgrader.TransactionLogsUpgradeException.class, () ->
                newUpgrader( check, pageCache, config, new VisibleMigrationProgressMonitor( logProvider.getLog( "test" ) ) )
                .migrateIfNeeded( migrationLayout ) );
    }

    @ParameterizedTest
    @MethodSource( "versions" )
    void notParticipatingParticipantsAreNotPartOfMigration( RecordFormats formats ) throws IOException
    {
        init( formats );

        StoreVersionCheck check = getVersionCheck( pageCache );
        StoreUpgrader storeUpgrader = newUpgrader( check, pageCache );
        assertThat( storeUpgrader.getParticipants() ).hasSize( 2 );
    }

    private void createDummyTxLogFiles( Path databaseTransactionLogsHome ) throws IOException
    {
        Set<String> preparedLogFiles = getLogFileNames( prepareDatabaseDirectory );
        assertThat( preparedLogFiles ).isNotEmpty();
        for ( String preparedLogFile : preparedLogFiles )
        {
            fileSystem.write( databaseTransactionLogsHome.resolve( preparedLogFile ) ).close();
        }
    }

    private Path[] getLogFiles( Path directory ) throws IOException
    {
        return LogFilesBuilder.logFilesBasedOnlyBuilder( directory, fileSystem )
                .build()
                .logFiles();
    }

    private Set<String> getLogFileNames( Path directory ) throws IOException
    {
        return Arrays.stream( LogFilesBuilder.logFilesBasedOnlyBuilder( directory, fileSystem )
                     .build()
                     .logFiles() )
                         .map( Path::getFileName )
                         .map( Path::toString )
                         .collect( Collectors.toSet() );
    }

    protected void prepareSampleDatabase( String version, FileSystemAbstraction fileSystem, RecordDatabaseLayout databaseLayout,
            Path databaseDirectory ) throws IOException
    {
        MigrationTestUtils.prepareSampleLegacyDatabase( version, fileSystem, databaseLayout, databaseDirectory );
    }

    private StoreVersionCheck getVersionCheck( PageCache pageCache )
    {
        return getVersionCheck( pageCache, CONTEXT_FACTORY );
    }

    private StoreVersionCheck getVersionCheck( PageCache pageCache, CursorContextFactory contextFactory )
    {
        return new RecordStoreVersionCheck( fileSystem, pageCache, databaseLayout, NullLogProvider.getInstance(), getTuningConfig(), contextFactory );
    }

    private static StoreMigrationParticipant participantThatWillFailWhenMoving( final String failureMessage )
    {
        return new AbstractStoreMigrationParticipant( "Failing" )
        {
            @Override
            public void migrate( DatabaseLayout directoryLayout, DatabaseLayout migrationLayout, ProgressReporter progress, StoreVersion fromVersion,
                    StoreVersion toVersion, IndexImporterFactory indexImporterFactory, LogTailMetadata tailMetadata )
            {
                // nop
            }

            @Override
            public void moveMigratedFiles( DatabaseLayout migrationLayout, DatabaseLayout directoryLayout, String versionToUpgradeFrom,
                    String versionToMigrateTo ) throws IOException
            {
                throw new IOException( failureMessage );
            }

            @Override
            public void cleanup( DatabaseLayout migrationLayout )
            {
                // nop
            }
        };
    }

    private StoreUpgrader newUpgrader( StoreVersionCheck storeVersionCheck, Config config, PageCache pageCache ) throws IOException
    {
        return newUpgrader( storeVersionCheck, pageCache, config, NULL );
    }

    private StoreUpgrader newUpgrader( StoreVersionCheck storeVersionCheck, PageCache pageCache ) throws IOException
    {
        return newUpgrader( storeVersionCheck, pageCache, allowMigrateConfig, NULL );
    }

    private StoreUpgrader newUpgrader( StoreVersionCheck storeVersionCheck, PageCache pageCache, Config config,
            PageCacheTracer pageCacheTracer ) throws IOException
    {
        return newUpgrader( storeVersionCheck, pageCache, config, SILENT, pageCacheTracer );
    }

    private StoreUpgrader newUpgrader( StoreVersionCheck storeVersionCheck, PageCache pageCache, Config config, MigrationProgressMonitor progressMonitor )
            throws IOException
    {
        return newUpgrader( storeVersionCheck, pageCache, config, progressMonitor, NULL );
    }

    private StoreUpgrader newUpgrader( StoreVersionCheck storeVersionCheck, PageCache pageCache, Config config,
            MigrationProgressMonitor progressMonitor, PageCacheTracer pageCacheTracer ) throws IOException
    {
        NullLogService instance = NullLogService.getInstance();
        BatchImporterFactory batchImporterFactory = BatchImporterFactory.withHighestPriority();
        var contextFactory = new CursorContextFactory( pageCacheTracer, EMPTY );
        RecordStorageMigrator defaultMigrator = new RecordStorageMigrator( fileSystem, pageCache, pageCacheTracer, getTuningConfig(), instance, jobScheduler,
                contextFactory, batchImporterFactory, INSTANCE );
        StorageEngineFactory storageEngineFactory = StorageEngineFactory.defaultStorageEngine();
        SchemaIndexMigrator indexMigrator =
                new SchemaIndexMigrator( "Indexes", fileSystem, pageCache, IndexProvider.EMPTY.directoryStructure(), storageEngineFactory,
                                         contextFactory );

        Dependencies dependencies = new Dependencies();
        dependencies.satisfyDependencies( new Monitors() );
        var logTail = loadLogTail( databaseLayout, config, storageEngineFactory );
        var supplier = Suppliers.lazySingleton( () -> logTail );
        var logsUpgrader = new LogsMigrator( fileSystem, storageEngineFactory, storageEngineFactory, databaseLayout, pageCache, config, contextFactory,
                supplier );
        StoreUpgrader upgrader = new StoreUpgrader( storageEngineFactory, storeVersionCheck, progressMonitor, config, fileSystem,
                NullLogProvider.getInstance(), logsUpgrader, contextFactory, supplier );
        upgrader.addParticipant( indexMigrator );
        upgrader.addParticipant( NOT_PARTICIPATING );
        upgrader.addParticipant( NOT_PARTICIPATING );
        upgrader.addParticipant( NOT_PARTICIPATING );
        upgrader.addParticipant( NOT_PARTICIPATING );
        upgrader.addParticipant( defaultMigrator );
        return upgrader;
    }

    private List<Path> migrationHelperDirs()
    {
        Path[] tmpDirs = databaseLayout.listDatabaseFiles( file -> Files.isDirectory( file ) &&
                (file.getFileName().toString().equals( StoreUpgrader.MIGRATION_DIRECTORY ) ||
                        file.getFileName().toString().startsWith( StoreUpgrader.MIGRATION_LEFT_OVERS_DIRECTORY )) );
        assertNotNull( tmpDirs, "Some IO errors occurred" );
        return Arrays.asList( tmpDirs );
    }

    private Config getTuningConfig()
    {
        return Config.defaults( GraphDatabaseSettings.record_format, getRecordFormatsName() );
    }

    protected String getRecordFormatsName()
    {
        return Standard.LATEST_NAME;
    }

    private LogTailMetadata loadLogTail( DatabaseLayout layout, Config config, StorageEngineFactory engineFactory ) throws IOException
    {
        return new LogTailExtractor( fileSystem, pageCache, config, engineFactory, DatabaseTracers.EMPTY ).getTailMetadata( layout, INSTANCE );
    }

    public static void removeCheckPointFromTxLog( FileSystemAbstraction fileSystem, RecordDatabaseLayout databaseLayout ) throws IOException
    {
        LogFiles logFiles = LogFilesBuilder.logFilesBasedOnlyBuilder( databaseLayout.getTransactionLogsDirectory(), fileSystem )
                .withStorageEngineFactory( StorageEngineFactory.defaultStorageEngine() )
                .build();
        LogTailInformation logTailInformation = (LogTailInformation) logFiles.getTailMetadata();

        if ( logTailInformation.commitsAfterLastCheckpoint() )
        {
            // done already
            return;
        }

        // let's assume there is at least a checkpoint
        assertNotNull( logTailInformation.lastCheckPoint );

        LogPosition logPosition = logTailInformation.lastCheckPoint.getCheckpointEntryPosition();
        Path logFile = logFiles.getCheckpointFile().getDetachedCheckpointFileForVersion( logPosition.getLogVersion() );
        long byteOffset = logPosition.getByteOffset();
        fileSystem.truncate( logFile, byteOffset );
    }

    private static class EmptyNamedMigrationParticipant extends AbstractStoreMigrationParticipant
    {
        protected EmptyNamedMigrationParticipant( String name )
        {
            super( name );
        }

        @Override
        public void migrate( DatabaseLayout directoryLayout, DatabaseLayout migrationLayout, ProgressReporter progress, StoreVersion fromVersion,
                StoreVersion toVersion, IndexImporterFactory indexImporterFactory, LogTailMetadata tailMetadata )
        {
            // empty
        }

        @Override
        public void moveMigratedFiles( DatabaseLayout migrationLayout, DatabaseLayout directoryLayout, String versionToMigrateFrom,
                String versionToMigrateTo )
        {
            // empty
        }

        @Override
        public void cleanup( DatabaseLayout migrationLayout )
        {
            // empty
        }
    }
}
