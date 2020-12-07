/*
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
package io.prestosql.plugin.hive.util;

import com.google.common.collect.AbstractIterator;
import io.airlift.stats.TimeStat;
import io.prestosql.plugin.hive.DirectoryLister;
import io.prestosql.plugin.hive.HdfsEnvironment;
import io.prestosql.plugin.hive.HdfsEnvironment.HdfsContext;
import io.prestosql.plugin.hive.NamenodeStats;
import io.prestosql.plugin.hive.metastore.Table;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.security.ConnectorIdentity;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_FILESYSTEM_ERROR;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_FILE_NOT_FOUND;
import static java.util.Collections.emptyIterator;
import static java.util.Objects.requireNonNull;

public class HiveFileIterator
        extends AbstractIterator<LocatedFileStatus>
{
    public enum NestedDirectoryPolicy
    {
        IGNORED,
        RECURSE,
        FAIL
    }

    private final Deque<Path> paths = new ArrayDeque<>();
    private final Table table;
    private final HdfsEnvironment hdfsEnvironment;
    private final HdfsContext hdfsContext;
    private final DirectoryLister directoryLister;
    private final NamenodeStats namenodeStats;
    private final NestedDirectoryPolicy nestedDirectoryPolicy;
    private final boolean ignoreAbsentPartitions;

    private Iterator<LocatedFileStatus> remoteIterator = emptyIterator();

    public HiveFileIterator(
            Table table,
            HdfsEnvironment hdfsEnvironment,
            HdfsContext hdfsContext,
            Path path,
            DirectoryLister directoryLister,
            NamenodeStats namenodeStats,
            NestedDirectoryPolicy nestedDirectoryPolicy,
            boolean ignoreAbsentPartitions)
    {
        paths.addLast(requireNonNull(path, "path is null"));
        this.table = requireNonNull(table, "table is null");
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfs environment is null");
        this.hdfsContext = requireNonNull(hdfsContext, "hdfs context is null");
        this.directoryLister = requireNonNull(directoryLister, "directoryLister is null");
        this.namenodeStats = requireNonNull(namenodeStats, "namenodeStats is null");
        this.nestedDirectoryPolicy = requireNonNull(nestedDirectoryPolicy, "nestedDirectoryPolicy is null");
        this.ignoreAbsentPartitions = ignoreAbsentPartitions;
    }

    @Override
    protected LocatedFileStatus computeNext()
    {
        while (true) {
            while (remoteIterator.hasNext()) {
                LocatedFileStatus status = getLocatedFileStatus(remoteIterator);

                // Ignore hidden files and directories. Hive ignores files starting with _ and . as well.
                String fileName = status.getPath().getName();
                if (fileName.startsWith("_") || fileName.startsWith(".")) {
                    continue;
                }

                if (status.isDirectory()) {
                    switch (nestedDirectoryPolicy) {
                        case IGNORED:
                            continue;
                        case RECURSE:
                            paths.add(status.getPath());
                            continue;
                        case FAIL:
                            throw new NestedDirectoryNotAllowedException();
                    }
                }

                return status;
            }

            if (paths.isEmpty()) {
                return endOfData();
            }
            remoteIterator = getLocatedFileStatusRemoteIterator(paths.removeFirst());
        }
    }

    private Iterator<LocatedFileStatus> getLocatedFileStatusRemoteIterator(Path path)
    {
        try (TimeStat.BlockTimer ignored = namenodeStats.getListLocatedStatus().time()) {
            if (ignoreAbsentPartitions && !exists(path)) {
                return emptyIterator();
            }
            return new FileStatusIterator(table, hdfsEnvironment, hdfsContext, path, directoryLister, namenodeStats);
        }
    }

    private boolean exists(Path path)
    {
        try {
            FileSystem fileSystem = hdfsEnvironment.getFileSystem(hdfsContext, path);
            return fileSystem.exists(path);
        }
        catch (IOException e) {
            throw new PrestoException(HIVE_FILESYSTEM_ERROR, "Failed to check if path exists: " + path, e);
        }
    }

    private LocatedFileStatus getLocatedFileStatus(Iterator<LocatedFileStatus> iterator)
    {
        try (TimeStat.BlockTimer ignored = namenodeStats.getRemoteIteratorNext().time()) {
            return iterator.next();
        }
    }

    private static class FileStatusIterator
            implements Iterator<LocatedFileStatus>
    {
        private final Path path;
        private final NamenodeStats namenodeStats;
        private final RemoteIterator<LocatedFileStatus> fileStatusIterator;
        private final ConnectorIdentity identity;

        private FileStatusIterator(Table table, HdfsEnvironment hdfsEnvironment, HdfsContext hdfsContext, Path path, DirectoryLister directoryLister, NamenodeStats namenodeStats)
        {
            this.path = path;
            this.namenodeStats = namenodeStats;
            this.identity = hdfsContext.getIdentity();
            try {
                FileSystem fileSystem = hdfsEnvironment.getFileSystem(hdfsContext, path);
                this.fileStatusIterator = directoryLister.list(fileSystem, table, path);
            }
            catch (IOException e) {
                throw processException(e);
            }
        }

        @Override
        public boolean hasNext()
        {
            try {
                HdfsEnvironment.setConnectorIdentity(identity);
                return fileStatusIterator.hasNext();
            }
            catch (IOException e) {
                throw processException(e);
            }
        }

        @Override
        public LocatedFileStatus next()
        {
            try {
                HdfsEnvironment.setConnectorIdentity(identity);
                return fileStatusIterator.next();
            }
            catch (IOException e) {
                throw processException(e);
            }
        }

        private PrestoException processException(IOException exception)
        {
            namenodeStats.getRemoteIteratorNext().recordException(exception);
            if (exception instanceof FileNotFoundException) {
                return new PrestoException(HIVE_FILE_NOT_FOUND, "Partition location does not exist: " + path);
            }
            return new PrestoException(HIVE_FILESYSTEM_ERROR, "Failed to list directory: " + path, exception);
        }
    }

    public static class NestedDirectoryNotAllowedException
            extends RuntimeException
    {
        public NestedDirectoryNotAllowedException()
        {
            super("Nested sub-directories are not allowed");
        }
    }
}
