package us.kbase.workspace.kbase;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.LoggerFactory;

import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;

import us.kbase.abstracthandle.AbstractHandleClient;
import us.kbase.auth.AuthConfig;
import us.kbase.auth.AuthException;
import us.kbase.auth.AuthToken;
import us.kbase.auth.ConfigurableAuthService;
import us.kbase.common.service.ServerException;
import us.kbase.handlemngr.HandleMngrClient;
import us.kbase.shock.client.BasicShockClient;
import us.kbase.shock.client.exceptions.InvalidShockUrlException;
import us.kbase.shock.client.exceptions.ShockHttpException;
import us.kbase.typedobj.core.LocalTypeProvider;
import us.kbase.typedobj.core.TempFilesManager;
import us.kbase.typedobj.core.TypedObjectValidator;
import us.kbase.typedobj.db.MongoTypeStorage;
import us.kbase.typedobj.db.TypeDefinitionDB;
import us.kbase.typedobj.exceptions.TypeStorageException;
import us.kbase.workspace.database.ResourceUsageConfigurationBuilder;
import us.kbase.workspace.database.Types;
import us.kbase.workspace.database.Workspace;
import us.kbase.workspace.database.WorkspaceDatabase;
import us.kbase.workspace.database.WorkspaceUser;
import us.kbase.workspace.database.exceptions.CorruptWorkspaceDBException;
import us.kbase.workspace.database.exceptions.WorkspaceDBException;
import us.kbase.workspace.database.mongo.BlobStore;
import us.kbase.workspace.database.mongo.GridFSBlobStore;
import us.kbase.workspace.database.mongo.MongoWorkspaceDB;
import us.kbase.workspace.database.mongo.ShockBlobStore;
import us.kbase.workspace.kbase.KBaseWorkspaceConfig.ListenerConfig;
import us.kbase.workspace.kbase.admin.AdministratorHandler;
import us.kbase.workspace.kbase.admin.AdministratorHandlerException;
import us.kbase.workspace.kbase.admin.DefaultAdminHandler;
import us.kbase.workspace.kbase.admin.KBaseAuth2AdminHandler;
import us.kbase.workspace.kbase.admin.WorkspaceAdministration;
import us.kbase.workspace.listener.ListenerInitializationException;
import us.kbase.workspace.listener.WorkspaceEventListener;
import us.kbase.workspace.listener.WorkspaceEventListenerFactory;

public class InitWorkspaceServer {
	
	//TODO TEST unittests
	//TODO JAVADOC
	
	private static final String COL_SETTINGS = InitConstants.COL_SETTINGS;
	public static final String COL_SHOCK_NODES = InitConstants.COL_SHOCK_NODES;
	
	private static final int ADMIN_CACHE_MAX_SIZE = 100; // seems like more than enough admins
	private static final int ADMIN_CACHE_EXP_TIME_MS = 5 * 60 * 1000; // cache admin role for 5m
	
	private static int maxUniqueIdCountPerCall = 100000;

	private static int instanceCount = 0;
	private static boolean wasTempFileCleaningDone = false;
	
	public static abstract class InitReporter {
		
		private boolean failed = false;
		
		public abstract void reportInfo(final String info);
		
		public void reportFail(final String fail) {
			failed = true;
			handleFail(fail);
		}
		
		public abstract void handleFail(final String fail);
		
		public boolean isFailed() {
			return failed;
		}
	}
	
	public static class WorkspaceInitResults {
		private Workspace ws;
		private WorkspaceServerMethods wsmeth;
		private WorkspaceAdministration wsadmin;
		private Types types;
		private URL handleManagerUrl;
		private AuthToken handleMgrToken;
		
		public WorkspaceInitResults(
				final Workspace ws,
				final WorkspaceServerMethods wsmeth,
				final WorkspaceAdministration wsadmin,
				final Types types,
				final URL handleManagerUrl,
				final AuthToken handleMgrToken) {
			super();
			this.ws = ws;
			this.wsmeth = wsmeth;
			this.wsadmin = wsadmin;
			this.types = types;
			this.handleManagerUrl = handleManagerUrl;
			this.handleMgrToken = handleMgrToken;
		}

		public Workspace getWs() {
			return ws;
		}

		public WorkspaceServerMethods getWsmeth() {
			return wsmeth;
		}

		public WorkspaceAdministration getWsAdmin() {
			return wsadmin;
		}
		
