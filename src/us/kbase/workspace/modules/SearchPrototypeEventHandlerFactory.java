package us.kbase.workspace.modules;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;

import us.kbase.workspace.database.ObjectInformation;
import us.kbase.workspace.database.Permission;
import us.kbase.workspace.database.WorkspaceUser;
import us.kbase.workspace.listener.ListenerInitializationException;
import us.kbase.workspace.listener.WorkspaceEventListener;
import us.kbase.workspace.listener.WorkspaceEventListenerFactory;

/** A prototype event handler that emits workspace events in a format understood by the KBase
 * Search service. In production the workspace handler should feed into a message queue with a
 * generic format and then other services should read from that queue.
 * @author gaprice@lbl.gov
 *
 */
public class SearchPrototypeEventHandlerFactory implements WorkspaceEventListenerFactory {

	//TODO RESKE JAVADOC
	//TODO RESKE TEST
	
	@Override
	public WorkspaceEventListener configure(final Map<String, String> cfg)
			throws ListenerInitializationException {
		//TODO RESKE may need more opts for sharding
		final String mongoHost = cfg.get("mongohost");
		final String mongoDatabase = cfg.get("mongodatabase");
		String mongoUser = cfg.get("mongouser");
		if (mongoUser == null || mongoUser.trim().isEmpty()) {
			mongoUser = null;
		}
		final String mongoPwd = cfg.get("mongopwd");
		LoggerFactory.getLogger(getClass()).info("Starting Search Prototype event handler. " +
				"mongohost={} mongodatabase={} mongouser={}", mongoHost, mongoDatabase, mongoUser);
		return new SearthPrototypeEventHandler(mongoHost, mongoDatabase, mongoUser, mongoPwd);
	}
	
	public class SearthPrototypeEventHandler implements WorkspaceEventListener {
		
		private static final String TRUE = "true";
		private static final String IS_TEMP_NARRATIVE = "is_temporary";
		private static final String NARRATIVE_TYPE = "KBaseNarrative.Narrative";
		private static final String DATA_SOURCE = "WS";
		private static final String NEW_OBJECT_VER = "NEW_VERSION";
		private static final String NEW_OBJECT = "NEW_ALL_VERSIONS";
		private static final String CLONED_WORKSPACE = "COPY_ACCESS_GROUP";
		private static final String RENAME_OBJECT = "RENAME_ALL_VERSIONS";
		private static final String DELETE_OBJECT = "DELETE_ALL_VERSIONS";
		private static final String UNDELETE_OBJECT = "UNDELETE_ALL_VERSIONS";
		private static final String DELETE_WS = "DELETE_ACCESS_GROUP";
		private static final String SET_GLOBAL_READ = "PUBLISH_ACCESS_GROUP";
		private static final String REMOVE_GLOBAL_READ = "UNPUBLISH_ACCESS_GROUP";
		
		// this might need to be configurable
		private static final String COLLECTION = "searchEvents";
		
		private final DB db;

		public SearthPrototypeEventHandler(
				final String mongoHost,
				final String mongoDatabase,
				String mongoUser,
				final String mongoPwd)
				throws ListenerInitializationException {
			//TODO RESKE check args
			if (mongoUser == null || mongoUser.trim().isEmpty()) {
				mongoUser = null;
			}
			try {
				if (mongoUser != null) {
					final MongoCredential creds = MongoCredential.createCredential(
							mongoUser, mongoDatabase, mongoPwd.toCharArray());
					// unclear if and when it's safe to clear the password
					db = new MongoClient(new ServerAddress(mongoHost), creds,
							MongoClientOptions.builder().build()).getDB(mongoDatabase);
				} else {
					db = new MongoClient(new ServerAddress(mongoHost)).getDB(mongoDatabase);
				}
			} catch (MongoException e) {
				throw new ListenerInitializationException(
						"Failed to connect to MongoDB: " + e.getMessage(), e);
			}
		}

		@Override
		public void createWorkspace(final WorkspaceUser user, final long id, final Instant time) {
			// no action
		}

		@Override
		public void cloneWorkspace(
				final WorkspaceUser user,
				final long id,
				final boolean isPublic,
				final Instant time) {
			newWorkspaceEvent(id, CLONED_WORKSPACE, isPublic, time);
		}

		@Override
		public void setWorkspaceMetadata(
				final WorkspaceUser user,
				final long id,
				final Instant time) {
			// no action
		}

		@Override
		public void lockWorkspace(final WorkspaceUser user, final long id, final Instant time) {
			// no action
		}

		@Override
		public void renameWorkspace(
				final WorkspaceUser user,
				final long id,
				final String newname,
				final Instant time) {
			// no action
		}

		@Override
		public void setGlobalPermission(
				final WorkspaceUser user,
				final long id,
				final Permission perm,
				final Instant time) {
			newWorkspaceEvent(id, Permission.READ.equals(perm) ?
					SET_GLOBAL_READ : REMOVE_GLOBAL_READ, null, time);
		}

		@Override
		public void setPermissions(
				final WorkspaceUser user,
				final long id,
				final Permission permission,
				final List<WorkspaceUser> users,
				final Instant time) {
			// no action
		}

