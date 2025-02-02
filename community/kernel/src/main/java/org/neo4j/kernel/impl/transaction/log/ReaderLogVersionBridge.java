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
package org.neo4j.kernel.impl.transaction.log;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import org.neo4j.kernel.impl.transaction.log.entry.IncompleteLogHeaderException;
import org.neo4j.kernel.impl.transaction.log.files.LogFile;

/**
 * {@link LogVersionBridge} naturally transitioning from one {@link LogVersionedStoreChannel} to the next,
 * i.e. to log version with one higher version than the current.
 */
public class ReaderLogVersionBridge implements LogVersionBridge {
    private final LogFile logFile;

    public ReaderLogVersionBridge(LogFile logFile) {
        this.logFile = logFile;
    }

    @Override
    public LogVersionedStoreChannel next(LogVersionedStoreChannel channel, boolean raw) throws IOException {
        PhysicalLogVersionedStoreChannel nextChannel;
        try {
            nextChannel = logFile.openForVersion(channel.getVersion() + 1, raw);
        } catch (NoSuchFileException | IncompleteLogHeaderException e) {
            // See PhysicalLogFile#rotate() for description as to why these exceptions are OK
            return channel;
        }
        channel.close();
        return nextChannel;
    }
}