		public Types getTypes() {
			return types;
		}

		public URL getHandleManagerUrl() {
			return handleManagerUrl;
		}

		public AuthToken getHandleMgrToken() {
			return handleMgrToken;
		}
	}
	
	public static void setMaximumUniqueIdCountForTests(final int count) {
		maxUniqueIdCountPerCall = count;
	}
	
	public static WorkspaceInitResults initWorkspaceServer(
			final KBaseWorkspaceConfig cfg,
			final InitReporter rep) {
		final TempFilesManager tfm = initTempFilesManager(cfg.getTempDir(), rep);
		
		final ConfigurableAuthService auth = setUpAuthClient(cfg, rep);
		if (rep.isFailed()) {
			rep.reportFail("Server startup failed - all calls will error out.");
			return null;
		} 
		
		AuthToken handleMgrToken = null;
		if (!cfg.ignoreHandleService()) {
			handleMgrToken = getHandleToken(cfg, rep, auth);
			if (!rep.isFailed()) {
				checkHandleServiceConnection(cfg.getHandleServiceURL(), handleMgrToken, rep);
			}
			if (!rep.isFailed()) {
				checkHandleManagerConnection(cfg.getHandleManagerURL(), handleMgrToken, rep);
			}
		}
		
		if (rep.isFailed()) {
			rep.reportFail("Server startup failed - all calls will error out.");
			return null;
		} 
		rep.reportInfo("Starting server using connection parameters:\n" +
				cfg.getParamReport());
		rep.reportInfo("Temporary file location: " + tfm.getTempDir());

		final WorkspaceDependencies wsdeps;
		final AdministratorHandler ah;
		final Workspace ws;
		try {
			wsdeps = getDependencies(cfg, tfm, auth);
			ws = new Workspace(
					wsdeps.mongoWS,
					new ResourceUsageConfigurationBuilder().build(),
					wsdeps.validator,
					wsdeps.listeners);
			ah = getAdminHandler(cfg, ws);
		} catch (WorkspaceInitException wie) {
			rep.reportFail(wie.getLocalizedMessage());
			rep.reportFail(
					"Server startup failed - all calls will error out.");
			return null;
		}
		rep.reportInfo(String.format("Initialized %s backend",
				wsdeps.backendType));
		Types types = new Types(wsdeps.typeDB);
		WorkspaceServerMethods wsmeth = new WorkspaceServerMethods(
				ws, types, cfg.getHandleServiceURL(), cfg.getHandleManagerURL(),
				handleMgrToken, maxUniqueIdCountPerCall, auth);
		WorkspaceAdministration wsadmin = new WorkspaceAdministration(
				ws, wsmeth, types, ah,
				ADMIN_CACHE_MAX_SIZE, ADMIN_CACHE_EXP_TIME_MS);
		final String mem = String.format(
				"Started workspace server instance %s. Free mem: %s Total mem: %s, Max mem: %s",
				++instanceCount, Runtime.getRuntime().freeMemory(),
				Runtime.getRuntime().totalMemory(),
				Runtime.getRuntime().maxMemory());
		rep.reportInfo(mem);
		return new WorkspaceInitResults(
				ws, wsmeth, wsadmin, types, cfg.getHandleManagerURL(),
				handleMgrToken);
	}
	
	private static AdministratorHandler getAdminHandler(
			final KBaseWorkspaceConfig cfg,
			final Workspace ws) throws WorkspaceInitException {
		if (cfg.getAdminReadOnlyRoles().isEmpty() && cfg.getAdminRoles().isEmpty()) {
			final String a = cfg.getWorkspaceAdmin();
			final WorkspaceUser admin = a == null || a.trim().isEmpty() ?
					null : new WorkspaceUser(a);
			return new DefaultAdminHandler(ws, admin);
		} else {
			final PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
			cm.setMaxTotal(1000); //perhaps these should be configurable
			cm.setDefaultMaxPerRoute(1000);
			//TODO set timeouts for the client for 1/2m for conn req timeout and std timeout
			final CloseableHttpClient client = HttpClients.custom().setConnectionManager(cm)
					.build();
			try {
				return new KBaseAuth2AdminHandler(
						client,
						cfg.getAuth2URL(),
						cfg.getAdminReadOnlyRoles(),
						cfg.getAdminRoles());
			} catch (URISyntaxException | AdministratorHandlerException e) {
				throw new WorkspaceInitException(
						"Could not start the KBase auth2 adminstrator handler: " +
						e.getMessage(), e);
			}
		}
	}

