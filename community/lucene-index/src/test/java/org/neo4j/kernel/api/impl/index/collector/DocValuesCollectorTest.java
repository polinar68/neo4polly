/*
 * Copyright (c) 2002-2018 "Neo4j,"
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
package org.neo4j.kernel.api.impl.index.collector;

import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.eclipse.collections.api.iterator.LongIterator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.neo4j.internal.kernel.api.IndexOrder;
import org.neo4j.internal.kernel.api.IndexQuery;
import org.neo4j.kernel.api.impl.index.IndexReaderStub;
import org.neo4j.kernel.api.index.IndexProgressor;
import org.neo4j.storageengine.api.schema.IndexDescriptor;
import org.neo4j.values.storable.Value;

import static java.util.Collections.emptyList;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DocValuesCollectorTest
{
    @Test
    void shouldStartWithEmptyMatchingDocs()
    {
        //given
        DocValuesCollector collector = new DocValuesCollector();

        // when
        // then
        assertEquals( emptyList(), collector.getMatchingDocs() );
    }

    @Test
    void shouldCollectAllHitsPerSegment() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector();
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );
        collector.collect( 1 );
        collector.collect( 3 );
        collector.collect( 5 );
        collector.collect( 9 );

        // then
        assertEquals( 4, collector.getTotalHits() );
        List<DocValuesCollector.MatchingDocs> allMatchingDocs = collector.getMatchingDocs();
        assertEquals( 1, allMatchingDocs.size() );
        DocValuesCollector.MatchingDocs matchingDocs = allMatchingDocs.get( 0 );
        assertSame( readerStub.getContext(), matchingDocs.context );
        assertEquals( 4, matchingDocs.totalHits );
        DocIdSetIterator idIterator = matchingDocs.docIdSet;
        assertEquals( 1, idIterator.nextDoc() );
        assertEquals( 3, idIterator.nextDoc() );
        assertEquals( 5, idIterator.nextDoc() );
        assertEquals( 9, idIterator.nextDoc() );
        assertEquals( DocIdSetIterator.NO_MORE_DOCS, idIterator.nextDoc() );
    }

    @Test
    void shouldCollectOneMatchingDocsPerSegment() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector();
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );
        collector.collect( 1 );
        collector.collect( 3 );
        collector.doSetNextReader( readerStub.getContext() );
        collector.collect( 5 );
        collector.collect( 9 );

        // then
        assertEquals( 4, collector.getTotalHits() );
        List<DocValuesCollector.MatchingDocs> allMatchingDocs = collector.getMatchingDocs();
        assertEquals( 2, allMatchingDocs.size() );

        DocValuesCollector.MatchingDocs matchingDocs = allMatchingDocs.get( 0 );
        assertSame( readerStub.getContext(), matchingDocs.context );
        assertEquals( 2, matchingDocs.totalHits );
        DocIdSetIterator idIterator = matchingDocs.docIdSet;
        assertEquals( 1, idIterator.nextDoc() );
        assertEquals( 3, idIterator.nextDoc() );
        assertEquals( DocIdSetIterator.NO_MORE_DOCS, idIterator.nextDoc() );

        matchingDocs = allMatchingDocs.get( 1 );
        assertSame( readerStub.getContext(), matchingDocs.context );
        assertEquals( 2, matchingDocs.totalHits );
        idIterator = matchingDocs.docIdSet;
        assertEquals( 5, idIterator.nextDoc() );
        assertEquals( 9, idIterator.nextDoc() );
        assertEquals( DocIdSetIterator.NO_MORE_DOCS, idIterator.nextDoc() );
    }

    @Test
    void shouldNotSaveScoresWhenNotRequired() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector( false );
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );
        collector.collect( 1 );

        // then
        DocValuesCollector.MatchingDocs matchingDocs = collector.getMatchingDocs().get( 0 );
        assertNull( matchingDocs.scores );
    }

    @Test
    void shouldNotSaveScoresForIndexProgressorWhenNotRequired() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector( false );
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );
        collector.collect( 1 );

        // then
        AtomicReference<AcceptedEntity> ref = new AtomicReference<>();
        IndexProgressor.EntityValueClient client = new EntityValueClientWritingToReference( ref );
        IndexProgressor progressor = collector.getIndexProgressor( "field", client );
        assertTrue( progressor.next() );
        assertFalse( progressor.next() );
        progressor.close();
        AcceptedEntity entity = ref.get();
        assertThat( entity.reference, is( 1L ) );
        assertTrue( Float.isNaN( entity.score ) );
    }

    @Test
    void shouldSaveScoresWhenRequired() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector( true );
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );
        collector.setScorer( constantScorer( 13.42f ) );
        collector.collect( 1 );

        // then
        DocValuesCollector.MatchingDocs matchingDocs = collector.getMatchingDocs().get( 0 );
        assertArrayEquals( new float[]{13.42f}, matchingDocs.scores, 0.001f );
    }

    @Test
    void shouldSaveScoresForIndexProgressorWhenRequired() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector( true );
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );
        float score = 13.42f;

        // when
        collector.doSetNextReader( readerStub.getContext() );
        collector.setScorer( constantScorer( score ) );
        collector.collect( 1 );

        // then
        AtomicReference<AcceptedEntity> ref = new AtomicReference<>();
        IndexProgressor.EntityValueClient client = new EntityValueClientWritingToReference( ref );
        IndexProgressor progressor = collector.getIndexProgressor( "field", client );
        assertTrue( progressor.next() );
        assertFalse( progressor.next() );
        progressor.close();
        AcceptedEntity entity = ref.get();
        assertThat( entity.reference, is( 1L ) );
        assertThat( entity.score, is( score ) );
    }

    @Test
    void shouldSaveScoresInADenseArray() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector( true );
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );
        collector.setScorer( constantScorer( 1.0f ) );
        collector.collect( 1 );
        collector.setScorer( constantScorer( 41.0f ) );
        collector.collect( 41 );

        // then
        DocValuesCollector.MatchingDocs matchingDocs = collector.getMatchingDocs().get( 0 );
        assertArrayEquals( new float[]{1.0f, 41.0f}, matchingDocs.scores, 0.001f );
    }

    @Test
    void shouldDynamicallyResizeScoresArray() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector( true );
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );
        collector.setScorer( constantScorer( 1.0f ) );
        // scores starts with array size of 32, adding 42 docs forces resize
        for ( int i = 0; i < 42; i++ )
        {
            collector.collect( i );
        }

        // then
        DocValuesCollector.MatchingDocs matchingDocs = collector.getMatchingDocs().get( 0 );
        float[] scores = new float[42];
        Arrays.fill( scores, 1.0f );
        assertArrayEquals( scores, matchingDocs.scores, 0.001f );
    }

    @Test
    void shouldReadDocValuesInIndexOrder() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector( false );
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );
        collector.collect( 1 );
        collector.collect( 2 );

        // then
        DocValuesCollector.LongValuesIterator valuesIterator = collector.getValuesIterator( "id" );
        assertEquals( 2, valuesIterator.remaining() );
        assertEquals( 1, valuesIterator.next() );
        assertEquals( 2, valuesIterator.next() );
        assertFalse( valuesIterator.hasNext() );
    }

    @Test
    void shouldSilentlyMergeSegmentsWhenReadingDocValues() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector( false );
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );
        collector.collect( 1 );
        collector.doSetNextReader( readerStub.getContext() );
        collector.collect( 2 );

        // then
        DocValuesCollector.LongValuesIterator valuesIterator = collector.getValuesIterator( "id" );
        assertEquals( 2, valuesIterator.remaining() );
        assertEquals( 1, valuesIterator.next() );
        assertEquals( 2, valuesIterator.next() );
        assertFalse( valuesIterator.hasNext() );
    }

    @Test
    void shouldReturnEmptyIteratorWhenNoDocValues() throws IOException
    {
        // given
        DocValuesCollector collector = new DocValuesCollector( false );
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );

        // then
        DocValuesCollector.LongValuesIterator valuesIterator = collector.getValuesIterator( "id" );
        assertEquals( 0, valuesIterator.remaining() );
        assertFalse( valuesIterator.hasNext() );
    }

    @Test
    void shouldReturnDocValuesInIndexOrderWhenNoSortIsGiven() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector( false );
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );
        collector.collect( 1 );
        collector.collect( 2 );

        // then
        LongIterator valuesIterator = collector.getSortedValuesIterator( "id", null );
        assertEquals( 1, valuesIterator.next() );
        assertEquals( 2, valuesIterator.next() );
        assertFalse( valuesIterator.hasNext() );
    }

    @Test
    void shouldReturnDocValuesInRelevanceOrder() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector( true );
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );
        collector.setScorer( constantScorer( 1.0f ) );
        collector.collect( 1 );
        collector.setScorer( constantScorer( 2.0f ) );
        collector.collect( 2 );

        // then
        LongIterator valuesIterator = collector.getSortedValuesIterator( "id", Sort.RELEVANCE );
        assertEquals( 2, valuesIterator.next() );
        assertEquals( 1, valuesIterator.next() );
        assertFalse( valuesIterator.hasNext() );
    }

    @Test
    void shouldReturnDocValuesInGivenOrder() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector( false );
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );
        collector.collect( 1 );
        collector.collect( 2 );

        // then
        Sort byIdDescending = new Sort( new SortField( "id", SortField.Type.LONG, true ) );
        LongIterator valuesIterator = collector.getSortedValuesIterator( "id", byIdDescending );
        assertEquals( 2, valuesIterator.next() );
        assertEquals( 1, valuesIterator.next() );
        assertFalse( valuesIterator.hasNext() );
    }

    @Test
    void shouldSilentlyMergeSegmentsWhenReturnDocValuesInOrder() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector( true );
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );
        collector.setScorer( constantScorer( 1.0f ) );
        collector.collect( 1 );
        collector.doSetNextReader( readerStub.getContext() );
        collector.setScorer( constantScorer( 2.0f ) );
        collector.collect( 2 );

        // then
        LongIterator valuesIterator = collector.getSortedValuesIterator( "id", Sort.RELEVANCE );
        assertEquals( 2, valuesIterator.next() );
        assertEquals( 1, valuesIterator.next() );
        assertFalse( valuesIterator.hasNext() );
    }

    @Test
    void shouldReturnEmptyIteratorWhenNoDocValuesInOrder() throws Exception
    {
        // given
        DocValuesCollector collector = new DocValuesCollector( false );
        IndexReaderStub readerStub = indexReaderWithMaxDocs( 42 );

        // when
        collector.doSetNextReader( readerStub.getContext() );

        // then
        LongIterator valuesIterator = collector.getSortedValuesIterator( "id", Sort.RELEVANCE );
        assertFalse( valuesIterator.hasNext() );
    }

    private static IndexReaderStub indexReaderWithMaxDocs( int maxDocs )
    {
        NumericDocValues identityValues = new NumericDocValues()
        {
            @Override
            public long get( int docID )
            {
                return docID;
            }
        };
        IndexReaderStub stub = new IndexReaderStub( identityValues );
        stub.setElements( new String[maxDocs] );
        return stub;
    }

    private static Scorer constantScorer( float score )
    {
        return new ConstantScoreScorer( null, score, (DocIdSetIterator) null );
    }

    private static final class AcceptedEntity
    {
        long reference;
        float score;
        Value[] values;
    }

    private static class EntityValueClientWritingToReference implements IndexProgressor.EntityValueClient
    {
        private final AtomicReference<AcceptedEntity> ref;

        private EntityValueClientWritingToReference( AtomicReference<AcceptedEntity> ref )
        {
            this.ref = ref;
        }

        @Override
        public void initialize( IndexDescriptor descriptor, IndexProgressor progressor, IndexQuery[] query, IndexOrder indexOrder, boolean needsValues,
                boolean indexIncludesTransactionState )
        {
        }

        @Override
        public boolean acceptEntity( long reference, float score, Value... values )
        {
            assertNull( ref.get() );
            AcceptedEntity entity = new AcceptedEntity();
            entity.reference = reference;
            entity.score = score;
            entity.values = values;
            ref.set( entity );
            return true;
        }

        @Override
        public boolean needsValues()
        {
            return false;
        }
    }
}
