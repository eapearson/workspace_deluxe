package us.kbase.typedobj.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import us.kbase.typedobj.exceptions.RelabelIdReferenceException;
import us.kbase.typedobj.idref.IdReference;
import us.kbase.typedobj.idref.IdReferenceManager;
import us.kbase.typedobj.idref.WsIdReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.fge.jsonschema.report.LogLevel;
import com.github.fge.jsonschema.report.ProcessingMessage;
import com.github.fge.jsonschema.report.ProcessingReport;


/**
 * The report generated when a typed object instance is validated.  If the type definition indicates
 * that fields are ID references, those ID references can be extracted from this report.  If a
 * searchable subset flag is set in the type definition, you can extract that too.
 *
 * @author msneddon
 */
public class TypedObjectValidationReport {

	/**
	 * the report object generated by the json-schema-validator library, which is the core object we are wrapping
	 */
	protected ProcessingReport processingReport;
	
	/**
	 * This is the ID of the type definition used in validation - it is an AbsoluteTypeDefId so you always have full version info
	 */
	private final AbsoluteTypeDefId validationTypeDefId;
	
	/**
	 * Used to keep track of the IDs that were parsed from the 
	 */
	private IdReferenceManager idRefManager;

	/**
	 * we keep a reference to the original instance that was validated so we can later easily rename labels or extract
	 * the ws searchable subset
	 */
	private JsonNode originalInstance;
	
	
	/**
	 * keep a jackson mapper around so we don't have to create a new one over and over during subset extration
	 */
	private ObjectMapper mapper;
	
	/**
	 * Initialize with the given processingReport (created when a JsonSchema is used to validate) and the
	 * typeDefId of the typed object definition used when validating.
	 * @param processingReport
	 * @param validationTypeDefId
	 */
	public TypedObjectValidationReport(ProcessingReport processingReport, AbsoluteTypeDefId validationTypeDefId, JsonNode originalInstance) {
		this.processingReport=processingReport;
		this.validationTypeDefId=validationTypeDefId;
		this.idRefManager= new IdReferenceManager(processingReport);
		this.originalInstance=originalInstance;
		this.mapper = new ObjectMapper();
	}
	
	/**
	 * Get the absolute ID of the typedef that was used to validate the instance
	 * @return
	 */
	public AbsoluteTypeDefId getValidationTypeDefId() {
		return validationTypeDefId;
	}
	
	/**
	 * @return boolean true if the instance is valid, false otherwise
	 */
	public boolean isInstanceValid() {
		return processingReport.isSuccess();
	}
	
	/**
	 * Iterate over all items in the report and count the errors.
	 * @return n_errors
	 */
	public int getErrorCount() {
		if(isInstanceValid()) { return 0; }
		Iterator<ProcessingMessage> mssgs = processingReport.iterator();
		int n_errors=0;
		while(mssgs.hasNext()) {
			ProcessingMessage pm = mssgs.next();
			if(pm.getLogLevel().equals(LogLevel.ERROR)) {
				n_errors++;
			}
		}
		return n_errors;
	}
	
	/**
	 * Iterate over all items in the report and return the error messages.
	 * @return n_errors
	 */
	public List <String> getErrorMessagesAsList() {
		ArrayList <String> errMssgs = new ArrayList<String>();
		if(isInstanceValid()) { return errMssgs; }
		
		Iterator<ProcessingMessage> mssgs = processingReport.iterator();
		JsonNode instance; JsonNode instancePointer;
		while(mssgs.hasNext()) {
			ProcessingMessage pm = mssgs.next();
			if(pm.getLogLevel().equals(LogLevel.ERROR)) {
				String foundPositionString = "";
				instance = pm.asJson().get("instance");
				if(instance!=null) {
					instancePointer = instance.get("pointer");
					if(instancePointer!=null) {
						foundPositionString = ", at "+instancePointer.asText();
					}
				}
				errMssgs.add(pm.getMessage()+foundPositionString);
			}
		}
		return errMssgs;
	}
	