	private static class WorkspaceDependencies {
		public TypeDefinitionDB typeDB;
		public TypedObjectValidator validator;
		public WorkspaceDatabase mongoWS;
		public String backendType;
		public List<WorkspaceEventListener> listeners;
	}
	
	private static WorkspaceDependencies getDependencies(
			final KBaseWorkspaceConfig cfg,
			final TempFilesManager tfm,
			final ConfigurableAuthService auth)
			throws WorkspaceInitException {
		
		final WorkspaceDependencies deps = new WorkspaceDependencies();
		//TODO CODE update to new mongo APIs
		final DB db = buildMongo(cfg, cfg.getDBname()).getDB(cfg.getDBname());
		
		final Settings settings = getSettings(db);
		deps.backendType = settings.isGridFSBackend() ? "GridFS" : "Shock";
		
		final BlobStore bs = setupBlobStore(db, deps.backendType, settings.getShockUrl(),
				settings.getShockUser(), cfg, auth);
		
		// see https://jira.mongodb.org/browse/JAVA-2656
		final DB typeDB = buildMongo(cfg, settings.getTypeDatabase())
				.getDB(settings.getTypeDatabase());
		
		try {
			deps.typeDB = new TypeDefinitionDB(new MongoTypeStorage(typeDB));
		} catch (TypeStorageException e) {
			throw new WorkspaceInitException("Couldn't set up the type database: "
					+ e.getLocalizedMessage(), e);
		}
		deps.validator = new TypedObjectValidator(
				new LocalTypeProvider(deps.typeDB));
		try {
			deps.mongoWS = new MongoWorkspaceDB(db, bs, tfm);
		} catch (WorkspaceDBException wde) {
			throw new WorkspaceInitException(
					"Error initializing the workspace database: " +
					wde.getLocalizedMessage(), wde);
		}
		deps.listeners = loadListeners(cfg);
		return deps;
	}
	
	private static MongoClient buildMongo(final KBaseWorkspaceConfig c, final String dbName)
			throws WorkspaceInitException {
		//TODO ZLATER MONGO handle shards & replica sets
		try {
			if (c.getMongoUser() != null) {
				final MongoCredential creds = MongoCredential.createCredential(
						c.getMongoUser(), dbName, c.getMongoPassword().toCharArray());
				// unclear if and when it's safe to clear the password
				return new MongoClient(new ServerAddress(c.getHost()), creds,
						MongoClientOptions.builder().build());
			} else {
				return new MongoClient(new ServerAddress(c.getHost()));
			}
		} catch (MongoException e) {
			LoggerFactory.getLogger(InitWorkspaceServer.class).error(
					"Failed to connect to MongoDB: " + e.getMessage(), e);
			throw new WorkspaceInitException("Failed to connect to MongoDB: " + e.getMessage(), e);
		}
	}
	
	private static List<WorkspaceEventListener> loadListeners(final KBaseWorkspaceConfig cfg)
			throws WorkspaceInitException {
		final List<WorkspaceEventListener> wels = new LinkedList<>();
		for (final ListenerConfig lc: cfg.getListenerConfigs()) {
			final WorkspaceEventListenerFactory fac = loadFac(lc.getListenerClass());
			try {
				wels.add(fac.configure(lc.getConfig()));
			} catch (ListenerInitializationException e) {
				throw new WorkspaceInitException(String.format(
						"Error initializing listener %s: %s",
						lc.getListenerClass(), e.getMessage()), e);
			}
		}
		return wels;
	}

	private static WorkspaceEventListenerFactory loadFac(final String className)
			throws WorkspaceInitException {
		final Class<?> cls;
		try {
			cls = Class.forName(className);
		} catch (ClassNotFoundException e) {
			throw new WorkspaceInitException(String.format(
					"Cannot find class %s: %s", className, e.getMessage()), e);
		}
		final Set<Class<?>> interfaces = new HashSet<>(Arrays.asList(cls.getInterfaces()));
		if (!interfaces.contains(WorkspaceEventListenerFactory.class)) {
			throw new WorkspaceInitException(String.format(
					"Module %s must implement %s interface",
					className, WorkspaceEventListenerFactory.class.getName()));
		}
		@SuppressWarnings("unchecked")
		final Class<WorkspaceEventListenerFactory> inter =
				(Class<WorkspaceEventListenerFactory>) cls;
		try {
			return inter.newInstance();
		} catch (IllegalAccessException | InstantiationException e) {
			throw new WorkspaceInitException(String.format(
					"Module %s could not be instantiated: %s", className, e.getMessage()), e);
		}
	}

