/*
 * Copyright 2014, Tuplejump Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tuplejump.stargate;

import com.tuplejump.stargate.lucene.LuceneUtils;
import org.apache.cassandra.cql3.CQL3Type;
import org.apache.cassandra.db.Column;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.BooleanType;
import org.apache.cassandra.db.marshal.CollectionType;
import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.UUIDGen;
import org.apache.lucene.document.*;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

import static com.tuplejump.stargate.lucene.Constants.*;

/**
 * User: satya
 * <p/>
 * Utility methods to deal in fields.
 */
public class Fields {


    public static Field field(String name, AbstractType type, ByteBuffer byteBufferValue, FieldType fieldType) {
        if (fieldType.docValueType() != null) {
            if (fieldType.numericType() != null) return numericDocValuesField(name, type, byteBufferValue);
            else return stringDocValuesField(name, type, byteBufferValue);
        }
        CQL3Type cqlType = type.asCQL3Type();
        if (cqlType == CQL3Type.Native.INT) {
            return new IntField(name, (Integer) type.compose(byteBufferValue), fieldType);
        } else if (cqlType == CQL3Type.Native.VARINT || cqlType == CQL3Type.Native.BIGINT || cqlType == CQL3Type.Native.COUNTER) {
            return new LongField(name, ((Number) type.compose(byteBufferValue)).longValue(), fieldType);
        } else if (cqlType == CQL3Type.Native.DECIMAL || cqlType == CQL3Type.Native.DOUBLE) {
            return new DoubleField(name, ((Number) type.compose(byteBufferValue)).doubleValue(), fieldType);
        } else if (cqlType == CQL3Type.Native.FLOAT) {
            return new FloatField(name, ((Number) type.compose(byteBufferValue)).floatValue(), fieldType);
        } else if (cqlType == CQL3Type.Native.ASCII || cqlType == CQL3Type.Native.TEXT || cqlType == CQL3Type.Native.VARCHAR) {
            return new Field(name, type.getString(byteBufferValue), fieldType);
        } else if (cqlType == CQL3Type.Native.UUID) {
            return new Field(name, type.getString(byteBufferValue), fieldType);
        } else if (cqlType == CQL3Type.Native.TIMEUUID) {
            //TimeUUID toString is not comparable. So we reorder to get a comparable value while searching
            return new Field(name, reorderTimeUUId(type.getString(byteBufferValue)), fieldType);
        } else if (cqlType == CQL3Type.Native.TIMESTAMP) {
            return new LongField(name, ((Date) type.compose(byteBufferValue)).getTime(), fieldType);
        } else if (cqlType == CQL3Type.Native.BOOLEAN) {
            Boolean val = ((Boolean) type.compose(byteBufferValue));
            return new Field(name, val.toString(), fieldType);
        } else {
            return new Field(name, toString(byteBufferValue, type), fieldType);
        }
    }

    public static String reorderTimeUUId(String originalTimeUUID) {
        StringTokenizer tokens = new StringTokenizer(originalTimeUUID, "-");
        if (tokens.countTokens() == 5) {
            String time_low = tokens.nextToken();
            String time_mid = tokens.nextToken();
            String time_high_and_version = tokens.nextToken();
            String variant_and_sequence = tokens.nextToken();
            String node = tokens.nextToken();
            return time_high_and_version + '-' + time_mid + '-' + time_low + '-' + variant_and_sequence + '-' + node;
        }

        return originalTimeUUID;
    }

    public static ByteBuffer defaultValue(AbstractType type) {
        return defaultValue(type, true);
    }

