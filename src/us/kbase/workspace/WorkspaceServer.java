package us.kbase.workspace;

import us.kbase.JsonServerMethod;
import us.kbase.JsonServerServlet;
import us.kbase.Tuple5;
import us.kbase.auth.AuthToken;

//BEGIN_HEADER
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Arrays;

import org.apache.commons.lang3.builder.ToStringBuilder;

import us.kbase.workspace.database.Database;
import us.kbase.workspace.database.MongoDatabase;
import us.kbase.workspace.database.exceptions.DBAuthorizationException;
import us.kbase.workspace.database.exceptions.InvalidHostException;
import us.kbase.workspace.database.exceptions.WorkspaceDBException;
//END_HEADER

/**
 * <p>Original spec-file module name: Workspace</p>
 * <pre>
 * The workspace service at its core is a storage and retrieval system for 
 * typed objects. Objects are organized by the user into one or more workspaces.
 * Features:
 * Versioning of objects
 * Data provenenance
 * Object to object references
 * Workspace sharing
 * TODO
 * BINARY DATA:
 * All binary data must be hex encoded prior to storage in a workspace. 
 * Attempting to send binary data via a workspace client will cause errors.
 * </pre>
 */
public class WorkspaceServer extends JsonServerServlet {
    private static final long serialVersionUID = 1L;

    //BEGIN_CLASS_HEADER
	//required deploy parameters:
	private static final String HOST = "mongodb-host";
	private static final String DB = "mongodb-database";
	//required backend param:
	private static final String BACKEND_SECRET = "backend-secret"; 
	//auth params:
	private static final String USER = "mongodb-user";
	private static final String PWD = "mongodb-pwd";
	
	private final Workspaces ws;
	
	private void logger(String log) {
		//TODO when logging is released (check places that call this method)
		System.out.println(log);
	}
	private Database getDB(String host, String dbs, String secret, String user,
			String pwd) {
		try {
			if (user != null) {
				return new MongoDatabase(host, dbs, secret, user, pwd);
			} else {
				return new MongoDatabase(host, dbs, secret);
			}
		} catch (UnknownHostException uhe) {
			die("Couldn't find host " + host + ": " +
					uhe.getLocalizedMessage());
		} catch (IOException io) {
			die("Couldn't connect to host " + host + ": " +
					io.getLocalizedMessage());
		} catch (DBAuthorizationException ae) {
			die("Not authorized: " + ae.getLocalizedMessage());
		} catch (InvalidHostException ihe) {
			die(host + " is an invalid database host: "  +
					ihe.getLocalizedMessage());
		} catch (WorkspaceDBException uwde) {
			die("The workspace database is invalid: " +
					uwde.getLocalizedMessage());
		}
		return null; //shut up eclipse you bastard
	}
	
	private void die(String error) {
		System.err.println(error);
		System.err.println("Terminating server.");
		System.exit(1);
	}
    //END_CLASS_HEADER

    public WorkspaceServer() throws Exception {
        //BEGIN_CONSTRUCTOR
		if (!config.containsKey(HOST)) {
			die("Must provide param " + HOST + " in config file");
		}
		String host = config.get(HOST);
		if (!config.containsKey(DB)) {
			die("Must provide param " + DB + " in config file");
		}
		String dbs = config.get(DB);
		if (!config.containsKey(BACKEND_SECRET)) {
			die("Must provide param " + BACKEND_SECRET + " in config file");
		}
		String secret = config.get(BACKEND_SECRET);
		if (config.containsKey(USER) ^ config.containsKey(PWD)) {
			die(String.format("Must provide both %s and %s ",
					USER, PWD) + "params in config file if authentication " + 
					"is to be used");
		}
		String user = config.get(USER);
		String pwd = config.get(PWD);
		String params = "";
		for (String s: Arrays.asList(HOST, DB, USER)) {
			if (config.containsKey(s)) {
				params += s + "=" + config.get(s) + "\n";
			}
		}
		params += BACKEND_SECRET + "=[redacted for your safety and comfort]\n";
		if (pwd != null) {
			params += PWD + "=[redacted for your safety and comfort]\n";
		}
		System.out.println("Using connection parameters:\n" + params);
		Database db = getDB(host, dbs, secret, user, pwd);
		System.out.println(String.format("Initialized %s backend", db.getBackendType()));
		ws = new Workspaces(db);
        //END_CONSTRUCTOR
    }

    /**
     * <p>Original spec-file function name: create_workspace</p>
     * <pre>
     * Creates a new workspace.
     * </pre>
     * @param   params   Original type "create_workspace_params" (see {@link us.kbase.workspace.CreateWorkspaceParams CreateWorkspaceParams} for details)
     * @return   Original type "workspace_metadata" (Meta data associated with a workspace. workspace_id workspace - ID of the workspace. username owner - name of the user who owns (e.g. created) this workspace. timestamp moddate - date when the workspace was last modified. permission user_permission - permissions for the authenticated user of this workspace permission globalread - whether this workspace is globally readable.)
     */
    @JsonServerMethod(rpc = "Workspace.create_workspace")
    public Tuple5<String, String, String, String, String> createWorkspace(CreateWorkspaceParams params, AuthToken authPart) throws Exception {
        Tuple5<String, String, String, String, String> returnVal = null;
        //BEGIN create_workspace
		if (!params.getGlobalread().equals("r") && !params.getGlobalread().equals("n")) {
			throw new IllegalArgumentException("globalread must be r or n");
		}
		ws.createWorkspace(authPart.getUserName(), params.getWorkspace(),
				params.getGlobalread().equals("r"), params.getDescription());
        //END create_workspace
        return returnVal;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: <program> <server_port>");
            return;
        }
        new WorkspaceServer().startupServer(Integer.parseInt(args[0]));
    }
}
