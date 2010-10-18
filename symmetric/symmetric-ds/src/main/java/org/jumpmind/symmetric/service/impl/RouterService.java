/*
 * Licensed to JumpMind Inc under one or more contributor 
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding 
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU Lesser General Public License (the
 * "License"); you may not use this file except in compliance
 * with the License. 
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see           
 * <http://www.gnu.org/licenses/>.
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.  */

package org.jumpmind.symmetric.service.impl;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.lang.StringUtils;
import org.jumpmind.symmetric.common.Constants;
import org.jumpmind.symmetric.common.ParameterConstants;
import org.jumpmind.symmetric.ddl.model.Table;
import org.jumpmind.symmetric.model.Data;
import org.jumpmind.symmetric.model.DataMetaData;
import org.jumpmind.symmetric.model.Node;
import org.jumpmind.symmetric.model.NodeChannel;
import org.jumpmind.symmetric.model.NodeGroupLink;
import org.jumpmind.symmetric.model.NodeSecurity;
import org.jumpmind.symmetric.model.OutgoingBatch;
import org.jumpmind.symmetric.model.OutgoingBatch.Status;
import org.jumpmind.symmetric.model.Router;
import org.jumpmind.symmetric.model.TriggerRouter;
import org.jumpmind.symmetric.route.DataToRouteReaderFactory;
import org.jumpmind.symmetric.route.IBatchAlgorithm;
import org.jumpmind.symmetric.route.IDataRouter;
import org.jumpmind.symmetric.route.IDataToRouteGapDetector;
import org.jumpmind.symmetric.route.IDataToRouteReader;
import org.jumpmind.symmetric.route.IRouterContext;
import org.jumpmind.symmetric.route.RouterContext;
import org.jumpmind.symmetric.service.ClusterConstants;
import org.jumpmind.symmetric.service.IClusterService;
import org.jumpmind.symmetric.service.IConfigurationService;
import org.jumpmind.symmetric.service.IDataService;
import org.jumpmind.symmetric.service.INodeService;
import org.jumpmind.symmetric.service.IOutgoingBatchService;
import org.jumpmind.symmetric.service.IRouterService;
import org.jumpmind.symmetric.service.ITriggerRouterService;
import org.jumpmind.symmetric.statistic.IStatisticManager;
import org.jumpmind.symmetric.statistic.StatisticConstants;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * This service is responsible for routing data to specific nodes and managing
 * the batching of data to be delivered to each node.
 * 
 * @since 2.0
 */
public class RouterService extends AbstractService implements IRouterService {

    private IClusterService clusterService;

    private IDataService dataService;

    private IConfigurationService configurationService;

    private ITriggerRouterService triggerRouterService;

    private IOutgoingBatchService outgoingBatchService;

    private INodeService nodeService;

    private Map<String, IDataRouter> routers;

    private Map<String, IBatchAlgorithm> batchAlgorithms;
    
    private DataToRouteReaderFactory dataToRouteReaderFactory;
    
    private IStatisticManager statisticManager;

    transient ExecutorService readThread = null;

    /**
     * For use in data load events
     */
    public boolean shouldDataBeRouted(IRouterContext context, DataMetaData dataMetaData,
            Set<Node> nodes, boolean initialLoad) {
        IDataRouter router = getDataRouter(dataMetaData.getTriggerRouter());
        Collection<String> nodeIds = router.routeToNodes(context, dataMetaData, nodes, initialLoad);
        for (Node node : nodes) {
            if (nodeIds != null && nodeIds.contains(node.getNodeId())) {
                return true;
            }
        }
        return false;
    }
    
    protected synchronized ExecutorService getReadService() {
        if (readThread == null) {
            readThread = Executors.newSingleThreadExecutor();
        }
        return readThread;
    }
    
    public synchronized void stop() {
        try {
            log.info("RouterShuttingDown");
            getReadService().shutdown();
            readThread = null;
        } catch (Exception ex) {
            log.error(ex);
        }
    }
    
    public synchronized void destroy() {

    }

    /**
     * This method will route data to specific nodes.
     */
    synchronized public void routeData() {
        if (clusterService.lock(ClusterConstants.ROUTE)) {
            try {          
                insertInitialLoadEvents();
                long ts = System.currentTimeMillis();
                IDataToRouteGapDetector gapDetector = dataToRouteReaderFactory.getDataToRouteGapDetector();
                gapDetector.beforeRouting();
                int dataCount = routeDataForEachChannel();
                gapDetector.afterRouting();
                ts = System.currentTimeMillis() - ts;
                if (dataCount > 0 || ts > Constants.LONG_OPERATION_THRESHOLD) {
                    log.info("RoutedDataInTime", dataCount, ts);
                }
            } finally {
                clusterService.unlock(ClusterConstants.ROUTE);
            }
        }
    }
    