	public String [] getErrorMessages() {
		List <String> errMssgs = getErrorMessagesAsList();
		return errMssgs.toArray(new String [errMssgs.size()]);
	}
	
	/**
	 * This method returns the raw report generated by the JsonSchema, useful in some cases if
	 * you need to dig down into the guts of keywords or to investigate why something failed.
	 */
	public ProcessingReport getRawProcessingReport() {
		return processingReport;
	}
	
	/**
	 * use getWsIdReferences() or getAllIdReferences() instead 
	 * @deprecated
	**/
	public List <String> getListOfIdReferences() {
		return idRefManager.getAllIds();
		
	}
	
	public List<WsIdReference> getWsIdReferences() {
		return idRefManager.getAllWsIdReferences();
	}
	
	public List<IdReference> getAllIdReferences() {
		return idRefManager.getAllIdReferences();
	}
	
	public List<String> getAllIds() {
		return idRefManager.getAllIds();
	}
	
	public List<IdReference> getAllIdReferencesOfType(String type) {
		return idRefManager.getAllIdReferencesOfType(type);
	}
	
	
	
	/**
	 * Use relabelWsIdReferences for relabeling ws id references from now on. You no
	 * longer need to call this method (although it still works)
	 * @deprecated
	 */
	public void setAbsoluteIdReferences(Map<String,String> absoluteIdRefMapping) {
		idRefManager.setWsReplacementNames(absoluteIdRefMapping);
	}
	
	/**
	 * Relabel the WS IDs in the original Json document based on the specified set of
	 * ID Mappings, where keys are the original ids and values are the replacement ids.
	 * 
	 * Caution: this relabeling happens in-place, so if you have modified the structure
	 * of the JSON node between validation and invocation of this method, you will likely
	 * get many runtime errors.  You should make a deep copy first if you indent to do this.
	 * 
	 * Memory of the original ids is not changed by this operation.  Thus, if you need
	 * to rename the ids a second time, you must still refer to the id as its original name,
	 * not necessarily be the name in the current version of the object.
	 */
	public JsonNode relabelWsIdReferences(Map<String,String> absoluteIdRefMapping) throws RelabelIdReferenceException {
		idRefManager.setWsReplacementNames(absoluteIdRefMapping);
		idRefManager.relabelWsIds(originalInstance);
		return originalInstance;
	}
	
	/**
	 * Get a copy of the original json instance that was validated to generate this report
	 */
	public JsonNode getJsonInstance() {
		return originalInstance;
	}
	
	
	
	
	
