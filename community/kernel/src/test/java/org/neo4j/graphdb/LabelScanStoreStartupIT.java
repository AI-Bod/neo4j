/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.graphdb;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import org.neo4j.collection.primitive.PrimitiveLongCollections;
import org.neo4j.graphdb.factory.GraphDatabaseBuilder;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.kernel.api.impl.labelscan.LabelScanStoreTest;
import org.neo4j.kernel.api.labelscan.LabelScanStore;
import org.neo4j.kernel.api.labelscan.LabelScanWriter;
import org.neo4j.kernel.api.labelscan.NodeLabelUpdate;
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge;
import org.neo4j.storageengine.api.schema.LabelScanReader;
import org.neo4j.test.rule.DatabaseRule;
import org.neo4j.test.rule.EmbeddedDatabaseRule;
import org.neo4j.test.rule.RandomRule;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public abstract class LabelScanStoreStartupIT
{
    private static final Label LABEL = Label.label( "testLabel" );

    @Rule
    public final DatabaseRule dbRule = new EmbeddedDatabaseRule( getClass() )
    {
        @Override
        protected void configure( GraphDatabaseBuilder builder )
        {
            addSpecificConfig( builder );
        }
    };
    @Rule
    public final RandomRule random = new RandomRule();

    private int labelId;

    protected abstract void addSpecificConfig( GraphDatabaseBuilder builder );

    @Test
    public void scanStoreStartWithoutExistentIndex() throws IOException
    {
        LabelScanStore labelScanStore = getLabelScanStore();
        labelScanStore.shutdown();

        deleteLabelScanStoreFiles( dbRule.getStoreDirFile() );

        labelScanStore.init();
        labelScanStore.start();

        checkLabelScanStoreAccessible( labelScanStore );
    }

    @Test
    public void scanStoreRecreateCorruptedIndexOnStartup() throws IOException
    {
        LabelScanStore labelScanStore = getLabelScanStore();

        createTestNode();
        long[] labels = readNodesForLabel( labelScanStore );
        assertEquals( "Label scan store see 1 label for node", 1, labels.length );
        labelScanStore.force( IOLimiter.unlimited() );
        labelScanStore.shutdown();

        corruptLabelScanStoreFiles( dbRule.getStoreDirFile() );

        labelScanStore.init();
        labelScanStore.start();

        long[] rebuildLabels = readNodesForLabel( labelScanStore );
        assertArrayEquals( "Store should rebuild corrupted index", labels, rebuildLabels );
    }

    private LabelScanStore getLabelScanStore()
    {
        return dbRule.getDependencyResolver().resolveDependency( LabelScanStore.class );
    }

    private long[] readNodesForLabel( LabelScanStore labelScanStore )
    {
        try ( LabelScanReader reader = labelScanStore.newReader() )
        {
            return PrimitiveLongCollections.asArray( reader.nodesWithLabel( labelId ) );
        }
    }

    private Node createTestNode()
    {
        Node node;
        try ( Transaction transaction = dbRule.beginTx() )
        {
            node = dbRule.createNode( LABEL);
            labelId = dbRule.getDependencyResolver().resolveDependency( ThreadToStatementContextBridge.class )
                    .getKernelTransactionBoundToThisThread( true )
                    .acquireStatement().readOperations().labelGetForName( LABEL.name() );
            transaction.success();
        }
        return node;
    }

    protected void scrambleFile( File file ) throws IOException
    {
        LabelScanStoreTest.scrambleFile( random.random(), file );
    }

    protected abstract void corruptLabelScanStoreFiles( File storeDirectory ) throws IOException;

    protected abstract void deleteLabelScanStoreFiles( File storeDirectory ) throws IOException;

    private void checkLabelScanStoreAccessible( LabelScanStore labelScanStore ) throws IOException
    {
        int labelId = 1;
        try ( LabelScanWriter labelScanWriter = labelScanStore.newWriter() )
        {
            labelScanWriter.write( NodeLabelUpdate.labelChanges( 1, new long[]{}, new long[]{labelId} ) );
        }
        try ( LabelScanReader labelScanReader = labelScanStore.newReader() )
        {
            assertEquals( 1, labelScanReader.nodesWithLabel( labelId ).next() );
        }
    }
}
