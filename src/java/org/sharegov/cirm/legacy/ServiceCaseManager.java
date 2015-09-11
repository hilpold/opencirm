package org.sharegov.cirm.legacy;

import static org.sharegov.cirm.OWL.owlClass;
import static org.sharegov.cirm.OWL.reasoner;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import mjson.Json;

import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.sharegov.cirm.MetaOntology;
import org.sharegov.cirm.OWL;
import org.sharegov.cirm.Refs;
import org.sharegov.cirm.owl.OwlRepo;
import org.sharegov.cirm.rest.OWLIndividuals;
import org.sharegov.cirm.rest.OntoAdmin;
import org.sharegov.cirm.utils.GenUtils;
import org.sharegov.cirm.utils.ThreadLocalStopwatch;

import com.hp.hpl.jena.reasoner.IllegalParameterException;

/**
 * Handles the User Cases for CIRM Admin Interface
 * 
 * @author chirino
 */
public class ServiceCaseManager extends OntoAdmin {		

	private static final String PREFIX = "legacy:";
	private static ServiceCaseManager instance = null; 
	private Map<String, Json> cache;
	
	/**
	 * private to defeat multiple instantiation
	 * 
	 */
	private ServiceCaseManager() {
		cache = new ConcurrentHashMap<String, Json>();
		
		ThreadLocalStopwatch.startTop("Started Service Case Admin Cache.");
		getAll();
		ThreadLocalStopwatch.now("End Service Case Admin Cache.");
	}

	/**
	 * Singleton instance getter. Synchronized to defeat multiple instantiation when instance == null
	 *  
	 * @return a unique instance of the class 
	 */
	public synchronized static ServiceCaseManager getInstance(){
		if (instance == null){
			instance = new ServiceCaseManager ();
		}
		return instance;
	}
	
	/**
	 * Getter for the OWL repository
	 * 	
	 * @return
	 */
	private OwlRepo getRepo() {
		return Refs.owlRepo.resolve();
	}
	
	/**
	 * Removes object defined by aKey from the cache
	 * 
	 * @param aKey null not allowed
	 */
	private synchronized void clearCache (String aKey){
		cache.remove(aKey);
		cache.remove(PREFIX + aKey);		
	}
	
	/**
	 * Removes a list of objects identified by their keys from the cache	 * 
	 * 
	 * @param keys a list of keys to remove from the cache.
	 */
	
	private synchronized void clearCache(List<String> keys){
		for (String key: keys){
			cache.remove(key);
			cache.remove(PREFIX + key);
		}
		MetaOntology.clearCacheAndSynchronizeReasoner();
	}
	
	/**
	 * 
	 * @return a formated list of enabled Service Case Types 
	 */

	public Json getEnabled() {
		return getServiceCasesByStatus(true);
	}
	
	/**
	 * 
	 * @return a formated list of disabled Service Case Types
	 */

	public Json getDisabled() {
		return getServiceCasesByStatus(false);
	}
	
	/**
	 * Search a parent Agency for the individual p within the ontology
	 * 
	 * @param p a serialized individual
	 * @return the parent agency name as string 
	 */
	
	private String getParentAgencyName (Json p){
		if (p.has("hasParentAgency"))  {
			String parentIri;
			if (p.at("hasParentAgency").isObject()){
				if (p.at("hasParentAgency").has("iri")){
					parentIri = (p.at("hasParentAgency").at("iri").asString());
				} else throw new IllegalParameterException("Cannot find IRI property for Individual: " + p.asString());
			} else parentIri = p.at("hasParentAgency").asString();
			
			OWLNamedIndividual ind = OWL.individual(parentIri);
			
			Json np = getSerializedIndividual(ind.getIRI().getFragment(), ind.getIRI().getScheme());
			
			return getParentAgencyName (np);
		}
		
		return p.at("Name").asString();
	}
	
	/** 
	 * 
	 * @param srType a serialized individual
	 * @return the name of the jurisdiction to whom the individual belongs
	 */
	
	private String findJusrisdiction (Json srType){
		if (srType.has("providedBy")) return getParentAgencyName(srType.at("providedBy"));
		
		return "";
	}
	
	/**
	 * Search for the department/office to whom the individual p belongs  
	 * 
	 * @param p a serialized individual
	 * @return Json representation of the attributes of the department/office 
	 */
	
