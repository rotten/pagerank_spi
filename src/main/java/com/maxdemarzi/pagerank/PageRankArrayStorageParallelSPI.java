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
    
    public void compute(int iterations, String relProperty, float relMaxValue) {
       
        final int[] src = new int[nodeCount];
        dst = new AtomicIntegerArray(nodeCount);

        try ( Transaction tx = db.beginTx()) {

            ThreadToStatementContextBridge ctx = this.db.getDependencyResolver().resolveDependency(ThreadToStatementContextBridge.class);
            final ReadOperations ops = ctx.get().readOperations();
            final Integer propertyNameId = ops.propertyKeyGetForName(relProperty);
            //System.out.println("propertyNameId:  " + Integer.toString(propertyNameId));          
            
            int[] degrees = computeDegrees(ops);

            final RelationshipVisitor<RuntimeException> visitor = new RelationshipVisitor<RuntimeException>() {
                public void visit(long relId, int relTypeId, long startNode, long endNode) throws RuntimeException {
 
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
                        } catch (Exception e) {
                            //System.out.println(e);
                            e.printStackTrace();
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
          
                runOperations(pool, rels, relCount , ops, new OpsRunner() {
                    public void run(int id) throws EntityNotFoundException {
                        ops.relationshipVisit(id, visitor);
                    }
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

    private int[] computeDegrees(final ReadOperations ops) throws EntityNotFoundException {
        final int[] degree = new int[nodeCount];
        Arrays.fill(degree, -1);
        PrimitiveLongIterator it = ops.nodesGetAll();
        int totalCount = nodeCount;
        runOperations(pool, it, totalCount, ops, new OpsRunner() {
            public void run(int id) throws EntityNotFoundException {
                degree[id] = ops.nodeGetDegree(id, Direction.OUTGOING);
            }
        });
        return degree;
    }


    public double getResult(long node) {
        return dst != null ? toFloat(dst.get((int) node)) : 0;
    }


    public long numberOfNodes() {
        return nodeCount;
    }

    public String getPropertyName() {
        return "pagerank";
    }

}