		@Override
		public void setWorkspaceDescription(
				final WorkspaceUser user,
				final long id,
				final Instant time) {
			// no action
		}

		@Override
		public void setWorkspaceOwner(
				final WorkspaceUser user,
				final long id,
				final WorkspaceUser newUser,
				final Optional<String> newName,
				final Instant time) {
			// no action
		}

		@Override
		public void setWorkspaceDeleted(
				final WorkspaceUser user,
				final long id,
				final boolean delete,
				final long maxObjectID,
				final Instant time) {
			if (delete) {
				newEvent(id, maxObjectID, null, null, null, DELETE_WS, null, time);
			} else {
				LoggerFactory.getLogger(getClass()).info(
						"Workspace {} was undeleted. Workspace undeletion events are not " +
								"supported by KBase Search", id);
			}
			
		}

		@Override
		public void renameObject(
				final WorkspaceUser user,
				final long workspaceId,
				final long objectId,
				final String newName,
				final Instant time) {
			newEvent(workspaceId, objectId, null, newName, null, RENAME_OBJECT, null, time);
		}

		@Override
		public void revertObject(final ObjectInformation oi, final boolean isPublic) {
			newVersionEvent(oi.getWorkspaceId(), oi.getObjectId(), oi.getVersion(),
					oi.getTypeString(), isPublic, oi.getSavedDate().toInstant());
		}

		@Override
		public void setObjectDeleted(
				final WorkspaceUser user,
				final long workspaceId,
				final long objectId,
				final boolean delete,
				final Instant time) {
			newEvent(workspaceId, objectId, null, null, null,
					delete ? DELETE_OBJECT : UNDELETE_OBJECT, null, time);
		}

		@Override
		public void copyObject(final ObjectInformation oi, final boolean isPublic) {
			newVersionEvent(oi.getWorkspaceId(), oi.getObjectId(), oi.getVersion(),
					oi.getTypeString(), isPublic, oi.getSavedDate().toInstant());
		}

		@Override
		public void copyObject(
				final WorkspaceUser user,
				final long workspaceId,
				final long objectId,
				final int latestVersion,
				final Instant time,
				final boolean isPublic) {
			newObjectEvent(workspaceId, objectId, isPublic, time);
		}
		
		@Override
		public void saveObject(final ObjectInformation oi, final boolean isPublic) {
			if (oi.getTypeString().startsWith(NARRATIVE_TYPE) &&
				TRUE.equals(oi.getUserMetaData().getMetadata().get(IS_TEMP_NARRATIVE))) {
					return;
			}
			newVersionEvent(oi.getWorkspaceId(), oi.getObjectId(), oi.getVersion(),
					oi.getTypeString(), isPublic, oi.getSavedDate().toInstant());
		}

		private void newObjectEvent(
				final long workspaceId,
				final long objectId,
				final boolean isPublic,
				final Instant time) {
			newEvent(workspaceId, objectId, null, null, null, NEW_OBJECT, isPublic, time);
		}
		
		private void newVersionEvent(
				final long workspaceId,
				final long objectId,
				final Integer version,
				final String type,
				final boolean isPublic,
				final Instant time) {
			newEvent(workspaceId, objectId, version, null, type, NEW_OBJECT_VER, isPublic, time);
		}
		
		private void newWorkspaceEvent(
				final long workspaceId,
				final String eventType,
				final Boolean isPublic,
				final Instant time) {
			newEvent(workspaceId, null, null, null, null, eventType, isPublic, time);
		}
		
		private void newEvent(
				final long workspaceId,
				final Long objectId,
				final Integer version,
				final String newName,
				final String type,
				final String eventType,
				final Boolean isPublic,
				final Instant time) {
			if (!wsidOK(workspaceId)) {
				return;
			}
			
			final DBObject dobj = new BasicDBObject();
			dobj.put("strcde", DATA_SOURCE);
			dobj.put("accgrp", (int) workspaceId);
			dobj.put("objid", objectId == null ? null : "" + objectId);
			dobj.put("ver", version);
			dobj.put("newname", newName);
			dobj.put("time", Date.from(time));
			dobj.put("evtype", eventType);
			dobj.put("objtype", type == null ? null : type.split("-")[0]);
			dobj.put("objtypever", type == null ?
					null : Integer.parseInt(type.split("-")[1].split("\\.")[0]));
			dobj.put("public", isPublic);
			dobj.put("status", "UNPROC");
			try {
				db.getCollection(COLLECTION).insert(dobj);
			} catch (MongoException me) {
				LoggerFactory.getLogger(getClass()).error(String.format(
						"RESKE save %s/%s/%s: Failed to connect to MongoDB",
						workspaceId, objectId, version), me);
			}
		}
		
		private boolean wsidOK(final long workspaceId) {
			if (workspaceId > Integer.MAX_VALUE) {
				LoggerFactory.getLogger(getClass()).error(
						"Workspace id {} is out of int range. Cannot send data to KBase Search",
						workspaceId);
				return false;
			}
			return true;
		}
	}
}
