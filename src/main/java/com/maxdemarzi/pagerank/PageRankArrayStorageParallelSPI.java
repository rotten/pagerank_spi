package com.maxdemarzi.pagerank;

import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.api.ReadOperations;
import org.neo4j.kernel.api.exceptions.EntityNotFoundException;
import org.neo4j.kernel.impl.api.RelationshipVisitor;
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static com.maxdemarzi.pagerank.Utils.*;


public class PageRankArrayStorageParallelSPI implements PageRank {
    public static final int ONE_MINUS_ALPHA_INT = toInt(ONE_MINUS_ALPHA);
    private final GraphDatabaseAPI db;
    private final int nodeCount;
    private final ExecutorService pool;
    private final int relCount;
    private AtomicIntegerArray dst;
     
    public PageRankArrayStorageParallelSPI(GraphDatabaseService db, ExecutorService pool) {
        this.pool = pool;
        this.db = (GraphDatabaseAPI) db;
        this.nodeCount = new NodeCounter().getNodeCount(db);
        this.relCount = new NodeCounter().getRelationshipCount(db);
    }

    
    @Override
    public void compute(int iterations) {}
    
    @Override
    public void compute(int iterations, String relProperty, float relMaxValue) {
       
        final int[] src = new int[nodeCount];
        dst = new AtomicIntegerArray(nodeCount);

        try ( Transaction tx = db.beginTx()) {

            ThreadToStatementContextBridge ctx = this.db.getDependencyResolver().resolveDependency(ThreadToStatementContextBridge.class);
            final ReadOperations ops = ctx.get().readOperations();
            final Integer propertyNameId = ops.propertyKeyGetForName(relProperty);
            //System.out.println("propertyNameId:  " + Integer.toString(propertyNameId));          
            
            int[] degrees = computeDegrees(ops, propertyNameId, relMaxValue);

            // Only traverse edges that matter:
            final RelationshipVisitor<RuntimeException> visitor;
            visitor = new RelationshipVisitor<RuntimeException>() {
                @Override
                public void visit(long relId, int relTypeId, long startNode, long endNode) {
                    boolean includeRel = true;
                    if (propertyNameId > 0) {
                        try {
                            if ((boolean)ops.relationshipHasProperty(relId, propertyNameId) == true) {
                                float relValue = (float)ops.relationshipGetProperty(relId, propertyNameId);
                                //System.out.println("relValue:  " + Float.toString(relValue));
                                if (relValue > relMaxValue) {
                                    includeRel = false;
                                }
                            }
                        } catch (EntityNotFoundException e) {
                            System.out.println(e);
                        } catch (NullPointerException e) {
                            System.out.print("relId: " + Long.toString(relId));
                            System.out.print(";  relTypeId:  " + Integer.toString(relTypeId));
                            System.out.print(";  startNode:  " + Long.toString(startNode));
                            System.out.print(";  endNode:    " + Long.toString(endNode));
                            System.out.print(";  propertyNameId:  " + Integer.toString(propertyNameId));
                            System.out.print(";  relMaxValue:  " + Float.toString(relMaxValue) + "\n");
                            //e.printStackTrace();
                            System.out.println(e);
                        }
                    }
                    if (includeRel == true) {
                        dst.addAndGet(((int) endNode), src[(int) startNode]);
                    }
                }
            };

            for (int iteration = 0; iteration < iterations; iteration++) {
                startIteration(src, dst, degrees);

                PrimitiveLongIterator rels = ops.relationshipsGetAll();
          
                runOperations(pool, rels, relCount , ops, (int id) -> {
                    ops.relationshipVisit(id, visitor);
                });
            }
            tx.success();
        } catch (EntityNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void startIteration(int[] src, AtomicIntegerArray dst, int[] degrees) {
        for (int node = 0; node < this.nodeCount; node++) {
            if (degrees[node] == -1) continue;
            src[node]= toInt(ALPHA * toFloat(dst.getAndSet(node, ONE_MINUS_ALPHA_INT)) / degrees[node]);

        }
    }

    // Only count edges that matter:
    private int[] computeDegrees(final ReadOperations ops, final int propertyNameId, final float relMaxValue) throws EntityNotFoundException {
        
        final int[] degree = new int[nodeCount];
        Arrays.fill(degree, -1);
        
        PrimitiveLongIterator it = ops.nodesGetAll();
        int totalCount = nodeCount;
        
        runOperations(pool, it, totalCount, ops, (int id) -> {
            degree[id] = 0;
            PrimitiveLongIterator rel_it = ops.nodeGetRelationships(id, Direction.OUTGOING);
            while(rel_it.hasNext()) {
                long relId = rel_it.next();
                if ((boolean)ops.relationshipHasProperty(relId, propertyNameId) == true) {
                    // This edge has the property, we need to check its value before we include it in our count:
                    float relValue = (float)ops.relationshipGetProperty(relId, propertyNameId);
                    if (relValue <= relMaxValue) {
                        degree[id] += 1;
                    }
                } else {
                    // This edge does not have the property, we'll just include it in our count:
                    degree[id] += 1;
                }
            }
        });
        return degree;
    }

    @Override
    public double getResult(long node) {
        return dst != null ? toFloat(dst.get((int) node)) : 0;
    }


    @Override
    public long numberOfNodes() {
        return nodeCount;
    }

    @Override
    public String getPropertyName() {
        return "pagerank";
    }

}
