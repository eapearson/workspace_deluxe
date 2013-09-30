package us.kbase.typedobj.tests;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import us.kbase.typedobj.core.TypeDefId;
import us.kbase.typedobj.core.TypeDefName;
import us.kbase.typedobj.core.TypedObjectValidationReport;
import us.kbase.typedobj.core.TypedObjectValidator;
import us.kbase.typedobj.db.FileTypeStorage;
import us.kbase.typedobj.db.TypeDefinitionDB;
import us.kbase.typedobj.db.UserInfoProviderForTests;


/**
 * simple test of the basic typed object validation framework that creates a simple
 * file storage typed object database using a couple spec files, looks at the directory
 * containing instances to validate, and ensures that the instances validate or don't
 * as indicated.
 * 
 * The test files are in us.kbase.typedobj.tests.files.t1
 * 
 * You can add as many instances to validate as you like by naming text files as:
 *   ModuleName.TypeName.[valid|invalid].instance.N
 * where you need to indicate if the instance is valid or not, and N is any string
 * identifier for the test, usually integer numbers.
 * 
 * If the spec files are updated or new ones added, you need to modify the db
 * setup method to add the new typed obj defs.
 * 
 * Note: we could/should migrate this to JUnit parameterized tests in the future...
 * 
 * @author msneddon
 *
 */
public class TestBasicValidation {

	/**
	 * location to stash the temporary database for testing
	 * WARNING: THIS DIRECTORY WILL BE WIPED OUT AFTER TESTS!!!!
	 */
	private final static String TEST_DB_LOCATION = "test/typedobj_test_files/t1";
	
	private final static String TEST_RESOURCE_LOCATION = "files/t1/";
	
	private static TypeDefinitionDB db;
	
	private static TypedObjectValidator validator;
	
	/*
	 * structures to store info on each instance we wish to validate 
	 */
	
	private static List<TestInstanceInfo> validInstanceResources = new ArrayList <TestInstanceInfo> ();
	private static List<TestInstanceInfo> invalidInstanceResources = new ArrayList <TestInstanceInfo> ();
	
	private static class TestInstanceInfo {
		public TestInstanceInfo(String resourceName, String moduleName, String typeName) {
			this.resourceName = resourceName;
			this.moduleName = moduleName;
			this.typeName = typeName;
		}
		public String resourceName;
		public String moduleName;
		public String typeName;
	}
	
	/**
	 * Setup the typedef database, load and release the types in the simple specs, and
	 * identify all the files containing instances to validate.
	 * @throws Exception
	 */
	@BeforeClass
	public static void prepareDb() throws Exception
	{
		System.out.println("setting up the typed obj database");
		
		//ensure test location is available
		File dir = new File(TEST_DB_LOCATION);
		if (dir.exists()) {
			//fail("database at location: "+TEST_DB_LOCATION+" already exists, remove/rename this directory first");
			removeDb();
		}
		if(!dir.mkdirs()) {
			fail("unable to create needed test directory: "+TEST_DB_LOCATION);
		}
		
		// point the type definition db to point there
		db = new TypeDefinitionDB(new FileTypeStorage(TEST_DB_LOCATION), new UserInfoProviderForTests());
		
		// create a validator that uses the type def db
		validator = new TypedObjectValidator(db);
	
		
		System.out.println("loading db with types");
		String username = "wstester1";
		
		String kbSpec = loadResourceFile(TEST_RESOURCE_LOCATION+"KB.spec");
		List<String> kb_types =  Arrays.asList("Feature","Genome","FeatureGroup","genome_id","feature_id");
		db.approveModuleRegistrationRequest(username, "KB", username);
		db.registerModule(kbSpec ,kb_types, username);
		for(String typename : kb_types) {
			db.releaseType(new TypeDefName("KB." + typename), username);
		}
		
		String fbaSpec = loadResourceFile(TEST_RESOURCE_LOCATION+"FBA.spec");
		List<String> fba_types =  Arrays.asList("FBAModel","FBAResult","fba_model_id");
		db.approveModuleRegistrationRequest(username, "FBA", username);
		db.registerModule(fbaSpec ,fba_types, username);
		for(String typename : fba_types) {
			db.releaseType(new TypeDefName("FBA." + typename), username);
		}
		
		System.out.println("finding test instances");
		String [] resources = getResourceListing(TEST_RESOURCE_LOCATION);
		for(int k=0; k<resources.length; k++) {
			String [] tokens = resources[k].split("\\.");
			if(tokens.length!=5) { continue; }
			if(tokens[3].equals("instance")) {
				if(tokens[2].equals("valid")) {
					validInstanceResources.add(new TestInstanceInfo(resources[k],tokens[0],tokens[1]));
				} else if(tokens[2].equals("invalid")) {
					invalidInstanceResources.add(new TestInstanceInfo(resources[k],tokens[0],tokens[1]));
				}
			}
		}
	}
	
	//@After
	//public void clear 
	
