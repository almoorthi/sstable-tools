package com.csforge.sstable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.stream.Stream;

import com.csforge.reader.Partition;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter.Indenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter.NopIndenter;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.rows.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JsonTransformer {

    private static final Logger logger = LoggerFactory.getLogger(JsonTransformer.class);

    private static final JsonFactory jsonFactory = new JsonFactory();

    private final JsonGenerator json;

    private final CompactIndenter indenter = new CompactIndenter();

    private final CFMetaData metadata;

    private JsonTransformer(JsonGenerator json, CFMetaData metadata) {
        this.json = json;
        this.metadata = metadata;
    }

    public static void toJson(Stream<Partition> partitions, CFMetaData metadata, OutputStream out) throws IOException {
        try(JsonGenerator json = jsonFactory.createGenerator(new OutputStreamWriter(out, "UTF-8"))) {
            JsonTransformer transformer = new JsonTransformer(json, metadata);
            DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
            prettyPrinter.indentObjectsWith(transformer.indenter);
            prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
            json.setPrettyPrinter(prettyPrinter);

            json.writeStartArray();
            partitions.forEach(transformer::serializePartition);
            json.writeEndArray();
        }
    }

    public static void keysToJson(Stream<DecoratedKey> keys, CFMetaData metadata, OutputStream out) throws IOException {
        try(JsonGenerator json = jsonFactory.createGenerator(new OutputStreamWriter(out, "UTF-8"))) {
            JsonTransformer transformer = new JsonTransformer(json, metadata);
            DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
            prettyPrinter.indentObjectsWith(transformer.indenter);
            prettyPrinter.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
            json.setPrettyPrinter(prettyPrinter);

            json.writeStartArray();
            keys.forEach(key -> {
                try {
                    json.writeString(metadata.getKeyValidator().getString(key.getKey()));
                } catch (IOException e) {
                    logger.error("Fatal error writing key.", e);
                }
            });
            json.writeEndArray();
        }
    }

    private void serializePartition(Partition partition) {
        String key = metadata.getKeyValidator().getString(partition.getKey().getKey());
        try {
            /* TODO: Parse the individual key parts.
            List<AbstractType<?>> keyParts = metadata.getKeyValidator().getComponents();
            List<ColumnDefinition> partitionColumns = metadata.partitionKeyColumns();
            assert keyParts.size() == partitionColumns.size();
            writer.name("partition_key");
            writer.beginObject();
            for(int i = 0; i < keyParts.size(); i++) {
                ColumnDefinition column = partitionColumns.get(i);
                AbstractType<?> type = keyParts.get(i);
                writer.name("name");
                writer.value(column.name.toCQLString());
                writer.name("value");
                writer.value(type.getString());
            }
            writer.endObject();
            */

            json.writeStartObject();
            json.writeFieldName("key");
            json.writeString(key);

            if(!partition.isLive()) {
                json.writeFieldName("deletion_info");
                indenter.setCompact(true);
                json.writeStartObject();
                json.writeFieldName("deletion_time");
                json.writeNumber(partition.markedForDeleteAt());
                json.writeFieldName("tstamp");
                json.writeNumber(partition.localDeletionTime());
                json.writeEndObject();
                indenter.setCompact(false);
            }

            json.writeFieldName("rows");
            json.writeStartArray();
            if (!partition.staticRow().isEmpty()) {
                serializeRow(partition.staticRow());
            }

            partition.rows().forEach(unfiltered -> {
                if (unfiltered instanceof Row) {
                    serializeRow((Row)unfiltered);
                } else if (unfiltered instanceof RangeTombstoneMarker) {
                    serializeTombstone((RangeTombstoneMarker)unfiltered);
                }
            });
            json.writeEndArray();
            json.writeEndObject();
        } catch (IOException e) {
            logger.error("Fatal error parsing partition: {}", key, e);
        }
    }

    private void serializeRow(Row row) {
        try {
            json.writeStartObject();
            String rowType = row.isStatic() ? "static_block" : (row.deletion().isLive() ? "row" : "row_tombstone");
            json.writeFieldName("type");
            json.writeString(rowType);

            LivenessInfo liveInfo = row.primaryKeyLivenessInfo();
            if (!liveInfo.isEmpty()) {
                indenter.setCompact(true);
                json.writeFieldName("tstamp");
                json.writeNumber(liveInfo.timestamp());
                if (liveInfo.isExpiring()) {
                    json.writeFieldName("ttl");
                    json.writeNumber(liveInfo.ttl());
                    json.writeFieldName("ttl_timestamp");
                    json.writeNumber(liveInfo.localExpirationTime());
                    json.writeFieldName("expired");
                    json.writeBoolean(liveInfo.isLive((int)System.currentTimeMillis() / 1000));
                }
                indenter.setCompact(false);
            }


            // Only print clustering information for non-static rows.
            if (!row.isStatic()) {
                serializeClustering(row.clustering());
            }

            // If this is a deletion, indicate that, otherwise write cells.
            if(!row.deletion().isLive()) {
                json.writeFieldName("deletion_info");
                indenter.setCompact(true);
                json.writeStartObject();
                json.writeFieldName("deletion_time");
                json.writeNumber(row.deletion().time().markedForDeleteAt());
                json.writeFieldName("tstamp");
                json.writeNumber(row.deletion().time().localDeletionTime());
                json.writeEndObject();
                indenter.setCompact(false);
            } else {
                json.writeFieldName("cells");
                json.writeStartArray();
                row.cells().forEach(this::serializeCell);
                json.writeEndArray();
            }
            json.writeEndObject();
        } catch(IOException e) {
            logger.error("Fatal error parsing row.", e);
        }
    }

    private void serializeTombstone(RangeTombstoneMarker tombstone) {
        try {
            json.writeStartObject();
            json.writeFieldName("type");

            if (tombstone instanceof RangeTombstoneBoundMarker) {
                json.writeString("range_tombstone_bound");
                RangeTombstoneBoundMarker bm = (RangeTombstoneBoundMarker)tombstone;
                serializeBound(bm.clustering(), bm.deletionTime());
            } else {
                assert tombstone instanceof RangeTombstoneBoundaryMarker;
                json.writeString("range_tombstone_boundary");
                RangeTombstoneBoundaryMarker bm = (RangeTombstoneBoundaryMarker)tombstone;
                serializeBound(bm.openBound(false), bm.openDeletionTime(false));
                serializeBound(bm.closeBound(false), bm.closeDeletionTime(false));
            }
            json.writeEndObject();
            indenter.setCompact(false);
        } catch(IOException e) {
            logger.error("Failure parsing tombstone.", e);
        }
    }

    private void serializeBound(RangeTombstone.Bound bound, DeletionTime deletionTime) throws IOException {
        json.writeFieldName(bound.isStart() ? "start" : "end");
        json.writeStartObject();
        json.writeFieldName("type");
        json.writeString(bound.isInclusive() ? "inclusive" : "exclusive");
        serializeClustering(bound.clustering());
        serializeDeletion(deletionTime);
        json.writeEndObject();
    }

    private void serializeClustering(ClusteringPrefix clustering) throws IOException {
        json.writeFieldName("clustering");
        json.writeStartObject();
        indenter.setCompact(true);
        List<ColumnDefinition> clusteringColumns = metadata.clusteringColumns();
        for (int i = 0; i < clusteringColumns.size(); i++) {
            ColumnDefinition column = clusteringColumns.get(i);
            json.writeFieldName(column.name.toCQLString());
            if(i >= clustering.size()) {
                json.writeString("*");
            } else {
                json.writeString(column.cellValueType().getString(clustering.get(i)));
            }
        }
        json.writeEndObject();
        indenter.setCompact(false);
    }

    private void serializeDeletion(DeletionTime deletion) throws IOException {
        json.writeFieldName("deletion_info");
        indenter.setCompact(true);
        json.writeStartObject();
        json.writeFieldName("deletion_time");
        json.writeNumber(deletion.markedForDeleteAt());
        json.writeFieldName("tstamp");
        json.writeNumber(deletion.localDeletionTime());
        json.writeEndObject();
        indenter.setCompact(false);
    }

    private void serializeCell(Cell cell) {
        try {
            json.writeStartObject();
            indenter.setCompact(true);
            json.writeFieldName("name");
            json.writeString(cell.column().name.toCQLString());

            if (cell.isTombstone()) {
                json.writeFieldName("deletion_time");
                json.writeNumber(cell.localDeletionTime());
            } else {
                json.writeFieldName("value");
                json.writeString(cell.column().cellValueType().getString(cell.value()));
                if (cell.isExpiring()) {
                    json.writeFieldName("ttl");
                    json.writeNumber(cell.ttl());
                    json.writeFieldName("deletion_time");
                    json.writeNumber(cell.localDeletionTime());
                    json.writeFieldName("expired");
                    json.writeBoolean(!cell.isLive((int)System.currentTimeMillis() / 1000));
                }
            }
            json.writeFieldName("tstamp");
            json.writeNumber(cell.timestamp());
            json.writeEndObject();
            indenter.setCompact(false);
        } catch(IOException e) {
            logger.error("Failure parsing cell.", e);
        }
    }

    /**
     * A specialized {@link Indenter} that enables a 'compact' mode which puts all subsequent json
     * values on the same line.  This is manipulated via {@link CompactIndenter#setCompact(boolean)}
     */
    private static final class CompactIndenter extends NopIndenter {

        private static final int INDENT_LEVELS = 16;
        private final char[] indents;
        private final int charsPerLevel;
        private final String eol;
        private static final String space = " ";

        private boolean compact = false;

        public CompactIndenter() {
            this("  ", DefaultIndenter.SYS_LF);
        }

        public CompactIndenter(String indent, String eol) {
            this.eol = eol;

            charsPerLevel = indent.length();

            indents = new char[indent.length() * INDENT_LEVELS];
            int offset = 0;
            for (int i=0; i<INDENT_LEVELS; i++) {
                indent.getChars(0, indent.length(), indents, offset);
                offset += indent.length();
            }
        }

        @Override
        public boolean isInline() {
            return false;
        }

        /**
         * Configures whether or not subsequent json values should be on the same line delimited by string or not.
         * @param compact Whether or not to compact.
         */
        public void setCompact(boolean compact) {
            this.compact = compact;
        }

        @Override
        public void writeIndentation(JsonGenerator jg, int level) throws IOException
        {
            if(!compact) {
                jg.writeRaw(eol);
                if (level > 0) { // should we err on negative values (as there's some flaw?)
                    level *= charsPerLevel;
                    while (level > indents.length) { // unlike to happen but just in case
                        jg.writeRaw(indents, 0, indents.length);
                        level -= indents.length;
                    }
                    jg.writeRaw(indents, 0, level);
                }
            } else {
                jg.writeRaw(space);
            }
        }
    }
}
