package com.orientechnologies.orient.distributed;

import com.orientechnologies.common.exception.OException;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.cache.OCommandCacheSoftRefs;
import com.orientechnologies.orient.core.command.OCommandOutputListener;
import com.orientechnologies.orient.core.db.*;
import com.orientechnologies.orient.core.db.config.ONodeConfiguration;
import com.orientechnologies.orient.core.db.config.ONodeIdentity;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentEmbedded;
import com.orientechnologies.orient.core.exception.ODatabaseException;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.distributed.impl.*;
import com.orientechnologies.orient.distributed.impl.coordinator.*;
import com.orientechnologies.orient.distributed.impl.coordinator.transaction.OSessionOperationId;
import com.orientechnologies.orient.distributed.impl.metadata.ODistributedContext;
import com.orientechnologies.orient.distributed.impl.metadata.OSharedContextDistributed;
import com.orientechnologies.orient.distributed.impl.structural.*;
import com.orientechnologies.orient.distributed.impl.structural.operations.OCreateDatabaseSubmitRequest;
import com.orientechnologies.orient.distributed.impl.structural.operations.OCreateDatabaseSubmitResponse;
import com.orientechnologies.orient.distributed.impl.structural.operations.ODropDatabaseSubmitRequest;
import com.orientechnologies.orient.distributed.impl.structural.raft.OStructuralFollower;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelBinary;
import com.orientechnologies.orient.server.*;
import com.orientechnologies.orient.server.config.OServerUserConfiguration;
import com.orientechnologies.orient.server.network.OServerNetworkListener;
import com.orientechnologies.orient.server.network.protocol.binary.ONetworkProtocolBinary;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Created by tglman on 08/08/17.
 */
public class OrientDBDistributed extends OrientDBEmbedded implements OServerAware, OServerLifecycleListener {

  private static final String DISTRIBUTED_USER = "distributed_replication";

  private          OServer                       server;
  private volatile boolean                       coordinator = false;
  private volatile ONodeIdentity                 coordinatorIdentity;
  private          OStructuralDistributedContext structuralDistributedContext;
  private          ODistributedNetworkManager    networkManager;
  private          ONodeConfiguration            nodeConfiguration;
  private          OStructuralConfiguration      structuralConfiguration;

  public OrientDBDistributed(String directoryPath, OrientDBConfig config, Orient instance) {
    super(directoryPath, config, instance);

    this.nodeConfiguration = config.getNodeConfiguration();
  }

  public ONodeConfiguration getNodeConfig() {
    return nodeConfiguration;
  }

  public ONodeIdentity getNodeIdentity() {
    return structuralConfiguration.getCurrentNodeIdentity();
  }

  @Override
  public void init(OServer server) {
    //Cannot get the plugin from here, is too early, doing it lazy  
    this.server = server;
    this.server.registerLifecycleListener(this);
  }

  @Override
  public void onAfterActivate() {
    structuralConfiguration = new OStructuralConfiguration(this.getServer().getSystemDatabase(), this);
    checkPort();
    ONodeInternalConfiguration conf = generateInternalConfiguration();
    OCoordinatedExecutorMessageHandler requestHandler = new OCoordinatedExecutorMessageHandler(this);
    networkManager = new ODistributedNetworkManager(requestHandler, getNodeConfig(), conf, this);
    structuralDistributedContext = new OStructuralDistributedContext(this);
    networkManager.startup(structuralDistributedContext.getOpLog());

  }

  public void checkPort() {
    // Use the inbound port in case it's not provided
    if (this.nodeConfiguration.getTcpPort() == null) {
      OServerNetworkListener protocol = server.getListenerByProtocol(ONetworkProtocolBinary.class);
      this.nodeConfiguration.setTcpPort(protocol.getInboundAddr().getPort());
    }
  }

  private ONodeInternalConfiguration generateInternalConfiguration() {
    String userName = getNodeIdentity().getName() + DISTRIBUTED_USER;
    OServerUserConfiguration user = server.getUser(userName);
    if (user == null) {
      server.addTemporaryUser(userName, "" + new SecureRandom().nextLong(), "*");
      user = server.getUser(userName);
    }

    return new ONodeInternalConfiguration(getNodeIdentity(), userName, user.password);
  }

  @Override
  public void onBeforeDeactivate() {
    networkManager.shutdown();
  }