    public static ByteBuffer defaultValue(AbstractType type, boolean min) {
        CQL3Type cqlType = type.asCQL3Type();
        if (cqlType == CQL3Type.Native.INT || cqlType == CQL3Type.Native.VARINT) {
            return ByteBufferUtil.bytes(min ? Integer.MIN_VALUE : Integer.MAX_VALUE);
        } else if (cqlType == CQL3Type.Native.BIGINT) {
            return ByteBufferUtil.bytes(min ? Long.MIN_VALUE : Long.MAX_VALUE);
        } else if (cqlType == CQL3Type.Native.DECIMAL || cqlType == CQL3Type.Native.DOUBLE) {
            return ByteBufferUtil.bytes(min ? Double.MIN_VALUE : Double.MAX_VALUE);
        } else if (cqlType == CQL3Type.Native.FLOAT) {
            return ByteBufferUtil.bytes(min ? Float.MIN_VALUE : Float.MAX_VALUE);
        } else if (cqlType == CQL3Type.Native.TEXT || cqlType == CQL3Type.Native.VARCHAR) {
            return ByteBufferUtil.bytes("");
        } else if (cqlType == CQL3Type.Native.UUID) {
            return ByteBufferUtil.bytes(UUID.randomUUID());
        } else if (cqlType == CQL3Type.Native.TIMEUUID) {
            return ByteBufferUtil.bytes(UUIDGen.getTimeUUID(0));
        } else if (cqlType == CQL3Type.Native.TIMESTAMP) {
            return ByteBufferUtil.bytes(0l);
        } else if (cqlType == CQL3Type.Native.BOOLEAN) {
            return BooleanType.instance.decompose(min ? false : true);
        } else if (type.isCollection()) {
            CollectionType collectionType = (CollectionType) type;
            List<Pair<ByteBuffer, Column>> collection = new ArrayList<>();
            ByteBuffer dummyColumn = defaultValue(collectionType.nameComparator());
            collection.add(Pair.create(dummyColumn, new Column(dummyColumn, defaultValue(collectionType.valueComparator(), min))));
            return collectionType.serialize(collection);
        } else {
            return ByteBufferUtil.EMPTY_BYTE_BUFFER;
        }
    }


    public static void setNumericType(AbstractType type, FieldType luceneFieldType) {
        CQL3Type cqlType = type.asCQL3Type();
        if (cqlType == CQL3Type.Native.INT) {
            luceneFieldType.setNumericType(FieldType.NumericType.INT);
        } else if (cqlType == CQL3Type.Native.VARINT || cqlType == CQL3Type.Native.BIGINT || cqlType == CQL3Type.Native.COUNTER) {
            luceneFieldType.setNumericType(FieldType.NumericType.LONG);
        } else if (cqlType == CQL3Type.Native.DECIMAL || cqlType == CQL3Type.Native.DOUBLE) {
            luceneFieldType.setNumericType(FieldType.NumericType.DOUBLE);
        } else if (cqlType == CQL3Type.Native.FLOAT) {
            luceneFieldType.setNumericType(FieldType.NumericType.FLOAT);
        } else if (cqlType == CQL3Type.Native.TIMESTAMP) {
            luceneFieldType.setNumericType(FieldType.NumericType.LONG);
        }
    }


    private static Field stringDocValuesField(String name, final AbstractType abstractType, final ByteBuffer byteBufferValue) {
        Object value = abstractType.compose(byteBufferValue);
        BytesRef bytesRef = new BytesRef(value.toString());
        final String stripedName = striped + name;
        return new BinaryDocValuesField(stripedName, bytesRef);
    }

    private static Field numericDocValuesField(String name, final AbstractType abstractType, final ByteBuffer byteBufferValue) {
        final String stripedName = striped + name;
        CQL3Type cqlType = abstractType.asCQL3Type();
        if (cqlType == CQL3Type.Native.TIMESTAMP) {
            Date date = (Date) abstractType.compose(byteBufferValue);
            return new NumericDocValuesField(stripedName, date.getTime());
        }
        Number value = (Number) abstractType.compose(byteBufferValue);
        if (cqlType == CQL3Type.Native.INT || cqlType == CQL3Type.Native.VARINT || cqlType == CQL3Type.Native.BIGINT || cqlType == CQL3Type.Native.COUNTER) {
            return new NumericDocValuesField(stripedName, value.longValue());
        } else if (cqlType == CQL3Type.Native.FLOAT) {
            return new FloatDocValuesField(stripedName, value.floatValue());
        } else if (cqlType == CQL3Type.Native.DECIMAL || cqlType == CQL3Type.Native.DOUBLE) {
            return new DoubleDocValuesField(stripedName, value.doubleValue());
        } else throw new IllegalArgumentException(String.format("Invalid type for numeric doc values <%s>", cqlType));
    }


    public static String toString(ByteBuffer byteBuffer, AbstractType<?> type) {
        if (type instanceof CompositeType) {
            CompositeType composite = (CompositeType) type;
            List<AbstractType<?>> types = composite.types;
            ByteBuffer[] components = composite.split(byteBuffer);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < components.length; i++) {
                AbstractType<?> componentType = types.get(i);
                ByteBuffer component = components[i];
                sb.append(componentType.compose(component));
                if (i < types.size() - 1) {
                    sb.append(':');
                }
            }
            return sb.toString();
        } else {
            return type.compose(byteBuffer).toString();
        }
    }

}