	/**
	 * If a searchable ws_subset was defined in the Json Schema, then you can use this method
	 * to extract out the contents.  Note that this method does not perform a deep copy of the data,
	 * so if you extract a subset, then modify the original instance that was validated, it can
	 * (in some but not all cases) modify this subdata as well.  So you should always perform a
	 * deep copy of the original instance if you intend to modify it and subset data has already
	 * been extracted.
	 */
	public JsonNode extractSearchableWsSubset() {
		if(!isInstanceValid()) {
			return mapper.createObjectNode();
		}
		
		// Create the new node to store our subset
		ObjectNode subset = mapper.createObjectNode();
		
		// Identify what we need to extract
		ObjectNode keys_of  = null;
		ObjectNode fields   = null;
		Iterator<ProcessingMessage> mssgs = processingReport.iterator();
		while(mssgs.hasNext()) {
			ProcessingMessage m = mssgs.next();
			if( m.getMessage().compareTo("searchable-ws-subset") == 0 ) {
				JsonNode searchData = m.asJson().get("search-data");
				keys_of = (ObjectNode)searchData.get("keys");
				fields = (ObjectNode)searchData.get("fields");
				//there can only one per report, so we can break as soon as we got it!
				break;
			}
		}
		
		// call our private method for extracting out the fields and keys_of mappings
		if(fields!=null)
			extractFields(subset, originalInstance, fields, false);
		if(keys_of!=null)
			extractFields(subset, originalInstance, keys_of, true);
		
		return subset;
	}
	
	
	/**
	 * extract the fields listed in selection from the element and add them to the subset
	 * 
	 * selection must either be an object containing structure field names to extract, '*' in the case of
	 * extracting a mapping, or '[*]' for extracting a list.  if the selection is empty, nothing is added.
	 * If extractKeysOf is set, and the element is an Object (ie a kidl mapping), then an array of the keys
	 * is added instead of the entire mapping.
	 * 
	 * we assume here that selection has already been validated against the structure of the document, so that
	 * if we get true on extractKeysOf, it really is a mapping, and if we get a '*' or '[*]', it really is
	 * a mapping or array.
	 */
	private void extractFields(JsonNode subset, JsonNode element, ObjectNode selection, boolean extractKeysOf) {
		
		//System.out.println(" - subset: "+subset);
		//System.out.println(" - element: "+element);
		//System.out.println(" - selection: " + selection);
		Iterator <Map.Entry<String,JsonNode>> selectedFields = selection.fields();
		
		//if the selection is empty, we return without adding anything
		if(!selectedFields.hasNext()) return;
		
		//otherwise we need to add every selected field in the selection from the element to the subset
		while(selectedFields.hasNext()) {
			
			// get the selected field name
			Map.Entry<String,JsonNode> selectedField = selectedFields.next();
			String selectedFieldName = selectedField.getKey();
			
			// if there are no more subfields beyond this, we figure it out now...
			boolean atTheEnd = false;
			if(selectedField.getValue().size()==0) {
				atTheEnd = true;
			}
			
			////// KIDL MAPPING
			if(selectedFieldName.equals("*")) {
				// we have descended into a kidl mapping, so we need to handle with care.
				// we must go through each value in the mapping, and add the extracted portion
				// Note: subset must be an ObjectNode if we are at a mapping
				Iterator <Map.Entry<String,JsonNode>> mappingElements = element.fields();
				while(mappingElements.hasNext()) {
					Map.Entry<String,JsonNode> mappingElement = mappingElements.next();
					String mappingKey      = mappingElement.getKey();
					JsonNode mappingValue  = mappingElement.getValue();
						
					if(atTheEnd) {
						// if we are at the end, we either add the data or add "keys_of" the sub mapping
						if(extractKeysOf) {
							ArrayNode subKeyList = JsonNodeFactory.instance.arrayNode();
							((ObjectNode)subset).set(mappingKey,subKeyList);
							Iterator <Map.Entry<String,JsonNode>> subMappingElements = mappingValue.fields();
							while(subMappingElements.hasNext()) {
								subKeyList.add(subMappingElements.next().getKey());
							}
						} else {
							// we want everything here, so add it...
							((ObjectNode)subset).set(mappingKey,mappingValue);
						}
					}
						
					else {
						// if we are not at the end, then we recurse down
						JsonNode subsetDataForKey = subset.get(mappingKey);
						if(subsetDataForKey==null) {
							if(mappingValue.isObject()) {
								subsetDataForKey = mapper.createObjectNode();
								((ObjectNode)subset).set(mappingKey,subsetDataForKey);
							} else if(mappingValue.isArray()) {
								subsetDataForKey = JsonNodeFactory.instance.arrayNode();
								((ObjectNode)subset).set(mappingKey,subsetDataForKey);
							}
						}
						extractFields(subsetDataForKey, mappingValue, (ObjectNode)selection.get("*"), extractKeysOf);
					}
				}
			}
			
			////// KIDL LIST
			else if (selectedFieldName.equals("[*]")) {
				// we have descended into a kidl list, so we must deal with that
				// subset must be an ArrayNode, and it is not possible to get keys_of an ArrayNode, so we don't need to handle anything special there
				//  (could explicitly check and throw an error if extractKeysOf is true!?)
				// loop over every item in the array element and add it to the ArrayNode subset
				for(int k=0; k<element.size(); k++) {
					// get the data
					JsonNode elementDataAtK = element.get(k);
					if(atTheEnd) {
						// if we are at the end, then we add the element to the ArrayNode (we could do a quick check to make sure nothing was added
						// at this position yet.  But for now if there was something added, then we just blow it away, which should be ok...)
						if(subset.get(k)==null) {
							((ArrayNode)subset).add(elementDataAtK);
						} else {
							((ArrayNode)subset).set(k, elementDataAtK);
						}
					} else {
						// check if there is anything in the subset for this object yet, if not we have to create it
						JsonNode subsetDataAtK = subset.get(k);
						if(subsetDataAtK==null) {
							if(elementDataAtK.isObject()) {
								subsetDataAtK = mapper.createObjectNode();
								((ArrayNode)subset).add(subsetDataAtK);
							} else if(elementDataAtK.isArray()) {
								subsetDataAtK = JsonNodeFactory.instance.arrayNode();
								((ArrayNode)subset).add(subsetDataAtK);
							}
						}
						extractFields(subsetDataAtK, elementDataAtK, (ObjectNode)selection.get("[*]"), extractKeysOf);
					}
				}
			}
			
			////// KIDL FIELD
			else {
				// we are descending into a field of an object, so go down to that field.
				JsonNode fieldData = element.get(selectedFieldName);
				// fieldData may be null if it was an optional field.  If that is the case we can skip it without error.
				if(fieldData!=null) {
					// if there are no more sub selections, we can just add a pointer to the element data at this field and be done with it
					// (of course, if we indicate keys_of, then we need to extract the keys as an array) 
					if(atTheEnd) {
						if(extractKeysOf) {
							ArrayNode keyList = JsonNodeFactory.instance.arrayNode();
							((ObjectNode)subset).set(selectedFieldName, keyList);
							Iterator <Map.Entry<String,JsonNode>> mappingPairs = element.get(selectedFieldName).fields();
							while(mappingPairs.hasNext()) {
								keyList.add(mappingPairs.next().getKey());
							}
						} else {
							((ObjectNode)subset).set(selectedFieldName, element.get(selectedFieldName));
						}
					}
					
					// otherwise there are sub selections, so we have to handle them.
					else {
						if(fieldData.isObject()) {
							ObjectNode structSubsetData = (ObjectNode)subset.get(selectedFieldName);
							if(structSubsetData==null) {
								structSubsetData = mapper.createObjectNode();
								((ObjectNode)subset).set(selectedFieldName, structSubsetData);
							}
							extractFields(structSubsetData, fieldData, (ObjectNode)selectedField.getValue(), extractKeysOf);
						}
						
						else if(fieldData.isArray()) {
							ArrayNode structSubsetArrayData = (ArrayNode)subset.get(selectedFieldName);
							if(structSubsetArrayData==null) {
								structSubsetArrayData = JsonNodeFactory.instance.arrayNode();
								((ObjectNode)subset).set(selectedFieldName, structSubsetArrayData);
							}
							extractFields(structSubsetArrayData, fieldData, (ObjectNode)selectedField.getValue(), extractKeysOf);
						}
					}
				}
			}
			
			// NOTE: we cannot descend into a tuple - we could support it by detecting something like [1] or [4], but for now
			// we do not allow it!  We will encounter an error if subfields of a tuple are defined
			
		}
		return;
	}
	
	
	
	
	
	
	
	@Override
	public String toString() {
		StringBuilder mssg = new StringBuilder();
		mssg.append("TYPED OBJECT VALIDATION REPORT\n");
		mssg.append(" -validated instance against: '"+validationTypeDefId.getTypeString()+"'\n");
		mssg.append(" -status: ");
		if(this.isInstanceValid()) {
			mssg.append("pass\n");
			mssg.append(" -id refs extracted: "+idRefManager.getAllIds().size());
			mssg.append(" -ws id refs extracted: "+idRefManager.getAllWsIdReferences().size());
		}
		else {
			List<String> errs = getErrorMessagesAsList();
			mssg.append("fail ("+errs.size()+" error(s))\n");
			for(int k=0; k<errs.size(); k++) {
				mssg.append(" -["+(k+1)+"]:"+errs.get(k));
			}
		}
		return mssg.toString();
	}
	
	
}