	private Json resolveDepartment (Json p){			
		if (p.has("hasParentAgency")) p = p.at("hasParentAgency");
		else 
			if (p.has("Department")) p = p.at("Department");
			else throw new IllegalParameterException("Division: " + p.at("iri").asString() + " have no Parent Agency or Department assigned.");
		
		
		String iri;
		if (p.isObject()){
			if (p.has("iri")){
				iri = (p.at("iri").asString());
			} else throw new IllegalParameterException("Cannot find IRI property for Individual: " + p.asString());
		} else iri = p.asString();
		

		OWLNamedIndividual ind = OWL.individual(iri);
		
		Json np = (p.isObject()&&p.has("type")&&p.has("Name")) ? p : getSerializedIndividual(ind.getIRI().getFragment(), ind.getIRI().getScheme());
		
		if (np.has("type") && np.at("type").asString().toLowerCase().compareTo("division_county") != 0){
			return Json.object().set("Name", np.at("Name").asString()).set("Type", np.at("type").asString());
		} else {
			return resolveDepartment(np);
		}
		
	}
	
	/**
	 * Returns the Division and Department to whom the individual belongs
	 * 
	 * @param p a serialized individual
	 * @return a Json representation of the attributes of the department and division
	 */
	
	private Json resolveDepartmentDivision (Json p){
		Json result = Json.object();
		
		if (p.has("iri")){
			OWLNamedIndividual ind = OWL.individual(p.at("iri").asString());
			
			Json np = (p.has("type")&&p.has("Name")) ? p : getSerializedIndividual(ind.getIRI().getFragment(), ind.getIRI().getStart()); 
			
			if (np.has("type") && np.at("type").asString().toLowerCase().compareTo("division_county") == 0){
				result.set("division",  Json.object().set("Name", np.at("Name").asString()).set("Division_Code", np.at("Division_Code").asString()));
				
				if (!np.has("hasParentAgency")&&!np.has("Department")) np = getSerializedIndividual(ind.getIRI().getFragment(), ind.getIRI().getStart());
				
				result.set("department", resolveDepartment (np));					
			} else {
				result.set("division", Json.object().set("Name", Json.nil()).set("Division_Code", Json.nil()));
				result.set("department", Json.object().set("Name", np.has("Name") ? np.at("Name").asString(): Json.nil()).set("Type", np.has("type") ? np.at("type").asString(): Json.nil()));
			}
		} else throw new IllegalParameterException("Cannot find IRI property for Individual: " + p.asString());
		
		
		return result;
	}
	
	/**
	 * Entry point for the search of the department/division  
	 * 
	 * @param srType
	 * @return a Json representation of the attributes of the department and division
	 */
	
	private Json findDepartmentDivision (Json srType){
		if (srType.has("providedBy")) return resolveDepartmentDivision (srType.at("providedBy"));
		else throw new IllegalParameterException("Cannot find providedBy property for SR type: " +srType.at("iri").asString());
	}
	
	/**
	 * retrieves the contracted information of a Service Case Type from the ontology
	 * 
	 * @param individual assumes is type Service Case
	 * @return Json representation of the contracted data required for the user interface
	 */
	
	private Json getRequiredData (OWLNamedIndividual individual){
		Json el = cache.get(individual.getIRI().getFragment());
		
		if (el != null && !el.isNull()) return el;
		
		String iri = individual.getIRI().toString();
		
		Json result = Json.object().set("iri", MetaOntology.getOntologyFromUri(iri) + ":" + individual.getIRI().getFragment())
//								   .set("code", individual.getIRI().getFragment())
								   .set("label", OWL.getEntityLabel(individual))
								   .set("disabled", isSrDisabledOrDisabledCreate(individual));
		
		try {		
			Json jIndividual = getMetaIndividual(individual.getIRI().getFragment());
			
			String jurisdiction;		
			if (jIndividual.has("hasJurisdictionDescription")){
				jurisdiction = jIndividual.at("hasJurisdictionDescription").asString();
			} else {
				jurisdiction = findJusrisdiction(jIndividual);
				if (jurisdiction == null || jurisdiction.isEmpty()) throw new IllegalParameterException("Individual legacy:" +  individual.getIRI().getFragment() + " have no jurisdiction associated.");
				
			}		
			result.set("jurisdiction", jurisdiction);
			
			Json depdiv = findDepartmentDivision(jIndividual);
			
			if (!depdiv.has("department")) throw new IllegalParameterException("Individual legacy:" +  individual.getIRI().getFragment() + " have no provider/owner associated.");
			if (!depdiv.has("division")) throw new IllegalParameterException("Cannot resolve division for Individual legacy:" +  individual.getIRI().getFragment());
			
			result.with(depdiv);
		} catch (Exception e) {
			System.out.println("Error while trying to resolve data for legacy:" + individual.getIRI().getFragment());
			
			if (!result.has("jurisdiction")) result.set("jurisdiction", Json.nil());
			if (!result.has("department")) result.set("department", Json.nil());
			if (!result.has("division")) result.set("division", Json.nil());
		}
		
		cache.put(individual.getIRI().getFragment(), result);
		
		return result;
		
	}	
	