  protected OSharedContext createSharedContext(OAbstractPaginatedStorage storage) {
    if (OSystemDatabase.SYSTEM_DB_NAME.equals(storage.getName())) {
      return new OSharedContextEmbedded(storage, this);
    }
    return new OSharedContextDistributed(storage, this);
  }

  protected ODatabaseDocumentEmbedded newSessionInstance(OAbstractPaginatedStorage storage) {
    if (OSystemDatabase.SYSTEM_DB_NAME.equals(storage.getName())) {
      return new ODatabaseDocumentEmbedded(storage);
    }
    return new ODatabaseDocumentDistributed(storage, getNodeIdentity());
  }

  protected ODatabaseDocumentEmbedded newPooledSessionInstance(ODatabasePoolInternal pool, OAbstractPaginatedStorage storage) {
    if (OSystemDatabase.SYSTEM_DB_NAME.equals(storage.getName())) {
      return new ODatabaseDocumentEmbeddedPooled(pool, storage);
    }
    return new ODatabaseDocumentDistributedPooled(pool, storage, getNodeIdentity());

  }

  public OStorage fullSync(String dbName, String backupPath, OrientDBConfig config) {
    final ODatabaseDocumentEmbedded embedded;
    OAbstractPaginatedStorage storage = null;
    synchronized (this) {

      try {
        storage = storages.get(dbName);

        if (storage != null) {
          OCommandCacheSoftRefs.clearFiles(storage);
          OSharedContext context = sharedContexts.remove(dbName);
          context.close();
          storage.delete();
          storages.remove(dbName);
        }
        storage = (OAbstractPaginatedStorage) disk
            .createStorage(buildName(dbName), new HashMap<>(), maxWALSegmentSize, doubleWriteLogMaxSegSize, generateStorageId());
        embedded = internalCreate(config, storage);
        storages.put(dbName, storage);
      } catch (Exception e) {
        if (storage != null) {
          storage.delete();
        }

        throw OException.wrapException(new ODatabaseException("Cannot restore database '" + dbName + "'"), e);
      }
    }
    storage.restoreFromIncrementalBackup(backupPath);
    //DROP AND CREATE THE SHARED CONTEXT SU HAS CORRECT INFORMATION.
    synchronized (this) {
      OSharedContext context = sharedContexts.remove(dbName);
      context.close();
    }
    ODatabaseDocumentEmbedded instance = openNoAuthorization(dbName);
    instance.close();
    checkCoordinator(dbName);
    return storage;
  }

  @Override
  public void restore(String name, InputStream in, Map<String, Object> options, Callable<Object> callable,
      OCommandOutputListener iListener) {
    super.restore(name, in, options, callable, iListener);
    //THIS MAKE SURE THAT THE SHARED CONTEXT IS INITED.
    ODatabaseDocumentEmbedded instance = openNoAuthorization(name);
    instance.close();
    checkCoordinator(name);
  }

  @Override
  public void restore(String name, String user, String password, ODatabaseType type, String path, OrientDBConfig config) {
    super.restore(name, user, password, type, path, config);
    //THIS MAKE SURE THAT THE SHARED CONTEXT IS INITED.
    ODatabaseDocumentEmbedded instance = openNoAuthorization(name);
    instance.close();
    checkCoordinator(name);
  }

  @Override
  public void create(String name, String user, String password, ODatabaseType type, OrientDBConfig config) {
    if (OSystemDatabase.SYSTEM_DB_NAME.equals(name)) {
      super.create(name, user, password, type, config);
      return;
    }
    checkReadyForHandleRequests();
    //TODO:RESOLVE CONFIGURATION PARAMETERS
    OCreateDatabaseSubmitResponse created = (OCreateDatabaseSubmitResponse) structuralDistributedContext
        .forwardAndWait(new OCreateDatabaseSubmitRequest(name, type.name(), new HashMap<>()));
    if (!created.isSuccess()) {
      throw new ODatabaseException(created.getError());
    }
    //This initialize the distributed configuration.
    checkCoordinator(name);
  }

  @Override
  public ODatabaseDocumentEmbedded openNoAuthenticate(String name, String user) {
    checkDatabaseReady(name);
    ODatabaseDocumentEmbedded session = super.openNoAuthenticate(name, user);
    checkCoordinator(name);
    return session;
  }