	private static AuthToken getBackendToken(
			final String shockUserFromSettings,
			final KBaseWorkspaceConfig cfg,
			final ConfigurableAuthService auth)
			throws WorkspaceInitException {
		if (cfg.getBackendToken() == null) {
			throw new WorkspaceInitException(
					"No token provided for Shock backend in configuration");
		}
		try {
			final AuthToken t = auth.validateToken(cfg.getBackendToken());
			if (!t.getUserName().equals(shockUserFromSettings)) {
				throw new WorkspaceInitException(String.format(
						"The username from the backend token, %s, does " +
						"not match the backend username stored in the " +
						"database, %s",
						t.getUserName(), shockUserFromSettings));
			}
			return t;
		} catch (AuthException e) {
			throw new WorkspaceInitException(
					"Couldn't log in with backend credentials for user " +
					shockUserFromSettings + ": " + e.getMessage(), e);
		} catch (IOException e) {
			throw new WorkspaceInitException(
					"Couldn't contact the auth service to obtain a token for the backend: "
					+ e.getMessage(), e);
		}
	}

	private static BlobStore setupBlobStore(
			final DB db,
			final String blobStoreType,
			final String blobStoreURL,
			final String shockUserFromSettings,
			final KBaseWorkspaceConfig cfg,
			final ConfigurableAuthService auth)
			throws WorkspaceInitException {
		
		if (blobStoreType.equals("GridFS")) {
			return new GridFSBlobStore(db);
		}
		if (blobStoreType.equals("Shock")) {
			final URL shockurl;
			try {
				shockurl = new URL(blobStoreURL);
			} catch (MalformedURLException mue) {
				throw new WorkspaceInitException(
						"Workspace database settings document has bad shock url: "
						+ blobStoreURL, mue);
			}
			final AuthToken token = getBackendToken(shockUserFromSettings, cfg, auth);
			try {
				return new ShockBlobStore(
						db.getCollection(COL_SHOCK_NODES), new BasicShockClient(shockurl, token));
			} catch (InvalidShockUrlException isue) {
				throw new WorkspaceInitException(
						"The shock url " + shockurl + " is invalid", isue);
			} catch (ShockHttpException she) {
				throw new WorkspaceInitException(
						"Shock appears to be misconfigured - the client could not initialize. " +
						"Shock said: " + she.getLocalizedMessage(), she);
			} catch (IOException ioe) {
				throw new WorkspaceInitException(
						"Could not connect to the shock backend: " +
						ioe.getLocalizedMessage(), ioe);
			}
		}
		throw new WorkspaceInitException("Unknown backend type: " + blobStoreType);
	}

	
	private static Settings getSettings(final DB db)
			throws WorkspaceInitException {
		
		if (!db.collectionExists(COL_SETTINGS)) {
			throw new WorkspaceInitException(
					"There is no settings collection in the workspace database");
		}
		final DBCollection settings = db.getCollection(COL_SETTINGS);
		if (settings.count() != 1) {
			throw new WorkspaceInitException(
					"Zero or more than one settings document exists in the workspace " +
					"database settings collection");
		}
		final DBObject set = settings.findOne();
		final Settings wsSettings;
		try {
			wsSettings = new Settings(
					(String) set.get(Settings.SET_SHOCK_LOC),
					(String) set.get(Settings.SET_SHOCK_USER),
					(String) set.get(Settings.SET_BACKEND),
					(String) set.get(Settings.SET_TYPE_DB));
		} catch (CorruptWorkspaceDBException e) {
			throw new WorkspaceInitException(
					"Unable to unmarshal settings workspace database document: " +
							e.getMessage(), e);
		}
		if (db.getName().equals(wsSettings.getTypeDatabase())) {
			throw new WorkspaceInitException(
					"The type database name is the same as the workspace database name: "
							+ db.getName());
		}
		return wsSettings;
	}