	/**
	 * 
	 * @return a list of Service Case Types that contains the required data for the user interface
	 */
	
	public Json getAll() {
		Set<OWLNamedIndividual> S = getAllIndividuals();
		Json A = Json.array();
		for (OWLNamedIndividual ind : S) {			
			A.add(getRequiredData(ind));
		}

		return A;
	}
	
	/**
	 * 
	 * @return a list of individuals that belong to the class ServiceCase
	 */

	private Set<OWLNamedIndividual> getAllIndividuals() {
		OWLReasoner reasoner = reasoner();
		OWLClass serviceCase = owlClass(PREFIX + "ServiceCase");
		// TODO: Permission check
		// permissionCheck(serviceCase)
		return reasoner.getInstances(serviceCase, false).getFlattened();
	}
	
	/**
	 *  
	 * @param isGetEnabled describes whether the function returns all enabled or all disabled SRs
	 * if isGetEnabled == true, returns all enabled
	 * if isGetEnabled == false, returns all disabled
	 * 
	 * @return  a list of enabled/disabled service cases
	 */
	
	private Json getServiceCasesByStatus(boolean isGetEnabled) {
		Set<OWLNamedIndividual> S = getAllIndividuals();
		Json A = Json.array();
		for (OWLNamedIndividual ind : S) {
			boolean isSrDisabledOrDisabledCreate = isSrDisabledOrDisabledCreate(ind);
			boolean shouldAddServiceCase = (!isGetEnabled && isSrDisabledOrDisabledCreate) || (isGetEnabled && !isSrDisabledOrDisabledCreate);
			if (shouldAddServiceCase) {
				A.add(getRequiredData(ind));
			}
		}

		return A;
	}

	/**
	 * Checks if an Sr has isDisabledCreate true or isDisabled true
	 * 
	 * @param srTypeIndividual
	 * @return false if either no property
	 */
	private boolean isSrDisabledOrDisabledCreate(OWLNamedIndividual srTypeIndividual) {
		Set<OWLLiteral> values = OWL.dataProperties(srTypeIndividual, PREFIX
				+ "isDisabledCreate");
		boolean isDisabledCreate = values.contains(OWL.dataFactory()
				.getOWLLiteral(true));
		values = OWL.dataProperties(srTypeIndividual, PREFIX + "isDisabled");
		return isDisabledCreate
				|| values.contains(OWL.dataFactory().getOWLLiteral(true));
	}
	
	/**
	 * Disables a Service Case Type
	 * 
	 * @param srType individual identifier 
	 * @param userName who commits the action
	 * @return commit success true or false
	 */

	public Json disable(String srType, String userName) {

		srType = MetaOntology.getIndividualIdentifier(srType);
		
		OwlRepo repo = getRepo();

		synchronized (repo) {
			repo.ensurePeerStarted();
						
			List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
			changes.addAll(MetaOntology.getRemoveIndividualPropertyChanges(srType, "isDisabledCreate"));	
			changes.addAll(MetaOntology.getRemoveIndividualPropertyChanges(srType, "isDisabled"));			

			AddAxiom isDisabledCreateAddAxiom = MetaOntology.getIndividualLiteralAddAxiom(srType, "isDisabledCreate", true);
			
			changes.add(isDisabledCreateAddAxiom);
			
			String comment = "Disable Service Request "+PREFIX+srType;
			
			boolean r = commit(userName, comment, changes);
			
			if (r) clearCache(srType);
			
			return Json.object().set("success", r);
		}
	}
	
	/**
	 * Enables a Service Case Type
	 * 
	 * @param srType individual identifier 
	 * @param userName who commits the action
	 * @return commit success true or false
	 */
	

	public Json enable(String srType, String userName) {

		srType = MetaOntology.getIndividualIdentifier(srType);
		
		OwlRepo repo = getRepo();
		
		synchronized (repo) {
			repo.ensurePeerStarted();
			
			List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
			changes.addAll(MetaOntology.getRemoveIndividualPropertyChanges(srType, "isDisabledCreate"));	
			changes.addAll(MetaOntology.getRemoveIndividualPropertyChanges(srType, "isDisabled"));			

			AddAxiom isDisabledCreateAddAxiom = MetaOntology.getIndividualLiteralAddAxiom(srType, "isDisabledCreate", false);
			
			changes.add(isDisabledCreateAddAxiom);
			
			String comment = "Enable Service Request "+PREFIX+srType;
			
			boolean r = commit(userName, comment, changes);
			
			if (r) clearCache(PREFIX + srType);
			
			return Json.object().set("success", r);
		}
	}
	