    protected void insertInitialLoadEvents() {
        try {
            Node identity = nodeService.findIdentity();
            if (identity != null) {
                Map<String, NodeSecurity> nodeSecurities = nodeService.findAllNodeSecurity(false);
                if (nodeSecurities != null) {
                    for (NodeSecurity security : nodeSecurities.values()) {
                        if (security.isInitialLoadEnabled()
                                && (security.getRegistrationTime() != null || security.getNodeId()
                                        .equals(identity.getCreatedAtNodeId()))) {
                            long ts = System.currentTimeMillis();
                            dataService.insertReloadEvents(nodeService.findNode(security
                                    .getNodeId()));
                            ts = System.currentTimeMillis() - ts;
                            if (ts > Constants.LONG_OPERATION_THRESHOLD) {
                                log.warn("ReloadedNode", security.getNodeId(), ts);
                            } else {
                                log.info("ReloadedNode", security.getNodeId(), ts);
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.error(ex);
        }
    }

    /**
     * We route data channel by channel for two reasons. One is that if/when we
     * decide to multi-thread the routing it is a simple matter of inserting a
     * thread pool here and waiting for all channels to be processed. The other
     * reason is to reduce the number of connections we are required to have.
     */
    protected int routeDataForEachChannel() {
        Node sourceNode = nodeService.findIdentity();
        final List<NodeChannel> channels = configurationService.getNodeChannels(false);
        int dataCount = 0;
        for (NodeChannel nodeChannel : channels) {
            if (!nodeChannel.isSuspendEnabled() && nodeChannel.isEnabled()) {
                dataCount += routeDataForChannel(nodeChannel, sourceNode);
            }
        }
        return dataCount;
    }

    protected int routeDataForChannel(final NodeChannel nodeChannel,
            final Node sourceNode) {
        RouterContext context = null;
        long ts = System.currentTimeMillis();
        int dataCount = -1;
        try {
            context = new RouterContext(sourceNode.getNodeId(), nodeChannel, dataSource);
            dataCount = selectDataAndRoute(context);
            return dataCount;
        } catch (Exception ex) {
            if (context != null) {
                context.rollback();
            }
            log.error("RouterRoutingFailed", ex, nodeChannel.getChannelId());
            return 0;
        } finally {
            try {
                if (dbDialect.supportsJdbcBatch()) {
                    dataService.insertDataEvents(context.getJdbcTemplate(), context.getDataEventList());
                    context.clearDataEventsList();
                }
                completeBatchesAndCommit(context);
                if (context.getLastDataIdProcessed() > 0) {
                    String channelId = nodeChannel.getChannelId();
                    long queryTs = System.currentTimeMillis();
                    long dataLeftToRoute = jdbcTemplate.queryForInt(getSql("selectUnroutedCountForChannelSql"), channelId, context.getLastDataIdProcessed());
                    queryTs = System.currentTimeMillis() - queryTs;
                    if (queryTs > Constants.LONG_OPERATION_THRESHOLD) {
                        log.warn("UnRoutedQueryTookLongTime", channelId, queryTs);
                    }
                    statisticManager.setDataUnRouted(channelId, dataLeftToRoute);
                }
            } catch (SQLException e) {
                if (context != null) {
                    context.rollback();
                }                
                log.error(e);
            } finally {
                context.logStats(log, dataCount, System.currentTimeMillis() - ts);
                context.cleanup();
            }
        }
    }

    protected void completeBatchesAndCommit(RouterContext context) throws SQLException {
        List<OutgoingBatch> batches = new ArrayList<OutgoingBatch>(context.getBatchesByNodes()
                .values());
        for (OutgoingBatch batch : batches) {
            batch.setRouterMillis(System.currentTimeMillis()-batch.getCreateTime().getTime());
            Set<IDataRouter> usedRouters = context.getUsedDataRouters();
            for (IDataRouter dataRouter : usedRouters) {
                dataRouter.completeBatch(context, batch);
            }
            if (!Constants.UNROUTED_NODE_ID.equals(batch.getNodeId())) {
                batch.setStatus(Status.NE);
            }
            outgoingBatchService.updateOutgoingBatch(batch);
            context.getBatchesByNodes().remove(batch.getNodeId());
        }
        context.commit();
        context.setNeedsCommitted(false);
    }

    protected Set<Node> findAvailableNodes(TriggerRouter triggerRouter, RouterContext context) {
        Set<Node> nodes = context.getAvailableNodes().get(triggerRouter);
        if (nodes == null) {
            nodes = new HashSet<Node>();
            Router router = triggerRouter.getRouter();
            List<NodeGroupLink> links = configurationService.getGroupLinksFor(router
                    .getSourceNodeGroupId(), router.getTargetNodeGroupId());
            if (links.size() > 0) {
               nodes.addAll(nodeService.findEnabledNodesFromNodeGroup(router.getTargetNodeGroupId()));           
            } else {
               log.error("RouterIllegalNodeGroupLink", router.getRouterId(), router.getSourceNodeGroupId(), router.getTargetNodeGroupId());
            }
            context.getAvailableNodes().put(triggerRouter, nodes);
        }
        return nodes;
    }

    /**
     * Pre-read data and fill up a queue so we can peek ahead to see if we have
     * crossed a database transaction boundary. Then route each {@link Data}
     * while continuing to keep the queue filled until the result set is
     * entirely read.
     * 
     * @param conn
     *            The connection to use for selecting the data.
     * @param context
     *            The current context of the routing process
     */
    protected int selectDataAndRoute(RouterContext context) throws SQLException {
        IDataToRouteReader reader = dataToRouteReaderFactory.getDataToRouteReader(context);
        getReadService().execute(reader);
        Data data = null;
        int totalDataCount = 0;
        int statsDataCount = 0;
        int statsDataEventCount = 0;
        try {
            do {
                data = reader.take();
                if (data != null) {
                    context.setLastDataIdProcessed(data.getDataId());
                    statsDataCount++;
                    totalDataCount++;
                    statsDataEventCount += routeData(data, context);
                    if (dbDialect.supportsJdbcBatch() && 
                       (parameterService.getInt(ParameterConstants.ROUTING_FLUSH_JDBC_BATCH_SIZE) <= context.getDataEventList().size() || context.isNeedsCommitted())) {
                        dataService.insertDataEvents(context.getJdbcTemplate(), context.getDataEventList());
                        context.clearDataEventsList();
                    }
                    if (context.isNeedsCommitted()) {
                        completeBatchesAndCommit(context);
                        long maxDataToRoute = context.getChannel().getMaxDataToRoute();
                        if (maxDataToRoute > 0 && totalDataCount > maxDataToRoute) {
                            log.info("RoutedMaxNumberData", totalDataCount, context.getChannel()
                                    .getChannelId());
                            break;
                        }
                    }
                    
                    if (statsDataCount > StatisticConstants.FLUSH_SIZE_ROUTER_DATA) {
                        statisticManager.incrementDataRouted(context.getChannel().getChannelId(), statsDataCount);
                        statsDataCount = 0;
                        statisticManager.incrementDataEventInserted(context.getChannel().getChannelId(), statsDataEventCount);
                        statsDataEventCount = 0;
                    }
                }
            } while (data != null);

        } finally {
            reader.setReading(false);
            if (statsDataCount > 0) {
                statisticManager.incrementDataRouted(context.getChannel().getChannelId(), statsDataCount);
            }
            if (statsDataEventCount > 0) {
                statisticManager.incrementDataEventInserted(context.getChannel().getChannelId(), statsDataEventCount);
            }
        }

        return totalDataCount;

    }

    protected int routeData(Data data, RouterContext context) throws SQLException {
        int numberOfDataEventsInserted = 0;
        context.recordTransactionBoundaryEncountered(data);
        List<TriggerRouter> triggerRouters = getTriggerRoutersForData(data);
        if (triggerRouters != null && triggerRouters.size() > 0) {
            for (TriggerRouter triggerRouter : triggerRouters) {
                Table table = dbDialect.getTable(triggerRouter.getTrigger(), true);
                DataMetaData dataMetaData = new DataMetaData(data, table, triggerRouter, context
                        .getChannel());
                Collection<String> nodeIds = null;
                if (!context.getChannel().isIgnoreEnabled()
                        && triggerRouter.isRouted(data.getEventType())) {
                    IDataRouter dataRouter = getDataRouter(triggerRouter);
                    context.addUsedDataRouter(dataRouter);
                    long ts = System.currentTimeMillis();
                    nodeIds = dataRouter.routeToNodes(context, dataMetaData, findAvailableNodes(
                            triggerRouter, context), false);
                    context.incrementStat(System.currentTimeMillis() - ts,
                            RouterContext.STAT_DATA_ROUTER_MS);

                    if (!triggerRouter.isPingBackEnabled() && data.getSourceNodeId() != null && nodeIds != null) {
                        nodeIds.remove(data.getSourceNodeId());
                    }
                }

                numberOfDataEventsInserted += insertDataEvents(context, dataMetaData, nodeIds, triggerRouter);
            }

        } else {
            log.warn("TriggerProcessingFailedMissing", data.getTriggerHistory().getTriggerId(),
                    data.getDataId());
        }
        
        context.incrementStat(numberOfDataEventsInserted, RouterContext.STAT_DATA_EVENTS_INSERTED);
        return numberOfDataEventsInserted;

    }

    protected int insertDataEvents(RouterContext context, DataMetaData dataMetaData,
            Collection<String> nodeIds, TriggerRouter triggerRouter) {
        int numberOfDataEventsInserted = 0;
        if (nodeIds == null || nodeIds.size() == 0) {
            nodeIds = new HashSet<String>(1);
            nodeIds.add(Constants.UNROUTED_NODE_ID);
        }
        long ts = System.currentTimeMillis();
        for (String nodeId : nodeIds) {
            Map<String, OutgoingBatch> batches = context.getBatchesByNodes();
            OutgoingBatch batch = batches.get(nodeId);
            if (batch == null) {
                batch = new OutgoingBatch(nodeId, dataMetaData.getNodeChannel().getChannelId(), Status.RT);
                if (Constants.UNROUTED_NODE_ID.equals(nodeId)) {
                    batch.setStatus(Status.OK);
                }
                outgoingBatchService.insertOutgoingBatch(batch);
                context.getBatchesByNodes().put(nodeId, batch);
            }
            batch.incrementEventCount(dataMetaData.getData().getEventType());
            batch.incrementDataEventCount();
            numberOfDataEventsInserted++;
            try {
                if (dbDialect.supportsJdbcBatch()) {
                    context.addDataEvent(dataMetaData.getData().getDataId(), batch.getBatchId(),
                            triggerRouter.getRouter().getRouterId());
                } else {
                    dataService.insertDataEvent(context.getJdbcTemplate(), dataMetaData.getData()
                            .getDataId(), batch.getBatchId(), triggerRouter.getRouter()
                            .getRouterId());
                }
                if (batchAlgorithms.get(context.getChannel().getBatchAlgorithm()).isBatchComplete(
                        batch, dataMetaData, context)) {
                    context.setNeedsCommitted(true);
                }
            } catch (DataIntegrityViolationException ex) {
                log.warn("RoutedDataIntegrityError", dataMetaData.getData().getDataId());
            }
            
            if (batchAlgorithms.get(context.getChannel().getBatchAlgorithm()).isBatchComplete(
                    batch, dataMetaData, context)) {
                context.setNeedsCommitted(true);
            }
        }
        context.incrementStat(System.currentTimeMillis() - ts,
                RouterContext.STAT_INSERT_DATA_EVENTS_MS);
        return numberOfDataEventsInserted;
    }

    protected IDataRouter getDataRouter(TriggerRouter trigger) {
        IDataRouter router = null;
        if (!StringUtils.isBlank(trigger.getRouter().getRouterType())) {
            router = routers.get(trigger.getRouter().getRouterType());
            if (router == null) {
                log.warn("RouterMissing", trigger.getRouter().getRouterType(), trigger.getTrigger()
                        .getTriggerId());
            }
        }

        if (router == null) {
            return routers.get("default");
        }
        return router;
    }

    protected List<TriggerRouter> getTriggerRoutersForData(Data data) {
        List<TriggerRouter> triggerRouters = triggerRouterService.getTriggerRoutersForCurrentNode(
                false).get((data.getTriggerHistory().getTriggerId()));
        if (triggerRouters == null || triggerRouters.size() == 0) {
            triggerRouters = triggerRouterService.getTriggerRoutersForCurrentNode(true).get(
                    (data.getTriggerHistory().getTriggerId()));
        }
        return triggerRouters;
    }

    public void addDataRouter(String name, IDataRouter dataRouter) {
        routers.put(name, dataRouter);
    }

    public void addBatchAlgorithm(String name, IBatchAlgorithm algorithm) {
        batchAlgorithms.put(name, algorithm);
    }

    public void setConfigurationService(IConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    public void setOutgoingBatchService(IOutgoingBatchService outgoingBatchService) {
        this.outgoingBatchService = outgoingBatchService;
    }

    public void setClusterService(IClusterService clusterService) {
        this.clusterService = clusterService;
    }

    public void setNodeService(INodeService nodeService) {
        this.nodeService = nodeService;
    }

    public void setDataService(IDataService dataService) {
        this.dataService = dataService;
    }

    public void setRouters(Map<String, IDataRouter> routers) {
        this.routers = routers;
    }

    public void setBatchAlgorithms(Map<String, IBatchAlgorithm> batchAlgorithms) {
        this.batchAlgorithms = batchAlgorithms;
    }

    public void setTriggerRouterService(ITriggerRouterService triggerService) {
        this.triggerRouterService = triggerService;
    }
    
    public void setDataToRouteReaderFactory(DataToRouteReaderFactory dataToRouteReaderFactory) {
        this.dataToRouteReaderFactory = dataToRouteReaderFactory;
    }
    
    public void setStatisticManager(IStatisticManager statisticManager) {
        this.statisticManager = statisticManager;
    }
    
}