  @Override
  public ODatabaseDocumentEmbedded openNoAuthorization(String name) {
    checkDatabaseReady(name);
    ODatabaseDocumentEmbedded session = super.openNoAuthorization(name);
    checkCoordinator(name);
    return session;

  }

  @Override
  public ODatabaseDocumentInternal open(String name, String user, String password, OrientDBConfig config) {
    checkDatabaseReady(name);
    ODatabaseDocumentInternal session = super.open(name, user, password, config);
    checkCoordinator(name);
    return session;
  }

  @Override
  public ODatabaseDocumentInternal poolOpen(String name, String user, String password, ODatabasePoolInternal pool) {
    checkDatabaseReady(name);
    ODatabaseDocumentInternal session = super.poolOpen(name, user, password, pool);
    checkCoordinator(name);
    return session;

  }

  private synchronized void checkCoordinator(String database) {
    if (!database.equals(OSystemDatabase.SYSTEM_DB_NAME)) {
      OSharedContext shared = sharedContexts.get(database);
      if (shared instanceof OSharedContextDistributed) {
        ODistributedContext distributed = ((OSharedContextDistributed) shared).getDistributedContext();
        if (distributed.getCoordinator() == null) {
          if (coordinator) {
            //distributed.makeCoordinator(getNodeIdentity(), shared);
          } else {
            distributed.setExternalCoordinator(coordinatorIdentity);
          }
        }
      }
    }
  }

  public synchronized void nodeConnected(ONodeIdentity nodeIdentity) {
    if (this.getNodeIdentity().equals(nodeIdentity))
      return;
    for (OSharedContext context : sharedContexts.values()) {
      if (isContextToIgnore(context))
        continue;
      ODistributedContext distributed = ((OSharedContextDistributed) context).getDistributedContext();
      distributed.connected(nodeIdentity);
    }
    structuralDistributedContext.connected(nodeIdentity);
  }

  public synchronized void nodeDisconnected(ONodeIdentity nodeIdentity) {
    if (this.getNodeIdentity().equals(nodeIdentity))
      return;
    structuralDistributedContext.disconnected(nodeIdentity);
    for (OSharedContext context : sharedContexts.values()) {
      if (isContextToIgnore(context))
        continue;
      ODistributedContext distributed = ((OSharedContextDistributed) context).getDistributedContext();
      distributed.disconnected(nodeIdentity);
    }
  }

  private boolean isContextToIgnore(OSharedContext context) {
    return context.getStorage().getName().equals(OSystemDatabase.SYSTEM_DB_NAME) || context.getStorage().isClosed();
  }

  public Set<ONodeIdentity> getActiveNodes() {
    return networkManager.getRemoteServers();
  }

  private void realignToLog(OLogId lastValid) {
    OOperationLog opLog = this.structuralDistributedContext.getOpLog();
    OLogId lastPersistent = opLog.lastPersistentLog();
    if (lastPersistent != null && lastValid != null) {
      nodeSyncRequest(lastPersistent);
      //TODO: at the end of the sync, the state should align to the log
    } else if (lastValid != null) {
      nodeFirstJoin();
    } else if (lastPersistent != null) {
      // If the leader has nothing and this node something, start from scratch anyway
      nodeFirstJoin();
    } else {
      //TODO: First join for everyone, do nothing for a while ? and then sync the databases existing on the disc on nodes.
    }
  }

  private void nodeFirstJoin() {
    // TODO handle eventual database that are in the database folder but not registered in the configuration
    this.getStructuralDistributedContext().getSubmitContext().send(new OSessionOperationId(), new OSyncRequest(Optional.empty()));
  }

  public void nodeSyncRequest(OLogId logId) {
    this.getStructuralDistributedContext().getSubmitContext().send(new OSessionOperationId(), new OSyncRequest(Optional.of(logId)));
  }

  private synchronized void syncDatabase(OStructuralNodeDatabase configuration) {

  }

  private synchronized void parkDatabase(String database) {
    forceDatabaseClose(database);
    //TODO: Move The  database folder somewhere else, backup folder?
  }

  private void triggerParkDatabase(String databaseName) {
    //TODO: This should make sure that two park do not happen concurrently on the same database
    executeNoDb(() -> {
      parkDatabase(databaseName);
      return null;
    });
  }

  private void triggerSyncDatabase(OStructuralNodeDatabase db) {
    //TODO: This should make sure that two sync do not happen concurrently on the same database
    executeNoDb(() -> {
      syncDatabase(db);
      return null;
    });
  }