	/**
	 * Queries the reasoner for a serialized individual
	 * 
	 * @param individualID identifier of the individual
	 * @param ontologyID ontology prefix
	 * @return a Json representation of the individual
	 */
	
	private Json getSerializedIndividual (String individualID, String ontologyID){
		try {			
			if (ontologyID.toLowerCase().contains("legacy")) ontologyID = "legacy";
			else ontologyID = "mdc";
			
			String cacheKey = ontologyID + ":" + individualID;
			
			Json el = cache.get(cacheKey);
			
			if (el != null && !el.isNull()) return el;
					
//			OWLNamedIndividual ind = individual(individualID);
//			Json jInd = OWL.toJSON(ontology(), ind);
			OWLIndividuals q = new OWLIndividuals();
			
			Json S = q.doInternalQuery("{" + cacheKey + "}");
			
			for (Json ind: S.asJsonList()){
				cache.put(cacheKey, ind);
				return ind;
			}
			
		} catch (Exception e) {
			System.out.println("Error while querying the Ontology for " + ontologyID + ":" + individualID);
			e.printStackTrace();		
		}
		
		return Json.object();
	}
	
	/**
	 * getter for serialized individuals from the legacy ontology
	 * 
	 * @param individualID individual identifier
	 * @return a Json representation of the individual
	 */
	
	public Json getMetaIndividual (String individualID){
		return getSerializedIndividual(individualID, "legacy");						
	}
	
	public Json getServiceCaseAlert (String srType){		

		srType = MetaOntology.getIndividualIdentifier(srType);
		
		Json sr = getMetaIndividual(srType);		
		
		if (sr.has("hasServiceCaseAlert") && sr.at("hasServiceCaseAlert").isObject()){
			String iri = sr.at("hasServiceCaseAlert").at("iri").asString();
			OWLNamedIndividual ind = OWL.individual(iri);
			sr.at("hasServiceCaseAlert").set("iri", MetaOntology.getOntologyFromUri(ind.getIRI().toString()) + ":" + ind.getIRI().getFragment());
			return sr.at("hasServiceCaseAlert");
		} else return Json.nil();
	
	}
	
	/**
	 * push committed changes from local ontology to the central repository 
	 * 
	 * @return whether the changes were successfully pushed or not 
	 */

	public Json push() {
		OwlRepo repo = getRepo();
		synchronized (repo) {
			repo.ensurePeerStarted();
			
			String ontologyIri = Refs.defaultOntologyIRI.resolve();

			return push(ontologyIri);
		}
	}
		
	public Json addIndividualObjectPropertyToIndividual(String individualID, String propertyID, Json data, String userName, String comment){

		individualID = MetaOntology.getIndividualIdentifier(individualID);
		
		OwlRepo repo = getRepo();
		synchronized (repo) {
			repo.ensurePeerStarted();		 
			
			List<OWLOntologyChange> changes = MetaOntology.getAddIndividualObjectFromJsonChanges(individualID, propertyID, data);	
			
			boolean r = commit(userName, comment, changes);
			
			if (r) clearCache(PREFIX + individualID);
			
			return Json.object().set("success", r);
		}
	}
	
	/**
	 * Sends Jenkins the signal to start the job that restart servers with fresh ontologies
	 *  
	 * @return whether Jenkins acknowledge the signal or not
	 */
	
	public Json refreshOnto() {
		// String jenkingsEndpointFullDeploy = "https://api.miamidade.gov/jenkins/job/CIRM-ADMIN-TEST-CI-JOB-OPENCIRM/build?token=7ef54dc3a604a1514368e8707d8415";
		String jenkingsEndpointRefreshOntosOnly = "https://api.miamidade.gov/jenkins/job/CIRM-ADMIN-TEST-REFRESH-ONTOS/build?token=1a85a585ef7c424191c7c58ee3c4a97d556eec91";

		return GenUtils.httpPostWithBasicAuth(jenkingsEndpointRefreshOntosOnly, "cirm", "admin", "");
	}
	
	/**
	 * 
	 * @param individualID
	 * @param newAnnotationContent
	 * @param userName
	 * @param comment
	 * @return
	 */
	