	private static TempFilesManager initTempFilesManager(
			final String tempDir,
			final InitReporter rep) {
		try {
			final TempFilesManager tfm = new TempFilesManager(
					new File(tempDir));
			if (!wasTempFileCleaningDone) {
				// check the directory is writeable
				tfm.generateTempFile("startuptest", "tmp");
				wasTempFileCleaningDone = true;
				tfm.cleanup();
			}
			return tfm;
		} catch (Exception e) {
			rep.reportFail("There was an error initializing the temporary " +
					"file location: " + e.getLocalizedMessage());
			return null;
		}
	}
	
	private static AuthToken getHandleToken(
			final KBaseWorkspaceConfig cfg,
			final InitReporter rep,
			final ConfigurableAuthService auth) {
		try {
			return auth.validateToken(cfg.getHandleManagerToken());
		} catch (AuthException e) {
			rep.reportFail("Invalid handle manager token: " + e.getMessage());
		} catch (IOException e) {
			rep.reportFail("Couldn't contact the auth service to obtain a token " +
					"for the handle manager: " + e.getLocalizedMessage());
		}
		return null;
	}
	

	private static void checkHandleServiceConnection(
			final URL handleServiceUrl,
			final AuthToken handleMgrToken,
			final InitReporter rep) {
		try {
			final AbstractHandleClient cli = new AbstractHandleClient(
					handleServiceUrl, handleMgrToken);
			if (handleServiceUrl.getProtocol().equals("http")) {
				rep.reportInfo("Warning - the Handle Service url uses insecure http. " +
						"https is recommended.");
				cli.setIsInsecureHttpConnectionAllowed(true);
			}
			cli.isOwner(new LinkedList<String>());
		} catch (Exception e) {
			if (!(e instanceof ServerException) || !e.getMessage().contains(
							"can not execute select * from Handle")) {
				rep.reportFail("Could not establish a connection to the Handle Service at "
						+ handleServiceUrl + ": " + e.getMessage());
			}
		}
	}
	
	private static void checkHandleManagerConnection(
			final URL handleManagerUrl,
			final AuthToken handleMgrToken,
			final InitReporter rep) {
		try {
			final HandleMngrClient cli = new HandleMngrClient(
					handleManagerUrl, handleMgrToken);
			if (handleManagerUrl.getProtocol().equals("http")) {
				rep.reportInfo("Warning - the Handle Manager url uses insecure http. " +
						"https is recommended.");
				cli.setIsInsecureHttpConnectionAllowed(true);
			}
			cli.setPublicRead(Arrays.asList("FAKEHANDLE_-100"));
		} catch (Exception e) {
			if (!(e instanceof ServerException) || !e.getMessage().contains(
							"Unable to set acl(s) on handles FAKEHANDLE_-100")) {
				rep.reportFail("Could not establish a connection to the Handle Manager Service at "
						+ handleManagerUrl + ": " + e.getMessage());
			}
		}
	}
	
	private static ConfigurableAuthService setUpAuthClient(
			final KBaseWorkspaceConfig cfg,
			final InitReporter rep) {
		final AuthConfig c = new AuthConfig();
		if (cfg.getAuth2URL().getProtocol().equals("http")) {
			c.withAllowInsecureURLs(true);
			rep.reportInfo("Warning - the Auth Service MKII url uses insecure http. " +
					"https is recommended.");
		}
		if (cfg.getAuthURL().getProtocol().equals("http")) {
			c.withAllowInsecureURLs(true);
			rep.reportInfo(
					"Warning - the Auth Service url uses insecure http. https is recommended.");
		}
		try {
			final URL auth2url = cfg.getAuth2URL().toURI().resolve("api/legacy/globus").toURL();
			c.withGlobusAuthURL(auth2url)
				.withKBaseAuthServerURL(cfg.getAuthURL());
		} catch (URISyntaxException | MalformedURLException e) {
			rep.reportFail("Invalid Auth Service url: " + cfg.getAuth2URL());
			return null;
		}
		try {
			return new ConfigurableAuthService(c);
		} catch (IOException e) {
			rep.reportFail("Couldn't connect to authorization service at " +
					c.getAuthServerURL() + " : " + e.getLocalizedMessage());
			return null;
		}
	}

	
	private static class WorkspaceInitException extends Exception {
		
		private static final long serialVersionUID = 1L;

		public WorkspaceInitException(final String message) {
			super(message);
		}
		
		public WorkspaceInitException(final String message,
				final Throwable throwable) {
			super(message, throwable);
		}
	}
}