  public synchronized void syncToConfiguration(OLogId lastId, OReadStructuralSharedConfiguration sharedConfiguration) {
    getStructuralConfiguration().receiveSharedConfiguration(lastId, sharedConfiguration);
    OStructuralNodeConfiguration nodeConfig = getStructuralConfiguration().readSharedConfiguration().getNode(getNodeIdentity());
    assert nodeConfig != null : "if arrived here the configuration should have this node configured";
    super.loadAllDatabases();
    Collection<OStorage> storages = super.getStorages();
    for (OStorage st : storages) {
      if (st instanceof OAbstractPaginatedStorage) {
        OAbstractPaginatedStorage storage = (OAbstractPaginatedStorage) st;
        OStructuralNodeDatabase db = nodeConfig.getDatabase(storage.getUuid());
        if (db != null) {
          triggerSyncDatabase(db);
        } else {
          triggerParkDatabase(st.getName());
        }
      }
    }
    Set<UUID> existingIds = storages.stream().map((s) -> ((OAbstractPaginatedStorage) s).getUuid()).collect(Collectors.toSet());
    for (OStructuralNodeDatabase db : nodeConfig.getDatabases()) {
      if (!existingIds.contains(db.getUuid())) {
        triggerSyncDatabase(db);
      }
    }
  }

  public synchronized ODistributedContext getDistributedContext(String database) {
    OSharedContext shared = sharedContexts.get(database);
    if (shared != null) {
      return ((OSharedContextDistributed) shared).getDistributedContext();
    }
    return null;
  }

  public OStructuralDistributedContext getStructuralDistributedContext() {
    return structuralDistributedContext;
  }

  @Override
  public void drop(String name, String user, String password) {
    if (OSystemDatabase.SYSTEM_DB_NAME.equals(name)) {
      super.drop(name, user, password);
      return;
    }
    checkReadyForHandleRequests();
    //TODO:RESOLVE CONFIGURATION PARAMETERS
    structuralDistributedContext.getSubmitContext().sendAndWait(new OSessionOperationId(), new ODropDatabaseSubmitRequest(name));
  }

  public void coordinatedRequest(OClientConnection connection, int requestType, int clientTxId, OChannelBinary channel)
      throws IOException {
    networkManager.coordinatedRequest(connection, requestType, clientTxId, channel);
  }

  public synchronized void internalCreateDatabase(OSessionOperationId operationId, String database, String type,
      Map<String, String> configurations) {
    //TODO:INIT CONFIG
    super.create(database, null, null, ODatabaseType.valueOf(type), null);
    OStructuralSharedConfiguration config = getStructuralConfiguration().modifySharedConfiguration();
    config.addDatabase(database);
    getStructuralConfiguration().update(config);
    checkCoordinator(database);
    //TODO: double check this notify, it may unblock as well checkReadyForHandleRequests that is not what is expected
    this.notifyAll();
  }

  public void internalDropDatabase(String database) {
    OSharedContextDistributed context = (OSharedContextDistributed) getOrCreateSharedContext(getStorage(database));
    context.getDistributedContext().close();
    super.drop(database, null, null);
    OStructuralSharedConfiguration config = getStructuralConfiguration().modifySharedConfiguration();
    config.removeDatabase(database);
    getStructuralConfiguration().update(config);
  }

  public synchronized void checkReadyForHandleRequests() {
    if (structuralDistributedContext != null) {
      structuralDistributedContext.waitApplyLastRequest();
    }
  }

  public synchronized void setOnline() {
    this.notifyAll();
  }

  public synchronized void checkDatabaseReady(String database) {
    checkReadyForHandleRequests();
  }

  @Override
  public void close() {
    if (structuralDistributedContext != null) {
      structuralDistributedContext.waitApplyLastRequest();
      OStructuralFollower follower = structuralDistributedContext.getFollower();
      if (follower != null) {
        follower.close();
      }
    }
    if (networkManager != null) {
      this.networkManager.shutdown();
    }
    super.close();
  }

  public OServer getServer() {
    return server;
  }

  public OStructuralConfiguration getStructuralConfiguration() {
    return structuralConfiguration;
  }

  public ODistributedNetwork getNetworkManager() {
    return networkManager;
  }

  public OSharedContextDistributed getSharedContext(String database) {
    return (OSharedContextDistributed) sharedContexts.get(database);
  }
}