	public Json replaceAlertLabel(String individualID, String newAnnotationContent, String userName){

		individualID = MetaOntology.getIndividualIdentifier(individualID);
		
		OwlRepo repo = getRepo();
		synchronized (repo) {
			repo.ensurePeerStarted();		 
			
			List<OWLOntologyChange> changes = MetaOntology.getReplaceObjectAnnotationChanges(individualID, newAnnotationContent);	
            
			String comment = "Replace Alert Message for SR type: " + PREFIX + individualID; 
			
			boolean r = commit(userName, comment, changes);
			
			if (r) clearCache(PREFIX + individualID);
			
			return Json.object().set("success", r);
		}
	}
	
	/**
	 * Creates or Replace the alert message of a Service Case Type
	 * 
	 * @param individualID the identifier of the Service Case Type
	 * @param data the Json representation of the Service Case Alert 
	 * @param userName that performs the commit
	 * @return
	 */
	
	public Json addNewAlertServiceCase (String individualID, Json data, String userName){

		individualID = MetaOntology.getIndividualIdentifier(individualID);
		
		List<String> evictionList = new ArrayList<String>();
		evictionList.add(individualID);
		
		OwlRepo repo = getRepo();
		synchronized (repo) {
			repo.ensurePeerStarted();			
			
			String propertyID = "hasServiceCaseAlert";
			
			if(data.at("iri").isNull())
			{
				
				Date now = new Date(); 
				
				String newIri = individualID + "_" + Long.toString(now.getTime());
				data.set("iri", newIri); 
			}
			
			
			Json oldAlert = getServiceCaseAlert(individualID);
			
			List<OWLOntologyChange> changes;
			
			if (oldAlert.isObject() && oldAlert.has("iri")){
				OWLNamedIndividual ind = OWL.individual(oldAlert.at("iri").asString());
				oldAlert.set("iri", ind.getIRI().getFragment());
				evictionList.add(ind.getIRI().getFragment());
				changes = MetaOntology.getAddReplaceIndividualObjectFromJsonChanges(individualID, propertyID, data, oldAlert);
			} else {
				changes = MetaOntology.getAddIndividualObjectFromJsonChanges(individualID, propertyID, data);
			}		

			String comment = "Create new Alert Message for SR "+ PREFIX + individualID;	
			
			boolean r = commit(userName, comment, changes);
			
			if (r) clearCache(evictionList);
			
			return Json.object().set("success", r);
				
		}
	}
	
	/**
	 * Delete the current alert message of a Service Case Type
	 * 
	 * @param individualID the identifier of the Service Case Type
	 * @param userName that performs the commit
	 * @return
	 */
	
	public Json deleteAlertServiceCase (String individualID, String userName){

		individualID = MetaOntology.getIndividualIdentifier(individualID);
		
		List<String> evictionList = new ArrayList<String>();
		evictionList.add(individualID);
		
		OwlRepo repo = getRepo();
		synchronized (repo) {
			repo.ensurePeerStarted();						
			
			Json oldAlert = getServiceCaseAlert(individualID);
			
			List<OWLOntologyChange> changes;
			
			if (oldAlert.isObject() && oldAlert.has("iri")){
				OWLNamedIndividual ind = OWL.individual(oldAlert.at("iri").asString());
				evictionList.add(ind.getIRI().getFragment());
				changes = MetaOntology.getRemoveAllPropertiesIndividualChanges(ind);
			} else throw new IllegalParameterException("No alert for individual " + PREFIX + individualID);
			
			String comment = "Delete Alert Message for SR "+ PREFIX + individualID;	
			
			boolean r = commit(userName, comment, changes);
			
			if (r) clearCache(evictionList);
			
			return Json.object().set("success", r);
				
		}
	}
	
	/*
	 * Test by Syed
	 * 
	 */
		
	public void testAxiom() {
		OwlRepo repo = getRepo();
		repo.ensurePeerStarted();
		// OWLOntology O = OWL.ontology();
		Set<OWLLiteral> propValues = OWL.reasoner()
				.getDataPropertyValues(OWL.individual("legacy:311OTHER_Q3"),
						OWL.dataProperty("label"));
		System.out.println("isEmpty" + propValues.isEmpty());
		for (OWLLiteral v : propValues) {
			System.out.println(v.getLiteral().toString());
		}

		Set<OWLLiteral> propValues1 = OWL.reasoner().getDataPropertyValues(
				OWL.individual("legacy:311OTHER_Q3"),
				OWL.dataProperty("legacy:label"));
		System.out.println("isEmpty(legacy)" + propValues.isEmpty());
		for (OWLLiteral v : propValues1) {
			System.out.println("legacy:" + v.getLiteral().toString());
		}

		String annotation = OWL.getEntityLabel(OWL
				.individual("legacy:311OTHER_Q3"));
		System.out.println("annotation:" + annotation);

	}
}