	@AfterClass
	public static void removeDb() throws IOException {
		File dir = new File(TEST_DB_LOCATION);
		FileUtils.deleteDirectory(dir);
		System.out.println("\ndeleting typed obj database");
	}
	
	
	@Test
	public void testValidInstances() throws Exception {
		
		System.out.println("\ntesting valid instances ("+validInstanceResources.size()+" total)");
		for(TestInstanceInfo instance : validInstanceResources) {
			System.out.println("  -("+instance.resourceName+")");
			String instanceJson = loadResourceFile(TEST_RESOURCE_LOCATION+instance.resourceName);
			
			TypedObjectValidationReport report = 
				validator.validate(
					instanceJson,
					new TypeDefId(new TypeDefName(instance.moduleName,instance.typeName))
					);
			
			// print errors, if any before the assert to aid in testing
			String [] mssgs = report.getErrorMessages();
			for(int i=0; i<mssgs.length; i++) {
				System.out.println("    ["+i+"]:"+mssgs[i]);
			}
			
			assertTrue("  -("+instance.resourceName+") does not validate, but should",report.isInstanceValid());
		}

		
		
	}

	@Test
	public void testInvalidInstances() throws Exception {
		
		System.out.println("\ntesting invalid instances ("+invalidInstanceResources.size()+" total)");
		for(TestInstanceInfo instance : invalidInstanceResources) {
			System.out.println("  -("+instance.resourceName+")");
			String instanceJson = loadResourceFile(TEST_RESOURCE_LOCATION+instance.resourceName);
			
			try {
				TypedObjectValidationReport report = 
					validator.validate(
						instanceJson,
						new TypeDefId(new TypeDefName(instance.moduleName,instance.typeName))
						);
				assertFalse("  -("+instance.resourceName+") validates, but should not",report.isInstanceValid());
				String [] mssgs = report.getErrorMessages();
				for(int i=0; i<mssgs.length; i++) {
					System.out.println("    ["+i+"]:"+mssgs[i]);
				}
			} catch (Exception e) {
				//if an exception is thrown, it must be an InstanceValidationException
				//we are not testing if an incorrect module name or type name is given here
				assertEquals("InstanceValidationException",e.getClass().getSimpleName());
			}
		}
	}
	
	
	/**
	 * helper method to load test files, mostly copied from TypeRegistering test
	 */
	private static String loadResourceFile(String resourceName) throws Exception {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		InputStream is = TestBasicValidation.class.getResourceAsStream(resourceName);
		if (is == null)
			throw new IllegalStateException("Resource not found: " + resourceName);
		BufferedReader br = new BufferedReader(new InputStreamReader(is));
		while (true) {
			String line = br.readLine();
			if (line == null)
				break;
			pw.println(line);
		}
		br.close();
		pw.close();
		return sw.toString();
	}
	
	
	
	/**
	 * List directory contents for a resource folder. Not recursive.
	 * This is basically a brute-force implementation.
	 * Works for regular files and also JARs.
	 * adapted from: http://www.uofr.net/~greg/java/get-resource-listing.html
	 * 
	 * @author Greg Briggs
	 * @author msneddon
	 * @param path Should end with "/", but not start with one.
	 * @return Just the name of each member item, not the full paths.
	 * @throws URISyntaxException 
	 * @throws IOException 
	 */
	private static String[] getResourceListing(String path) throws URISyntaxException, IOException {
		URL dirURL = TestBasicValidation.class.getResource(path);
		if (dirURL != null && dirURL.getProtocol().equals("file")) {
			/* A file path: easy enough */
			return new File(dirURL.toURI()).list();
		}

		if (dirURL == null) {
			// In case of a jar file, we can't actually find a directory.
			// Have to assume the same jar as the class.
			String me = TestBasicValidation.class.getName().replace(".", "/")+".class";
			dirURL = TestBasicValidation.class.getResource(me);
		}

		if (dirURL.getProtocol().equals("jar")) {
			/* A JAR path */
			String jarPath = dirURL.getPath().substring(5, dirURL.getPath().indexOf("!")); //strip out only the JAR file
			JarFile jar = new JarFile(URLDecoder.decode(jarPath, "UTF-8"));
			Enumeration<JarEntry> entries = jar.entries(); //gives ALL entries in jar
			Set<String> result = new HashSet<String>(); //avoid duplicates in case it is a subdirectory
			while(entries.hasMoreElements()) {
				String name = entries.nextElement().getName();
				// construct internal jar path relative to the class
				String fullPath = TestBasicValidation.class.getPackage().getName().replace(".","/") + "/" + path;
				if (name.startsWith(fullPath)) { //filter according to the path
					String entry = name.substring(fullPath.length());
					int checkSubdir = entry.indexOf("/");
					if (checkSubdir >= 0) {
						// if it is a subdirectory, we just return the directory name
						entry = entry.substring(0, checkSubdir);
					}
					result.add(entry);
				}
			}
			return result.toArray(new String[result.size()]);
		}
		throw new UnsupportedOperationException("Cannot list files for URL "+dirURL);
	}
	
}
