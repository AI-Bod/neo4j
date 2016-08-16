/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.catchup.tx;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

import org.neo4j.coreedge.identity.StoreId;
import org.neo4j.coreedge.messaging.NetworkReadableClosableChannelNetty4;
import org.neo4j.coreedge.messaging.marshalling.storeid.StoreIdMarshal;
import org.neo4j.kernel.impl.storageengine.impl.recordstorage.RecordStorageCommandReaderFactory;
import org.neo4j.kernel.impl.transaction.CommittedTransactionRepresentation;
import org.neo4j.kernel.impl.transaction.log.PhysicalTransactionCursor;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntryReader;
import org.neo4j.kernel.impl.transaction.log.entry.VersionAwareLogEntryReader;

public class TxPullResponseDecoder extends MessageToMessageDecoder<ByteBuf>
{
    @Override
    protected void decode( ChannelHandlerContext ctx, ByteBuf msg, List<Object> out ) throws Exception
    {
        NetworkReadableClosableChannelNetty4 logChannel = new NetworkReadableClosableChannelNetty4( msg );
        byte version = logChannel.get();
        StoreId storeId = StoreIdMarshal.unmarshal( logChannel );
        LogEntryReader<NetworkReadableClosableChannelNetty4> reader =
                new VersionAwareLogEntryReader<>( new RecordStorageCommandReaderFactory() );
        PhysicalTransactionCursor<NetworkReadableClosableChannelNetty4> transactionCursor =
                new PhysicalTransactionCursor<>( logChannel, reader );

        transactionCursor.next();
        CommittedTransactionRepresentation tx = transactionCursor.get();

        if ( tx != null )
        {
            out.add( new TxPullResponse( version, storeId, tx ) );
        }
    }
}
